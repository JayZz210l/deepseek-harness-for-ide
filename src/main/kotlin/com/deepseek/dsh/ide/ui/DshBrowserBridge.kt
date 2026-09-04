package com.deepseek.dsh.ide.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefBrowser
import java.util.concurrent.ConcurrentLinkedQueue

/** Project-scoped handle used by editor actions to address the visible web composer. */
@Service(Service.Level.PROJECT)
class DshBrowserBridge(private val project: Project) : Disposable {

    @Volatile
    private var browser: JBCefBrowser? = null

    @Volatile
    private var pageLoaded = false

    private val pending = ConcurrentLinkedQueue<String>()

    fun attach(value: JBCefBrowser) {
        browser = value
        pageLoaded = false
    }

    fun detach(value: JBCefBrowser) {
        if (browser === value) {
            browser = null
            pageLoaded = false
        }
    }

    fun markLoading() {
        pageLoaded = false
    }

    fun markLoaded() {
        pageLoaded = true
        val target = browser ?: return
        while (true) inject(target, pending.poll() ?: break)
    }

    /**
     * Appends text to the controlled React textarea.  The small retry loop also
     * covers first-open navigation, where the tool window becomes visible just
     * before the web application mounts its composer.
     */
    fun appendToComposer(text: String): Boolean {
        val target = browser ?: return false
        if (!pageLoaded) {
            pending.add(text)
            return true
        }
        inject(target, text)
        return true
    }

    private fun inject(target: JBCefBrowser, text: String) {
        val encoded = jsString(text)
        val script = """
            (() => {
              const addition = $encoded;
              let attempts = 0;
              const append = () => {
                const inputs = Array.from(document.querySelectorAll('textarea'));
                const input = inputs.find((el) => !el.disabled && !el.readOnly && el.offsetParent !== null);
                if (!input) return false;
                const current = input.value || '';
                const separator = current.length === 0 || /\n$/.test(current) ? '' : '\n\n';
                const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set;
                setter.call(input, current + separator + addition);
                input.dispatchEvent(new Event('input', { bubbles: true }));
                input.dispatchEvent(new Event('change', { bubbles: true }));
                input.focus();
                input.setSelectionRange(input.value.length, input.value.length);
                return true;
              };
              if (append()) return;
              const timer = setInterval(() => {
                attempts += 1;
                if (append() || attempts >= 60) clearInterval(timer);
              }, 250);
            })();
        """.trimIndent()
        target.cefBrowser.executeJavaScript(script, target.cefBrowser.url, 0)
    }

    override fun dispose() {
        browser = null
        pending.clear()
    }

    private fun jsString(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }
}
