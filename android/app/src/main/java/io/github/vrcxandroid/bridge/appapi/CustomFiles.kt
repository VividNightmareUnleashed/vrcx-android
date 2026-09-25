package io.github.vrcxandroid.bridge.appapi

import java.io.File

/**
 * Where `CustomCss` / `CustomScript` read the user's `custom.css` / `custom.js` from. ARCHITECTURE.md §9 puts both in
 * `getExternalFilesDir(null)`, which users reach from file managers and over USB as `Android/data/<package>/files/`.
 *
 * `custom.js` runs inside the page with full bridge access (SQLite with the saved logins, the WebApi cookies, every
 * AppApi file method). Before Android 11 any app holding WRITE_EXTERNAL_STORAGE can write `Android/data/<package>`
 * (on Android 10 through legacy storage), so there the script is read only from the internal files directory, which
 * no other app can write. `custom.css` cannot run code and is read from the external folder on every version.
 */
object CustomFiles {
    const val CSS = "custom.css"
    const val SCRIPT = "custom.js"

    /** Android 11: scoped storage keeps other apps out of `Android/data/<package>`. */
    const val SCOPED_STORAGE_SDK = 30

    /** The folder `custom.js` is read from on [sdkInt]; null when there is none. */
    fun scriptDir(sdkInt: Int, externalFilesDir: File?, internalFilesDir: File?): File? =
        if (sdkInt >= SCOPED_STORAGE_SDK) externalFilesDir else internalFilesDir
}
