import { describe, expect, test } from 'vitest';

import { ANDROID_DEFAULT_UGC_FOLDER, describeUgcFolder, normalizeAndroidUgcFolder } from '../androidPaths';

describe('describeUgcFolder', () => {
    test('uses the MediaStore default when no folder is set', () => {
        expect(describeUgcFolder('')).toBe(ANDROID_DEFAULT_UGC_FOLDER);
        expect(describeUgcFolder(null)).toBe('Pictures/VRCX');
        expect(describeUgcFolder('   ')).toBe('Pictures/VRCX');
    });

    test('shows the relative path of a primary storage tree URI', () => {
        expect(
            describeUgcFolder('content://com.android.externalstorage.documents/tree/primary%3APictures%2FVRChat')
        ).toBe('Pictures/VRChat');
    });

    test('ignores a document suffix after the tree id', () => {
        expect(
            describeUgcFolder(
                'content://com.android.externalstorage.documents/tree/primary%3ADCIM/document/primary%3ADCIM%2FX'
            )
        ).toBe('DCIM');
    });

    test('keeps the volume name for SD cards', () => {
        expect(describeUgcFolder('content://com.android.externalstorage.documents/tree/1A2B-3C4D%3AVRChat')).toBe(
            '1A2B-3C4D/VRChat'
        );
    });

    test('shows the default folder for a PC path from an imported database', () => {
        expect(describeUgcFolder('D:\\VRChat')).toBe(ANDROID_DEFAULT_UGC_FOLDER);
        expect(describeUgcFolder('/home/me/Pictures/VRChat')).toBe(ANDROID_DEFAULT_UGC_FOLDER);
    });

    test('shows the storage root for a root tree', () => {
        expect(describeUgcFolder('content://com.android.externalstorage.documents/tree/primary%3A')).toBe('/');
    });
});

describe('normalizeAndroidUgcFolder', () => {
    test('keeps SAF tree URIs', () => {
        const uri = 'content://com.android.externalstorage.documents/tree/primary%3APictures%2FVRChat';
        expect(normalizeAndroidUgcFolder(uri)).toBe(uri);
        expect(normalizeAndroidUgcFolder(` ${uri} `)).toBe(uri);
    });

    test('treats Windows, Linux and non-tree values as unset', () => {
        expect(normalizeAndroidUgcFolder('D:\\VRChat')).toBe('');
        expect(normalizeAndroidUgcFolder('C:\\Users\\me\\Pictures\\VRChat')).toBe('');
        expect(normalizeAndroidUgcFolder('/home/me/Pictures/VRChat')).toBe('');
        expect(normalizeAndroidUgcFolder('content://media/external/images/media/12')).toBe('');
        expect(normalizeAndroidUgcFolder(null)).toBe('');
        expect(normalizeAndroidUgcFolder(undefined)).toBe('');
    });
});
