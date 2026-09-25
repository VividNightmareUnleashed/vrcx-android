<template>
    <SettingsItem :label="t('android.system.background_label')" :description="t('android.system.background_description')">
        <Switch
            :model-value="backgroundMode"
            :disabled="backgroundMode === null"
            :ariaLabel="t('android.system.background_label')"
            data-testid="android-background-mode"
            @update:modelValue="setBackgroundMode" />
    </SettingsItem>

    <SettingsItem
        :label="t('android.system.battery_label')"
        :description="
            ignoringBatteryOptimizations
                ? t('android.system.battery_unrestricted')
                : t('android.system.battery_restricted')
        ">
        <Button
            v-if="ignoringBatteryOptimizations === false"
            size="sm"
            variant="outline"
            data-testid="android-battery-request"
            @click="requestBatteryExemption"
            >{{ t('android.system.battery_button') }}</Button
        >
    </SettingsItem>

    <SettingsItem :label="t('android.system.notification_label')" :description="notificationPermissionText">
        <Button
            v-if="notificationPermission === 'default'"
            size="sm"
            variant="outline"
            data-testid="android-notification-request"
            @click="requestNotificationPermission"
            >{{ t('android.system.notification_allow') }}</Button
        >
        <Button
            v-else-if="notificationPermission"
            size="sm"
            variant="outline"
            data-testid="android-notification-settings"
            @click="openNotificationSettings"
            >{{ t('android.system.notification_settings') }}</Button
        >
    </SettingsItem>
</template>

<script setup>
    import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
    import { useI18n } from 'vue-i18n';

    import { Button } from '@/components/ui/button';
    import { Switch } from '@/components/ui/switch';
    import { getAndroidHost, onAndroidEvent } from '@/shared/utils/platform';
    import SettingsItem from '@/views/Settings/components/SettingsItem.vue';

    // Android replacements for the PC's tray row (docs/ARCHITECTURE.md §7): background mode, battery
    // optimization and the notification permission. Statuses are read on mount and again when the app returns
    // to the foreground (the user may have changed them in Android settings); nothing polls.

    const { t } = useI18n();

    /** @type {import('vue').Ref<boolean | null>} */
    const backgroundMode = ref(null);
    /** @type {import('vue').Ref<boolean | null>} */
    const ignoringBatteryOptimizations = ref(null);
    /** @type {import('vue').Ref<'granted' | 'denied' | 'default' | null>} */
    const notificationPermission = ref(null);

    const notificationPermissionText = computed(() => {
        switch (notificationPermission.value) {
            case 'granted':
                return t('android.system.notification_granted');
            case 'denied':
                return t('android.system.notification_denied');
            case 'default':
                return t('android.system.notification_default');
            default:
                return '';
        }
    });

    /**
     * @param {string} method
     * @param {...any} args
     */
    async function callHost(method, ...args) {
        const host = getAndroidHost();
        if (!host) return undefined;
        try {
            return await host[method](...args);
        } catch (error) {
            console.error(`AndroidHost.${method} failed`, error);
            return undefined;
        }
    }

    async function refreshStatus() {
        const [background, battery, permission] = await Promise.all([
            callHost('GetBackgroundMode'),
            callHost('IsIgnoringBatteryOptimizations'),
            callHost('GetNotificationPermission')
        ]);
        if (typeof background === 'boolean') backgroundMode.value = background;
        if (typeof battery === 'boolean') ignoringBatteryOptimizations.value = battery;
        if (typeof permission === 'string') notificationPermission.value = permission;
    }

    /**
     * @param {boolean} value
     */
    async function setBackgroundMode(value) {
        const previous = backgroundMode.value;
        backgroundMode.value = Boolean(value);
        const result = await callHost('SetBackgroundMode', Boolean(value));
        backgroundMode.value = typeof result === 'boolean' ? result : previous;
    }

    async function requestBatteryExemption() {
        // Opens the system dialog; the new status is read when the app is back in the foreground.
        await callHost('RequestIgnoreBatteryOptimizations');
    }

    async function requestNotificationPermission() {
        const result = await callHost('RequestNotificationPermission');
        if (typeof result === 'string') notificationPermission.value = result;
    }

    async function openNotificationSettings() {
        await callHost('OpenNotificationSettings');
    }

    const unsubscribers = [];
    onMounted(() => {
        refreshStatus();
        unsubscribers.push(
            onAndroidEvent('focus', () => refreshStatus()),
            onAndroidEvent('visibility', (payload) => {
                if (payload?.visible !== false) refreshStatus();
            })
        );
    });
    onBeforeUnmount(() => {
        unsubscribers.splice(0).forEach((off) => off());
    });

    defineExpose({ refreshStatus });
</script>
