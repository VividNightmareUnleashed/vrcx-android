import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';

// Settings action that reloads custom.css / custom.js on Android (docs/ARCHITECTURE.md §9).
const mocks = vi.hoisted(() => ({
    refreshCustomCss: vi.fn(),
    toast: Object.assign(vi.fn(), { success: vi.fn(), error: vi.fn() }),
    confirm: vi.fn()
}));

vi.mock('vue-i18n', async (importOriginal) => ({
    ...(await importOriginal()),
    useI18n: () => ({ t: (key, params) => (params ? `${key}|${JSON.stringify(params)}` : key) })
}));
vi.mock('vue-sonner', () => ({ toast: mocks.toast }));
vi.mock('../../../shared/utils/base/ui', () => ({ refreshCustomCss: mocks.refreshCustomCss }));
vi.mock('../../../stores/modal', () => ({ useModalStore: () => ({ confirm: mocks.confirm }) }));

import AndroidCustomFilesSettings from '../components/settings/AndroidCustomFilesSettings.vue';
import { CUSTOM_STYLE_ELEMENT_ID, reloadCustomCss, reloadCustomScript } from '../../../shared/utils/androidCustomFiles';

enableAutoUnmount(afterEach);

function addStyleElement() {
    const link = document.createElement('link');
    link.id = CUSTOM_STYLE_ELEMENT_ID;
    document.head.appendChild(link);
}

describe('custom CSS and JS reload', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        document.getElementById(CUSTOM_STYLE_ELEMENT_ID)?.remove();
        mocks.refreshCustomCss.mockImplementation(async () => {
            document.getElementById(CUSTOM_STYLE_ELEMENT_ID)?.remove();
            addStyleElement();
        });
    });

    test('reloadCustomCss re-reads custom.css and reports whether it applied', async () => {
        await expect(reloadCustomCss()).resolves.toBe(true);
        expect(mocks.refreshCustomCss).toHaveBeenCalledTimes(1);

        mocks.refreshCustomCss.mockImplementation(async () => {
            document.getElementById(CUSTOM_STYLE_ELEMENT_ID)?.remove();
        });
        await expect(reloadCustomCss()).resolves.toBe(false);
    });

    test('reloadCustomScript reloads the page instead of running custom.js twice', () => {
        const win = { location: { reload: vi.fn() } };
        reloadCustomScript(win);
        expect(win.location.reload).toHaveBeenCalledTimes(1);
    });

    test('the CSS button reloads custom.css in place', async () => {
        const wrapper = mount(AndroidCustomFilesSettings);
        expect(wrapper.text()).toContain('android.custom_files.header');
        expect(wrapper.text()).toContain('android.custom_files.import_description');

        await wrapper.find('[data-testid="android-reload-custom-css"]').trigger('click');
        await flushPromises();

        expect(mocks.refreshCustomCss).toHaveBeenCalledTimes(1);
        expect(mocks.toast.success).toHaveBeenCalledWith('android.custom_files.css_reloaded');
    });

    test('the CSS button says when there is no custom.css', async () => {
        mocks.refreshCustomCss.mockImplementation(async () => {});
        const wrapper = mount(AndroidCustomFilesSettings);
        await wrapper.find('[data-testid="android-reload-custom-css"]').trigger('click');
        await flushPromises();
        expect(mocks.toast).toHaveBeenCalledWith('android.custom_files.css_missing');
        expect(mocks.toast.success).not.toHaveBeenCalled();
    });

    test('a bridge error is shown and the button works again', async () => {
        mocks.refreshCustomCss.mockRejectedValueOnce(new Error('IOException: denied'));
        const wrapper = mount(AndroidCustomFilesSettings);
        const button = wrapper.find('[data-testid="android-reload-custom-css"]');
        await button.trigger('click');
        await flushPromises();
        expect(mocks.toast.error).toHaveBeenCalledWith(
            'android.custom_files.reload_failed|{"message":"IOException: denied"}'
        );
        expect(button.attributes('disabled')).toBeUndefined();
    });

    test('the JS button is present', () => {
        const wrapper = mount(AndroidCustomFilesSettings);
        expect(wrapper.find('[data-testid="android-reload-custom-js"]').exists()).toBe(true);
    });
});

