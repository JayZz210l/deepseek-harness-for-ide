package com.deepseek.dsh.ide.process

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Resolves a real, supported Node.js executable without assuming that the IDE process and
 * its integrated terminal have the same environment.
 *
 * On Windows the IDE's environment is a snapshot taken when the IDE was launched. Node's
 * installer and version managers can update the user/machine environment afterwards, while
 * a newly opened terminal already sees the update. When the inherited PATH has no usable
 * Node, this resolver reads the current Windows environment in a short-lived PowerShell
 * process and checks a few well-known installation locations before using the optional
 * plugin-bundled runtime.
 */
internal class NodeExecutableResolver(
    private val environment: Map<String, String> = System.getenv(),
    private val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
    private val freshWindowsPath: () -> String? = { readFreshWindowsPath(environment) },
    private val versionProbe: (File) -> String? = ::probeVersion,
) {

    enum class Source {
        PROCESS_PATH,
        CURRENT_WINDOWS_ENVIRONMENT,
        WELL_KNOWN_LOCATION,
        BUNDLED,
    }

    data class Resolution(
        val executable: String?,
        val version: String? = null,
        val source: Source? = null,
        val unsupportedVersion: String? = null,
        val unsupportedExecutable: String? = null,
    )

    fun resolve(bundledNode: Path?): Resolution {
        val visited = linkedSetOf<String>()
        var unsupportedVersion: String? = null
        var unsupportedExecutable: String? = null

        fun inspect(files: Sequence<File>, source: Source): Resolution? {
            for (file in files) {
                if (!file.isFile) continue
                val absolute = file.absoluteFile
                val identity = runCatching { absolute.canonicalPath }.getOrDefault(absolute.path)
                val key = if (isWindows) identity.lowercase() else identity
                if (!visited.add(key)) continue

                val version = runCatching { versionProbe(absolute) }.getOrNull() ?: continue
                val major = NODE_VERSION.find(version)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
                if (major >= MIN_NODE_MAJOR) {
                    return Resolution(absolute.path, version, source)
                }
                if (unsupportedVersion == null) {
                    unsupportedVersion = version
                    unsupportedExecutable = absolute.path
                }
            }
            return null
        }

        inspect(pathCandidates(env("PATH")), Source.PROCESS_PATH)?.let { return it }

        if (isWindows) {
            val currentWindowsPath = runCatching { freshWindowsPath() }.getOrNull()
            inspect(pathCandidates(currentWindowsPath), Source.CURRENT_WINDOWS_ENVIRONMENT)?.let { return it }
            inspect(wellKnownWindowsCandidates(), Source.WELL_KNOWN_LOCATION)?.let { return it }
        }

        val bundledCandidates: Sequence<File> = if (bundledNode != null) {
            sequenceOf(bundledNode.toFile())
        } else {
            emptySequence()
        }
        inspect(bundledCandidates, Source.BUNDLED)?.let { return it }
        return Resolution(
            executable = null,
            unsupportedVersion = unsupportedVersion,
            unsupportedExecutable = unsupportedExecutable,
        )
    }

    private fun pathCandidates(path: String?): Sequence<File> {
        if (path.isNullOrBlank()) return emptySequence()
        val executable = if (isWindows) "node.exe" else "node"
        val separator = if (isWindows) ';' else File.pathSeparatorChar
        return path.split(separator)
            .asSequence()
            .map { it.trim().trim('"') }
            .filter { it.isNotEmpty() }
            .map { File(it, executable) }
    }

    private fun wellKnownWindowsCandidates(): Sequence<File> = sequence {
        // Environment variables cover nvm-windows, Volta and installations that expose a
        // dedicated Node home but have not yet updated the running IDE's PATH.
        env("NODEJS_HOME")?.let { yield(File(it.trim().trim('"'), "node.exe")) }
        env("NVM_SYMLINK")?.let { yield(File(it.trim().trim('"'), "node.exe")) }
        env("VOLTA_HOME")?.let { yield(File(File(it.trim().trim('"'), "bin"), "node.exe")) }

        env("ProgramFiles")?.let { yield(File(File(it, "nodejs"), "node.exe")) }
        env("ProgramFiles(x86)")?.let { yield(File(File(it, "nodejs"), "node.exe")) }
        env("LOCALAPPDATA")?.let { yield(File(File(File(it, "Programs"), "nodejs"), "node.exe")) }
        env("USERPROFILE")?.let { yield(File(File(File(it, ".volta"), "bin"), "node.exe")) }
    }

    private fun env(name: String): String? =
        environment.entries.firstOrNull { it.key.equals(name, ignoreCase = isWindows) }?.value

    companion object {
        const val MIN_NODE_MAJOR = 18
        private val NODE_VERSION = Regex("""^\s*v?(\d+)(?:\.\d+){1,2}""")

        private fun probeVersion(executable: File): String? {
            return runForOutput(listOf(executable.absolutePath, "--version"), VERSION_TIMEOUT_SECONDS)
                ?.trim()
                ?.takeIf { NODE_VERSION.containsMatchIn(it) }
        }

        private fun readFreshWindowsPath(environment: Map<String, String>): String? {
            fun env(name: String): String? =
                environment.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

            val systemRoot = env("SystemRoot")?.takeIf { it.isNotBlank() } ?: "C:\\Windows"
            val systemPowerShell = File(systemRoot, "System32\\WindowsPowerShell\\v1.0\\powershell.exe")
            val powerShell = systemPowerShell.takeIf { it.isFile }?.absolutePath ?: "powershell.exe"
            val script = """
                [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
                ${'$'}vars = @{}
                [Environment]::GetEnvironmentVariables('Machine').GetEnumerator() | ForEach-Object { ${'$'}vars[${'$'}_.Key] = [string]${'$'}_.Value }
                [Environment]::GetEnvironmentVariables('User').GetEnumerator() | ForEach-Object { ${'$'}vars[${'$'}_.Key] = [string]${'$'}_.Value }
                ${'$'}machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
                ${'$'}userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
                ${'$'}path = "${'$'}machinePath;${'$'}userPath"
                ${'$'}expanded = [regex]::Replace(${'$'}path, '%([^%]+)%', {
                    param(${'$'}match)
                    ${'$'}value = ${'$'}vars[${'$'}match.Groups[1].Value]
                    if (${'$'}null -eq ${'$'}value) { ${'$'}match.Value } else { [string]${'$'}value }
                })
                [Console]::Out.Write(${'$'}expanded)
            """.trimIndent()

            return runForOutput(
                listOf(
                    powerShell,
                    "-NoLogo",
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command",
                    script,
                ),
                ENVIRONMENT_TIMEOUT_SECONDS,
            )
                ?.trim()
                ?.trimStart('\uFEFF')
                ?.takeIf { it.isNotBlank() }
        }

        /** Drain output concurrently so a long Windows PATH cannot fill the subprocess pipe. */
        private fun runForOutput(command: List<String>, timeoutSeconds: Long): String? {
            val process = runCatching {
                ProcessBuilder(command).redirectErrorStream(true).start()
            }.getOrNull() ?: return null
            val output = AtomicReference<String?>()
            val reader = thread(start = true, isDaemon = true, name = "DeepSeekHarness-NodeProbe") {
                output.set(
                    runCatching {
                        process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                    }.getOrNull(),
                )
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(1, TimeUnit.SECONDS)
                reader.join(1_000)
                return null
            }
            reader.join(1_000)
            if (process.exitValue() != 0) return null
            return output.get()
        }

        private const val VERSION_TIMEOUT_SECONDS = 3L
        private const val ENVIRONMENT_TIMEOUT_SECONDS = 5L
    }
}
