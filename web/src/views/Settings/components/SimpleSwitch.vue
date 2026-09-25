<template>
    <div class="simple-switch">
        <div class="name" :style="{ width: longLabel ? '300px' : undefined }">
            {{ label }}
            <TooltipWrapper v-if="tooltip" side="top" :content="tooltip">
                <Info class="tooltip" />
            </TooltipWrapper>
            <!-- Phones: the tooltip is hover-only information, so it is shown under the label (DESIGN.md §3.3). -->
            <span v-if="tooltip && isCompact" class="tooltip-text">{{ tooltip }}</span>
        </div>

        <Switch class="switch" :model-value="value" @update:modelValue="change" :disabled="disabled" />
    </div>
</template>

<script setup>
    import { Info } from 'lucide-vue-next';

    import { Switch } from '../../../components/ui/switch';
    import { useCompactLayout } from '../../../composables/useCompactLayout';

    defineProps({
        label: String,
        value: Boolean,
        tooltip: String,
        disabled: Boolean,
        longLabel: Boolean
    });

    const emit = defineEmits(['change']);

    // Always false in desktop builds.
    const { isCompact } = useCompactLayout();

    /**
     * @param event
     */
    function change(event) {
        emit('change', event);
    }
</script>

<style scoped>
    .simple-switch {
        font-size: 12px;
        display: flex;
        align-items: center;
    }
    .simple-switch > .name {
        width: 225px;
        min-width: 225px;
        word-wrap: break-word;
        padding-top: 7px;
        display: flex;
        align-items: center;
    }
    .simple-switch > .switch {
        margin-left: 8px;
    }
    .simple-switch .tooltip {
        margin-left: 3px;
    }

    /* Phones: the label takes the free width (the fixed 225/300 px column does not fit next to the switch). */
    :global(html.vrcx-compact) .simple-switch {
        justify-content: space-between;
        gap: 12px;
    }
    :global(html.vrcx-compact) .simple-switch > .name {
        width: auto !important;
        min-width: 0;
        flex: 1 1 auto;
        flex-wrap: wrap;
        padding-top: 0;
    }
    :global(html.vrcx-compact) .simple-switch > .switch {
        margin-left: 0;
        flex-shrink: 0;
    }
    .simple-switch .tooltip-text {
        flex-basis: 100%;
        margin-top: 2px;
        font-size: 11px;
        line-height: 1.3;
        color: var(--muted-foreground);
    }
</style>
