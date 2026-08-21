package com.deepseek.dsh.ide.settings

import com.deepseek.dsh.ide.i18n.DshBundle
import com.deepseek.dsh.ide.process.DshBundledRuntime
import com.deepseek.dsh.ide.process.DshBuildInfo
import com.deepseek.dsh.ide.process.DshHomePolicy
import com.deepseek.dsh.ide.ui.DshFeedback
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings → Tools → DeepSeek Harness.
 */
class DshSettingsConfigurable : Configurable {

    private val commandField = JBTextField()
    private val hostField = JBTextField()
    private val portField = JBTextField()
    private val dshHomeField = JBTextField()
    private val fileJumpModeCombo = ComboBox(arrayOf("auto", "proxy", "off"))
    private val fileOpenModeCombo = ComboBox(arrayOf("auto", "file"))
    private val autoStartCheck = JBCheckBox(DshBundle.message("dsh.settings.autoStart"))
    private val autoRestartCheck = JBCheckBox(DshBundle.message("dsh.settings.autoRestart"))

    private var panel: JComponent? = null

    override fun getDisplayName(): String = DshBundle.message("dsh.settings.displayName")

    override fun createComponent(): JComponent {
        fileJumpModeCombo.setRenderer { _, value, _, _, _ ->
            JBLabel(
                when (value) {
                    "auto" -> DshBundle.message("dsh.settings.fileJump.auto")
                    "proxy" -> DshBundle.message("dsh.settings.fileJump.proxy")
                    else -> DshBundle.message("dsh.settings.fileJump.off")
                }
            )
        }
        fileOpenModeCombo.setRenderer { _, value, _, _, _ ->
            JBLabel(
                when (value) {
                    "auto" -> DshBundle.message("dsh.settings.fileOpen.auto")
                    else -> DshBundle.message("dsh.settings.fileOpen.file")
                }
            )
        }
        val builder = FormBuilder.createFormBuilder()
            .addLabeledComponent(DshBundle.message("dsh.settings.command"), commandField)
            .addTooltip(DshBundle.message("dsh.settings.command.tip"))
            .addLabeledComponent(DshBundle.message("dsh.settings.host"), hostField)
            .addTooltip(DshBundle.message("dsh.settings.host.tip"))
            .addLabeledComponent(DshBundle.message("dsh.settings.port"), portField)
            .addTooltip(DshBundle.message("dsh.settings.port.tip"))
            .addLabeledComponent(DshBundle.message("dsh.settings.dshHome"), dshHomeField)
            .addTooltip(DshBundle.message("dsh.settings.dshHome.tip"))
            .addLabeledComponent(DshBundle.message("dsh.settings.fileJump"), fileJumpModeCombo)
            .addTooltip(DshBundle.message("dsh.settings.fileJump.tip"))
            .addLabeledComponent(DshBundle.message("dsh.settings.fileOpen"), fileOpenModeCombo)
            .addTooltip(DshBundle.message("dsh.settings.fileOpen.tip"))
            .addComponent(autoStartCheck)
            .addComponent(autoRestartCheck)
            .addComponent(JBLabel("<html><font color='gray'>${DshBundle.message("dsh.settings.applyNote")}</font></html>"))
            .addComponent(pluginInfoPanel())
        panel = builder.panel
        return panel!!
    }

    /** Plugin info section with the bug-report and diagnostics-copy affordances. */
    private fun pluginInfoPanel(): JComponent {
        val version = pluginVersion()
        val infoPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            for ((label, value) in listOf(
                DshBundle.message("dsh.settings.info.version") to version,
                DshBundle.message("dsh.settings.info.buildDate") to (DshBuildInfo.buildDate() ?: "?"),
                DshBundle.message("dsh.settings.info.dshVersion") to (DshBundledRuntime.version() ?: "?"),
                DshBundle.message("dsh.settings.info.dataDir") to DshHomePolicy.isolatedRoot().toString(),
            )) {
                add(JBLabel("<html><b>$label</b>&nbsp;&nbsp;<font color='gray'>$value</font></html>"))
            }
        }
        val feedbackButton = JButton(DshBundle.message("dsh.settings.feedback")).apply {
            addActionListener { DshFeedback.openFeedback() }
        }
        val copyButton = JButton(DshBundle.message("dsh.settings.copyDiag")).apply {
            addActionListener {
                DshFeedback.copyDiagnostics(version)
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("DeepSeekHarness")
                    .createNotification(DshBundle.message("dsh.notify.diagCopied"), NotificationType.INFORMATION)
                    .notify(null)
            }
        }
        val buttonRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(feedbackButton)
            add(Box.createHorizontalStrut(8))
            add(copyButton)
            add(Box.createHorizontalGlue())
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JBLabel("<html><b>${DshBundle.message("dsh.settings.info")}</b></html>"))
            add(Box.createVerticalStrut(6))
            add(infoPanel)
            add(Box.createVerticalStrut(8))
            add(buttonRow)
        }
    }

    private fun pluginVersion(): String = DshBuildInfo.version() ?: "?"

    override fun isModified(): Boolean {
        val s = DshSettingsState.getInstance().current
        return s.dshCommand != commandField.text.trim()
            || s.host != hostField.text.trim()
            || s.port != portOrMinus()
            || s.dshHomeOverride != dshHomeField.text.trim()
            || s.fileJumpMode != fileJumpModeCombo.selectedItem
            || s.fileOpenMode != fileOpenModeCombo.selectedItem
            || s.autoStartOnProjectOpen != autoStartCheck.isSelected
            || s.autoRestartOnExit != autoRestartCheck.isSelected
    }

    override fun apply() {
        val portText = portField.text.trim()
        val port = when {
            portText.isEmpty() -> 0
            else -> portText.toIntOrNull()
                ?: throw ConfigurationException(DshBundle.message("dsh.settings.portError"))
        }
        if (port !in 0..65535) throw ConfigurationException(DshBundle.message("dsh.settings.portError"))

        val s = DshSettingsState.getInstance().current
        s.dshCommand = commandField.text.trim()
        s.host = hostField.text.trim().ifBlank { "127.0.0.1" }
        s.port = port
        s.dshHomeOverride = dshHomeField.text.trim()
        s.fileJumpMode = (fileJumpModeCombo.selectedItem as? String) ?: "auto"
        s.fileOpenMode = (fileOpenModeCombo.selectedItem as? String) ?: "auto"
        s.autoStartOnProjectOpen = autoStartCheck.isSelected
        s.autoRestartOnExit = autoRestartCheck.isSelected
    }

    override fun reset() {
        val s = DshSettingsState.getInstance().current
        commandField.text = s.dshCommand
        hostField.text = s.host
        portField.text = if (s.port == 0) "" else s.port.toString()
        dshHomeField.text = s.dshHomeOverride
        fileJumpModeCombo.selectedItem = if (s.fileJumpMode in listOf("auto", "proxy", "off")) s.fileJumpMode else "auto"
        fileOpenModeCombo.selectedItem = if (s.fileOpenMode in listOf("auto", "file")) s.fileOpenMode else "auto"
        autoStartCheck.isSelected = s.autoStartOnProjectOpen
        autoRestartCheck.isSelected = s.autoRestartOnExit
    }

    override fun disposeUIResources() {
        panel = null
    }

    private fun portOrMinus(): Int = portField.text.trim().toIntOrNull() ?: -1
}
