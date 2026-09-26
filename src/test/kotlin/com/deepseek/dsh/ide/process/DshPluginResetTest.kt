package com.deepseek.dsh.ide.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class DshPluginResetTest {

    /**
     * Lays out the shape a DSH link-backend release leaves behind: real packages
     * in `web/node_modules` plus `.dsh-module-fallback/node_modules` junctions
     * pointing back at them.
     */
    private fun linkBackendProfile(home: Path, vararg packages: String): Path {
        val web = Files.createDirectories(home.resolve("profiles").resolve("web"))
        val owned = Files.createDirectories(
            web.resolve(DshPluginReset.LINK_PROJECTION_DIR).resolve("node_modules"),
        )
        for (name in packages) {
            val target = Files.createDirectories(web.resolve("node_modules").resolve(name))
            Files.writeString(target.resolve("index.js"), "real package")
            junction(owned.resolve(name), target)
        }
        return web
    }

    private fun junction(link: Path, target: Path) {
        Files.createDirectories(link.parent)
        val process = ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val created = process.waitFor() == 0
        // Junctions need no elevation, but a non-NTFS or locked-down host cannot make one.
        assumeTrue("directory junctions unavailable: $output", created)
    }

    /**
     * The reported failure: the reset renamed `profiles/web` aside while the
     * `.dsh-module-fallback` junctions still pointed into it. Every junction then
     * dangled, and the post-reset boot died on
     * `NoSuchFileException ...\.dsh-module-fallback\node_modules\@antfu\install-pkg`.
     */
    @Test
    fun `reset removes link projections instead of stranding them dangling`() {
        val home = Files.createTempDirectory("dsh-reset-projections")
        val web = linkBackendProfile(home, "marked", "@antfu/install-pkg")
        val backup = home.resolve("profiles").resolve(DshPluginReset.BACKUP_NAME)

        val outcome = DshPluginReset.removeWebProfile(home) {}

        assertNull(outcome.error)
        assertTrue(outcome.removed)
        assertFalse("no projection may survive into the backup", Files.exists(backup.resolve(DshPluginReset.LINK_PROJECTION_DIR)))
        // The projections were the only thing dropped: the real packages moved with the profile.
        assertTrue(Files.exists(backup.resolve("node_modules/marked/index.js")))
        assertTrue(Files.exists(backup.resolve("node_modules/@antfu/install-pkg/index.js")))
    }

    /** A reset commits by deleting the backup; a junction that dangles must not abort that. */
    @Test
    fun `discardBackup removes a tree holding a dangling junction`() {
        val home = Files.createTempDirectory("dsh-reset-dangling")
        val web = linkBackendProfile(home, "marked")
        // Move the profile by hand so the projection is left dangling, as a
        // pre-fix reset left it on disk.
        val backup = home.resolve("profiles").resolve(DshPluginReset.BACKUP_NAME)
        Files.move(web, backup)

        DshPluginReset.discardBackup(home) {}

        assertFalse("backup must be gone", Files.exists(backup))
    }

    /**
     * `walkFileTree` recurses through a junction whose target still resolves, so a
     * delete of the projections could follow them into the packages they point at.
     * The target here is a stand-in for a package shared between profiles: a walk
     * that followed the junction would empty it while clearing the projections.
     */
    @Test
    fun `removing projections never deletes the packages they point at`() {
        val home = Files.createTempDirectory("dsh-reset-live-links")
        val shared = Files.createDirectories(home.resolve("shared-package"))
        Files.writeString(shared.resolve("index.js"), "shared package")
        val web = Files.createDirectories(home.resolve("profiles").resolve("web"))
        val owned = Files.createDirectories(
            web.resolve(DshPluginReset.LINK_PROJECTION_DIR).resolve("node_modules"),
        )
        junction(owned.resolve("shared-package"), shared)

        DshPluginReset.removeWebProfile(home) {}

        assertEquals("shared package", Files.readString(shared.resolve("index.js")))
    }

    @Test
    fun `restoreBackup brings a moved-aside profile back intact`() {
        val home = Files.createTempDirectory("dsh-reset-restore")
        val web = linkBackendProfile(home, "marked")
        Files.writeString(web.resolve("cordis.yml"), "profile state")

        DshPluginReset.removeWebProfile(home) {}
        // A failed post-reset boot re-creates a partial profile, which restore replaces.
        Files.createDirectories(web)
        Files.writeString(web.resolve("partial.txt"), "half initialized")
        DshPluginReset.restoreBackup(home) {}

        assertEquals("profile state", Files.readString(web.resolve("cordis.yml")))
        assertFalse(Files.exists(web.resolve("partial.txt")))
        assertFalse(Files.exists(home.resolve("profiles").resolve(DshPluginReset.BACKUP_NAME)))
    }

    @Test
    fun `reset reports nothing to do without a web profile`() {
        val home = Files.createTempDirectory("dsh-reset-empty")

        val outcome = DshPluginReset.removeWebProfile(home) {}

        assertFalse(outcome.removed)
        assertNull(outcome.error)
    }
}
