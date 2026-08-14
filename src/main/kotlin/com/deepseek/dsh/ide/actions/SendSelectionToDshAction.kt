package com.deepseek.dsh.ide.actions

import com.deepseek.dsh.ide.i18n.DshBundle
import com.deepseek.dsh.ide.process.DshApiClient
import com.deepseek.dsh.ide.process.DshProcessManager
import com.deepseek.dsh.ide.process.DshServerState
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import java.io.File

/**
 * Editor context-menu action: sends the selected text (or the current line when
 * nothing is selected) to DeepSeek Harness as a queued user message.
 *
 * The message is sent through the DSH `/api` protocol directly to the real server
 * port. When the server is not running yet, it is started first and the action
 * waits for it to become ready (up to 60 s).
 */
class SendSelectionToDshAction : DumbAwareAction(
    DshBundle.message("dsh.action.sendSelection"),
    DshBundle.message("dsh.action.sendSelection.desc"),
    null,
) {

    override fun update(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT)
        val editor = e.getData(CommonDataKeys.EDITOR)
        val hasText = editor != null && (editor.selectionModel.hasSelection() || !lineAtCaret(editor).isNullOrBlank())
        e.presentation.isEnabled = project != null && hasText
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.getData(CommonDataKeys.PROJECT) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val text = selectionText(editor) ?: lineAtCaret(editor) ?: return
        val fullText = buildPromptText(project, editor, text)

        ApplicationManager.getApplication().executeOnPooledThread {
            send(project, fullText)
        }
    }

    private fun send(project: Project, text: String) {
        val manager = project.service<DshProcessManager>()

        val baseUrl = waitForReadyUrl(manager, project)
        if (baseUrl == null) {
            notify(project, DshBundle.message("dsh.notify.timeout"), NotificationType.WARNING)
            return
        }

        try {
            val sessions = DshApiClient.listSessions(baseUrl)
            val chosen = sessions.firstOrNull { it.running }
                ?: sessions.firstOrNull { !it.blank }
                ?: sessions.firstOrNull()
            val sessionId = if (chosen != null) {
                chosen.sessionId
            } else {
                val cwd = project.basePath ?: System.getProperty("user.home")
                DshApiClient.createSession(baseUrl, cwd)
            }
            DshApiClient.sendPrompt(baseUrl, sessionId, text)
            notify(project, DshBundle.message("dsh.notify.sent"), NotificationType.INFORMATION)
            // Bring the tool window to the front so the user sees the message land.
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed) {
                    ToolWindowManager.getInstance(project).getToolWindow("Deepseek Harness For IDE")?.show()
                }
            }
        } catch (error: Exception) {
            notify(project, DshBundle.message("dsh.notify.sendFailed", error.message ?: error.javaClass.simpleName), NotificationType.ERROR)
        }
    }

    private fun waitForReadyUrl(manager: DshProcessManager, project: Project): String? {
        var status = manager.currentStatus()
        if (status.state != DshServerState.RUNNING) {
            manager.startAsync()
            notify(project, DshBundle.message("dsh.notify.starting"), NotificationType.INFORMATION)
        }
        val deadline = System.nanoTime() + 60_000_000_000L
        while (System.nanoTime() < deadline) {
            status = manager.currentStatus()
            if (status.state == DshServerState.RUNNING) {
                return status.realUrl ?: status.url
            }
            if (status.state == DshServerState.FAILED) return null
            Thread.sleep(500)
        }
        return null
    }

    private fun notify(project: Project, content: String, type: NotificationType) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            NotificationGroupManager.getInstance()
                .getNotificationGroup("DeepSeekHarness")
                .createNotification(content, type)
                .notify(project)
        }
    }

    private fun selectionText(editor: Editor): String? =
        editor.selectionModel.selectedText?.takeIf { it.isNotBlank() }

    private fun lineAtCaret(editor: Editor): String? {
        val document = editor.document
        val line = document.getLineNumber(editor.caretModel.offset)
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)
        return document.getText(com.intellij.openapi.util.TextRange(start, end)).takeIf { it.isNotBlank() }
    }

    private fun buildPromptText(project: Project, editor: Editor, text: String): String {
        val file: VirtualFile? = FileDocumentManager.getInstance().getFile(editor.document)
        if (file == null) return text
        val line = editor.document.getLineNumber(editor.selectionModel.selectionStart)
        val relative = project.basePath?.let { base ->
            runCatching { File(base).toPath().relativize(file.toNioPath()).toString() }.getOrNull()
        } ?: file.path
        return "### $relative:$line\n$text"
    }
}
