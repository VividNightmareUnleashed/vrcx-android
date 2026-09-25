<template>
    <SettingsGroup :title="t('android.custom_files.header')" data-testid="android-custom-files">
        <template #description>
            {{ t('android.custom_files.description') }}
        </template>

        <SettingsItem
            :label="t('android.custom_files.css_label')"
            :description="t('android.custom_files.css_description')">
            <Button
                size="sm"
                variant="outline"
                :disabled="reloadingCss"
                data-testid="android-reload-custom-css"
                @click="reloadCss"
                >{{ t('android.custom_files.reload_button') }}</Button
            >
        </SettingsItem>

        <SettingsItem
            :label="t('android.custom_files.js_label')"
            :description="t('android.custom_files.js_description')">
            <Button size="sm" variant="outline" data-testid="android-reload-custom-js" @click="reloadScript">{{
                t('android.custom_files.reload_button')
            }}</Button>
        </SettingsItem>
    </SettingsGroup>
</template>

<script setup>
    import { ref } from 'vue';
    import { toast } from 'vue-sonner';
    import { useI18n } from 'vue-i18n';

    import { Button } from '@/components/ui/button';
    import SettingsGroup from '@/views/Settings/components/SettingsGroup.vue';
    import SettingsItem from '@/views/Settings/components/SettingsItem.vue';

    import { reloadCustomCss, reloadCustomScript } from '@/shared/utils/androidCustomFiles';

    // Settings → Advanced on Android: reload custom.css and custom.js (docs/ARCHITECTURE.md §9).

    const { t } = useI18n();

    const reloadingCss = ref(false);

    async function reloadCss() {
        if (reloadingCss.value) {
            return;
        }
        reloadingCss.value = true;
        try {
            const applied = await reloadCustomCss();
            if (applied) {
                toast.success(t('android.custom_files.css_reloaded'));
            } else {
                toast(t('android.custom_files.css_missing'));
            }
        } catch (error) {
            toast.error(t('android.custom_files.reload_failed', { message: String(error?.message ?? error) }));
        } finally {
            reloadingCss.value = false;
        }
    }

    function reloadScript() {
        reloadCustomScript();
    }
</script>
