<template>
    <!-- The span carries the tooltip trigger: as-child on the Toggle itself would overwrite its data-state="on". -->
    <TooltipWrapper side="bottom" :content="t('android.shell.quick_actions.hint')">
        <span class="inline-flex shrink-0">
            <Toggle
                variant="outline"
                size="sm"
                data-slot="quick-actions-toggle"
                class="shrink-0 gap-1.5"
                :class="pressed && 'border-destructive/60 text-destructive data-[state=on]:text-destructive'"
                :model-value="pressed"
                :aria-label="t('android.shell.quick_actions.label')"
                @update:model-value="setPressed">
                <Zap />
                <span v-if="showLabel">{{ t('android.shell.quick_actions.label') }}</span>
            </Toggle>
        </span>
    </TooltipWrapper>
</template>

<script setup>
    // Touch stand-in for holding Shift on PC (docs/DESIGN.md §3.3): while on, delete buttons delete without asking
    // and the Shift-only actions appear, exactly as with `ui.shiftHeld`.
    import { computed, onBeforeUnmount, onDeactivated } from 'vue';
    import { Zap } from 'lucide-vue-next';
    import { useI18n } from 'vue-i18n';

    import { useUiStore } from '@/stores';

    import { Toggle } from '../toggle';
    import { TooltipWrapper } from '../tooltip';

    defineProps({
        showLabel: {
            type: Boolean,
            default: false
        }
    });

    const { t } = useI18n();
    const uiStore = useUiStore();

    const pressed = computed(() => Boolean(uiStore.shiftHeld));

    function setPressed(value) {
        uiStore.shiftHeld = Boolean(value);
    }

    // Never leave the dangerous mode on behind the user's back.
    const release = () => {
        if (uiStore.shiftHeld) {
            uiStore.shiftHeld = false;
        }
    };
    onDeactivated(release);
    onBeforeUnmount(release);
</script>
