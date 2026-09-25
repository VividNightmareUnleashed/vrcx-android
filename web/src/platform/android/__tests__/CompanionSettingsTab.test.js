import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';

const mocks = vi.hoisted(() => ({
    modal: { confirm: vi.fn(), prompt: vi.fn() },
    toast: { success: vi.fn(), error: vi.fn() }
}));

vi.mock('vue-i18n', async (importOriginal) => ({
    ...(await importOriginal()),
    useI18n: () => ({ t: (key, params) => (params ? `${key}|${JSON.stringify(params)}` : key) })
}));
vi.mock('vue-sonner', () => ({ toast: mocks.toast }));
vi.mock('@/stores/modal', () => ({ useModalStore: () => mocks.modal }));
vi.mock('@/shared/utils', () => ({ formatDateFilter: (value) => `date(${value})` }));

import CompanionSettingsTab from '../components/settings/CompanionSettingsTab.vue';
import { useCompanionStore } from '../companionStore.js';

const connected = {
    status: 'connected',
    activeId: 'pc-1',
    paired: [
        { id: 'pc-1', name: 'DESKTOP', hosts: ['192.168.1.20'], port: 49460, fp: 'a', pairedAt: 1, lastSeen: 5 },
        { id: 'pc-2', name: 'LAPTOP', hosts: ['192.168.1.30'], port: 49460, fp: 'b', pairedAt: 2, lastSeen: 0 }
    ],
    machineName: 'DESKTOP',
    tz: { ianaId: 'Europe/Berlin', currentUtcOffsetMin: 120 },
    vrchatRunning: true,
    steamVrRunning: false,
    syncing: false,
    lastError: null
};

