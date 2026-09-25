<template>
    <template v-if="mode === 'unpaired' || mode === 'disconnected'">
        <div
            v-if="variant === 'widget'"
            class="flex h-full flex-col items-center justify-center gap-2 px-4 text-center"
            data-testid="companion-empty-state">
            <MonitorSmartphone class="size-5 text-muted-foreground" />
            <div class="text-[13px] font-medium text-foreground">{{ title }}</div>
            <div class="text-xs text-muted-foreground">{{ description }}</div>
            <Button size="sm" variant="outline" class="mt-1" data-testid="companion-empty-action" @click="openSettings">
                {{ actionLabel }}
            </Button>
        </div>
        <Empty v-else class="p-6 md:p-8" data-testid="companion-empty-state">
            <EmptyHeader>
                <EmptyMedia variant="icon">
                    <MonitorSmartphone class="text-lg" />
                </EmptyMedia>
                <EmptyTitle class="text-sm">{{ title }}</EmptyTitle>
                <EmptyDescription>{{ description }}</EmptyDescription>
            </EmptyHeader>
            <EmptyContent>
                <Button size="sm" variant="outline" data-testid="companion-empty-action" @click="openSettings">
                    {{ actionLabel }}
                </Button>
            </EmptyContent>
        </Empty>
    </template>
    <template v-else-if="mode === 'fallback'">
        <div
            v-if="variant === 'widget'"
            class="flex h-full items-center justify-center text-[13px] text-muted-foreground"
            data-testid="companion-empty-fallback">
            {{ fallbackText }}
        </div>
        <DataTableEmpty v-else :type="emptyType" data-testid="companion-empty-fallback" />
    </template>
</template>

<script setup>
    import { computed } from 'vue';
    import { MonitorSmartphone } from 'lucide-vue-next';
    import { useI18n } from 'vue-i18n';
    import { useRouter } from 'vue-router';

    import { Button } from '@/components/ui/button';
    import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyMedia, EmptyTitle } from '@/components/ui/empty';
    import DataTableEmpty from '@/components/ui/data-table/DataTableEmpty.vue';

    import { COMPANION_SETTINGS_ROUTE, resolveCompanionEmptyMode, useCompanionStore } from '../companionStore.js';

    // Empty state for lists that are filled from the PC's VRChat log (docs/DESIGN.md §4). When a companion is
    // connected, or the list is only empty because of a filter, it renders the normal empty state instead.

    const props = defineProps({
        /** `table` inside a DataTableLayout / page, `widget` inside a dashboard widget. */
        variant: { type: String, default: 'table' },
        /** Which list this is: `gameLog` or `playerList` (changes the description). */
        kind: { type: String, default: 'gameLog' },
        loading: { type: Boolean, default: false },
        filtered: { type: Boolean, default: false },
        /** DataTableEmpty type for the table fallback. */
        emptyType: { type: String, default: 'nodata' },
        /** Text of the widget fallback (the widget's own empty text). */
        fallbackText: { type: String, default: '' }
    });

    const { t } = useI18n();
    const router = useRouter();
    const companion = useCompanionStore();

    const mode = computed(() =>
        resolveCompanionEmptyMode({
            loading: props.loading,
            filtered: props.filtered,
            isPaired: companion.isPaired,
            isConnected: companion.isConnected
        })
    );

    const pcName = computed(() => companion.activePc?.name || companion.machineName || '');

    // DESIGN.md §4: one title and one action whether no PC is paired or the paired PC isn't connected; only the
    // description changes, so it can name the PC.
    const title = computed(() => t('android.empty.title'));

    const description = computed(() => {
        if (mode.value === 'disconnected') {
            return t('android.empty.disconnected_description', { name: pcName.value || t('android.empty.your_pc') });
        }
        return props.kind === 'playerList'
            ? t('android.empty.player_list_description')
            : t('android.empty.game_log_description');
    });

    const actionLabel = computed(() => t('android.empty.action'));

    function openSettings() {
        router.push({ ...COMPANION_SETTINGS_ROUTE, query: { ...COMPANION_SETTINGS_ROUTE.query } });
    }
</script>
