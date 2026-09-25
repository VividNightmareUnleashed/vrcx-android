import { beforeEach, describe, expect, test, vi } from 'vitest';
import { flushPromises } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';

// On Android the UGC folder is either an SAF tree URI or unset (MediaStore Pictures/VRCX). A PC path that arrives
// with a database imported from the PC must be ignored, so gallery saves never receive it.
const mocks = vi.hoisted(() => ({
    config: new Map(),
    setString: vi.fn()
}));

vi.mock('../../shared/utils/platform', async (importOriginal) => ({
    ...(await importOriginal()),
    isAndroid: true,
    hasLocalGame: false,
    hasLocalVrchatFiles: false
}));
vi.mock('../../services/config', () => ({
    default: {
        getBool: vi.fn(async (key, def) => (mocks.config.has(key) ? mocks.config.get(key) : def)),
        getString: vi.fn(async (key, def) => (mocks.config.has(key) ? mocks.config.get(key) : def)),
        getFloat: vi.fn(async (key, def) => (mocks.config.has(key) ? mocks.config.get(key) : def)),
        getInt: vi.fn(async (key, def) => (mocks.config.has(key) ? mocks.config.get(key) : def)),
        setString: mocks.setString,
        setBool: vi.fn(),
        setFloat: vi.fn(),
        setInt: vi.fn()
    }
}));
vi.mock('../../services/database', () => ({ database: {} }));
vi.mock('../../services/webapi', () => ({ default: {} }));
vi.mock('../../services/appConfig', () => ({ logWebRequest: vi.fn() }));
vi.mock('../../services/watchState', () => ({ watchState: { isLoggedIn: false } }));
vi.mock('../../localization', () => ({ languageCodes: ['en'] }));
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key) => key }) }));
vi.mock('vue-sonner', () => ({ toast: { error: vi.fn(), success: vi.fn() } }));
vi.mock('../game', () => ({ useGameStore: () => ({}) }));
vi.mock('../modal', () => ({ useModalStore: () => ({ confirm: vi.fn() }) }));
vi.mock('../updateLoop', () => ({ useUpdateLoopStore: () => ({}) }));
vi.mock('../vrcxUpdater', () => ({ useVRCXUpdaterStore: () => ({}) }));
vi.mock('../vrcx', () => ({ useVrcxStore: () => ({}) }));

import { useAdvancedSettingsStore } from '../settings/advanced';

const TREE_URI = 'content://com.android.externalstorage.documents/tree/primary%3APictures%2FVRChat';

describe('UGC folder on Android', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        mocks.config.clear();
        mocks.setString.mockClear();
    });

    test('a Windows path from an imported database is treated as unset', async () => {
        mocks.config.set('VRCX_userGeneratedContentPath', 'D:\\VRChat');
        const store = useAdvancedSettingsStore();
        await flushPromises();
        expect(store.ugcFolderPath).toBe('');
    });

    test('an SAF folder chosen on the device is kept', async () => {
        mocks.config.set('VRCX_userGeneratedContentPath', TREE_URI);
        const store = useAdvancedSettingsStore();
        await flushPromises();
        expect(store.ugcFolderPath).toBe(TREE_URI);
    });

    test('setting a non-SAF path stores the default instead', async () => {
        const store = useAdvancedSettingsStore();
        await flushPromises();

        await store.setUGCFolderPath('C:\\Users\\me\\Pictures\\VRChat');
        expect(store.ugcFolderPath).toBe('');
        expect(mocks.setString).toHaveBeenLastCalledWith('VRCX_userGeneratedContentPath', '');

        await store.setUGCFolderPath(TREE_URI);
        expect(store.ugcFolderPath).toBe(TREE_URI);
        expect(mocks.setString).toHaveBeenLastCalledWith('VRCX_userGeneratedContentPath', TREE_URI);
    });
});
