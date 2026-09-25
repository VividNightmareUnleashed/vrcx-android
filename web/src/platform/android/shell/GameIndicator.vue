<template>
    <TooltipWrapper v-if="companion.isPaired" side="bottom" :content="label">
        <button
            type="button"
            class="vrcx-game-indicator inline-flex size-10 shrink-0 items-center justify-center rounded-full cursor-pointer"
            :aria-label="label"
            :data-state="state"
            @click="openCompanionSettings(router)">
            <span class="inline-block size-2.5 rounded-full" :class="dotClass" />
        </button>
    </TooltipWrapper>
</template>

<script setup>
    // App-bar game indicator (docs/DESIGN.md §2.1): hidden without a paired PC companion; --status-online while
    // VRChat runs on the PC, muted when it does not, hollow while the companion is disconnected.
    import { computed } from 'vue';
    import { useI18n } from 'vue-i18n';
    import { useRouter } from 'vue-router';

    import { TooltipWrapper } from '../../../components/ui/tooltip';
    import { useCompanionStore } from '../companionStore';
    import { openCompanionSettings } from './navigation';

    const { t } = useI18n();
    const router = useRouter();
    const companion = useCompanionStore();

    const state = computed(() => {
        if (!companion.isConnected) return 'disconnected';
        return companion.vrchatRunning ? 'running' : 'stopped';
    });

    const dotClass = computed(() => {
        switch (state.value) {
            case 'running':
                return 'bg-status-online';
            case 'stopped':
                return 'bg-status-offline-alt';
            default:
                return 'border-2 border-status-offline-alt bg-transparent';
        }
    });

    const label = computed(() => t(`android.shell.game_indicator.${state.value}`));
</script>