describe('custom CSS and JS import and removal', () => {
    let host;

    beforeEach(() => {
        vi.clearAllMocks();
        mocks.confirm.mockResolvedValue({ ok: true });
        host = {
            ImportCustomFile: vi.fn(async (type) => ({ ok: true, name: `theme.${type}` })),
            RemoveCustomFile: vi.fn(async () => true)
        };
        window.AndroidHost = host;
    });

    afterEach(() => {
        delete window.AndroidHost;
    });

    test('importing custom.css applies it at once', async () => {
        const wrapper = mount(AndroidCustomFilesSettings);
        await wrapper.find('[data-testid="android-import-custom-css"]').trigger('click');
        await flushPromises();
        expect(host.ImportCustomFile).toHaveBeenCalledWith('css');
        expect(mocks.refreshCustomCss).toHaveBeenCalledTimes(1);
        expect(mocks.toast.success).toHaveBeenCalledWith('android.custom_files.css_imported|{"name":"theme.css"}');
        expect(mocks.confirm).not.toHaveBeenCalled();
    });

    test('importing custom.js is confirmed first and offers a reload', async () => {
        const wrapper = mount(AndroidCustomFilesSettings);
        await wrapper.find('[data-testid="android-import-custom-js"]').trigger('click');
        await flushPromises();
        expect(mocks.confirm).toHaveBeenCalledWith(
            expect.objectContaining({ title: 'android.custom_files.js_confirm_title' })
        );
        expect(host.ImportCustomFile).toHaveBeenCalledWith('js');
        const [message, options] = mocks.toast.success.mock.calls[0];
        expect(message).toBe('android.custom_files.js_imported|{"name":"theme.js"}');
        expect(options.action.label).toBe('android.custom_files.reload_now');
    });

    test('a declined custom.js confirmation opens no picker', async () => {
        mocks.confirm.mockResolvedValue({ ok: false });
        const wrapper = mount(AndroidCustomFilesSettings);
        await wrapper.find('[data-testid="android-import-custom-js"]').trigger('click');
        await flushPromises();
        expect(host.ImportCustomFile).not.toHaveBeenCalled();
    });

    test('a cancelled picker says nothing', async () => {
        host.ImportCustomFile.mockResolvedValue({ ok: false });
        const wrapper = mount(AndroidCustomFilesSettings);
        await wrapper.find('[data-testid="android-import-custom-css"]').trigger('click');
        await flushPromises();
        expect(mocks.toast.success).not.toHaveBeenCalled();
        expect(mocks.toast.error).not.toHaveBeenCalled();
        expect(mocks.refreshCustomCss).not.toHaveBeenCalled();
    });

    test('an import error is shown and the buttons work again', async () => {
        host.ImportCustomFile.mockRejectedValue(new Error('IOException: too large'));
        const wrapper = mount(AndroidCustomFilesSettings);
        const button = wrapper.find('[data-testid="android-import-custom-css"]');
        await button.trigger('click');
        await flushPromises();
        expect(mocks.toast.error).toHaveBeenCalledWith(
            'android.custom_files.import_failed|{"message":"IOException: too large"}'
        );
        expect(button.attributes('disabled')).toBeUndefined();
    });

    test('removing custom.css takes its styles away', async () => {
        const wrapper = mount(AndroidCustomFilesSettings);
        await wrapper.find('[data-testid="android-remove-custom-css"]').trigger('click');
        await flushPromises();
        expect(host.RemoveCustomFile).toHaveBeenCalledWith('css');
        expect(mocks.refreshCustomCss).toHaveBeenCalledTimes(1);
        expect(mocks.toast.success).toHaveBeenCalledWith('android.custom_files.css_removed');
    });

    test('removing a file that is not there says so', async () => {
        host.RemoveCustomFile.mockResolvedValue(false);
        const wrapper = mount(AndroidCustomFilesSettings);
        await wrapper.find('[data-testid="android-remove-custom-js"]').trigger('click');
        await flushPromises();
        expect(mocks.toast).toHaveBeenCalledWith('android.custom_files.nothing_to_remove|{"file":"custom.js"}');
    });
});
