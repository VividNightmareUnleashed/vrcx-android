<template>
    <div class="flex flex-col flex-wrap gap-x-6 gap-y-2">
        <div v-if="description" class="flex flex-col gap-1">
            <span>{{ t('dialog.user.info.instance_description') }}</span>
            <span class="max-w-80 whitespace-pre-wrap break-words">{{ description }}</span>
        </div>

        <div
            v-if="instance?.platforms?.standalonewindows || instance?.platforms?.android || instance?.platforms?.ios"
            class="flex flex-col gap-1">
            <span v-if="instance.platforms.standalonewindows">
                <span class="text-platform-pc border-platform-pc!">PC: </span>
                {{ instance.platforms.standalonewindows }}
            </span>
            <span v-if="instance.platforms.android">
                <span class="text-platform-quest border-platform-quest!">Android: </span>
                {{ instance.platforms.android }}
            </span>
            <span v-if="instance.platforms.ios">
                <span class="text-platform-ios border-platform-quest!">iOS: </span>
                {{ instance.platforms.ios }}
            </span>
        </div>

        <div v-if="languages.length" class="flex flex-col gap-1">
            <span>{{ t('dialog.user.info.instance_languages') }}</span>
            <span v-for="lang in languages" :key="lang.key" class="flex items-center gap-1.5">
                <span :class="['flags', 'inline-block', languageClass(lang.flagKey)]"></span>
                {{ lang.name }}
                <span v-if="lang.percent !== null" class="text-muted-foreground"> {{ lang.percent }}% </span>
                <Star
                    v-if="lang.isCreationLanguage"
                    class="h-3 w-3 text-yellow-500"
                    :title="t('dialog.user.info.instance_creation_language')" />
            </span>
        </div>

        <span v-if="instance?.gameServerVersion">
            {{ t('dialog.user.info.instance_game_version') }} {{ instance?.gameServerVersion }}
        </span>

        <span v-if="instance?.queueEnabled" class="text-yellow-500 font-medium">
            {{ t('dialog.user.info.instance_queuing_enabled') }}
        </span>

        <span v-if="disabledContentSettings">
            {{ t('dialog.user.info.instance_disabled_content') }}
            {{ disabledContentSettings }}
        </span>
    </div>
</template>

<script setup>
    // The instance details behind the player count in InstanceActionBar: a tooltip on PC, a popover on touch
    // (a tooltip has no tap gesture). The description and language list are computed by InstanceActionBar.
    import { Star } from 'lucide-vue-next';
    import { useI18n } from 'vue-i18n';

    import { languageClass } from '../shared/utils';

    defineProps({
        instance: {
            type: Object,
            default: null
        },
        description: {
            type: String,
            default: ''
        },
        languages: {
            type: Array,
            default: () => []
        },
        disabledContentSettings: {
            type: String,
            default: ''
        }
    });

    const { t } = useI18n();
</script>
