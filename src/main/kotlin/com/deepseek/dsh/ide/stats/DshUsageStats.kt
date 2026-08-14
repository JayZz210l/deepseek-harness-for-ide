package com.deepseek.dsh.ide.stats

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Application-wide usage statistics, persisted to `deepseek-harness-usage.xml`.
 */
@State(name = "DeepSeekHarnessUsageStats", storages = [Storage("deepseek-harness-usage.xml")])
class DshUsageStats : PersistentStateComponent<DshUsageStats.Stats> {

    data class Stats(
        var firstUsedAt: Long = 0,
        var startCount: Long = 0,
        var crashCount: Long = 0,
        var totalUptimeMs: Long = 0,
        var lastStartAt: Long = 0,
        var lastStopAt: Long = 0,
    )

    private var stats = Stats()

    /** Mutable stats view; mutations persist on the next save. */
    val current: Stats get() = stats

    override fun getState(): Stats = stats

    override fun loadState(state: Stats) {
        stats = state
    }

    @Synchronized
    fun recordStart(now: Long = System.currentTimeMillis()) {
        if (stats.firstUsedAt == 0L) stats.firstUsedAt = now
        stats.startCount++
        stats.lastStartAt = now
    }

    @Synchronized
    fun recordCrash(now: Long = System.currentTimeMillis()) {
        stats.crashCount++
    }

    @Synchronized
    fun recordStop(uptimeMs: Long, now: Long = System.currentTimeMillis()) {
        stats.totalUptimeMs += uptimeMs.coerceAtLeast(0)
        stats.lastStopAt = now
    }

    companion object {
        @JvmStatic
        fun getInstance(): DshUsageStats =
            ApplicationManager.getApplication().getService(DshUsageStats::class.java)
    }
}
