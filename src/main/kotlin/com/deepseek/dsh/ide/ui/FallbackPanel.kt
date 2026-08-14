package com.deepseek.dsh.ide.ui

import com.deepseek.dsh.ide.i18n.DshBundle
import com.intellij.ide.BrowserUtil
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Shown when the running IDE cannot use JCEF: offers the live URL and an
 * "open in external browser" action instead of the embedded view.
 *
 * Note: [link] must be declared before [component] — the component initializer
 * runs during construction and would otherwise see a null link (NPE).
 */
class FallbackPanel {

    private val link = HyperlinkLabel()

    private var currentUrl: String? = null

    val component: JComponent = buildPanel()

    fun setUrl(url: String) {
        currentUrl = url
        link.setHyperlinkText(url)
        link.setHyperlinkTarget(url)
        link.toolTipText = url
        link.revalidate()
    }

    private fun buildPanel(): JComponent {
        val panel = JBPanel<JBPanel<*>>(BorderLayout()).apply { border = JBUI.Borders.empty(16) }

        val text = JBLabel("<html><body style='width: 260px'>${DshBundle.message("dsh.fallback.text")}</body></html>")

        val center = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(text)
            add(Box.createVerticalStrut(8))
            add(link)
        }

        val openButton = JButton(DshBundle.message("dsh.fallback.open")).apply {
            addActionListener {
                val url = currentUrl
                if (url != null) runCatching { BrowserUtil.browse(url) }
            }
        }
        val buttonRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(openButton)
            add(Box.createHorizontalGlue())
        }

        panel.add(center, BorderLayout.NORTH)
        panel.add(buttonRow, BorderLayout.SOUTH)
        return panel
    }
}
