package com.statsig.androidsdk

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.Gson
typealias DebugViewCallback = (Boolean) -> Unit // RELOAD_REQUIRED -> callback
class DebugView {
    companion object {
        fun show(
            context: Context,
            sdkKey: String,
            state: Map<String, Any?>,
            callback: DebugViewCallback?
        ) {
            val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            val client = DebugWebViewClient(Gson().toJson(state))
            val chromeClient = DebugWebChromeClient(dialog, callback)
            val webView = getConfiguredWebView(context, client, chromeClient)

            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setOnKeyListener(
                DialogInterface.OnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP &&
                        webView.canGoBack()
                    ) {
                        webView.goBack()

                        if (webView.url?.split("/")?.last()?.startsWith("client_sdk_debugger") ==
                            true
                        ) {
                            dialog.dismiss()
                        }
                        return@OnKeyListener true
                    }
                    return@OnKeyListener false
                }
            )

            webView.loadUrl(
                "https://console.statsig.com/client_sdk_debugger_redirect?sdkKey=$sdkKey"
            )

            dialog.setContentView(webView)
            dialog.show()

            // Do after .show()
            dialog.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        private fun getConfiguredWebView(
            context: Context,
            client: DebugWebViewClient,
            chromeClient: DebugWebChromeClient
        ): WebView {
            val webView = WebView(context)
            webView.webViewClient = client
            webView.webChromeClient = chromeClient

            webView.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            webView.settings.let {
                it.javaScriptCanOpenWindowsAutomatically = true
                it.javaScriptEnabled = true
                it.databaseEnabled = true
                it.domStorageEnabled = true
            }
            webView.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
            return webView
        }
    }

    private class DebugWebViewClient(private val json: String) : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)

            view?.evaluateJavascript("window.__StatsigAndroidDebug=true;", null)

            val js = "window.__StatsigClientState = $json;"
            view?.evaluateJavascript(js, null)
        }
    }

    private class DebugWebChromeClient(
        private val dialog: Dialog,
        private val callback: DebugViewCallback?
    ) : WebChromeClient() {
        private val closeAction = "STATSIG_ANDROID_DEBUG_CLOSE_DIALOG"
        private val reloadRequired = "STATSIG_ANDROID_DEBUG_RELOAD_REQUIRED"

        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
            consoleMessage?.message()?.let {
                if (it.contentEquals(closeAction, ignoreCase = true)) {
                    dialog.dismiss()
                }
                if (it.contentEquals(reloadRequired, ignoreCase = true)) {
                    callback?.invoke(true)
                }
            }

            return super.onConsoleMessage(consoleMessage)
        }
    }
}
