<template>
    <div class="flex flex-col flex-wrap gap-x-6 gap-y-2">
        <div class="flex flex-col gap-1">
            <span>
                <span class="text-platform-pc border-platform-pc!">PC: </span>
                {{ instance?.platforms?.standalonewindows }}
            </span>
            <span>
                <span class="text-platform-quest border-platform-quest!">Android: </span>
                {{ instance?.platforms?.android }}
            </span>
            <span>
                <span class="text-platform-ios border-platform-quest!">iOS: </span>
                {{ instance?.platforms?.ios }}
            </span>
        </div>

        <span> {{ t('dialog.user.info.instance_game_version') }} {{ instance?.gameServerVersion }} </span>

        <span v-if="instance?.queueEnabled" class="text-yellow-500 font-medium">
            {{ t('dialog.user.info.instance_queuing_enabled') }}
        </span>

        <span v-if="disabledContentSettings">
            {{ t('dialog.user.info.instance_disabled_content') }}
            {{ disabledContentSettings }}
        </span>

        <template v-if="canCloseInstance && !instance?.closedAt">
            <!-- Touch: the popover has room for the label, and a tooltip inside it would need another long-press. -->
            <Button
                v-if="labelled"
                class="h-8 w-fit gap-1.5 text-xs"
                size="sm"
                variant="destructive"
                @click="emit('close-instance')">
                <PowerIcon class="h-4 w-4" />
                {{ t('dialog.user.info.close_instance') }}
            </Button>
            <TooltipWrapper v-else side="top" :content="t('dialog.user.info.close_instance')">
                <Button
                    class="w-12 h-6 text-xs hover:text-muted-foreground"
                    size="icon-sm"
                    variant="destructive"
                    :ariaLabel="t('dialog.user.info.close_instance')"
                    @click="emit('close-instance')">
                    <PowerIcon class="h-4 w-4" />
                </Button>
            </TooltipWrapper>
        </template>
    </div>
</template>

<script setup>
    // The instance details behind the player count in InstanceActionBar: a tooltip on PC, a popover on touch
    // (the tooltip holds the only "Close instance" button).
    import { PowerIcon } from 'lucide-vue-next';
    import { useI18n } from 'vue-i18n';

    import { Button } from './ui/button';
    import { TooltipWrapper } from './ui/tooltip';

    defineProps({
        instance: {
            type: Object,
            default: null
        },
        disabledContentSettings: {
            type: String,
            default: ''
        },
        canCloseInstance: {
            type: Boolean,
            default: false
        },
        // Show "Close instance" as a labelled button (touch popover) instead of an icon with a tooltip.
        labelled: {
            type: Boolean,
            default: false
        }
    });

    const emit = defineEmits(['close-instance']);

    const { t } = useI18n();
</script>
