package io.github.vrcxandroid.bridge.webapi

import io.github.vrcxandroid.bridge.BridgeJson
import io.github.vrcxandroid.bridge.DotNetException
import io.github.vrcxandroid.bridge.storage.JsonText
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.util.Base64
import java.util.TimeZone
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The `(status, message)` pair upstream returns as `Tuple<int,string>`. */
data class WebApiResult(val status: Int, val message: String)

/** Failure with a .NET-style message; becomes status -1. */
class WebApiException(message: String) : Exception(message)

/**
 * Port of upstream `WebApi.Execute`.
 *
 * - Upload flags are tested by key presence, in the order `uploadImageLegacy`, `uploadFilePUT`, `uploadImage`,
 *   `uploadImagePrint`; otherwise a standard request is built from `method`, `body` and `headers`.
 * - Non-2xx statuses are ordinary results. `image/...` and `application/octet-stream` bodies become
 *   `data:image/png;base64,...`. Any failure (transport, timeout, bad input) is status -1 with the error message; the
 *   JS promise is never rejected for those.
 */
class WebApiEngine(
    private val client: () -> OkHttpClient,
    private val images: UploadImageProcessor,
    /** Awaited before each request (loads the cookie jar off the OkHttp threads). */
    private val beforeRequest: suspend () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** `WebApi.ExecuteJson(string)`: `{"status":<int>,"message":<string>}`. Unparseable JSON rejects, like upstream. */
    suspend fun executeJson(options: String): String {
        val result = when (val parsed = BridgeJson.parseToJsonElement(options)) {
            is JsonObject -> execute(parsed)
            is JsonNull -> WebApiResult(-1, NULL_REFERENCE)
            // Newtonsoft cannot read a non-object into Dictionary<string, object>; that throws outside Execute's catch.
            else -> throw DotNetException(
                "JsonSerializationException",
                "Cannot deserialize the current JSON value into type 'System.Collections.Generic.Dictionary`2[System.String,System.Object]'.",
            )
        }
        val sb = StringBuilder(result.message.length + 32)
        sb.append("{\"status\":").append(result.status).append(",\"message\":")
        JsonText.appendQuoted(sb, result.message)
        sb.append('}')
        return sb.toString()
    }

    /** `WebApi.Execute(IDictionary)`. */
    suspend fun execute(options: JsonObject?): WebApiResult {
        return try {
            if (options == null) throw WebApiException(NULL_REFERENCE)
            val request = buildRequest(options)
            beforeRequest()
            send(request)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            WebApiResult(-1, messageOf(e))
        }
    }

    internal fun buildRequest(options: JsonObject): Request {
        val url = requireString(options, "url")
        val builder = when {
            options.containsKey("uploadImageLegacy") -> legacyImageUpload(url, options)
            options.containsKey("uploadFilePUT") -> filePut(url, options)
            options.containsKey("uploadImage") -> imageUpload(url, options)
            options.containsKey("uploadImagePrint") -> printUpload(url, options)
            else -> standard(url, options)
        }
        val headers = parseHeaders(options["headers"])
        if (headers != null) {
            val merged = builder.build().headers.newBuilder()
            for ((key, value) in headers) {
                if (key.equals("Content-Type", ignoreCase = true)) continue
                if (key.equals("Referer", ignoreCase = true)) {
                    merged.set("Referer", referrer(value))
                } else {
                    try {
                        merged.addUnsafeNonAscii(key, value)
                    } catch (_: IllegalArgumentException) {
                        // .NET TryAddWithoutValidation silently skips names it cannot use.
                    }
                }
            }
            builder.headers(merged.build())
        }
        return builder.build()
    }

    // ---- standard request (WebApi.cs:408-436) ----

    private fun standard(url: String, options: JsonObject): Request.Builder {
        val method = when (val m = options["method"]) {
            null, is JsonNull -> "GET"
            is JsonPrimitive -> m.content.uppercase()
            else -> m.toString().uppercase()
        }
        var body: RequestBody? = null
        val bodyValue = options["body"]
        if (method != "GET" && bodyValue != null && bodyValue !is JsonNull) {
            val text = (bodyValue as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw WebApiException("Unable to cast object of type 'Newtonsoft.Json.Linq.JToken' to type 'System.String'.")
            val contentType = parseHeaders(options["headers"])?.lastOrNull { it.first == "Content-Type" }?.second
                ?.let(::parseContentType) ?: TEXT_PLAIN
            body = text.toByteArray(Charsets.UTF_8).toRequestBody(contentType)
        }
        if (body != null && !permitsRequestBody(method)) body = null // HEAD: OkHttp cannot send a body.
        if (body == null && requiresRequestBody(method)) body = ByteArray(0).toRequestBody(null)
        return Request.Builder().url(parseUrl(url)).method(method, body)
    }

    // ---- uploads (WebApi.cs:265-371) ----

    private fun legacyImageUpload(url: String, options: JsonObject): Request.Builder {
        val multipart = multipart()
        if (options.containsKey("postData")) multipart.addPart(textPart("data", castString(options["postData"], "content")))
        val image = images.resizeToFitLimits(base64(castString(options["imageData"] ?: missing("imageData"), "s")), false)
        multipart.addPart(filePart("image", "image.png", image))
        return Request.Builder().url(parseUrl(url)).post(multipart.build())
    }

    private fun filePut(url: String, options: JsonObject): Request.Builder {
        val data = base64(castString(options["fileData"] ?: missing("fileData"), "s"))
        val mime = castString(options["fileMIME"] ?: missing("fileMIME"), "mediaType")
        val mediaType = parseBareMediaType(mime)
        val builder = Request.Builder().url(parseUrl(url)).put(data.toRequestBody(mediaType))
        val md5 = options["fileMD5"]
        if (md5 != null && md5 !is JsonNull) {
            // Decoded and re-encoded, so the header carries canonical base64 (it is part of the S3 signature).
            builder.header("Content-MD5", Base64.getEncoder().encodeToString(base64(castString(md5, "s"))))
        }
        return builder
    }

    private fun imageUpload(url: String, options: JsonObject): Request.Builder {
        val multipart = multipart()
        if (options.containsKey("postData")) {
            val postData = castString(options["postData"], "value")
            when (val parsed = BridgeJson.parseToJsonElement(postData)) {
                is JsonObject -> for ((key, value) in parsed) multipart.addPart(textPart(key, jTokenText(value)))
                is JsonNull -> Unit
                else -> throw WebApiException("Unable to cast object of type 'Newtonsoft.Json.Linq.JArray' to type 'Newtonsoft.Json.Linq.JObject'.")
            }
        }
        val matching = (options["matchingDimensions"] as? JsonPrimitive)?.booleanOrNull ?: false
        val image = images.resizeToFitLimits(base64(castString(options["imageData"] ?: missing("imageData"), "s")), matching)
        multipart.addPart(filePart("file", "blob", image))
        return Request.Builder().url(parseUrl(url)).post(multipart.build())
    }

    private fun printUpload(url: String, options: JsonObject): Request.Builder {
        val crop = (options["cropWhiteBorder"] as? JsonPrimitive)?.booleanOrNull ?: false
        val source = base64(castString(options["imageData"] ?: missing("imageData"), "s"))
        val multipart = multipart()
        multipart.addPart(filePart("image", "image", images.preparePrint(source, crop)))
        val postData = options["postData"]
        if (postData != null && postData !is JsonNull) {
            val text = (postData as? JsonPrimitive)?.takeIf { it.isString }?.content ?: postData.toString()
            when (val parsed = BridgeJson.parseToJsonElement(text)) {
                is JsonObject -> for ((key, value) in parsed) {
                    val part = when {
                        value is JsonNull -> throw WebApiException("Value cannot be null. (Parameter 'content')")
                        value is JsonPrimitive -> jTokenText(value)
                        else -> throw WebApiException("Unexpected character encountered while parsing value.")
                    }
                    multipart.addPart(textPart(key, part))
                }
                is JsonNull -> Unit
                else -> throw WebApiException("Cannot deserialize the current JSON array into type 'System.Collections.Generic.Dictionary`2[System.String,System.String]'.")
            }
        } else if (postData is JsonNull) {
            throw WebApiException(NULL_REFERENCE)
        }
        return Request.Builder().url(parseUrl(url)).post(multipart.build())
    }

    private fun multipart(): MultipartBody.Builder =
        MultipartBody.Builder(boundary()).setType(MultipartBody.FORM)

    /** .NET: `"---------------------------" + DateTime.Now.Ticks.ToString("x")`. */
    private fun boundary(): String {
        val now = clock()
        val localMillis = now + TimeZone.getDefault().getOffset(now)
        val ticks = localMillis * 10_000L + 621_355_968_000_000_000L
        return "---------------------------" + java.lang.Long.toHexString(ticks)
    }

    private fun textPart(name: String, value: String): MultipartBody.Part =
        MultipartBody.Part.createFormData(name, null, value.toByteArray(Charsets.UTF_8).toRequestBody(TEXT_PLAIN))

    private fun filePart(name: String, fileName: String, bytes: ByteArray): MultipartBody.Part =
        MultipartBody.Part.createFormData(name, fileName, bytes.toRequestBody(IMAGE_PNG))

    // ---- sending ----

    private suspend fun send(request: Request): WebApiResult {
        val call = client().newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = try {
                        response.use { readResponse(it) }
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                        return
                    }
                    continuation.resume(result)
                }
            })
        }
    }

    private fun readResponse(response: Response): WebApiResult {
        val mediaType = response.header("Content-Type")?.substringBefore(';')?.trim().orEmpty()
        val body = response.body
        if (mediaType.contains("image/") || mediaType.contains("application/octet-stream")) {
            val bytes = body?.bytes() ?: ByteArray(0)
            return WebApiResult(response.code, "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes))
        }
        return WebApiResult(response.code, body?.string().orEmpty())
    }

    // ---- helpers ----

    /** Header pairs in object order; values stringified the way Newtonsoft converts them to `string`. */
    private fun parseHeaders(element: JsonElement?): List<Pair<String, String>>? = when (element) {
        null, is JsonNull -> null
        is JsonObject -> element.map { (key, value) -> key to jTokenText(value) }
        else -> throw WebApiException("Unable to cast object of type 'Newtonsoft.Json.Linq.JValue' to type 'System.Collections.Generic.IEnumerable`1[System.Collections.Generic.KeyValuePair`2[System.String,System.Object]]'.")
    }

    /** `JToken.ToString()` / Newtonsoft string conversion: strings as-is, booleans `True`/`False`, null empty. */
    private fun jTokenText(value: JsonElement): String = when (value) {
        is JsonNull -> ""
        is JsonPrimitive -> when {
            value.isString -> value.content
            value.content == "true" -> "True"
            value.content == "false" -> "False"
            else -> value.content
        }
        is JsonObject, is JsonArray -> value.toString()
    }

    private fun requireString(options: JsonObject, key: String): String {
        val element = options[key] ?: missing(key)
        return castString(element, key)
    }

    private fun castString(element: JsonElement?, parameter: String): String = when {
        element == null || element is JsonNull -> throw WebApiException("Value cannot be null. (Parameter '$parameter')")
        element is JsonPrimitive && element.isString -> element.content
        element is JsonPrimitive -> element.content
        else -> throw WebApiException("Unable to cast object of type 'Newtonsoft.Json.Linq.JObject' to type 'System.String'.")
    }

    private fun missing(key: String): Nothing = throw WebApiException("The given key '$key' was not present in the dictionary.")

    private fun parseUrl(url: String) = url.trim().toHttpUrlOrNull() ?: run {
        val scheme = url.substringBefore("://", "").takeIf { it.isNotEmpty() && url.contains("://") }
        if (scheme != null && !scheme.equals("http", true) && !scheme.equals("https", true)) {
            throw WebApiException("The '$scheme' scheme is not supported.")
        }
        throw WebApiException("An invalid request URI was provided. Either the request URI must be an absolute URI or BaseAddress must be set.")
    }

    /** `request.Headers.Referrer = new Uri(value)`: must be absolute; http(s) URIs are normalized like `Uri.AbsoluteUri`. */
    private fun referrer(value: String): String {
        value.toHttpUrlOrNull()?.let { return it.toString() }
        val uri = try {
            URI(value)
        } catch (e: Exception) {
            null
        }
        if (uri == null || !uri.isAbsolute) throw WebApiException("Invalid URI: The format of the URI could not be determined.")
        return uri.toASCIIString()
    }

    /** `MediaTypeHeaderValue.Parse`: re-serialized as `type/subtype; name=value`. */
    private fun parseContentType(value: String): MediaType {
        val parts = value.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        val normalized = parts.joinToString("; ")
        return normalized.toMediaTypeOrNull() ?: throw WebApiException("The format of value '$value' is invalid.")
    }

    /** `new MediaTypeHeaderValue(mime)`: a bare `type/subtype`, sent exactly as given. */
    private fun parseBareMediaType(value: String): MediaType {
        if (value.contains(';') || value.trim() != value) throw WebApiException("The format of value '$value' is invalid.")
        return value.toMediaTypeOrNull() ?: throw WebApiException("The format of value '$value' is invalid.")
    }

    private fun base64(text: String): ByteArray = try {
        Base64.getDecoder().decode(text.filterNot { it == ' ' || it == '\t' || it == '\r' || it == '\n' })
    } catch (e: IllegalArgumentException) {
        throw WebApiException(
            "The input is not a valid Base-64 string as it contains a non-base 64 character, more than two padding characters, or an illegal character among the padding characters.",
        )
    }

    // OkHttp's Request.Builder.method() rules.
    private fun permitsRequestBody(method: String) = !(method == "GET" || method == "HEAD")

    private fun requiresRequestBody(method: String) =
        method == "POST" || method == "PUT" || method == "PATCH" || method == "PROPPATCH" || method == "REPORT"

    private fun messageOf(e: Exception): String = when {
        e is InterruptedIOException && e.message == "timeout" ->
            "The request was canceled due to the configured HttpClient.Timeout of ${HttpClients.CALL_TIMEOUT_SECONDS} seconds elapsing."
        else -> e.message ?: e.toString()
    }

    companion object {
        private const val NULL_REFERENCE = "Object reference not set to an instance of an object."
        val TEXT_PLAIN = "text/plain; charset=utf-8".toMediaType()
        val IMAGE_PNG = "image/png".toMediaType()
    }
}
