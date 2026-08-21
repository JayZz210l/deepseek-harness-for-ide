package com.deepseek.dsh.ide.process

import java.util.Properties

/**
 * Build-time facts baked into the plugin jar by the Gradle build
 * (`dsh-build-info.properties`: version and ISO build date), used by the
 * update announcement shown after the startup environment checks.
 */
object DshBuildInfo {

    private const val RESOURCE = "/dsh-build-info.properties"

    private fun load(): Properties? = javaClass.getResourceAsStream(RESOURCE)?.use { input ->
        Properties().apply { load(input) }
    }

    /** Plugin version baked into this jar, or null when unavailable. */
    fun version(): String? = load()?.getProperty("version")

    /** ISO build date of this plugin jar, or null when unavailable. */
    fun buildDate(): String? = load()?.getProperty("buildDate")
}
