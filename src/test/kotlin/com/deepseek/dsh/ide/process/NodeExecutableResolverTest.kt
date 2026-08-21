package com.deepseek.dsh.ide.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class NodeExecutableResolverTest {

    @Test
    fun `uses current Windows environment when IDE PATH is stale`() {
        val staleDir = Files.createTempDirectory("stale-node-path")
        val currentDir = Files.createTempDirectory("current-node-path")
        val node = Files.createFile(currentDir.resolve("node.exe")).toFile()
        var freshPathRead = false

        try {
            val result = NodeExecutableResolver(
                environment = mapOf("PATH" to staleDir.toString()),
                isWindows = true,
                freshWindowsPath = {
                    freshPathRead = true
                    "\"$currentDir\""
                },
                versionProbe = { "v22.14.0" },
            ).resolve(bundledNode = null)

            assertTrue(freshPathRead)
            assertEquals(node.absolutePath, result.executable)
            assertEquals("v22.14.0", result.version)
            assertEquals(NodeExecutableResolver.Source.CURRENT_WINDOWS_ENVIRONMENT, result.source)
        } finally {
            node.delete()
            currentDir.toFile().delete()
            staleDir.toFile().delete()
        }
    }

    @Test
    fun `does not refresh Windows environment when inherited PATH already works`() {
        val currentDir = Files.createTempDirectory("inherited-node-path")
        val node = Files.createFile(currentDir.resolve("node.exe")).toFile()
        var freshPathRead = false

        try {
            val result = NodeExecutableResolver(
                environment = mapOf("Path" to currentDir.toString()),
                isWindows = true,
                freshWindowsPath = {
                    freshPathRead = true
                    null
                },
                versionProbe = { "v20.18.3" },
            ).resolve(bundledNode = null)

            assertFalse(freshPathRead)
            assertEquals(node.absolutePath, result.executable)
            assertEquals(NodeExecutableResolver.Source.PROCESS_PATH, result.source)
        } finally {
            node.delete()
            currentDir.toFile().delete()
        }
    }

    @Test
    fun `reports an installed but unsupported Node version separately`() {
        val currentDir = Files.createTempDirectory("old-node-path")
        val node = Files.createFile(currentDir.resolve("node.exe")).toFile()

        try {
            val result = NodeExecutableResolver(
                environment = mapOf("PATH" to currentDir.toString()),
                isWindows = true,
                freshWindowsPath = { null },
                versionProbe = { "v16.20.2" },
            ).resolve(bundledNode = null)

            assertNull(result.executable)
            assertEquals("v16.20.2", result.unsupportedVersion)
            assertEquals(node.absolutePath, result.unsupportedExecutable)
        } finally {
            node.delete()
            currentDir.toFile().delete()
        }
    }
}
