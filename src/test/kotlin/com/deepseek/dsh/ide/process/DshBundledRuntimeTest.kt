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
}
