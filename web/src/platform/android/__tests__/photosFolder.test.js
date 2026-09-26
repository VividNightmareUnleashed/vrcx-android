import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';

// The VRChat photos folder on Android: the Photos folder tool and the Screenshot Manager hint.
const mocks = vi.hoisted(() => ({ toast: null }));

vi.mock('vue-i18n', async (importOriginal) => ({
    ...(await importOriginal()),
    useI18n: () => ({ t: (key) => key })
}));
vi.mock('vue-sonner', () => {
    mocks.toast = Object.assign(vi.fn(), { success: vi.fn(), error: vi.fn() });
    return { toast: mocks.toast };
});

import PhotosFolderHint from '../components/PhotosFolderHint.vue';
import { getPhotosFolder, openPhotosFolder, runAndroidToolAction } from '../photosFolder.js';

enableAutoUnmount(afterEach);

const t = (key) => key;

describe('photos folder helpers', () => {
    let host;
    let appApi;

    beforeEach(() => {
        vi.clearAllMocks();
        host = { GetPhotosFolder: vi.fn().mockResolvedValue('') };
        appApi = { OpenVrcPhotosFolder: vi.fn().mockResolvedValue(false) };
    });

    test('getPhotosFolder answers the display name, or "" (also for old hosts)', async () => {
        host.GetPhotosFolder.mockResolvedValue('VRChat');
        await expect(getPhotosFolder(() => host)).resolves.toBe('VRChat');
        host.GetPhotosFolder.mockRejectedValue(new Error('MissingMethodException: x'));
        const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
        await expect(getPhotosFolder(() => host)).resolves.toBe('');
        warn.mockRestore();
        await expect(getPhotosFolder(() => null)).resolves.toBe('');
    });

    test('without a folder, the picker opens and the user is told what to pick', async () => {
        const result = await openPhotosFolder({ t, appApi, getHost: () => host, notify: mocks.toast });
        expect(result).toBe('choosing');
        expect(appApi.OpenVrcPhotosFolder).toHaveBeenCalled();
        expect(mocks.toast).toHaveBeenCalledWith('android.photos_folder.picker_opened');
    });

    test('with a folder, it opens it', async () => {
        host.GetPhotosFolder.mockResolvedValue('VRChat');
        appApi.OpenVrcPhotosFolder.mockResolvedValue(true);
        await expect(openPhotosFolder({ t, appApi, getHost: () => host, notify: mocks.toast })).resolves.toBe('opened');
        expect(mocks.toast.success).toHaveBeenCalledWith('message.file.folder_opened');
    });

    test('a chosen folder that cannot be opened is an error', async () => {
        host.GetPhotosFolder.mockResolvedValue('VRChat');
        await expect(openPhotosFolder({ t, appApi, getHost: () => host, notify: mocks.toast })).resolves.toBe('failed');
        expect(mocks.toast.error).toHaveBeenCalledWith('android.photos_folder.open_failed');
    });

    test('the Photos folder tool action opens the folder', async () => {
        window.AndroidHost = host;
        globalThis.AppApi = appApi;
        await runAndroidToolAction({ type: 'android', handler: 'photos-folder' }, { t });
        expect(appApi.OpenVrcPhotosFolder).toHaveBeenCalledTimes(1);
        await runAndroidToolAction({ type: 'android', handler: 'unknown' }, { t });
        expect(appApi.OpenVrcPhotosFolder).toHaveBeenCalledTimes(1);
        delete window.AndroidHost;
        delete globalThis.AppApi;
    });
});

describe('PhotosFolderHint', () => {
    let host;

    beforeEach(() => {
        vi.clearAllMocks();
        host = { GetPhotosFolder: vi.fn().mockResolvedValue('') };
        window.AndroidHost = host;
        globalThis.AppApi = { OpenVrcPhotosFolder: vi.fn().mockResolvedValue(false) };
    });

    afterEach(() => {
        delete window.AndroidHost;
        delete globalThis.AppApi;
    });

    test('shows while no photos folder is chosen and starts the picker', async () => {
        const wrapper = mount(PhotosFolderHint);
        await flushPromises();
        expect(wrapper.find('[data-testid="photos-folder-hint"]').exists()).toBe(true);
        expect(wrapper.text()).toContain('android.photos_folder.hint_title');

        await wrapper.find('[data-testid="photos-folder-choose"]').trigger('click');
        await flushPromises();
        expect(globalThis.AppApi.OpenVrcPhotosFolder).toHaveBeenCalled();
    });

    test('goes away once a folder was chosen and the app is back in front', async () => {
        const wrapper = mount(PhotosFolderHint);
        await flushPromises();
        host.GetPhotosFolder.mockResolvedValue('VRChat');
        window.dispatchEvent(new CustomEvent('vrcx-android:focus'));
        await flushPromises();
        expect(wrapper.find('[data-testid="photos-folder-hint"]').exists()).toBe(false);
    });

    test('stays hidden when a folder is chosen', async () => {
        host.GetPhotosFolder.mockResolvedValue('Pictures/VRChat');
        const wrapper = mount(PhotosFolderHint);
        await flushPromises();
        expect(wrapper.find('[data-testid="photos-folder-hint"]').exists()).toBe(false);
    });
});
