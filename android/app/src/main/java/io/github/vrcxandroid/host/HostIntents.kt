package io.github.vrcxandroid.host

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.FileProvider
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/** http(s) links: Custom Tab when possible, otherwise ACTION_VIEW (docs/ARCHITECTURE.md §6.4). */
object ExternalLinks {
    fun isWebUrl(url: String?): Boolean {
        val scheme = url?.trim()?.substringBefore(':', "")?.lowercase(Locale.ROOT)
        return scheme == "http" || scheme == "https"
    }

    fun open(url: String): Boolean {
        if (!isWebUrl(url)) return false
        val uri = Uri.parse(url.trim())
        return VrcxHost.onMainBlocking(false) {
            val activity = VrcxHost.activity?.takeUnless { it.isFinishing || it.isDestroyed }
            val frame = HostTheme.current.frame
            val tab = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(frame).build())
                .build()
            try {
                if (activity != null) {
                    tab.launchUrl(activity, uri)
                } else {
                    tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    tab.launchUrl(VrcxHost.app, uri)
                }
                true
            } catch (e: ActivityNotFoundException) {
                VrcxHost.startIntent(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
            } catch (e: SecurityException) {
                false
            }
        }
    }
}

/** QR scanning through the Google code scanner (no camera permission needed). */
object QrScanner {
    suspend fun scan(): String? = withContext(Dispatchers.Main) {
        val context: Context = VrcxHost.activity ?: return@withContext null
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        val scanner = try {
            GmsBarcodeScanning.getClient(context, options)
        } catch (t: Throwable) {
            Log.w("VRCXQr", "code scanner unavailable", t)
            return@withContext null
        }
        suspendCancellableCoroutine { cont ->
            scanner.startScan()
                .addOnSuccessListener { if (cont.isActive) cont.resume(it.rawValue) }
                .addOnCanceledListener { if (cont.isActive) cont.resume(null) }
                .addOnFailureListener {
                    Log.w("VRCXQr", "scan failed", it)
                    if (cont.isActive) cont.resume(null)
                }
        }
    }
}

/** Files shared with other apps. */
object HostFiles {
    fun authority(context: Context): String = "${context.packageName}.fileprovider"

    /** content:// URI (FileProvider) for a file in one of the folders res/xml/file_paths.xml shares. */
    fun contentUri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, authority(context), file)

    fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    fun writeBytes(context: Context, uri: Uri, bytes: ByteArray) {
        context.contentResolver.openOutputStream(uri, "wt").use { out ->
            requireNotNull(out) { "Could not open $uri for writing" }
            out.write(bytes)
        }
    }

    /** Puts a PNG on the clipboard through FileProvider (cacheDir/clipboard). */
    fun copyImageToClipboard(context: Context, png: ByteArray) {
        val dir = File(context.cacheDir, "clipboard").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "image-${System.currentTimeMillis()}.png")
        file.writeBytes(png)
        val uri = contentUri(context, file)
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "image", uri))
    }
}

/** File-name helpers (pure). */
object FileNames {
    // File-system separators and reserved characters, plus '#' and '%': a picked copy's absolute path doubles as an
    // `<img src>` URL (docs/ARCHITECTURE.md §6.6), where '#' would start a fragment and '%' an escape.
    private val FORBIDDEN = Regex("[\\\\/:*?\"<>|#%\\p{Cntrl}]")

    fun sanitize(name: String?, fallback: String): String {
        val cleaned = name.orEmpty().replace(FORBIDDEN, "_").trim().trimStart('.').take(120)
        return cleaned.ifEmpty { fallback }
    }
}
