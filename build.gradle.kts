import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.BuildPluginTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.LocalDate

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.deepseek.dsh"
version = "0.1.10"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // The IntelliJ Platform bundles its own Kotlin stdlib since 2024.2; keep ours compile-only.
    compileOnly(kotlin("stdlib"))
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        // JCEF API classes (com.intellij.ui.jcef.*) ship in the platform core (lib/app-client.jar)
        // and its natives in the JBR — no extra dependency is needed for 2024.3+.
        intellijIdeaCommunity("2024.3.5")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "243"
            untilBuild = "262.*"
        }
    }

    // Marketplace publishing (optional): fill the environment variables below,
    // then run `.\gradlew.bat publishPlugin`. Local builds are unaffected when
    // they are absent.
    //   PUBLISH_TOKEN          JetBrains Hub personal access token (plugins.jetbrains.com/author/me/tokens)
    //   CERTIFICATE_CHAIN      PEM certificate chain (registered on the Marketplace)
    //   PRIVATE_KEY            PEM private key matching the certificate chain
    //   PRIVATE_KEY_PASSWORD   password of the private key (optional)
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = listOf("stable")
    }
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    pluginVerification {
        ides {
            // Support matrix the local environment can verify: the IDEA packages for
            // 2025.3.6.1 / 2026.1.5 are not published to the reachable repositories
            // (skipped on purpose); those generations are still covered by the
            // JetBrains Marketplace-side verification.
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2024.3.5")
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2024.3.7.1")
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2025.1.7.2")
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2.6.3")
        }
    }
}

tasks {
    // The options index is only needed for publishing; skip it to keep builds fast.
    buildSearchableOptions {
        enabled = false
    }
}

// ---------------------------------------------------------------------------------------------
// Bundled DeepSeek Harness runtime
//
// The plugin ships a full dsh installation (node_modules closure) inside the plugin
// distribution so machines WITHOUT a global dsh install work out of the box. The
// profile bundles resolve through $DSH_HOME/profiles/node_modules symlinks that point
// into the installation root, so the complete dependency closure must be present.
//
// Source resolution at build time:
//   1. -PdshRuntimePath=<dir containing node_modules>  (explicit)
//   2. the newest npx cache checkout with node_modules/@deepseek-ai/dsh/package.json
// Disable bundling with -PskipDshRuntime=true (e.g. for a lightweight Marketplace build).
// ---------------------------------------------------------------------------------------------
val dshRuntimeSourcePath: String? = findProperty("dshRuntimePath") as String?
val skipDshRuntime: Boolean = (findProperty("skipDshRuntime") as String?)?.toBoolean() ?: false

fun findDshRuntimeRoot(): File? {
    val candidates = mutableListOf<File>()
    System.getenv("LOCALAPPDATA")?.let { candidates += File(File(it, "npm-cache"), "_npx") }
    System.getProperty("user.home")?.let { candidates += File(File(it, ".npm"), "_npx") }
    var best: File? = null
    var bestTime = 0L
    for (root in candidates) {
        for (dir in root.listFiles { f -> f.isDirectory } ?: emptyArray()) {
            val anchor = File(dir, "node_modules/@deepseek-ai/dsh/package.json")
            if (anchor.isFile && anchor.lastModified() > bestTime) {
                bestTime = anchor.lastModified()
                best = dir
            }
        }
    }
    return best
}

