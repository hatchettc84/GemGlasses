package com.lpecom.gemglasses.bobai.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lpecom.gemglasses.BuildConfig

/**
 * Owns the Office WebView for the lifetime of the BobAI UI so switching tabs
 * does not lose the page, the sign-in, or the history. Created lazily the first
 * time the Office tab is opened. Never logs URLs or page content.
 */
class OfficeWebHost(private val officeUrl: String = BuildConfig.BOBAI_OFFICE_URL) {
    private val policy = OfficeUrlPolicy(officeUrl)

    var webView: WebView? = null
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var loading by mutableStateOf(false)
        private set
    var mainFrameError by mutableStateOf(false)
        private set
    var canGoBack by mutableStateOf(false)
        private set

    @SuppressLint("SetJavaScriptEnabled")
    fun obtain(context: Context, backgroundArgb: Int): WebView {
        webView?.let { existing ->
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }
        val view = WebView(context)
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        view.setBackgroundColor(backgroundArgb)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            setGeolocationEnabled(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, false)
        }
        view.webViewClient = Client(context)
        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, newProgress: Int) {
                progress = newProgress / 100f
            }
        }
        webView = view
        if (policy.isConfigured) {
            loading = true
            view.loadUrl(officeUrl)
        } else {
            mainFrameError = true
        }
        return view
    }

    fun goBack() {
        webView?.takeIf { it.canGoBack() }?.goBack()
    }

    fun retry() {
        val view = webView ?: return
        if (!policy.isConfigured) return
        mainFrameError = false
        loading = true
        if (view.url.isNullOrEmpty() || view.url == "about:blank") view.loadUrl(officeUrl) else view.reload()
    }

    fun onPause() {
        webView?.onPause()
        CookieManager.getInstance().flush()
    }

    fun onResume() {
        webView?.onResume()
    }

    fun destroy() {
        CookieManager.getInstance().flush()
        webView?.let { v ->
            (v.parent as? ViewGroup)?.removeView(v)
            v.stopLoading()
            v.destroy()
        }
        webView = null
    }

    private inner class Client(private val context: Context) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url?.toString()
            val decision = policy.decide(url)
            if (decision == OfficeUrlPolicy.Decision.IN_WEBVIEW) return false
            // Sub-frame navigations nobody tapped are just dropped; never pop an app for them.
            val userInitiated = request.isForMainFrame || request.hasGesture()
            if (decision == OfficeUrlPolicy.Decision.EXTERNAL && userInitiated && url != null) {
                openExternally(url)
            }
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            loading = true
            mainFrameError = false
        }

        override fun onPageFinished(view: WebView, url: String?) {
            loading = false
            canGoBack = view.canGoBack()
            CookieManager.getInstance().flush()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            canGoBack = view.canGoBack()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                mainFrameError = true
                loading = false
            }
        }

        private fun openExternally(url: String) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE)
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                // No app can open it; stay on the office page.
            }
        }
    }
}

/** The Office tab: the office web app (BobAI on Plane) in a persistent WebView. */
@Composable
fun OfficeScreen(host: OfficeWebHost) {
    val background = MaterialTheme.colorScheme.background
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle, host) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> host.onPause()
                Lifecycle.Event.ON_RESUME -> host.onResume()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            // Leaving the tab: persist the sign-in cookie right away.
            CookieManager.getInstance().flush()
        }
    }

    // Only while the Office tab is shown and the page has somewhere to go back to.
    BackHandler(enabled = host.canGoBack && !host.mainFrameError) { host.goBack() }

    Box(Modifier.fillMaxSize().background(background)) {
        AndroidView(
            factory = { host.obtain(it, background.toArgb()) },
            modifier = Modifier.fillMaxSize(),
            // onRelease is left as the default no-op: the WebView outlives the tab and
            // OfficeWebHost.obtain() detaches it from the old holder when the tab returns.
        )
        if (host.loading && !host.mainFrameError) {
            LinearProgressIndicator(
                progress = { host.progress.coerceIn(0.05f, 1f) },
                modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.TopCenter),
            )
        }
        if (host.mainFrameError) {
            OfficeErrorState(onRetry = host::retry)
        }
    }
}

@Composable
private fun OfficeErrorState(onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Can't reach the office. Check the connection and try again.",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Retry", fontWeight = FontWeight.ExtraBold) }
    }
}
