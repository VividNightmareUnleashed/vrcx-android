<template>
    <Alert v-if="missing" class="mb-2 shrink-0" data-testid="photos-folder-hint">
        <FolderSearch />
        <AlertTitle>{{ t('android.photos_folder.hint_title') }}</AlertTitle>
        <AlertDescription>{{ t('android.photos_folder.hint_description') }}</AlertDescription>
        <Button
            size="sm"
            variant="outline"
            class="col-start-2 mt-2 justify-self-start"
            :disabled="choosing"
            data-testid="photos-folder-choose"
            @click="choose">
            {{ t('android.photos_folder.choose') }}
        </Button>
    </Alert>
</template>

<script setup>
    // Screenshot Manager on Android: says why Search and "Last screenshot" find nothing while no VRChat photos
    // folder is chosen, and lets the user choose it (docs/ARCHITECTURE.md §9). The folder is read again when the app
    // comes back from the system folder picker.
    import { onBeforeUnmount, onMounted, ref } from 'vue';
    import { FolderSearch } from 'lucide-vue-next';
    import { useI18n } from 'vue-i18n';

    import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
    import { Button } from '@/components/ui/button';
    import { onAndroidEvent } from '@/shared/utils/platform';

    import { getPhotosFolder, openPhotosFolder } from '../photosFolder.js';

    const { t } = useI18n();

    const missing = ref(false);
    const choosing = ref(false);

    async function refresh() {
        missing.value = (await getPhotosFolder()) === '';
    }

    async function choose() {
        choosing.value = true;
        try {
            await openPhotosFolder({ t });
            await refresh();
        } finally {
            choosing.value = false;
        }
    }

    function onVisibilityChange() {
        if (!document.hidden) refresh();
    }

    const unsubscribers = [];
    onMounted(() => {
        refresh();
        document.addEventListener('visibilitychange', onVisibilityChange);
        unsubscribers.push(
            () => document.removeEventListener('visibilitychange', onVisibilityChange),
            onAndroidEvent('focus', () => refresh()),
            onAndroidEvent('visibility', (payload) => {
                if (payload?.visible !== false) refresh();
            })
        );
    });
    onBeforeUnmount(() => {
        unsubscribers.splice(0).forEach((off) => off());
    });

    defineExpose({ refresh });
</script>
