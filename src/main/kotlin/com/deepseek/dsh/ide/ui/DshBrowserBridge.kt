package com.deepseek.dsh.ide.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.deepseek.dsh.ide.process.DshProcessManager
import java.util.concurrent.ConcurrentLinkedQueue

/** Project-scoped handle used by editor actions to address the visible web composer. */
@Service(Service.Level.PROJECT)
class DshBrowserBridge(private val project: Project) : Disposable {

    @Volatile
    private var browser: JBCefBrowser? = null

    @Volatile
    private var pageLoaded = false

    @Volatile
    private var ideActionQuery: JBCefJSQuery? = null

    private val pending = ConcurrentLinkedQueue<String>()

    fun attach(value: JBCefBrowser) {
        ideActionQuery?.dispose()
        browser = value
        pageLoaded = false
        ideActionQuery = JBCefJSQuery.create(value as JBCefBrowserBase).also { query ->
            query.addHandler { path ->
                project.service<DshProcessManager>().openPathFromBrowser(path)
                JBCefJSQuery.Response("{\"accepted\":true}")
            }
        }
    }

    fun detach(value: JBCefBrowser) {
        if (browser === value) {
            ideActionQuery?.dispose()
            ideActionQuery = null
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
        installIdeActionBridge(target)
        while (true) inject(target, pending.poll() ?: break)
    }

    /** Installs a JCEF-native command channel which cannot be bypassed by web API URL rewriting. */
    private fun installIdeActionBridge(target: JBCefBrowser) {
        val query = ideActionQuery ?: return
        val invoke = query.inject(
            "String(path)",
            "function(response) { resolve(response); }",
            "function(code, message) { reject(new Error(message || ('IDE bridge error ' + code))); }",
        )
        val script = """
            window.__dshIdeOpenPath = function(path) {
              return new Promise(function(resolve, reject) {
                $invoke
              });
            };
        """.trimIndent()
        target.cefBrowser.executeJavaScript(script, target.cefBrowser.url, 0)
    }

    /**
     * Appends text to the active web composer. DSH 0.1.2 uses a Lexical
     * contenteditable while older releases use a controlled React textarea.
     * The small retry loop also covers first-open navigation, where the tool
     * window becomes visible just before the application mounts its composer.
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
              const visible = (el) => el.offsetParent !== null && getComputedStyle(el).visibility !== 'hidden';
              const moveCaretToEnd = (el) => {
                el.focus();
                const selection = window.getSelection();
                if (!selection) return;
                const range = document.createRange();
                range.selectNodeContents(el);
                range.collapse(false);
                selection.removeAllRanges();
                selection.addRange(range);
              };
              const appendContentEditable = () => {
                const editors = Array.from(document.querySelectorAll(
                  '[data-composer-input][contenteditable="true"], [data-lexical-editor="true"][contenteditable="true"]'
                ));
                const input = editors.find((el) => visible(el) && el.getAttribute('aria-disabled') !== 'true');
                if (!input) return false;
                const current = input.textContent || '';
                const separator = current.length === 0 || /\n$/.test(current) ? '' : '\n\n';
                const inserted = separator + addition;
                moveCaretToEnd(input);

                // Lexical owns the DOM state, so deliver a paste event first.
                // Its root handler updates the editor model and cancels the
                // browser default. execCommand is a Chromium fallback for other
                // contenteditable implementations.
                let handled = false;
                try {
                  const transfer = new DataTransfer();
                  transfer.setData('text/plain', inserted);
                  handled = !input.dispatchEvent(new ClipboardEvent('paste', {
                    bubbles: true,
                    cancelable: true,
                    clipboardData: transfer,
                  }));
                } catch (_) {}
                if (!handled) {
                  try { handled = document.execCommand('insertText', false, inserted); } catch (_) {}
                }
                return handled;
              };
              const appendTextarea = () => {
                const inputs = Array.from(document.querySelectorAll('textarea'));
                const input = inputs.find((el) => !el.disabled && !el.readOnly && visible(el));
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
              const append = () => appendContentEditable() || appendTextarea();
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
        ideActionQuery?.dispose()
        ideActionQuery = null
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
