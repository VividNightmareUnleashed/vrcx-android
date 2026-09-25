import { describe, expect, it, vi } from 'vitest';

vi.mock('../../../plugins', () => ({
    i18n: { global: { t: (key) => key } }
}));

import { MY_AVATARS_PHONE_HIDDEN_COLUMNS, getMyAvatarsTableOptions } from '../myAvatarsTableOptions';
import { getColumns } from '../columns';

describe('getMyAvatarsTableOptions', () => {
    it('keeps the PC table state untouched', () => {
        expect(getMyAvatarsTableOptions(false)).toEqual({ persistKey: 'my-avatars' });
    });

    it('gives phones their own state with the detail columns hidden', () => {
        const options = getMyAvatarsTableOptions(true);
        expect(options.persistKey).toBe('my-avatars-phone');
        expect(options.initialColumnVisibility).toEqual({
            version: false,
            impostor: false,
            pcPerf: false,
            androidPerf: false,
            iosPerf: false,
            created_at: false
        });
    });

    it('only hides columns that exist and can be shown again', () => {
        const columns = getColumns({
            onShowAvatarDialog: () => {},
            onContextMenuAction: () => {},
            currentAvatarId: { value: '' }
        });
        const byId = new Map(columns.map((column) => [column.id, column]));
        for (const id of MY_AVATARS_PHONE_HIDDEN_COLUMNS) {
            expect(byId.has(id)).toBe(true);
            // A label makes the column toggleable in the View options sheet.
            expect(byId.get(id).meta?.label).toBeTypeOf('function');
        }
    });

    it('places every column in a phone card slot', () => {
        const columns = getColumns({
            onShowAvatarDialog: () => {},
            onContextMenuAction: () => {},
            currentAvatarId: { value: '' }
        });
        const slots = Object.fromEntries(columns.map((column) => [column.id, column.meta?.mobile?.slot]));
        expect(slots).toMatchObject({
            thumbnail: 'leading',
            name: 'title',
            visibility: 'badge',
            platforms: 'badge',
            customTags: 'body',
            timeSpent: 'footer',
            actions: 'actions'
        });
        expect(Object.values(slots).every(Boolean)).toBe(true);
    });
});
