package io.github.vrcxandroid.bridge.appapi.docs

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import android.provider.MediaStore

/**
 * Which `content://` URIs the AppApi file methods may open for the page (docs/ARCHITECTURE.md §6.6): documents the user
 * handed to the app (a picker grant, persisted or not, or a document inside a granted tree) and MediaStore items, whose
 * provider only lets the app reach its own items. Never the app's own providers (the FileProvider would expose any
 * app-private file) and never other apps' providers the app was not given anything from.
 */
object ContentGrants {
    fun canUse(context: Context, uri: Uri): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        val authority = uri.authority ?: return false
        if (isOwnAuthority(authority, context.packageName)) return false
        if (authority == MediaStore.AUTHORITY) return true
        val persisted = try {
            context.contentResolver.persistedUriPermissions
        } catch (e: Exception) {
            emptyList()
        }
        if (ContentDoc.isTreeDocument(uri)) {
            val tree = DocumentsContract.buildTreeDocumentUri(authority, DocumentsContract.getTreeDocumentId(uri))
            if (persisted.any { it.isReadPermission && it.uri == tree }) return true
        }
        if (persisted.any { it.isReadPermission && it.uri == uri }) return true
        // A picker's temporary grant (the grant table only: public providers and the app's own ones do not count).
        return context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** The app's own authorities (`<package>` and `<package>.*`, such as the FileProvider). */
    fun isOwnAuthority(authority: String, packageName: String): Boolean =
        authority.equals(packageName, ignoreCase = true) || authority.startsWith("$packageName.", ignoreCase = true)
}
