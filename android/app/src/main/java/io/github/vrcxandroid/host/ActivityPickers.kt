package io.github.vrcxandroid.host

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.result.ActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import io.github.vrcxandroid.AppGraph
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Suspend wrappers around the Activity Result API (docs/ARCHITECTURE.md §6.10). MainActivity registers one
 * StartActivityForResult launcher and one RequestPermission launcher in every instance; results reach the pending
 * request here even if the Activity was recreated meanwhile. One picker at a time. Every request returns null (or
 * false) when no Activity is started, when nothing handles the intent, or when the user cancels.
 *
 * Requests are only launched while the Activity is started: a start from a stopped Activity can be blocked by the
 * background activity start rules, and then no result ever arrives and the picker lock would be held for good.
 */
object ActivityPickers {
    private const val TAG = "VRCXPickers"

    private val mutex = Mutex()

    // Main thread only.
    private var pendingResult: CompletableDeferred<ActivityResult>? = null
    private var pendingPermission: CompletableDeferred<PermissionAnswer?>? = null
    private var pendingPermissionName: String? = null
    private var rationaleBefore = false

    /** A permission request's result, with shouldShowRequestPermissionRationale before and after it. */
    data class PermissionAnswer(val granted: Boolean, val rationaleBefore: Boolean, val rationaleAfter: Boolean)

    /** The attached Activity if it is started (visible) and can launch a request. Main thread. */
    private fun launchableActivity(): MainActivity? =
        VrcxHost.activity?.takeIf { VrcxHost.started && !it.isFinishing && !it.isDestroyed }

    fun onActivityResult(result: ActivityResult) {
        pendingResult?.complete(result)
        pendingResult = null
    }

    fun onPermissionResult(activity: Activity, granted: Boolean) {
        val name = pendingPermissionName
        val rationaleAfter = name != null && !granted && ActivityCompat.shouldShowRequestPermissionRationale(activity, name)
        pendingPermission?.complete(PermissionAnswer(granted, rationaleBefore, rationaleAfter))
        pendingPermission = null
        pendingPermissionName = null
    }

    /** The Activity is finishing: nobody will deliver the results any more. */
    fun cancelPending() {
        pendingResult?.complete(ActivityResult(Activity.RESULT_CANCELED, null))
        pendingResult = null
        pendingPermission?.complete(null)
        pendingPermission = null
        pendingPermissionName = null
    }

    suspend fun startForResult(intent: Intent): ActivityResult? = mutex.withLock {
        val deferred = withContext(Dispatchers.Main) {
            val activity = launchableActivity() ?: return@withContext null
            val d = CompletableDeferred<ActivityResult>()
            pendingResult = d
            try {
                activity.resultLauncher.launch(intent)
                d
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "nothing handles ${intent.action}")
                pendingResult = null
                null
            }
        } ?: return@withLock null
        deferred.await()
    }

    /** Shows the runtime permission prompt. Null when it was not shown (no started Activity) or never answered. */
    suspend fun requestPermission(permission: String): PermissionAnswer? = mutex.withLock {
        val deferred = withContext(Dispatchers.Main) {
            val activity = launchableActivity() ?: return@withContext null
            val d = CompletableDeferred<PermissionAnswer?>()
            pendingPermission = d
            pendingPermissionName = permission
            rationaleBefore = ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
            try {
                activity.permissionLauncher.launch(permission)
                d
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "no permission controller for $permission")
                pendingPermission = null
                pendingPermissionName = null
                null
            }
        } ?: return@withLock null
        deferred.await()
    }

    /** ACTION_OPEN_DOCUMENT; the grant is temporary (copy or read the document right away). */
    suspend fun openDocument(mimeTypes: List<String>): Uri? {
        val types = mimeTypes.filter { it.isNotBlank() }.ifEmpty { listOf("*/*") }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
        if (types.size == 1) {
            intent.type = types[0]
        } else {
            intent.type = "*/*"
            intent.putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
        }
        val result = startForResult(intent) ?: return null
        return if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
    }

    /** ACTION_OPEN_DOCUMENT_TREE with a persisted read/write grant. */
    suspend fun openDocumentTree(): Uri? {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val result = startForResult(intent) ?: return null
        if (result.resultCode != Activity.RESULT_OK) return null
        val uri = result.data?.data ?: return null
        try {
            VrcxHost.app.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "could not persist the tree grant", e)
        }
        return uri
    }

    /** ACTION_CREATE_DOCUMENT with a suggested name. */
    suspend fun createDocument(suggestedName: String, mimeType: String): Uri? {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mimeType.ifBlank { "application/octet-stream" })
            .putExtra(Intent.EXTRA_TITLE, suggestedName)
        val result = startForResult(intent) ?: return null
        return if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
    }

    /** `<input type=file>`: Photo Picker when only images are accepted, SAF otherwise. */
    suspend fun chooseFiles(acceptTypes: List<String>, multiple: Boolean): List<Uri>? {
        val context = withContext(Dispatchers.Main) { launchableActivity() } ?: return null
        val mimes = FileChooser.mimeTypesFor(acceptTypes)
        if (FileChooser.isImagesOnly(mimes)) {
            val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            return if (multiple) {
                val contract = ActivityResultContracts.PickMultipleVisualMedia()
                val result = startForResult(contract.createIntent(context, request)) ?: return null
                contract.parseResult(result.resultCode, result.data).ifEmpty { null }
            } else {
                val contract = ActivityResultContracts.PickVisualMedia()
                val result = startForResult(contract.createIntent(context, request)) ?: return null
                contract.parseResult(result.resultCode, result.data)?.let { listOf(it) }
            }
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
        if (mimes.size == 1) {
            intent.type = mimes[0]
        } else {
            intent.type = "*/*"
            if (mimes.isNotEmpty()) intent.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
        }
        if (multiple) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        val result = startForResult(intent) ?: return null
        if (result.resultCode != Activity.RESULT_OK) return null
        val data = result.data ?: return null
        val clip = data.clipData
        if (clip != null && clip.itemCount > 0) return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        return data.data?.let { listOf(it) }
    }
}

/** WebChromeClient.onShowFileChooser: always answers the callback exactly once (null on cancel). */
object FileChooser {
    private var pending: ValueCallback<Array<Uri>>? = null

    fun show(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        pending?.onReceiveValue(null)
        pending = callback
        val accept = params.acceptTypes?.toList().orEmpty()
        val multiple = params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        AppGraph.scope.launch(Dispatchers.Main) {
            val uris = try {
                ActivityPickers.chooseFiles(accept, multiple)
            } catch (t: Throwable) {
                Log.w("VRCXPickers", "file chooser failed", t)
                null
            }
            if (pending === callback) pending = null else return@launch
            callback.onReceiveValue(uris?.takeIf { it.isNotEmpty() }?.toTypedArray())
        }
        return true
    }

    // `accept` values (image/ wildcard, `.json`, `image/png,image/jpeg`) → MIME types; an empty list means anything.
    fun mimeTypesFor(
        acceptTypes: List<String>,
        mimeForExtension: (String) -> String? = { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) },
    ): List<String> = acceptTypes
        .flatMap { it.split(',') }
        .map { it.trim().lowercase(Locale.ROOT) }
        .filter { it.isNotEmpty() }
        .map { accept ->
            if (accept.startsWith(".")) {
                mimeForExtension(accept.substring(1)) ?: "*/*"
            } else {
                accept
            }
        }
        .distinct()
        .let { if ("*/*" in it) emptyList() else it }

    fun isImagesOnly(mimes: List<String>): Boolean = mimes.isNotEmpty() && mimes.all { it.startsWith("image/") }
}
