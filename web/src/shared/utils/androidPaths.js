// Display helpers for Android storage locations (docs/ARCHITECTURE.md §9: prints, stickers and emoji are saved to
// MediaStore `Pictures/VRCX/<type>/<YYYY-MM>/` unless the user picks a folder with the Storage Access Framework).

/** Where the Android bridge saves user-generated content when no folder was chosen. */
export const ANDROID_DEFAULT_UGC_FOLDER = 'Pictures/VRCX';

/**
 * The UGC folder Android actually uses: an SAF tree URI (`content://.../tree/...`), or `''` for the MediaStore default.
 * Anything else, such as a Windows path that arrived with a database imported from the PC, is treated as unset.
 *
 * @param {string | null | undefined} path Value of `VRCX_userGeneratedContentPath`
 * @returns {string} The tree URI, or `''`
 */
export function normalizeAndroidUgcFolder(path) {
    const value = typeof path === 'string' ? path.trim() : '';
    return /^content:\/\/[^/]+\/tree\/[^/?#]+/i.test(value) ? value : '';
}

/**
 * Turns the stored UGC folder (empty, or an SAF tree URI such as
 * `content://com.android.externalstorage.documents/tree/primary%3APictures%2FVRChat`) into a short label.
 *
 * @param {string | null | undefined} path Value of `VRCX_userGeneratedContentPath`
 * @returns {string} For example `Pictures/VRChat`, or the default folder when none (or a PC path) is set
 */
export function describeUgcFolder(path) {
    const value = normalizeAndroidUgcFolder(path);
    const match = value.match(/\/tree\/([^/?#]+)/);
    if (!match) {
        return ANDROID_DEFAULT_UGC_FOLDER;
    }
    let documentId = match[1];
    try {
        documentId = decodeURIComponent(documentId);
    } catch {
        // keep the raw segment
    }
    const separator = documentId.indexOf(':');
    const volume = separator >= 0 ? documentId.slice(0, separator) : '';
    const relativePath = separator >= 0 ? documentId.slice(separator + 1) : documentId;
    if (!volume || volume === 'primary') {
        return relativePath || '/';
    }
    return relativePath ? `${volume}/${relativePath}` : volume;
}
