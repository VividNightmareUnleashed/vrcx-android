import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';

const mocks = vi.hoisted(() => ({
    android: true,
    openLink: vi.fn(),
    getDeviceCode: vi.fn()
}));

vi.mock('../../../../shared/utils/platform', () => ({
    get isAndroid() {
        return mocks.android;
    }
}));
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key) => key }) }));
vi.mock('vue-sonner', () => ({ toast: { error: vi.fn() } }));
vi.mock('qrcode', () => ({ default: { toDataURL: vi.fn(async () => 'data:image/png;base64,AAAA') } }));
vi.mock('../../../../services/appConfig', () => ({ AppDebug: {} }));
vi.mock('../../../../api', () => ({
    authRequest: {
        getConfig: vi.fn(async () => ({})),
        getDeviceCode: (...args) => mocks.getDeviceCode(...args),
        getDeviceStatus: vi.fn()
    }
}));
vi.mock('../../../../coordinators/userCoordinator', () => ({ getCurrentUser: vi.fn() }));
vi.mock('../../../../stores', () => ({ useAuthStore: () => ({ queueSaveCredentials: vi.fn() }) }));
vi.mock('../../../../services/webapi', () => ({ default: { clearCookies: vi.fn(async () => {}) } }));
// The poll never fires: the dialog stays on the QR code.
vi.mock('worker-timers', () => ({ setTimeout: vi.fn() }));
vi.mock('@/components/ui/button', () => ({
    Button: { emits: ['click'], template: '<button data-testid="btn" @click="$emit(\'click\')"><slot /></button>' }
}));
vi.mock('lucide-vue-next', () => ({ ExternalLink: { template: '<i />' }, QrCode: { template: '<i />' } }));

import QrLogin from '../QrLogin.vue';

const URL = 'https://vrchat.com/home/device?code=ABCD-EFGH';

function button(wrapper, label) {
    return wrapper.findAll('[data-testid="btn"]').find((el) => el.text().includes(label));
}

async function showQrCode() {
    const wrapper = mount(QrLogin);
    await button(wrapper, 'view.login.qr.button').trigger('click');
    await flushPromises();
    return wrapper;
}

describe('QrLogin.vue', () => {
    beforeEach(() => {
        globalThis.AppApi = { OpenLink: mocks.openLink };
        mocks.openLink.mockClear();
        mocks.getDeviceCode.mockResolvedValue({
            json: { verificationUrlComplete: URL, deviceCode: 'device-1', interval: 5 }
        });
    });

    afterEach(() => {
        mocks.android = true;
        delete globalThis.AppApi;
    });

    test('Android: opens the approval link on the phone itself', async () => {
        const wrapper = await showQrCode();
        expect(wrapper.find('img').attributes('src')).toBe('data:image/png;base64,AAAA');
        expect(wrapper.text()).toContain('android.login.qr_open_link_hint');

        await button(wrapper, 'android.login.qr_open_link').trigger('click');
        expect(mocks.openLink).toHaveBeenCalledWith(URL);
    });

    test('Android: cancelling removes the link with the QR code', async () => {
        const wrapper = await showQrCode();
        await button(wrapper, 'view.login.qr.cancel').trigger('click');
        expect(button(wrapper, 'android.login.qr_open_link')).toBeUndefined();
        expect(button(wrapper, 'view.login.qr.button')).toBeDefined();
    });

    test('desktop shows only the QR code, as upstream', async () => {
        mocks.android = false;
        const wrapper = await showQrCode();
        expect(wrapper.find('img').exists()).toBe(true);
        expect(button(wrapper, 'android.login.qr_open_link')).toBeUndefined();
        expect(wrapper.text()).not.toContain('android.login.qr_open_link_hint');
    });
});
