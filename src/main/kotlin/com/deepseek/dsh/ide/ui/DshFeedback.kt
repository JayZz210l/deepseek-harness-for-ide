package com.deepseek.dsh.ide.ui

import com.deepseek.dsh.ide.process.DshBundledRuntime
import com.deepseek.dsh.ide.process.DshBuildInfo
import com.deepseek.dsh.ide.process.DshHomePolicy
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationInfo
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Bug-report affordances: a feedback entry point plus a diagnostics snapshot
 * (version, build date, bundled runtime, data directory, IDE, OS) that users
 * can paste into a bug report.
 */
object DshFeedback {

    /**
     * Where "Report a problem" opens. Points at the GitHub issues page of this
     * project.
     */
    const val FEEDBACK_URL = "https://github.com/JayZz210l/deepseek-harness-for-ide/issues"

    fun openFeedback() {
        runCatching { BrowserUtil.browse(FEEDBACK_URL) }
    }

    fun diagnosticsText(pluginVersion: String): String = buildString {
        appendLine("Deepseek Harness For IDE $pluginVersion")
        appendLine("Build date: ${DshBuildInfo.buildDate() ?: "?"}")
        appendLine("Bundled DeepSeek Harness: ${DshBundledRuntime.version() ?: "not bundled"}")
        appendLine("DSH home: ${DshHomePolicy.isolatedRoot()}")
        appendLine("IDE: ${ApplicationInfo.getInstance().fullApplicationName}")
        appendLine(
            "OS: ${System.getProperty("os.name")} ${System.getProperty("os.arch")} ${System.getProperty("os.version")}",
        )
    }

    fun copyDiagnostics(pluginVersion: String) {
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard
                .setContents(StringSelection(diagnosticsText(pluginVersion)), null)
        }
    }
}
