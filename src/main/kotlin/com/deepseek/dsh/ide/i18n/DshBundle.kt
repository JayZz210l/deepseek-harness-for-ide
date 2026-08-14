package com.deepseek.dsh.ide.i18n

import com.intellij.DynamicBundle

/**
 * Localized strings for the plugin UI and notifications.
 * Base bundle = English, `messages.DshBundle_zh.properties` = Simplified Chinese.
 */
class DshBundle : DynamicBundle("messages.DshBundle") {

    companion object {
        private val INSTANCE = DshBundle()

        @JvmStatic
        fun message(key: String, vararg params: Any): String =
            INSTANCE.getMessage(key, *params)
    }
}
