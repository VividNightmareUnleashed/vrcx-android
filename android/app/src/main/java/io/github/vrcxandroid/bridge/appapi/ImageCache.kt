package io.github.vrcxandroid.bridge.appapi

import io.github.vrcxandroid.bridge.BridgeJson
import io.github.vrcxandroid.bridge.DotNetException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Port of upstream `ImageCache` (Dotnet/ImageCache.cs): notification images cached under
 * `<root>/<fileId>/<version>.png`, downloads limited to an allow-list of hosts, cookies sent only to
 * `api.vrchat.cloud`, and the cache trimmed to the 1000 most recently used entries once it exceeds 1100.
 */
class ImageCache(
    private val root: File,
    private val baseClient: () -> OkHttpClient,
    private val userAgent: () -> String,
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private val hosts = CopyOnWriteArrayList(DEFAULT_HOSTS)

    @Volatile
    private var derived: Pair<OkHttpClient, OkHttpClient>? = null

    val imageHosts: List<String> get() = hosts.toList()

    /** `PopulateImageHosts(json)`: adds the host of every URL in the JSON array. Invalid entries are skipped. */
    fun populateImageHosts(json: String?) {
        if (json == null) throw DotNetException("ArgumentNullException", "Value cannot be null. (Parameter 'json')")
        val array = try {
            BridgeJson.parseToJsonElement(json)
        } catch (e: Exception) {
            throw DotNetException("JsonException", e.message.orEmpty())
        }
        if (array is JsonNull) throw DotNetException("ArgumentNullException", "Value cannot be null. (Parameter 'source')")
        if (array !is JsonArray) throw DotNetException("JsonException", "The JSON value could not be converted to List<string>.")
        for (element in array) {
            val entry = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (entry.isNullOrEmpty()) continue
            val host = hostOf(entry) ?: continue
            if (host.isNotEmpty() && host !in hosts) hosts += host
        }
    }

    /** `GetImage(url, fileId, version)`: absolute path of the cached file, or "" when it cannot be fetched. */
    suspend fun getImage(url: String?, fileId: String?, version: String?): String = withContext(Dispatchers.IO) {
        if (!isSafeName(fileId) || !isSafeName(version)) return@withContext ""
        val dir = File(root, fileId!!)
        val file = File(dir, "$version.png")
        if (file.isFile && file.length() > 0) {
            dir.setLastModified(System.currentTimeMillis())
            return@withContext file.absolutePath
        }
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        try {
            val temp = File(dir, "$version.png.part")
            download(url, temp)
            if (!temp.renameTo(file)) throw IOException("rename failed")
        } catch (e: Exception) {
            log("Failed to fetch image", e)
            return@withContext ""
        }
        val count = root.listFiles { f -> f.isDirectory }?.size ?: 0
        if (count > MAX_ENTRIES) clean()
        file.absolutePath
    }

    /** `SaveImageToFile` minus the file: the downloaded bytes. Throws on failure. */
    suspend fun fetchBytes(url: String?): ByteArray = withContext(Dispatchers.IO) {
        val request = requestFor(url)
        client().newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Response status code does not indicate success: ${response.code}")
            response.body?.bytes() ?: ByteArray(0)
        }
    }

    private fun download(url: String?, target: File) {
        val request = requestFor(url)
        client().newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Response status code does not indicate success: ${response.code}")
            val body = response.body ?: throw IOException("empty body")
            target.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
    }

    private fun requestFor(url: String?): Request {
        val httpUrl = url?.toHttpUrlOrNull() ?: throw DotNetException("UriFormatException", "Invalid URI: The format of the URI could not be determined.")
        if (httpUrl.host !in hosts) throw DotNetException("ArgumentException", "Invalid image host (Parameter '$url')")
        return Request.Builder().url(httpUrl).header("User-Agent", userAgent()).get().build()
    }

    /** The shared client, with a cookie jar that only sends cookies to api.vrchat.cloud and never stores any. */
    private fun client(): OkHttpClient {
        val base = baseClient()
        derived?.let { (b, c) -> if (b === base) return c }
        val c = base.newBuilder().cookieJar(ApiHostOnlyCookieJar(base.cookieJar)).build()
        derived = base to c
        return c
    }

    private fun clean() {
        val dirs = root.listFiles { f -> f.isDirectory } ?: return
        dirs.sortedByDescending { it.lastModified() }.drop(KEEP_ENTRIES).forEach { it.deleteRecursively() }
    }

    class ApiHostOnlyCookieJar(private val base: CookieJar) : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            if (url.host == "api.vrchat.cloud") base.loadForRequest(url) else emptyList()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {}
    }

    companion object {
        val DEFAULT_HOSTS = listOf("api.vrchat.cloud", "files.vrchat.cloud", "d348imysud55la.cloudfront.net", "assets.vrchat.com")
        const val MAX_ENTRIES = 1100
        const val KEEP_ENTRIES = 1000

        /** Host of an absolute URL like .NET `new Uri(url).Host` (lower case), or null when it is not absolute. */
        fun hostOf(url: String): String? {
            url.toHttpUrlOrNull()?.let { return it.host }
            return try {
                val uri = URI(url.trim())
                if (!uri.isAbsolute) null else (uri.host ?: "").lowercase()
            } catch (e: Exception) {
                null
            }
        }

        /** Rejects names that would escape the cache directory. */
        fun isSafeName(name: String?): Boolean =
            !name.isNullOrEmpty() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' }
    }
}
