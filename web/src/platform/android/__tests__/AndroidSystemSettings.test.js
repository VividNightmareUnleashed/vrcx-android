import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils';

vi.mock('vue-i18n', async (importOriginal) => ({
    ...(await importOriginal()),
    useI18n: () => ({ t: (key) => key })
}));

import AndroidSystemSettings from '../components/settings/AndroidSystemSettings.vue';

enableAutoUnmount(afterEach);

describe('AndroidSystemSettings', () => {
    let host;

    beforeEach(() => {
        host = {
            GetBackgroundMode: vi.fn().mockResolvedValue(true),
            SetBackgroundMode: vi.fn(async (value) => value),
            IsIgnoringBatteryOptimizations: vi.fn().mockResolvedValue(false),
            RequestIgnoreBatteryOptimizations: vi.fn().mockResolvedValue(undefined),
            GetNotificationPermission: vi.fn().mockResolvedValue('default'),
            RequestNotificationPermission: vi.fn().mockResolvedValue('granted'),
            OpenNotificationSettings: vi.fn().mockResolvedValue(undefined)
        };
        window.AndroidHost = host;
    });

    afterEach(() => {
        delete window.AndroidHost;
    });

    test('reads background mode, battery and notification status on mount', async () => {
        const wrapper = mount(AndroidSystemSettings);
        await flushPromises();

        expect(host.GetBackgroundMode).toHaveBeenCalled();
        expect(wrapper.find('[data-testid="android-background-mode"]').attributes('data-state')).toBe('checked');
        expect(wrapper.text()).toContain('android.system.battery_restricted');
        expect(wrapper.find('[data-testid="android-battery-request"]').exists()).toBe(true);
        expect(wrapper.text()).toContain('android.system.notification_default');
    });

    test('turning background mode off calls native and keeps its answer', async () => {
        const wrapper = mount(AndroidSystemSettings);
        await flushPromises();
        await wrapper.find('[data-testid="android-background-mode"]').trigger('click');
        await flushPromises();
        expect(host.SetBackgroundMode).toHaveBeenCalledWith(false);
        expect(wrapper.find('[data-testid="android-background-mode"]').attributes('data-state')).toBe('unchecked');
    });

    test('asks for notification permission, then offers the settings screen', async () => {
        const wrapper = mount(AndroidSystemSettings);
        await flushPromises();
        await wrapper.find('[data-testid="android-notification-request"]').trigger('click');
        await flushPromises();
        expect(host.RequestNotificationPermission).toHaveBeenCalled();
        expect(wrapper.text()).toContain('android.system.notification_granted');

        await wrapper.find('[data-testid="android-notification-settings"]').trigger('click');
        expect(host.OpenNotificationSettings).toHaveBeenCalled();
    });

    test('re-reads the status when the app returns to the foreground', async () => {
        const wrapper = mount(AndroidSystemSettings);
        await flushPromises();
        await wrapper.find('[data-testid="android-battery-request"]').trigger('click');
        expect(host.RequestIgnoreBatteryOptimizations).toHaveBeenCalled();

        host.IsIgnoringBatteryOptimizations.mockResolvedValue(true);
        window.dispatchEvent(new CustomEvent('vrcx-android:focus'));
        await flushPromises();
        expect(wrapper.text()).toContain('android.system.battery_unrestricted');
        expect(wrapper.find('[data-testid="android-battery-request"]').exists()).toBe(false);

        wrapper.unmount();
        host.IsIgnoringBatteryOptimizations.mockClear();
        window.dispatchEvent(new CustomEvent('vrcx-android:focus'));
        await flushPromises();
        expect(host.IsIgnoringBatteryOptimizations).not.toHaveBeenCalled();
    });

    test('a denied permission links to Android settings', async () => {
        host.GetNotificationPermission.mockResolvedValue('denied');
        const wrapper = mount(AndroidSystemSettings);
        await flushPromises();
        expect(wrapper.text()).toContain('android.system.notification_denied');
        expect(wrapper.find('[data-testid="android-notification-request"]').exists()).toBe(false);
        expect(wrapper.find('[data-testid="android-notification-settings"]').exists()).toBe(true);
    });

    describe('start on boot', () => {
        beforeEach(() => {
            host.GetStartOnBoot = vi.fn().mockResolvedValue(false);
            host.SetStartOnBoot = vi.fn(async (value) => value);
        });

        test('reads the setting and turns it on through native', async () => {
            const wrapper = mount(AndroidSystemSettings);
            await flushPromises();
            const toggle = wrapper.find('[data-testid="android-start-on-boot"]');
            expect(wrapper.text()).toContain('android.system.boot_label');
            expect(wrapper.text()).toContain('android.system.boot_description');
            expect(toggle.attributes('data-state')).toBe('unchecked');

            await toggle.trigger('click');
            await flushPromises();
            expect(host.SetStartOnBoot).toHaveBeenCalledWith(true);
            expect(wrapper.find('[data-testid="android-start-on-boot"]').attributes('data-state')).toBe('checked');
        });

        test('keeps the previous value when native fails', async () => {
            host.SetStartOnBoot.mockRejectedValue(new Error('SecurityException: no'));
            const error = vi.spyOn(console, 'error').mockImplementation(() => {});
            const wrapper = mount(AndroidSystemSettings);
            await flushPromises();
            await wrapper.find('[data-testid="android-start-on-boot"]').trigger('click');
            await flushPromises();
            expect(wrapper.find('[data-testid="android-start-on-boot"]').attributes('data-state')).toBe('unchecked');
            error.mockRestore();
        });

        test('needs background mode', async () => {
            host.GetBackgroundMode.mockResolvedValue(false);
            const wrapper = mount(AndroidSystemSettings);
            await flushPromises();
            expect(wrapper.text()).toContain('android.system.boot_needs_background');
            expect(wrapper.find('[data-testid="android-start-on-boot"]').attributes('disabled')).toBeDefined();
        });

        test('stays disabled on a host without the setting', async () => {
            delete host.GetStartOnBoot;
            const error = vi.spyOn(console, 'error').mockImplementation(() => {});
            const wrapper = mount(AndroidSystemSettings);
            await flushPromises();
            expect(wrapper.find('[data-testid="android-start-on-boot"]').attributes('disabled')).toBeDefined();
            error.mockRestore();
        });
    });
});
