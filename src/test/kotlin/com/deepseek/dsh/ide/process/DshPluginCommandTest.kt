package com.deepseek.dsh.ide.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DshPluginCommandTest {
    @Test
    fun `parses full dsh install command and shorthand spec`() {
        assertEquals(
            "@scope/plugin@latest",
            DshPluginCommand.parse("dsh plugin --profile web add @scope/plugin@latest"),
        )
        assertEquals("https://github.com/example/plugin.git", DshPluginCommand.parse("https://github.com/example/plugin.git"))
    }

    @Test
    fun `rejects commands outside plugin add scope and unsafe input`() {
        assertNull(DshPluginCommand.parse("dsh web"))
        assertNull(DshPluginCommand.parse("dsh plugin --profile default add foo"))
        assertNull(DshPluginCommand.parse("dsh plugin --profile web add foo; whoami"))
    }
}
