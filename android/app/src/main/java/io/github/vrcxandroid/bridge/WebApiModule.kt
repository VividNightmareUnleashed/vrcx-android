package io.github.vrcxandroid.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.widget.Toast
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import io.github.vrcxandroid.AppGraph
import io.github.vrcxandroid.HttpProvider
import io.github.vrcxandroid.bridge.webapi.BitmapUploadImageProcessor
import io.github.vrcxandroid.bridge.webapi.HttpClients
import io.github.vrcxandroid.bridge.webapi.InvalidProxyException
import io.github.vrcxandroid.bridge.webapi.PersistentCookieJar
import io.github.vrcxandroid.bridge.webapi.ProxySpec
import io.github.vrcxandroid.bridge.webapi.SqliteCookieBlobStore
import io.github.vrcxandroid.bridge.webapi.VrcxVersion
import io.github.vrcxandroid.bridge.webapi.WebApiBridge
import io.github.vrcxandroid.bridge.webapi.WebApiEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient

/**
 * `WebApi`: native HTTP for the frontend (ARCHITECTURE.md §5). Calls run concurrently; OkHttp
 * limits them to 10 per host.
 *
 * - [client] is shared with other packages (image cache, ...): persistent cookie jar in the SQLite `cookies` table,
 *   brotli/gzip, 100 s call timeout, `VRCX <version>` User-Agent, .NET redirect rules, and the proxy. It is built on
 *   first use, which may wait for the proxy setting, so it must be used from worker threads.
 * - The proxy is read once per process from `VRCXStorage["VRCX_ProxyServer"]` (changing it requires a restart, as
 *   upstream). The read starts on the IO dispatcher when the module is created, so `Application.onCreate` does no disk
 *   I/O. The proxy is applied to OkHttp and, where `PROXY_OVERRIDE` is supported, to the WebView (posted to the main
 *   thread; the page's own traffic starts long after that).
 * - A malformed value is cleared and reported with a toast, and the app continues without a proxy instead of exiting
 *   (upstream resets it only on `UriFormatException` too). A well-formed value OkHttp cannot use (`https://`) is kept:
 *   the WebView still uses it, native requests fail with an explanation instead of bypassing it, and a toast says so.
 */
class WebApiModule(private val context: Context) : BridgeModule, HttpProvider {
    override val className = WebApiBridge.CLASS_NAME
    override val serialized = false

    /** Resolved once, off the main thread (see [init]); whoever needs it first computes it or waits for it. */
    private val proxy: ProxySpec? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { resolveProxy() }

    internal val cookieJar = PersistentCookieJar(
        SqliteCookieBlobStore { AppGraph.database as SQLiteModule },
        AppGraph.scope,
    )

    override val client: OkHttpClient by lazy { HttpClients.create(cookieJar, VrcxVersion.current, proxy) }

    private val bridge by lazy {
        WebApiBridge(
            WebApiEngine({ client }, BitmapUploadImageProcessor(), cookieJar::ensureLoaded),
            cookieJar,
            ::clearWebViewCookies,
        )
    }

    init {
        AppGraph.scope.launch(Dispatchers.IO) { proxy }
    }

    override suspend fun invoke(method: String, args: JsonArray): JsonElement = bridge.invoke(method, args)

    override suspend fun flushCookies() = cookieJar.flush()

    /**
     * Database import, on the SQLite lane right before the file is replaced: forget the current session so no save
     * still queued on the lane writes it into the imported database. The imported cookies are read on next use.
     */
    internal fun onDatabaseReplacing() = cookieJar.invalidate()

    private fun resolveProxy(): ProxySpec? {
        val raw = try {
            AppGraph.storage.get(PROXY_KEY)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot read $PROXY_KEY ${SafeLog.kind(e)}")
            ""
        }
        val spec = try {
            ProxySpec.parse(raw)
        } catch (e: InvalidProxyException) {
            // The value may hold credentials, so it is not logged.
            Log.e(TAG, "Invalid proxy URI ${SafeLog.kind(e)}; the setting is cleared and VRCX continues without a proxy")
            AppGraph.storage.set(PROXY_KEY, "")
            (AppGraph.storage as? VRCXStorageModule)?.store?.save()
            toast(INVALID_PROXY_MESSAGE)
            return null
        } ?: return null
        spec.webViewRule?.let(::applyWebViewProxy)
        if (spec.nativeKind == null) {
            Log.w(TAG, "Proxy ${spec.displayName} cannot carry native requests; they fail until the proxy is changed")
            toast(
                "The proxy server ${spec.displayName} is not supported for VRCX's own requests on Android, so they " +
                    "will fail.\nChange it to an http://, socks4:// or socks5:// proxy, or clear it.",
            )
        }
        return spec
    }

    private fun toast(message: String) = onMainThread {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    private fun applyWebViewProxy(rule: String) = onMainThread {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                val config = ProxyConfig.Builder().addProxyRule(rule).build()
                ProxyController.getInstance().setProxyOverride(config, { it.run() }) {
                    Log.i(TAG, "WebView proxy set to $rule")
                }
            } else {
                Log.w(TAG, "WebView does not support PROXY_OVERRIDE; only native requests use the proxy")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot apply the proxy to the WebView", e)
        }
    }

    private fun clearWebViewCookies() = onMainThread {
        try {
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot clear WebView cookies", e)
        }
    }

    /** Runs now when already on the main thread, else posts. */
    private fun onMainThread(block: () -> Unit) {
        val main = Looper.getMainLooper()
        if (Looper.myLooper() == main) block() else Handler(main).post(block)
    }

    private companion object {
        const val TAG = "WebApi"
        const val PROXY_KEY = "VRCX_ProxyServer"
        const val INVALID_PROXY_MESSAGE =
            "The proxy server URI you used is invalid.\nIt has been cleared; VRCX continues without a proxy."
    }
}
