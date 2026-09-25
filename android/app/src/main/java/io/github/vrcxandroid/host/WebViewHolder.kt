package io.github.vrcxandroid.host

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.vrcxandroid.AppGraph
import io.github.vrcxandroid.BuildConfig
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The one app-scoped WebView (docs/ARCHITECTURE.md §6.1-6.2). It outlives the Activity: the Activity attaches it in
 * onCreate and detaches it in onDestroy, and a [MutableContextWrapper] points it at the Activity while attached (native
 * `<select>`, date pickers and file choosers need an Activity context). It is only destroyed after a renderer crash.
 * Main thread only, except [blockBridge].
 */
@SuppressLint("StaticFieldLeak")
object WebViewHolder {
    private const val TAG = "VRCXWebView"
    const val NATIVE_OBJECT = "VRCXNative"
    private const val HELLO_PREFIX = "{\"t\":\"hello\""

    private var webView: WebView? = null
    private var wrapper: MutableContextWrapper? = null
    private var shimSource: String? = null

    @Volatile
    private var bridgeBlocked = false

    /** Parses page messages off the main thread, in arrival order (BridgeDispatcher.handle parses the JSON). */
    private val inbound: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bridge-in").apply { isDaemon = true }
    }

    /** Background colour shown before the page paints (the VRCX frame colour of the current theme). */
    var backgroundColor: Int = ThemeColors.forMode(ThemeColors.LIGHT)!!.frame
        set(value) {
            field = value
            webView?.setBackgroundColor(value)
        }

    val current: WebView? get() = webView

    /** Creates the WebView on first use and adds it to [container]. */
    fun attach(activity: MainActivity, container: ViewGroup): WebView {
        val existing = webView
        val wv = existing ?: create(activity)
        wrapper?.baseContext = activity
        (wv.parent as? ViewGroup)?.removeView(wv)
        container.addView(wv, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (existing == null) load(wv)
        return wv
    }

    /** Removes the WebView from [activity] without destroying it and points its context back at the application. */
    fun detach(activity: MainActivity) {
        val wv = webView ?: return
        val w = wrapper ?: return
        if (w.baseContext !== activity) return
        (wv.parent as? ViewGroup)?.removeView(wv)
        w.baseContext = activity.applicationContext
    }

    fun pauseForBackground() {
        webView?.let {
            it.onPause()
            it.pauseTimers()
        }
    }

    fun resumeAfterBackground() {
        webView?.let {
            it.resumeTimers()
            it.onResume()
        }
    }

    /** Stops routing page calls to the bridge (restart/quit in progress). Any thread. */
    fun blockBridge() {
        bridgeBlocked = true
    }

    fun evaluate(script: String, callback: ((String?) -> Unit)? = null) {
        val wv = webView
        if (wv == null) {
            callback?.invoke(null)
            return
        }
        wv.evaluateJavascript(script) { callback?.invoke(it) }
    }

    /** WebViewClient.onRenderProcessGone: replace the WebView and reload (ARCHITECTURE.md §6.5). */
    fun onRenderProcessGone(view: WebView, didCrash: Boolean) {
        Log.e(TAG, "renderer gone (crashed=$didCrash), recreating the WebView")
        if (view !== webView) {
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
            return
        }
        VrcxHost.events.disconnect()
        VrcxHost.setPendingLaunchCommand(LaunchCommands.crash(didCrash))
        val parent = view.parent as? ViewGroup
        val index = parent?.indexOfChild(view) ?: 0
        parent?.removeView(view)
        webView = null
        view.destroy()
        val base: Context = VrcxHost.activity ?: VrcxHost.app
        val wv = create(base)
        parent?.addView(wv, index, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        load(wv)
        if (VrcxHost.pausedForBackground) wv.onPause()
    }

    private fun create(base: Context): WebView {
        val ctx = MutableContextWrapper(base)
        wrapper = ctx
        val wv = WebView(ctx)
        configure(wv)
        webView = wv
        return wv
    }

    private fun load(wv: WebView) {
        wv.loadUrl(HostUrls.START_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(wv: WebView) {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = false
        s.allowContentAccess = true
        @Suppress("DEPRECATION")
        run {
            s.allowFileAccessFromFileURLs = false
            s.allowUniversalAccessFromFileURLs = false
        }
        s.textZoom = 100
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setGeolocationEnabled(false)
        s.cacheMode = WebSettings.LOAD_DEFAULT
        // Set before the first loadUrl: changing the UA while a page loads makes the WebView load it again.
        s.userAgentString = s.userAgentString + " VRCX/" + BuildConfig.VRCX_VERSION
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, false)
        }
        CookieManager.getInstance().setAcceptCookie(true)

        wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        wv.setBackgroundColor(backgroundColor)
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) wv.isForceDarkAllowed = false

        wv.webViewClient = HostWebViewClient()
        wv.webChromeClient = HostWebChromeClient()
        wv.setDownloadListener { url, _, _, _, _ ->
            // <a download> with data:/blob: is handled by the shim; plain http(s) downloads go to the browser.
            if (url.startsWith("http://") || url.startsWith("https://")) ExternalLinks.open(url)
        }
        installBridge(wv)
    }

    // WebViewGate.check (MainActivity.onCreate) guarantees DOCUMENT_START_SCRIPT and WEB_MESSAGE_LISTENER before any
    // WebView is created, so the feature checks lint asks for are already done.
    @SuppressLint("RequiresFeature")
    private fun installBridge(wv: WebView) {
        val ctx = wv.context
        val config = BridgeConfig.json(
            vrcxVersion = BuildConfig.VRCX_VERSION,
            appVersion = BuildConfig.VERSION_NAME,
            abi = Build.SUPPORTED_ABIS.firstOrNull(),
            sdkInt = Build.VERSION.SDK_INT,
            debug = BuildConfig.DEBUG,
        )
        val script = BridgeConfig.prelude(config) + shim(ctx)
        WebViewCompat.addDocumentStartJavaScript(wv, script, setOf(HostUrls.ORIGIN))
        WebViewCompat.addWebMessageListener(wv, NATIVE_OBJECT, setOf(HostUrls.ORIGIN)) { view, message, _, isMainFrame, replyProxy ->
            onMessage(view, message, isMainFrame, replyProxy)
        }
    }

    private fun shim(context: Context): String {
        shimSource?.let { return it }
        val text = context.assets.open("shim/vrcx-android-shim.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
        shimSource = text
        return text
    }

    @SuppressLint("RequiresFeature")
    private fun onMessage(view: WebView, message: WebMessageCompat, isMainFrame: Boolean, proxy: JavaScriptReplyProxy) {
        if (!isMainFrame || view !== webView) return
        val data = message.data ?: return
        if (data.startsWith(HELLO_PREFIX)) {
            onPageHello(proxy)
            return
        }
        if (bridgeBlocked) return
        inbound.execute {
            AppGraph.dispatcher.handle(data) { reply ->
                VrcxHost.main.post { runCatching { proxy.postMessage(reply) } }
            }
        }
    }

    /** The shim of a freshly loaded page announced itself: route events to it and replay the state events. */
    @SuppressLint("RequiresFeature")
    private fun onPageHello(proxy: JavaScriptReplyProxy) {
        Log.i(TAG, "page connected")
        VrcxHost.events.connect { text -> VrcxHost.main.post { runCatching { proxy.postMessage(text) } } }
        if (BuildConfig.DEBUG) DebugSelfTest.onPageConnected()
    }
}
