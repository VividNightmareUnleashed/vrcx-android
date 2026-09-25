// Display helpers for Android storage locations (docs/ARCHITECTURE.md §9: prints, stickers and emoji are saved to
// MediaStore `Pictures/VRCX/<type>/<YYYY-MM>/` unless the user picks a folder with the Storage Access Framework).

/** Where the Android bridge saves user-generated content when no folder was chosen. */
export const ANDROID_DEFAULT_UGC_FOLDER = 'Pictures/VRCX';

/**
 * Turns the stored UGC folder (empty, or an SAF tree URI such as
 * `content://com.android.externalstorage.documents/tree/primary%3APictures%2FVRChat`) into a short label.
 *
 * @param {string | null | undefined} path value of `VRCX_userGeneratedContentPath`
 * @returns {string} for example `Pictures/VRChat`, or the default folder when none is set
 */
export function describeUgcFolder(path) {
    const value = typeof path === 'string' ? path.trim() : '';
    if (!value) {
        return ANDROID_DEFAULT_UGC_FOLDER;
    }
    const match = value.match(/\/tree\/([^/?#]+)/);
    if (!match) {
        return value;
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
