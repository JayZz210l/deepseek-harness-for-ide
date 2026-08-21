package com.deepseek.dsh.ide.process

import com.intellij.ide.plugins.cl.PluginAwareClassLoader
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Locates the DeepSeek Harness runtime that is BUNDLED inside the plugin
 * distribution (`dsh-runtime/node_modules`, copied from a real dsh installation
 * at build time). The IDE extracts the plugin zip to the plugins directory, so
 * the runtime sits at `<plugins>/deepseek-harness-jetbrains/dsh-runtime`.
 *
 * With this runtime present the plugin works on machines without a global
 * `dsh` install; only Node.js 18+ is still required to execute it. On Windows,
 * Node resolution also reads the current user/machine environment so an IDE
 * started before Node was installed does not need to be restarted.
 * Resolution order in [DshProcessManager]: explicit command setting → PATH →
 * bundled runtime → npx cache.
 */
object DshBundledRuntime {

    private const val BIN_JS = "node_modules/@deepseek-ai/dsh/lib/bin.js"

    /**
     * Absolute plugin directory, or null when it cannot be resolved (dev layouts).
     * PluginAwareClassLoader is the public platform contract for obtaining the
     * descriptor of the plugin that loaded this class.
     */
    fun pluginDir(): Path? {
        val descriptorPath = (DshBundledRuntime::class.java.classLoader as? PluginAwareClassLoader)
            ?.pluginDescriptor
            ?.pluginPath
        if (descriptorPath != null) return descriptorPath

        return pluginDirFromCodeSource(DshBundledRuntime::class.java.protectionDomain?.codeSource?.location)
    }

    /** Resolve the standard installed layout: `<plugin>/lib/<plugin>.jar`. */
    internal fun pluginDirFromCodeSource(location: URL?): Path? = runCatching {
        val codePath = location?.toURI()?.let(Paths::get) ?: return@runCatching null
        if (!Files.isRegularFile(codePath) || !codePath.fileName.toString().endsWith(".jar", ignoreCase = true)) {
            return@runCatching null
        }
        val libDir = codePath.parent ?: return@runCatching null
        if (!libDir.fileName.toString().equals("lib", ignoreCase = true)) return@runCatching null
        libDir.parent
    }.getOrNull()

    /** The bundled dsh install root (`<pluginDir>/dsh-runtime`), when it exists. */
    fun installRoot(): Path? {
        val root = pluginDir()?.resolve("dsh-runtime") ?: return null
        return if (Files.isDirectory(root)) root else null
    }

    /** The bundled `@deepseek-ai/dsh/lib/bin.js` entry point, when present. */
    fun binJs(): Path? {
        val bin = installRoot()?.resolve(BIN_JS) ?: return null
        return if (Files.isRegularFile(bin)) bin else null
    }

    /** Bundled dsh version from `dsh-runtime/version.txt`, for logging only. */
    fun version(): String? = installRoot()?.resolve("version.txt")
        ?.takeIf { Files.isRegularFile(it) }
        ?.let { runCatching { Files.readString(it).trim().ifBlank { null } }.getOrNull() }

    /** Bundled Node.js executable (`<pluginDir>/node-runtime/node.exe` or `node`), when present. */
    fun nodeExe(): Path? {
        val dir = pluginDir()?.resolve("node-runtime") ?: return null
        if (!Files.isDirectory(dir)) return null
        val names = if (com.intellij.openapi.util.SystemInfo.isWindows) listOf("node.exe") else listOf("node")
        return names.map { dir.resolve(it) }.firstOrNull { Files.isRegularFile(it) }
    }
}
