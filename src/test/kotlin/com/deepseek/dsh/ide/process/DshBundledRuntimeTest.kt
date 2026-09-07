package com.deepseek.dsh.ide.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DshBundledRuntimeTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `plugin directory is resolved from packaged jar layout`() {
        val pluginDir = temporaryFolder.newFolder("deepseek-harness-jetbrains")
        val libDir = pluginDir.resolve("lib").also { assertTrue(it.mkdir()) }
        val pluginJar = libDir.resolve("deepseek-harness-jetbrains.jar")
            .also { assertTrue(it.createNewFile()) }

        assertEquals(
            pluginDir.toPath(),
            DshBundledRuntime.pluginDirFromCodeSource(pluginJar.toURI().toURL()),
        )
    }

    @Test
    fun `default bare dsh command prefers bundled runtime`() {
        assertEquals(true, DshBundledRuntime.shouldPreferBundled(listOf("dsh")))
        assertEquals(true, DshBundledRuntime.shouldPreferBundled(listOf("DSH", "--trace")))
    }

    @Test
    fun `explicit external commands do not select bundled runtime`() {
        assertEquals(false, DshBundledRuntime.shouldPreferBundled(listOf("C:\\tools\\dsh.cmd")))
        assertEquals(false, DshBundledRuntime.shouldPreferBundled(listOf("node", "C:\\tools\\dsh\\lib\\bin.js")))
        assertEquals(false, DshBundledRuntime.shouldPreferBundled(listOf("custom-dsh")))
    }
}
