package com.deepseek.dsh.ide.ui

import com.deepseek.dsh.ide.i18n.DshBundle
import com.deepseek.dsh.ide.process.DshProcessManager
import com.deepseek.dsh.ide.process.DshServerState
import com.deepseek.dsh.ide.process.DshServerStatus
import com.deepseek.dsh.ide.process.DshServerStatusListener
import com.deepseek.dsh.ide.process.DshServerTopics
import com.deepseek.dsh.ide.stats.DshUsageStats
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.jcef.JBCefApp
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.text.DateFormat
import java.util.Date
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.Timer

/**
 * Tool window content: toolbar on top, card layout in the center
 * (status / JCEF browser / no-JCEF fallback) and a collapsible
 * log + statistics panel at the bottom.
 */
class DshToolWindowPanel(private val project: Project) {

    private val manager = project.service<DshProcessManager>()

    private val startStopAction = StartStopAction()
    private val restartAction = RestartAction()
    private val openBrowserAction = OpenInBrowserAction()
    private val copyUrlAction = CopyUrlAction()
    private val feedbackAction = FeedbackAction()
    private val toggleDetailsAction = ToggleDetailsAction()

    private val toolbar = ActionManager.getInstance().createActionToolbar(
        "DeepSeekHarnessToolbar",
        DefaultActionGroup(
            startStopAction,
            restartAction,
            Separator.create(),
            openBrowserAction,
            copyUrlAction,
            feedbackAction,
            Separator.create(),
            toggleDetailsAction,
        ),
        true,
    )

    private val cards = JPanel(CardLayout())
    private val statusPanel = StatusPanel()
    private val fallbackPanel = FallbackPanel()
    private var browserPanel: JcefBrowserPanel? = null

    private val logArea = JBTextArea().apply {
        isEditable = false
        font = JBUI.Fonts.label()
        lineWrap = false
        rows = 10
    }
    private val logScroll = JBScrollPane(logArea)

    private val statsPanel = StatsPanel()

    private val detailsTabs = JTabbedPane().apply {
        addTab(DshBundle.message("dsh.tab.log"), logScroll)
        addTab(DshBundle.message("dsh.tab.stats"), statsPanel.component)
    }

