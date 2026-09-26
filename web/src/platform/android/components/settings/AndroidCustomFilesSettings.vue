<template>
    <SettingsGroup :title="t('android.custom_files.header')" data-testid="android-custom-files">
        <template #description>
            {{ t('android.custom_files.import_description') }}
        </template>

        <SettingsItem
            :label="t('android.custom_files.css_file_label')"
            :description="t('android.custom_files.css_file_description')">
            <div class="flex flex-wrap gap-2">
                <Button
                    size="sm"
                    variant="outline"
                    :disabled="busy"
                    data-testid="android-import-custom-css"
                    @click="importFile('css')"
                    >{{ t('android.custom_files.import_button') }}</Button
                >
                <Button
                    size="sm"
                    variant="outline"
                    :disabled="busy"
                    data-testid="android-remove-custom-css"
                    @click="removeFile('css')"
                    >{{ t('android.custom_files.remove_button') }}</Button
                >
                <Button
                    size="sm"
                    variant="outline"
                    :disabled="busy || reloadingCss"
                    data-testid="android-reload-custom-css"
                    @click="reloadCss"
                    >{{ t('android.custom_files.reload_button') }}</Button
                >
            </div>
        </SettingsItem>

        <SettingsItem
            :label="t('android.custom_files.js_file_label')"
            :description="t('android.custom_files.js_file_description')">
            <div class="flex flex-wrap gap-2">
                <Button
                    size="sm"
                    variant="outline"
                    :disabled="busy"
                    data-testid="android-import-custom-js"
                    @click="importFile('js')"
                    >{{ t('android.custom_files.import_button') }}</Button
                >
                <Button
                    size="sm"
                    variant="outline"
                    :disabled="busy"
                    data-testid="android-remove-custom-js"
                    @click="removeFile('js')"
                    >{{ t('android.custom_files.remove_button') }}</Button
                >
                <Button size="sm" variant="outline" data-testid="android-reload-custom-js" @click="reloadScript">{{
                    t('android.custom_files.reload_button')
                }}</Button>
            </div>
        </SettingsItem>
    </SettingsGroup>
</template>

<script setup>
    import { ref } from 'vue';
    import { toast } from 'vue-sonner';
    import { useI18n } from 'vue-i18n';

    import { Button } from '@/components/ui/button';
    import { getAndroidHost } from '@/shared/utils/platform';
    import SettingsGroup from '@/views/Settings/components/SettingsGroup.vue';
    import SettingsItem from '@/views/Settings/components/SettingsItem.vue';

    import { reloadCustomCss, reloadCustomScript } from '@/shared/utils/androidCustomFiles';
    import { useModalStore } from '@/stores/modal';

    // Settings → Advanced on Android: import, remove and reload custom.css and custom.js (docs/ARCHITECTURE.md §9).
    // Importing copies the picked file into app storage (AndroidHost.ImportCustomFile), so no file manager access to
    // Android/data is needed.

    const { t } = useI18n();

    const reloadingCss = ref(false);
    const busy = ref(false);

    const FILE_NAMES = { css: 'custom.css', js: 'custom.js' };

    /**
     * @param {unknown} error
     * @returns {string}
     */
    function messageOf(error) {
        return String(error instanceof Error ? error.message : (error ?? ''));
    }

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
            toast.error(t('android.custom_files.reload_failed', { message: messageOf(error) }));
        } finally {
            reloadingCss.value = false;
        }
    }

    function reloadScript() {
        reloadCustomScript();
    }

    /**
     * Custom.js runs with the app's full access, so its import is confirmed first.
     *
     * @returns {Promise<boolean>}
     */
    async function confirmScriptImport() {
        const { ok } = await useModalStore().confirm({
            title: t('android.custom_files.js_confirm_title'),
            description: t('android.custom_files.js_confirm_description'),
            confirmText: t('android.custom_files.js_confirm'),
            cancelText: t('android.companion.cancel')
        });
        return ok;
    }

    /**
     * @param {'css' | 'js'} type
     */
    async function importFile(type) {
        const host = getAndroidHost();
        if (!host || busy.value) return;
        if (type === 'js' && !(await confirmScriptImport())) return;
        busy.value = true;
        try {
            const result = await host.ImportCustomFile(type);
            if (!result?.ok) {
                // The picker was cancelled.
                return;
            }
            const name = result.name || FILE_NAMES[type];
            if (type === 'css') {
                await reloadCustomCss();
                toast.success(t('android.custom_files.css_imported', { name }));
            } else {
                toast.success(t('android.custom_files.js_imported', { name }), {
                    action: { label: t('android.custom_files.reload_now'), onClick: () => reloadCustomScript() }
                });
            }
        } catch (error) {
            toast.error(t('android.custom_files.import_failed', { message: messageOf(error) }));
        } finally {
            busy.value = false;
        }
    }

    /**
     * @param {'css' | 'js'} type
     */
    async function removeFile(type) {
        const host = getAndroidHost();
        if (!host || busy.value) return;
        busy.value = true;
        try {
            const removed = await host.RemoveCustomFile(type);
            if (!removed) {
                toast(t('android.custom_files.nothing_to_remove', { file: FILE_NAMES[type] }));
                return;
            }
            if (type === 'css') {
                await reloadCustomCss();
                toast.success(t('android.custom_files.css_removed'));
            } else {
                toast.success(t('android.custom_files.js_removed'), {
                    action: { label: t('android.custom_files.reload_now'), onClick: () => reloadCustomScript() }
                });
            }
        } catch (error) {
            toast.error(t('android.custom_files.remove_failed', { message: messageOf(error) }));
        } finally {
            busy.value = false;
        }
    }
</script>
