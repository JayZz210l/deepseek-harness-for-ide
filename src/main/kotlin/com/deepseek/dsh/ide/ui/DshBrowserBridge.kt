package com.deepseek.dsh.ide.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.deepseek.dsh.ide.process.DshProcessManager
import java.nio.charset.StandardCharsets
import java.util.Base64
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

    @Volatile
    private var openFilesQuery: JBCefJSQuery? = null

    private val pending = ConcurrentLinkedQueue<String>()

    fun attach(value: JBCefBrowser) {
        ideActionQuery?.dispose()
        openFilesQuery?.dispose()
        browser = value
        pageLoaded = false
        ideActionQuery = JBCefJSQuery.create(value as JBCefBrowserBase).also { query ->
            query.addHandler { payload ->
                val diff = decodeDiffPayload(payload)
                val open = decodeOpenPayload(payload)
                if (diff != null) {
                    project.service<DshProcessManager>().openDiffFromBrowser(
                        diff.path,
                        diff.beforeText,
                        diff.afterText,
                    )
                } else {
                    project.service<DshProcessManager>().openPathFromBrowser(
                        open?.path ?: payload,
                        open?.line,
                    )
                }
                JBCefJSQuery.Response("{\"accepted\":true}")
            }
        }
        openFilesQuery = JBCefJSQuery.create(value as JBCefBrowserBase).also { query ->
            query.addHandler {
                val paths = project.service<DshProcessManager>().openEditorFilesJsonFromBrowser()
                JBCefJSQuery.Response(paths)
            }
        }
    }

    fun detach(value: JBCefBrowser) {
        if (browser === value) {
            ideActionQuery?.dispose()
            ideActionQuery = null
            openFilesQuery?.dispose()
            openFilesQuery = null
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
            "String(payload)",
            "function(response) { resolve(response); }",
            "function(code, message) { reject(new Error(message || ('IDE bridge error ' + code))); }",
        )
        val invokeDiff = query.inject(
            "String(payload)",
            "function(response) { resolve(response); }",
            "function(code, message) { reject(new Error(message || ('IDE bridge error ' + code))); }",
        )
        val invokeFiles = openFilesQuery?.inject(
            "",
            "function(response) { resolve(response); }",
            "function(code, message) { reject(new Error(message || ('IDE files bridge error ' + code))); }",
        ) ?: "resolve([])"
        val script = """
            window.__dshIdeOpenPath = function(path, line) {
              const encode = function(value) {
                const bytes = new TextEncoder().encode(String(value));
                let binary = '';
                for (let offset = 0; offset < bytes.length; offset += 8192) {
                  binary += String.fromCharCode.apply(null, bytes.subarray(offset, offset + 8192));
                }
                return btoa(binary);
              };
              const payload = Number.isInteger(line) && line > 0
                ? ['dsh-ide-open-v1', encode(path), String(line)].join('\n')
                : String(path);
              return new Promise(function(resolve, reject) {
                $invoke
              });
            };
            window.__dshIdeOpenDiff = function(path, diffs) {
              const encode = function(value) {
                const bytes = new TextEncoder().encode(String(value));
                let binary = '';
                // Avoid spreading large edits onto the JS call stack.
                for (let offset = 0; offset < bytes.length; offset += 8192) {
                  binary += String.fromCharCode.apply(null, bytes.subarray(offset, offset + 8192));
                }
                return btoa(binary);
              };
              const normalized = String(path).replaceAll('\\\\', '/').toLowerCase();
              const candidates = Array.isArray(diffs) ? diffs.filter(function(diff) {
                if (!diff || typeof diff.path !== 'string') return false;
                const candidate = diff.path.replaceAll('\\\\', '/').toLowerCase();
                return candidate === normalized || candidate.endsWith('/' + normalized) || normalized.endsWith('/' + candidate);
              }) : [];
              const selected = candidates.length > 0 ? candidates : (Array.isArray(diffs) ? diffs : []);
              const before = selected.map(function(diff) { return diff && typeof diff.oldText === 'string' ? diff.oldText : ''; }).join('\n\n');
              const after = selected.map(function(diff) { return diff && typeof diff.newText === 'string' ? diff.newText : ''; }).join('\n\n');
              const payload = ['dsh-ide-diff-v1', encode(path), encode(before), encode(after)].join('\n');
              return new Promise(function(resolve, reject) {
                $invokeDiff
              });
            };
            // Read the current/open editor snapshot directly from the JetBrains
            // process. This remains available even when the DSH proxy page is
            // reconnecting or its /__dsh_ide route is temporarily unavailable.
            window.__dshIdeOpenFiles = function() {
              return new Promise(function(resolve, reject) {
                $invokeFiles
              });
            };

            // DSH 0.1.2 tool rows render the underlined file name as a button
            // and send its openWorkspacePath call over the long-lived Remote
            // transport.  A TCP proxy cannot rewrite a single message inside
            // that stream, so catch the button before React dispatches it to
            // the desktop host. Markdown/result links are handled here too.
            if (!window.__dshIdeLocalFileLinkHandlerInstalled) {
              window.__dshIdeLocalFileLinkHandlerInstalled = true;
              const localPathFromLink = function(link) {
                const raw = (link.getAttribute('href') || '').trim();
                if (/^[A-Za-z]:[\\/]/.test(raw) || /^\\\\/.test(raw)) return raw;
                // Markdown commonly spells workspace files as relative links.
                // DSH has no anchor-based client-side routes, so these can go
                // straight to the IDE while explicit web schemes remain web.
                if (raw && !raw.startsWith('#') && !raw.startsWith('//') &&
                    !/^[A-Za-z][A-Za-z0-9+.-]*:/.test(raw)) {
                  const relative = raw.split(/[?#]/, 1)[0];
                  if (relative && !relative.startsWith('/api/')) {
                    try { return decodeURIComponent(relative); } catch (_) { return relative; }
                  }
                }
                try {
                  const url = new URL(raw, window.location.href);
                  if (url.protocol !== 'file:') return null;
                  let path = decodeURIComponent(url.pathname);
                  // file:///C:/... has a leading slash which is not part of a
                  // Windows drive path. UNC URLs retain their host name.
                  if (/^\/[A-Za-z]:[\\/]/.test(path)) path = path.slice(1);
                  if (url.host) path = '\\\\' + url.host + path.replaceAll('/', '\\\\');
                  return path;
                } catch (_) {
                  return null;
                }
              };
              const localPathFromToolButton = function(element) {
                // The bundled ToolRow routes both ordinary file opens and
                // authoritative Edit hunks itself; do not pre-empt its React handler.
                if (window.__dshIdeToolFileBridgeAvailable === true) return null;
                const button = element.closest('button');
                if (!button || !button.closest('[data-tool]')) return null;
                // CSS-module prefixes change between DSH builds; the semantic
                // suffix and dotted underline are both stable identifiers for
                // ToolRow's dedicated file button.
                const classMatch = Array.from(button.classList).some(function(name) {
                  return /(?:^|_)fileLink$/.test(name);
                });
                const decoration = getComputedStyle(button).textDecorationLine || '';
                if (!classMatch && !decoration.includes('underline')) return null;
                const path = (button.textContent || '').trim();
                return path || null;
              };
              const localPathFromProducedFile = function(element) {
                const button = element.closest('button');
                if (!button) return null;
                if (button.closest('[data-produced-files-row]')) {
                  return (button.getAttribute('title') || button.textContent || '').trim() || null;
                }
                const showFolder = Array.from(button.classList).some(function(name) {
                  return /(?:^|_)showFolder$/.test(name);
                });
                return showFolder ? '.' : null;
              };
              const localPathFromFileMention = function(element) {
                const button = element.closest('button[title]');
                if (!button) return null;
                const isMention = Array.from(button.classList).some(function(name) {
                  return /(?:^|_)fileMention$/.test(name);
                });
                return isMention ? (button.getAttribute('title') || '').trim() || null : null;
              };
              const ideSettingsAction = function(element) {
                const button = element.closest('button');
                if (!button) return null;
                const label = (button.textContent || '').trim();
                if (label === '打开配置文件' || label === 'Open configuration file') {
                  return 'dsh-ide://open-settings-document';
                }
                return null;
              };
              window.addEventListener('click', function(event) {
                if (event.defaultPrevented || event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
                const element = event.target instanceof Element ? event.target : null;
                if (!element) return;
                const link = element.closest('a[href]');
                const path = link ? localPathFromLink(link) :
                  localPathFromToolButton(element) ||
                  localPathFromProducedFile(element) ||
                  localPathFromFileMention(element) ||
                  ideSettingsAction(element);
                if (!path) return;
                event.preventDefault();
                event.stopPropagation();
                window.__dshIdeOpenPath(path).catch(function(error) {
                  console.warn('DeepSeek Harness IDE could not open local file', error);
                });
              }, true);
            }
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
        openFilesQuery?.dispose()
        openFilesQuery = null
        browser = null
        pending.clear()
    }

    private data class BrowserDiffPayload(
        val path: String,
        val beforeText: String,
        val afterText: String,
    )

    private data class BrowserOpenPayload(
        val path: String,
        val line: Int?,
    )

    private fun decodeOpenPayload(payload: String): BrowserOpenPayload? {
        if (!payload.startsWith("dsh-ide-open-v1\n")) return null
        val parts = payload.split('\n', limit = 3)
        if (parts.size != 3) return null
        return runCatching {
            BrowserOpenPayload(
                path = String(Base64.getDecoder().decode(parts[1]), StandardCharsets.UTF_8),
                line = parts[2].toIntOrNull()?.takeIf { it > 0 },
            )
        }.getOrNull()
    }

    private fun decodeDiffPayload(payload: String): BrowserDiffPayload? {
        if (!payload.startsWith("dsh-ide-diff-v1\n")) return null
        val parts = payload.split('\n', limit = 4)
        if (parts.size != 4) return null
        return runCatching {
            val decoder = Base64.getDecoder()
            fun decode(value: String): String = String(decoder.decode(value), StandardCharsets.UTF_8)
            BrowserDiffPayload(
                path = decode(parts[1]),
                beforeText = decode(parts[2]),
                afterText = decode(parts[3]),
            )
        }.getOrNull()
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
