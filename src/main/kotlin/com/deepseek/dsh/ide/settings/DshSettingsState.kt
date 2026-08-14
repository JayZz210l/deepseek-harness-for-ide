package com.deepseek.dsh.ide.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Application-wide plugin settings, persisted to `deepseek-harness.xml`.
 */
@State(name = "DeepSeekHarnessSettings", storages = [Storage("deepseek-harness.xml")])
class DshSettingsState : PersistentStateComponent<DshSettingsState.Settings> {

    data class Settings(
        /** Command used to launch DeepSeek Harness, e.g. `dsh` or an absolute path. */
        var dshCommand: String = "dsh",
        /** Bind host passed to `dsh web --host`. Keep loopback unless you know what you are doing. */
        var host: String = "127.0.0.1",
        /** Port passed to `dsh web --port`; 0 lets the OS pick a free port (default, avoids conflicts). */
        var port: Int = 0,
        /**
         * DSH_HOME for the spawned process. Blank = an isolated per-project home under
         * `%LOCALAPPDATA%\deepseek-harness-jetbrains\dsh-home` (recommended; credentials
         * and settings are copied in one-way from the main home). `default` = inherit the
         * IDE environment (shares the main `~/.dsh`, not multi-instance safe). Any other
         * value is used verbatim as the home directory.
         */
        var dshHomeOverride: String = "",
        /** Start the local server automatically when a project opens. */
        var autoStartOnProjectOpen: Boolean = true,
        /** Restart the server automatically when the process exits unexpectedly. */
        var autoRestartOnExit: Boolean = false,
        /**
         * File-jump strategy: `auto` = DSH composition-native gateway first, TCP proxy
         * fallback; `proxy` = TCP proxy only; `off` = no IDE file jump.
         */
        var fileJumpMode: String = "auto",
        /**
         * How a file opened from the DeepSeek Harness UI lands in the IDE: `auto` opens
         * the IDE's native VCS diff when the file is modified (else the plain editor),
         * `file` always opens the editor.
         */
        var fileOpenMode: String = "auto",
        /** Rolling log buffer size kept for the in-panel log view. */
        var maxLogLines: Int = 1000,
        /** The last plugin version the update announcement was shown for. */
        var lastUpdateNoticeVersion: String = "",
    )

    private var settings = Settings()

    /** Mutable settings view; mutations persist on the next save. */
    val current: Settings get() = settings

    override fun getState(): Settings = settings

    override fun loadState(state: Settings) {
        settings = state
    }

    companion object {
        @JvmStatic
        fun getInstance(): DshSettingsState =
            ApplicationManager.getApplication().getService(DshSettingsState::class.java)
    }
}

