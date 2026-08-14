package com.deepseek.dsh.ide.icons

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * Plugin icons. The tool window icon reference in plugin.xml points at
 * `DshIcons.ToolWindow` through the standard IconLoader `package.class.field` syntax.
 */
object DshIcons {
    @JvmField
    val ToolWindow: Icon = IconLoader.getIcon("/icons/dshToolWindow.svg", DshIcons::class.java)
}
