import { describe, expect, test } from 'vitest';

import { ANDROID_DEFAULT_UGC_FOLDER, describeUgcFolder } from '../androidPaths';

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

    test('returns other values unchanged', () => {
        expect(describeUgcFolder('C:\\Users\\me\\Pictures')).toBe('C:\\Users\\me\\Pictures');
    });

    test('shows the storage root for a root tree', () => {
        expect(describeUgcFolder('content://com.android.externalstorage.documents/tree/primary%3A')).toBe('/');
    });
});
