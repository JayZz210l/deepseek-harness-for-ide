package com.deepseek.dsh.ide.i18n

import com.intellij.DynamicBundle

/**
 * Localized strings for the plugin UI and notifications.
 * Base bundle = English, `messages.DshBundle_zh.properties` = Simplified Chinese.
 */
object DshBundle {
    private val instance = DynamicBundle(DshBundle::class.java, "messages.DshBundle")

    @JvmStatic
    fun message(key: String, vararg params: Any): String =
        instance.getMessage(key, *params)
}