    private val detailsWrapper = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        add(detailsTabs, BorderLayout.CENTER)
        isVisible = false
    }

    private val refreshTimer = Timer(1000) { refreshDetails() }

    private val wrapper = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        add(cards, BorderLayout.CENTER)
        add(detailsWrapper, BorderLayout.SOUTH)
    }

    val component: JComponent = SimpleToolWindowPanel(true, true).apply {
        toolbar = this@DshToolWindowPanel.toolbar.component
        setContent(wrapper)
    }

    init {
        cards.add(statusPanel.component, CARD_STATUS)
        cards.add(fallbackPanel.component, CARD_FALLBACK)

        project.messageBus.connect().subscribe(
            DshServerTopics.SERVER_STATUS,
            DshServerStatusListener { status -> onStatus(status) },
        )
        onStatus(manager.currentStatus())
    }

    private fun onStatus(status: DshServerStatus) {
        when (status.state) {
            DshServerState.RUNNING -> showBrowser(status)
            else -> {
                // Leaving RUNNING invalidates the loaded page: the next RUNNING must
                // reload even when the URL is unchanged (fixed port restarts).
                browserPanel?.markDirty()
                statusPanel.update(status)
                (cards.layout as CardLayout).show(cards, CARD_STATUS)
            }
        }
        statsPanel.refresh()
        // updateActionsImmediately() is deprecated since 2024.1; the platform's own
        // Javadoc points at updateActionsAsync() as the replacement (safe here: the
        // status message bus always delivers on the EDT, and updateActionsAsync()
        // requires the EDT).
        toolbar.updateActionsAsync()
    }

    private fun showBrowser(status: DshServerStatus) {
        val url = status.url
        if (url.isNullOrBlank()) {
            statusPanel.update(status)
            (cards.layout as CardLayout).show(cards, CARD_STATUS)
            return
        }
        if (!JBCefApp.isSupported()) {
            fallbackPanel.setUrl(url)
            (cards.layout as CardLayout).show(cards, CARD_FALLBACK)
            return
        }
        val panel = browserPanel ?: JcefBrowserPanel(project).also {
            browserPanel = it
            cards.add(it.component, CARD_BROWSER)
        }
        panel.load(url)
        (cards.layout as CardLayout).show(cards, CARD_BROWSER)
    }

    private fun refreshDetails() {
        if (!detailsWrapper.isVisible) return
        if (detailsTabs.selectedIndex == 0) {
            val lines = manager.snapshotLog()
            logArea.text = lines.joinToString("\n")
            val scrollBar = logScroll.verticalScrollBar
            scrollBar.value = scrollBar.maximum
        } else {
            statsPanel.refresh()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Toolbar actions
    // ---------------------------------------------------------------------------------------------

    private inner class StartStopAction : DumbAwareAction() {
        override fun update(e: AnActionEvent) {
            val status = manager.currentStatus()
            val running = status.state == DshServerState.RUNNING
            val busy = status.state == DshServerState.STARTING || status.state == DshServerState.STOPPING
                || status.state == DshServerState.SYNCING || status.state == DshServerState.RESETTING
            e.presentation.isEnabled = !busy
            e.presentation.text = if (running) DshBundle.message("dsh.action.stop") else DshBundle.message("dsh.action.start")
            e.presentation.description = if (running) DshBundle.message("dsh.action.stop.desc") else DshBundle.message("dsh.action.start.desc")
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (manager.currentStatus().state == DshServerState.RUNNING) {
                manager.stopAsync()
            } else {
                manager.startAsync()
            }
        }
    }

    private inner class RestartAction : DumbAwareAction(
        DshBundle.message("dsh.action.restart"),
        DshBundle.message("dsh.action.restart.desc"),
        null,
    ) {
        override fun update(e: AnActionEvent) {
            val status = manager.currentStatus()
            e.presentation.isEnabled = status.state != DshServerState.STARTING
                && status.state != DshServerState.STOPPING
                && status.state != DshServerState.SYNCING
                && status.state != DshServerState.RESETTING
        }

        override fun actionPerformed(e: AnActionEvent) {
            manager.restartAsync()
        }
    }

    private inner class OpenInBrowserAction : DumbAwareAction(
        DshBundle.message("dsh.action.openBrowser"),
        DshBundle.message("dsh.action.openBrowser.desc"),
        null,
    ) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = manager.currentStatus().url != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            manager.currentStatus().url?.let { url -> BrowserUtil.browse(url) }
        }
    }

    private inner class CopyUrlAction : DumbAwareAction(
        DshBundle.message("dsh.action.copyUrl"),
        DshBundle.message("dsh.action.copyUrl.desc"),
        null,
    ) {
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = manager.currentStatus().url != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            manager.currentStatus().url?.let { url ->
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(url), null)
            }
        }
    }

    private inner class FeedbackAction : DumbAwareAction(
        DshBundle.message("dsh.action.feedback"),
        DshBundle.message("dsh.action.feedback.desc"),
        null,
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            DshFeedback.openFeedback()
        }
    }

    private inner class ToggleDetailsAction : ToggleAction(
        DshBundle.message("dsh.action.toggleLog"),
        DshBundle.message("dsh.action.toggleLog.desc"),
        null,
    ) {
        override fun isSelected(e: AnActionEvent): Boolean = detailsWrapper.isVisible

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            detailsWrapper.isVisible = state
            component.revalidate()
            component.repaint()
            if (state) {
                refreshDetails()
                refreshTimer.start()
            } else {
                refreshTimer.stop()
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Status card
    // ---------------------------------------------------------------------------------------------

    private inner class StatusPanel {

        private val stateLabel = JBLabel()
        private val detailLabel = JBLabel()
        private val retryButton = JButton(DshBundle.message("dsh.status.retry")).apply {
            addActionListener { manager.startAsync() }
        }

        val component: JComponent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(16)
            val textBlock = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(stateLabel)
                add(Box.createVerticalStrut(8))
                add(detailLabel)
            }
            val buttons = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(retryButton)
                add(Box.createHorizontalGlue())
            }
            add(textBlock, BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }

        fun update(status: DshServerStatus) {
            when (status.state) {
                DshServerState.STOPPED -> {
                    stateLabel.text = "<html><b>${DshBundle.message("dsh.status.stopped")}</b></html>"
                    detailLabel.text = DshBundle.message("dsh.status.stopped.detail")
                    retryButton.isVisible = true
                }
                DshServerState.STARTING -> {
                    stateLabel.text = "<html><b>${DshBundle.message("dsh.status.starting")}</b></html>"
                    detailLabel.text = DshBundle.message("dsh.status.starting.detail")
                    retryButton.isVisible = false
                }
                DshServerState.STOPPING -> {
                    stateLabel.text = "<html><b>${DshBundle.message("dsh.status.stopping")}</b></html>"
                    detailLabel.text = ""
                    retryButton.isVisible = false
                }
                DshServerState.FAILED -> {
                    stateLabel.text = "<html><b>${DshBundle.message("dsh.status.failed")}</b></html>"
                    detailLabel.text = "<html>${status.detail.orEmpty()}<br>${DshBundle.message("dsh.status.failed.detail")}</html>"
                    retryButton.isVisible = true
                }
                DshServerState.SYNCING -> {
                    stateLabel.text = "<html><b>${DshBundle.message("dsh.status.syncing")}</b></html>"
                    detailLabel.text = "<html>${status.detail.orEmpty()}</html>"
                    retryButton.isVisible = false
                }
                DshServerState.RESETTING -> {
                    stateLabel.text = "<html><b>${DshBundle.message("dsh.status.resetting")}</b></html>"
                    detailLabel.text = "<html>${status.detail.orEmpty()}</html>"
                    retryButton.isVisible = false
                }
                DshServerState.RUNNING -> Unit // handled by the browser card
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Statistics card
    // ---------------------------------------------------------------------------------------------

    private inner class StatsPanel {

        private val rows = mutableListOf<Pair<JBLabel, JBLabel>>()

        val component: JComponent = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
            for (key in listOf(
                "dsh.stats.firstUsed",
                "dsh.stats.startCount",
                "dsh.stats.crashCount",
                "dsh.stats.totalUptime",
                "dsh.stats.currentUptime",
                "dsh.stats.lastStart",
                "dsh.stats.lastStop",
                "dsh.stats.pluginVersion",
            )) {
                val keyLabel = JBLabel("<html><font color='gray'>${DshBundle.message(key)}</font></html>")
                val valueLabel = JBLabel("")
                val row = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    add(keyLabel)
                    add(Box.createHorizontalStrut(12))
                    add(valueLabel)
                    add(Box.createHorizontalGlue())
                }
                add(row)
                rows += keyLabel to valueLabel
            }
        }

        fun refresh() {
            val stats = DshUsageStats.getInstance().current
            val values = listOf(
                formatTime(stats.firstUsedAt),
                stats.startCount.toString(),
                stats.crashCount.toString(),
                formatDuration(stats.totalUptimeMs),
                formatDuration(manager.currentUptimeMs()),
                formatTime(stats.lastStartAt),
                formatTime(stats.lastStopAt),
                pluginVersion(),
            )
            for (i in values.indices) {
                rows[i].second.text = values[i]
            }
        }

        private fun pluginVersion(): String = runCatching {
            com.intellij.ide.plugins.PluginManagerCore.getPlugin(
                com.intellij.openapi.extensions.PluginId.getId("com.deepseek.dsh.ide")
            )?.version ?: "?"
        }.getOrDefault("?")

        private fun formatTime(epochMs: Long): String =
            if (epochMs <= 0) DshBundle.message("dsh.stats.never")
            else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(epochMs))

        private fun formatDuration(ms: Long): String {
            val totalSeconds = ms / 1000
            val h = totalSeconds / 3600
            val m = (totalSeconds % 3600) / 60
            val s = totalSeconds % 60
            return when {
                h > 0 -> "${h}h ${m}m"
                m > 0 -> "${m}m ${s}s"
                else -> "${s}s"
            }
        }
    }

    companion object {
        private const val CARD_STATUS = "status"
        private const val CARD_FALLBACK = "fallback"
        private const val CARD_BROWSER = "browser"
    }
}
