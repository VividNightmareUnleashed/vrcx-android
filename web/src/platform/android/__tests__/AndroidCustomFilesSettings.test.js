import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';

// Settings action that reloads custom.css / custom.js on Android (docs/ARCHITECTURE.md §9).
const mocks = vi.hoisted(() => ({
    refreshCustomCss: vi.fn(),
    toast: Object.assign(vi.fn(), { success: vi.fn(), error: vi.fn() })
}));

vi.mock('vue-i18n', async (importOriginal) => ({
    ...(await importOriginal()),
    useI18n: () => ({ t: (key, params) => (params ? `${key}|${JSON.stringify(params)}` : key) })
}));
vi.mock('vue-sonner', () => ({ toast: mocks.toast }));
vi.mock('../../../shared/utils/base/ui', () => ({ refreshCustomCss: mocks.refreshCustomCss }));

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
        expect(wrapper.text()).toContain('android.custom_files.description');

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
