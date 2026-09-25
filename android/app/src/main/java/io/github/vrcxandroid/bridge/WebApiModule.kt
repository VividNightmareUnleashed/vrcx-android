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
import io.github.vrcxandroid.bridge.webapi.PersistentCookieJar
import io.github.vrcxandroid.bridge.webapi.ProxySpec
import io.github.vrcxandroid.bridge.webapi.SqliteCookieBlobStore
import io.github.vrcxandroid.bridge.webapi.VrcxVersion
import io.github.vrcxandroid.bridge.webapi.WebApiBridge
import io.github.vrcxandroid.bridge.webapi.WebApiEngine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient

/**
 * `WebApi`: native HTTP for the frontend (ARCHITECTURE.md §5). Calls run concurrently; OkHttp
 * limits them to 10 per host.
 *
 * - [client] is shared with other packages (image cache, ...): persistent cookie jar in the SQLite `cookies` table,
 *   brotli/gzip, 100 s call timeout, `VRCX <version>` User-Agent, .NET redirect rules, and the proxy.
 * - The proxy is read once, here, from `VRCXStorage["VRCX_ProxyServer"]` (changing it requires a restart, as upstream).
 *   It is applied to OkHttp and, where `PROXY_OVERRIDE` is supported, to the WebView. An invalid value is cleared and
 *   reported with a toast; the app continues without a proxy instead of exiting.
 */
class WebApiModule(private val context: Context) : BridgeModule, HttpProvider {
    override val className = WebApiBridge.CLASS_NAME
    override val serialized = false

    private val proxy: ProxySpec? = resolveProxy()

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
        proxy?.let(::applyWebViewProxy)
    }

    override suspend fun invoke(method: String, args: JsonArray): JsonElement = bridge.invoke(method, args)

    override suspend fun flushCookies() = cookieJar.flush()

    /** After a database import: forget the current session and read the imported database's cookies. */
    internal suspend fun onDatabaseReplaced() = cookieJar.reload()

    private fun resolveProxy(): ProxySpec? {
        val raw = try {
            AppGraph.storage.get(PROXY_KEY)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot read $PROXY_KEY", e)
            ""
        }
        return try {
            ProxySpec.parse(raw)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid proxy \"$raw\"; continuing without a proxy", e)
            AppGraph.storage.set(PROXY_KEY, "")
            (AppGraph.storage as? VRCXStorageModule)?.store?.save()
            onMainThread { Toast.makeText(context, INVALID_PROXY_MESSAGE, Toast.LENGTH_LONG).show() }
            null
        }
    }

    private fun applyWebViewProxy(spec: ProxySpec) = onMainThread {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                val config = ProxyConfig.Builder().addProxyRule(spec.webViewRule).build()
                ProxyController.getInstance().setProxyOverride(config, { it.run() }) {
                    Log.i(TAG, "WebView proxy set to ${spec.webViewRule}")
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

    /** Runs now when already on the main thread (the proxy must be set before the page loads), else posts. */
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
