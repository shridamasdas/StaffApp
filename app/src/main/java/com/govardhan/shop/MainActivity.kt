package com.govardhan.shop

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var printer: Printer

    private val home = "https://appassets.androidplatform.net/assets/index.html"
    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        printer = Printer(this)

        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.addJavascriptInterface(Bridge(), "AndroidApp")
        web.webChromeClient = WebChromeClient()          // shows confirm() dialogs and date pickers
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleUrl(request.url.toString())
        }

        // Phone back button: go back inside the app first, close only from Home
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.appBack ? appBack() : false") { r ->
                    if (r != "true") finish()
                }
            }
        })

        if (savedInstanceState == null) web.loadUrl(home) else web.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    private fun handleUrl(url: String): Boolean {
        if (url.startsWith("https://appassets.androidplatform.net/")) return false

        // Receipt buttons: "intent:base64,<ESC/POS data>#Intent;..." -> print over Bluetooth
        if (url.startsWith("intent:base64,")) {
            try {
                val data = url.removePrefix("intent:base64,").substringBefore("#Intent")
                printer.print(Base64.decode(data, Base64.DEFAULT))
            } catch (e: Exception) {
                toast("Could not read the receipt data")
            }
            return true
        }

        // Everything else (WhatsApp, phone links, websites) opens in the right app
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast("No app found to open this link")
        }
        return true
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    inner class Bridge {
        @JavascriptInterface
        fun config(): String = assets.open("config.json").bufferedReader().use { it.readText() }

        @JavascriptInterface
        fun choosePrinter() = runOnUiThread { printer.choose(null) }
    }
}
