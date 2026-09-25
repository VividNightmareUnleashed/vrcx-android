package io.github.vrcxandroid.host

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Asset loader, navigation policy and renderer-crash recovery (docs/ARCHITECTURE.md §4.2, §6.4-6.6). */
class HostWebViewClient : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        LocalFileServer.intercept(request.url)

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url?.toString()
        return when (val action = NavigationPolicy.decide(url)) {
            NavigationAction.Allow -> false
            is NavigationAction.LaunchCommand -> {
                if (request.isForMainFrame) VrcxHost.deliverLaunchCommand(action.command)
                true
            }
            is NavigationAction.External -> {
                if (request.isForMainFrame) ExternalLinks.open(action.url)
                true
            }
            NavigationAction.Block -> {
                Log.w(TAG, "blocked navigation to ${url?.take(200)}")
                true
            }
        }
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        Log.i(TAG, "page started: $url")
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) {
            Log.e(TAG, "main frame load failed: ${error.errorCode} ${error.description} (${request.url})")
        }
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        if (!request.isForMainFrame) return
        val hint = if (request.url?.toString() == HostUrls.START_URL) " (web bundle missing? run `npm run build:android` in web/)" else ""
        Log.e(TAG, "main frame HTTP ${errorResponse.statusCode} for ${request.url}$hint")
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        WebViewHolder.onRenderProcessGone(view, detail.didCrash())
        return true
    }

    companion object {
        private const val TAG = "VRCXWebView"
    }
}

/** File chooser, console forwarding and permission denial. */
class HostWebChromeClient : WebChromeClient() {
    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        WebConsole.log(consoleMessage)
        return true
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams,
    ): Boolean = FileChooser.show(filePathCallback, fileChooserParams)

    override fun onPermissionRequest(request: PermissionRequest) {
        request.deny()
    }

    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback) {
        callback.invoke(origin, false, false)
    }
}

/**
 * Serves APK assets under `/assets/` and app-private files under `/local/` (docs/ARCHITECTURE.md §6.6). Requests for
 * the app origin that match nothing get a 404 instead of going to the network.
 */
object LocalFileServer {
    /** cacheDir entries that must never be served (the WebView's own caches). */
    private val DENIED_CACHE_DIRS = setOf("WebView", "org.chromium.android_webview")

    private val loader: WebViewAssetLoader by lazy { build() }

    fun intercept(uri: Uri): WebResourceResponse? {
        loader.shouldInterceptRequest(uri)?.let { return it }
        if (uri.host.equals(HostUrls.DOMAIN, ignoreCase = true)) return notFound()
        return null
    }

    private fun build(): WebViewAssetLoader {
        val app = VrcxHost.app
        val paths = VrcxHost.paths
        val local = DirHandler(paths.localRoot, emptySet())
        val cache = DirHandler(paths.cacheRoot, DENIED_CACHE_DIRS)
        val builder = WebViewAssetLoader.Builder()
            .setDomain(HostUrls.DOMAIN)
            .addPathHandler(HostUrls.ASSETS_PREFIX, WebViewAssetLoader.AssetsPathHandler(app))
            // Order matters: the first handler that answers wins, so /local/cache/ goes before /local/.
            .addPathHandler(HostUrls.LOCAL_CACHE_PREFIX, cache)
            .addPathHandler(HostUrls.LOCAL_PREFIX, local)
        // Absolute paths double as same-origin URLs (an AppApi path used directly as <img src>).
        absolutePrefixes(paths.localRoot).forEach { builder.addPathHandler(it, local) }
        absolutePrefixes(paths.cacheRoot).forEach { builder.addPathHandler(it, cache) }
        return builder.build()
    }

    private fun absolutePrefixes(dir: File): Set<String> =
        setOf(dir.absolutePath, LocalPaths.canonical(dir).path).map { it.trimEnd('/') + "/" }.toSet()

    private class DirHandler(private val root: File, private val denied: Set<String>) : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse {
            val file = VrcxHost.paths.resolveServed(root, path, denied)
            if (file == null || !file.isFile) return notFound()
            return try {
                WebResourceResponse(
                    mimeType(file.name), null, 200, "OK",
                    mapOf("Cache-Control" to "no-cache"),
                    FileInputStream(file),
                )
            } catch (e: Exception) {
                notFound()
            }
        }
    }

    fun notFound(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)))

    fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        "json" -> "application/json"
        "txt", "log" -> "text/plain"
        "ics" -> "text/calendar"
        "css" -> "text/css"
        "js", "mjs" -> "text/javascript"
        "html", "htm" -> "text/html"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        else -> "application/octet-stream"
    }
}

/** Page console → logcat (tag VRCXWeb) and a small rotating file log (filesDir/logs/web.log). */
object WebConsole {
    private const val TAG = "VRCXWeb"

    private val fileLog: RotatingFileLog by lazy {
        RotatingFileLog(
            dir = File(VrcxHost.app.filesDir, "logs"),
            name = "web.log",
            executor = Executors.newSingleThreadExecutor { r -> Thread(r, "web-log").apply { isDaemon = true } },
        )
    }
    private val timeFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
    }

    val logFile: File get() = fileLog.file

    fun log(message: ConsoleMessage) {
        val source = message.sourceId()?.substringAfterLast('/')?.substringBefore('?').orEmpty()
        val text = if (source.isEmpty()) message.message() else "${message.message()} ($source:${message.lineNumber()})"
        val (priority, level) = when (message.messageLevel()) {
            ConsoleMessage.MessageLevel.ERROR -> Log.ERROR to "error"
            ConsoleMessage.MessageLevel.WARNING -> Log.WARN to "warn"
            ConsoleMessage.MessageLevel.DEBUG -> Log.DEBUG to "debug"
            ConsoleMessage.MessageLevel.TIP -> Log.VERBOSE to "tip"
            else -> Log.INFO to "info"
        }
        Log.println(priority, TAG, text.take(RotatingFileLog.MAX_LINE))
        fileLog.append("${timeFormat.get()!!.format(Date())} [$level] $text")
    }
}
