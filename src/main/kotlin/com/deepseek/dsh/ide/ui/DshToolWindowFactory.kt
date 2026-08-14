package com.deepseek.dsh.ide.ui

import com.deepseek.dsh.ide.i18n.DshBundle
import com.deepseek.dsh.ide.process.DshProcessManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.SwingConstants

/**
 * Registers the "DeepSeek Harness" tool window on the right side.
 * Opening the window starts the local server when it is not running yet.
 */
class DshToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentComponent: JComponent = runCatching {
            DshToolWindowPanel(project).component
        }.getOrElse { error ->
            thisLogger().error("DeepSeek Harness tool window content creation failed", error)
            // Defensive fallback so the tool window never stays blank and the
            // failure is visible right where the user looks.
            JBLabel(
                "<html><b>${DshBundle.message("dsh.status.initFailed")}</b><br>" +
                    "<font color='gray'>" + (error.message ?: error.javaClass.simpleName) +
                    "<br>${DshBundle.message("dsh.status.initFailed.logHint")}</font></html>",
                SwingConstants.LEFT,
            ).apply {
                border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
                verticalAlignment = SwingConstants.TOP
            }
        }

        val content = ContentFactory.getInstance().createContent(contentComponent, "", false)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)

        // Opening the tool window starts the server when it is not running yet (idempotent).
        runCatching { project.service<DshProcessManager>().startAsync() }
            .onFailure { thisLogger().error("DeepSeek Harness service start failed", it) }
    }
}
