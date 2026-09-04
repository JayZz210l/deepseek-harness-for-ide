package com.deepseek.dsh.ide.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBuilder
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import javax.swing.JComponent

/**
 * JCEF container hosting the DeepSeek Harness web app. Created lazily on the EDT,
 * disposed together with the project.
 */
class JcefBrowserPanel(project: Project) {

    private val browser: JBCefBrowser = JBCefBrowserBuilder().build()

    val component: JComponent = browser.component

    private var loadedUrl: String? = null

    /** Loads [url] into the browser; a no-op when the same URL is already loaded. */
    fun load(url: String) {
        if (url == loadedUrl) return
        loadedUrl = url
        browser.loadURL(url)
    }

    /**
     * Forces the next [load] to reload the page even when the URL is unchanged.
     * Called whenever the server leaves the RUNNING state, so a restart with a
     * fixed port re-runs the web app's initial workspace selection instead of
     * leaving a stale "choose workspace" page in place.
     */
    fun markDirty() {
        loadedUrl = null
    }

    init {
        val bridge = project.service<DshBrowserBridge>()
        bridge.attach(browser)
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadingStateChange(
                cefBrowser: CefBrowser?,
                isLoading: Boolean,
                canGoBack: Boolean,
                canGoForward: Boolean,
            ) {
                if (isLoading) bridge.markLoading()
            }

            override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true) bridge.markLoaded()
            }
        }, browser.cefBrowser)
        Disposer.register(project) { bridge.detach(browser) }
        Disposer.register(project, browser)
    }
}
