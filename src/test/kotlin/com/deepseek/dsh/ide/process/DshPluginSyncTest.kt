package com.deepseek.dsh.ide.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DshPluginSyncTest {

    @Test
    fun `rollback restores complete previous profile`() {
        val home = Files.createTempDirectory("dsh-sync-rollback")
        val profiles = Files.createDirectories(home.resolve("profiles"))
        val current = Files.createDirectories(profiles.resolve("web"))
        Files.writeString(current.resolve("state.txt"), "incompatible")
        val backup = Files.createDirectories(profiles.resolve(DshPluginSync.BACKUP_NAME))
        Files.writeString(backup.resolve("state.txt"), "working")

        DshPluginSync.rollback(home) {}

        assertEquals("working", Files.readString(current.resolve("state.txt")))
        assertFalse(Files.exists(backup))
    }

    @Test
    fun `commit removes transaction backup`() {
        val home = Files.createTempDirectory("dsh-sync-commit")
        val backup = Files.createDirectories(home.resolve("profiles").resolve(DshPluginSync.BACKUP_NAME))
        Files.writeString(backup.resolve("state.txt"), "working")

        DshPluginSync.commit(home) {}

        assertFalse(Files.exists(backup))
    }

    @Test
    fun `quarantine preserves incompatible profile`() {
        val home = Files.createTempDirectory("dsh-sync-quarantine")
        val web = Files.createDirectories(home.resolve("profiles").resolve("web"))
        Files.writeString(web.resolve("package.json"), "broken")

        val quarantine = DshPluginSync.quarantineIncompatibleProfile(home) {}

        assertTrue(quarantine != null && Files.exists(quarantine.resolve("package.json")))
        assertFalse(Files.exists(web))
    }
}
