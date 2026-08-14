package com.deepseek.dsh.ide.startup

import com.deepseek.dsh.ide.process.DshProcessManager
import com.deepseek.dsh.ide.settings.DshSettingsState
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

/**
 * Starts the local DeepSeek Harness server when a project opens,
 * honoring the "auto start" setting.
 */
class DshStartupActivity : StartupActivity.DumbAware {

    override fun runActivity(project: Project) {
        val settings = DshSettingsState.getInstance().current
        if (settings.autoStartOnProjectOpen && !project.basePath.isNullOrBlank()) {
            project.service<DshProcessManager>().startAsync()
        }
    }
}