describe('CompanionSettingsTab', () => {
    let pinia;
    let host;

    beforeEach(() => {
        pinia = createPinia();
        setActivePinia(pinia);
        host = {
            CompanionScanQr: vi.fn().mockResolvedValue(connected),
            CompanionDiscover: vi
                .fn()
                .mockResolvedValue([{ id: 'pc-3', name: 'GAMING', host: '192.168.1.40', port: 49460, fp: 'c', pairing: true }]),
            CompanionPair: vi.fn().mockResolvedValue(connected),
            CompanionForget: vi.fn().mockResolvedValue({ ...connected, paired: [connected.paired[0]] }),
            CompanionSetActive: vi.fn().mockResolvedValue({ ...connected, activeId: 'pc-2' }),
            ImportDatabase: vi.fn().mockResolvedValue({ ok: true, message: '' }),
            ExportDatabase: vi.fn().mockResolvedValue(true)
        };
        window.AndroidHost = host;
        vi.clearAllMocks();
    });

    afterEach(() => {
        delete window.AndroidHost;
    });

    function mountTab() {
        return mount(CompanionSettingsTab, { global: { plugins: [pinia] } });
    }

    test('shows the connection status, process state and PC time zone', () => {
        useCompanionStore().applyState(connected);
        const wrapper = mountTab();
        expect(wrapper.text()).toContain('android.companion.status.connected|{"name":"DESKTOP"}');
        expect(wrapper.text()).toContain('android.companion.vrchat_label');
        expect(wrapper.find('[data-testid="companion-time-zone"]').text()).toBe('Europe/Berlin (UTC+02:00)');
        expect(wrapper.find('[data-testid="companion-status-dot"]').classes()).toContain('bg-status-online');
        expect(wrapper.findAll('[data-testid="companion-paired-row"]')).toHaveLength(2);
    });

    test('shows the empty paired list and no process rows when unpaired', () => {
        const wrapper = mountTab();
        expect(wrapper.text()).toContain('android.companion.status.unpaired');
        expect(wrapper.text()).toContain('android.companion.paired_empty');
        expect(wrapper.text()).not.toContain('android.companion.vrchat_label');
    });

    test('shows the last error', () => {
        useCompanionStore().applyState({ ...connected, status: 'error', lastError: 'Connection refused' });
        const wrapper = mountTab();
        expect(wrapper.find('[data-testid="companion-last-error"]').text()).toContain('Connection refused');
    });

    test('scanning a QR code pairs and confirms', async () => {
        const wrapper = mountTab();
        await wrapper.find('[data-testid="companion-scan"]').trigger('click');
        await flushPromises();
        expect(host.CompanionScanQr).toHaveBeenCalled();
        expect(mocks.toast.success).toHaveBeenCalledWith('android.companion.paired_toast|{"name":"DESKTOP"}');
    });

    test('a cancelled scan shows nothing', async () => {
        host.CompanionScanQr.mockRejectedValue(new Error('OperationCanceledException: cancelled'));
        const wrapper = mountTab();
        await wrapper.find('[data-testid="companion-scan"]').trigger('click');
        await flushPromises();
        expect(mocks.toast.error).not.toHaveBeenCalled();
        expect(mocks.toast.success).not.toHaveBeenCalled();
    });

    test('discovered PCs can be paired with the code from the PC', async () => {
        mocks.modal.prompt.mockResolvedValue({ ok: true, value: 'abcde-12345' });
        const wrapper = mountTab();
        await wrapper.find('[data-testid="companion-discover"]').trigger('click');
        await flushPromises();

        const rows = wrapper.findAll('[data-testid="companion-discovered-row"]');
        expect(rows).toHaveLength(1);
        expect(rows[0].text()).toContain('GAMING');
        await rows[0].find('button').trigger('click');
        await flushPromises();

        expect(host.CompanionPair).toHaveBeenCalledWith(
            { host: '192.168.1.40', port: 49460, fp: 'c', id: 'pc-3', name: 'GAMING' },
            'ABCDE12345'
        );
        expect(wrapper.findAll('[data-testid="companion-discovered-row"]')).toHaveLength(0);
    });

    test('shows a pairing error from native', async () => {
        host.CompanionPair.mockRejectedValue(new Error('PairingException: code'));
        mocks.modal.prompt
            .mockResolvedValueOnce({ ok: true, value: '192.168.1.40:5000' })
            .mockResolvedValueOnce({ ok: true, value: 'ABCDE-12345' });
        const wrapper = mountTab();
        await wrapper.find('[data-testid="companion-manual"]').trigger('click');
        await flushPromises();

        expect(host.CompanionPair).toHaveBeenCalledWith({ host: '192.168.1.40', port: 5000 }, 'ABCDE12345');
        expect(mocks.toast.error).toHaveBeenCalledWith(expect.stringContaining('android.companion.errors.code'));
    });

    test('rejects an invalid manual address before calling native', async () => {
        mocks.modal.prompt.mockResolvedValueOnce({ ok: true, value: 'my pc' });
        const wrapper = mountTab();
        await wrapper.find('[data-testid="companion-manual"]').trigger('click');
        await flushPromises();
        expect(host.CompanionPair).not.toHaveBeenCalled();
        expect(mocks.toast.error).toHaveBeenCalledWith('android.companion.address_invalid');
    });

    test('forget asks first', async () => {
        useCompanionStore().applyState(connected);
        mocks.modal.confirm.mockResolvedValueOnce({ ok: false }).mockResolvedValueOnce({ ok: true });
        const wrapper = mountTab();
        const forgetButton = () =>
            wrapper
                .findAll('[data-testid="companion-paired-row"]')[1]
                .findAll('button')
                .find((button) => button.text() === 'android.companion.forget');

        await forgetButton().trigger('click');
        await flushPromises();
        expect(host.CompanionForget).not.toHaveBeenCalled();

        await forgetButton().trigger('click');
        await flushPromises();
        expect(host.CompanionForget).toHaveBeenCalledWith('pc-2');
    });

    test('set active switches the PC', async () => {
        useCompanionStore().applyState(connected);
        const wrapper = mountTab();
        const setActive = wrapper
            .findAll('[data-testid="companion-paired-row"]')[1]
            .findAll('button')
            .find((button) => button.text() === 'android.companion.set_active');
        await setActive.trigger('click');
        await flushPromises();
        expect(host.CompanionSetActive).toHaveBeenCalledWith('pc-2');
    });

    test('import runs only after confirmation and reports failures', async () => {
        mocks.modal.confirm.mockResolvedValue({ ok: true });
        host.ImportDatabase.mockResolvedValue({ ok: false, message: 'Not a VRCX database' });
        const wrapper = mountTab();
        await wrapper.find('[data-testid="companion-import"]').trigger('click');
        await flushPromises();
        expect(host.ImportDatabase).toHaveBeenCalled();
        expect(mocks.toast.error).toHaveBeenCalledWith(
            'android.companion.import_failed|{"message":"Not a VRCX database"}'
        );
    });

    test('export confirms a saved file and stays quiet when cancelled', async () => {
        const wrapper = mountTab();
        await wrapper.find('[data-testid="companion-export"]').trigger('click');
        await flushPromises();
        expect(mocks.toast.success).toHaveBeenCalledWith('android.companion.export_done');

        mocks.toast.success.mockClear();
        host.ExportDatabase.mockResolvedValue(false);
        await wrapper.find('[data-testid="companion-export"]').trigger('click');
        await flushPromises();
        expect(mocks.toast.success).not.toHaveBeenCalled();
        expect(mocks.toast.error).not.toHaveBeenCalled();
    });
});