val bundleDshRuntime by tasks.registering(Sync::class) {
    if (!skipDshRuntime) {
        val sourceRoot: File? = dshRuntimeSourcePath?.let(::File) ?: findDshRuntimeRoot()
        val root: File = sourceRoot ?: throw GradleException(
            "bundleDshRuntime: no dsh installation found. Run `npx @deepseek-ai/dsh` once to populate " +
                "the npx cache, pass -PdshRuntimePath=<dir containing node_modules>, or skip bundling " +
                "with -PskipDshRuntime=true."
        )
        from(File(root, "node_modules")) {
            into("dsh-runtime/node_modules")
            // npm bin shims, source maps, docs and TypeScript sources are never needed at runtime.
            exclude("**/.bin/**")
            exclude("**/*.map")
            exclude("**/*.md")
            exclude("**/*.ts")
        }
        into(layout.buildDirectory.dir("bundled-dsh-runtime"))
        // The ide-settings resources are read inside doLast — declare them as inputs so
        // a change re-runs the copy instead of being skipped as UP-TO-DATE.
        inputs.dir(project.file("src/main/resources/dsh/ide-settings"))
        doLast {
            val manifestText = File(root, "node_modules/@deepseek-ai/dsh/package.json").readText()
            val version = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"")
                .find(manifestText)?.groupValues?.get(1) ?: "unknown"
            destinationDir.resolve("dsh-runtime/version.txt").writeText("$version\n")

            // Client settings package: the "For IDE" section in the web UI settings page.
            // Shipped as a real package under the runtime's node_modules (the client-module
            // scanner resolves the composition row name against the profile, and the plugin
            // junctions it into the profile's node_modules at startup).
            val pkgDir = destinationDir.resolve("dsh-runtime/node_modules/dsh-ide-settings")
            pkgDir.mkdirs()
            val resBase = project.file("src/main/resources/dsh/ide-settings")
            File(resBase, "package.json").copyTo(File(pkgDir, "package.json"), overwrite = true)
            File(resBase, "index.js").copyTo(File(pkgDir, "index.js"), overwrite = true)
            val clientJs = File(resBase, "client.js").readText()
                .replace("__PLUGIN_VERSION__", project.version.toString())
                .replace("__BUILD_DATE__", LocalDate.now().toString())
                .replace("__FEEDBACK_URL__", "https://github.com/JayZz210l/deepseek-harness-for-ide/issues")
                .replace("__GITHUB_URL__", "https://github.com/JayZz210l/deepseek-harness-for-ide")
            File(pkgDir, "client.js").writeText(clientJs)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Bundled Node.js runtime (DISABLED by default)
//
// Node.js is NOT bundled by default: the plugin prompts the user to download it
// from nodejs.org instead (smaller distribution). Opt back in with
// -PskipNodeRuntime=false, and point the source with -PnodeRuntimePath=<dir>.
// ---------------------------------------------------------------------------------------------
val nodeRuntimeSourcePath: String? = findProperty("nodeRuntimePath") as String?
val skipNodeRuntime: Boolean = (findProperty("skipNodeRuntime") as String?)?.toBoolean() ?: true

fun findNodeInstallDir(): File? {
    nodeRuntimeSourcePath?.let { return File(it) }
    val pathVar = System.getenv("PATH") ?: ""
    for (dir in pathVar.split(File.pathSeparator)) {
        if (dir.isBlank()) continue
        if (File(dir, "node.exe").isFile || File(dir, "node").isFile) return File(dir)
    }
    return File("C:\\Program Files\\nodejs").takeIf { File(it, "node.exe").isFile }
}

val bundleNodeRuntime by tasks.registering(Sync::class) {
    if (!skipNodeRuntime) {
        val dir: File = findNodeInstallDir()
            ?: throw GradleException(
                "bundleNodeRuntime: node.exe not found. Pass -PnodeRuntimePath=<node install dir>, " +
                    "or skip bundling with -PskipNodeRuntime=true."
            )
        val exe = listOf("node.exe", "node").map { File(dir, it) }.firstOrNull { it.isFile }
            ?: throw GradleException("bundleNodeRuntime: no node executable in ${dir.absolutePath}")
        from(exe) { into("node-runtime") }
        val license = File(dir, "LICENSE")
        if (license.isFile) {
            from(license) { into("node-runtime"); rename { "NODE-LICENSE.txt" } }
        }
        into(layout.buildDirectory.dir("bundled-node-runtime"))
    }
}

// ---------------------------------------------------------------------------------------------
// Build info resource (version + ISO build date) for the update announcement.
// ---------------------------------------------------------------------------------------------
val generateBuildInfo by tasks.registering {
    val outDir = layout.buildDirectory.dir("generated/dsh-build-info")
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile
        dir.mkdirs()
        File(dir, "dsh-build-info.properties").writeText(
            "version=${project.version}\nbuildDate=${LocalDate.now()}\n",
        )
    }
}
sourceSets["main"].resources.srcDir(generateBuildInfo)

tasks.named<BuildPluginTask>("buildPlugin") {
    dependsOn(bundleDshRuntime, bundleNodeRuntime)
    if (!skipDshRuntime) {
        from(layout.buildDirectory.dir("bundled-dsh-runtime"))
    }
    if (!skipNodeRuntime) {
        from(layout.buildDirectory.dir("bundled-node-runtime"))
    }
}
