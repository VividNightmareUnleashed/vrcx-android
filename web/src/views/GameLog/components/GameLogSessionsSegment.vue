<template>
    <div class="border-b border-border last:border-b-0" :class="{ 'border-b-0': isLast }">
        <!-- Phones: the header on two lines (location, then time, duration and counts) and an explicit menu with the
             world actions that PC opens with a right-click on the location. -->
        <div
            v-if="isCompact"
            class="sticky top-0 z-[5] flex w-full items-center gap-1 border-b border-border bg-muted pr-1"
            data-testid="game-log-segment-compact-header">
            <button
                type="button"
                class="flex min-w-0 flex-1 cursor-pointer flex-col gap-0.5 border-none bg-transparent py-2 pl-3 pr-1 text-left"
                :aria-expanded="!collapsed"
                @click="collapsed = !collapsed">
                <span class="flex w-full min-w-0 items-center gap-2">
                    <ChevronRight
                        class="size-3.5 shrink-0 text-muted-foreground transition-transform duration-150"
                        :class="{ 'rotate-90': !collapsed }" />
                    <Location
                        :location="segment.location"
                        :hint="segment.worldName"
                        :grouphint="segment.groupName"
                        class="text-sm min-w-0"
                        enable-context-menu
                        @click.stop />
                </span>
                <span class="flex w-full min-w-0 items-center gap-2 pl-5.5 text-[0.6875rem] text-muted-foreground">
                    <span class="shrink-0 tabular-nums">{{ formatTime(segment.created_at) }}</span>
                    <Badge v-if="durationText" variant="outline" class="text-[0.625rem] font-tabular-nums h-4 px-1">
                        {{ durationText }}
                    </Badge>
                    <Badge v-else-if="showCurrentBadge" variant="outline" class="text-[0.625rem] h-4 px-1">
                        {{ t('common.current_session') }}
                    </Badge>
                    <span
                        v-if="segment.events && segment.events.length > 0"
                        class="ml-auto flex shrink-0 items-center gap-2">
                        <span
                            v-if="joinCount"
                            class="flex items-center gap-0.5"
                            :aria-label="t('view.game_log.filters.OnPlayerJoined')">
                            <UserPlus class="size-3" /> {{ joinCount }}
                        </span>
                        <span
                            v-if="leftCount"
                            class="flex items-center gap-0.5"
                            :aria-label="t('view.game_log.filters.OnPlayerLeft')">
                            <UserMinus class="size-3" /> {{ leftCount }}
                        </span>
                        <span
                            v-if="videoCount"
                            class="flex items-center gap-0.5"
                            :aria-label="t('view.game_log.filters.VideoPlay')">
                            <Play class="size-3" /> {{ videoCount }}
                        </span>
                    </span>
                </span>
            </button>
            <GameLogRowMenu :entry="{ type: 'Location', location: segment.location }" />
        </div>
        <!-- Session header: sticky + clickable to collapse. Touch tablets (PC layout, coarse pointer) get the explicit
             world menu at its end, so the header becomes a div with button semantics (a button cannot hold one). -->
        <component
            :is="isCoarsePointer ? 'div' : 'button'"
            v-else
            v-bind="headerAttrs"
            class="sticky top-0 z-[5] flex items-center gap-2 px-3 py-2 bg-muted/80 backdrop-blur-sm w-full text-left border-none cursor-pointer hover:bg-muted transition-colors border-b border-border"
            @click="collapsed = !collapsed"
            @keydown="onHeaderKeydown">
            <ChevronRight
                class="size-3.5 shrink-0 text-muted-foreground transition-transform duration-150"
                :class="{ 'rotate-90': !collapsed }" />
            <Location
                :location="segment.location"
                :hint="segment.worldName"
                :grouphint="segment.groupName"
                class="text-sm min-w-0"
                enable-context-menu
                @click.stop />
            <span class="shrink-0 text-muted-foreground text-[0.6875rem]">
                {{ formatTime(segment.created_at) }}
            </span>
            <Badge v-if="durationText" variant="outline" class="text-[0.625rem] font-tabular-nums h-4 px-1">
                {{ durationText }}
            </Badge>
            <Badge v-else-if="showCurrentBadge" variant="outline" class="text-[0.625rem] h-4 px-1">
                {{ t('common.current_session') }}
            </Badge>
            <div
                v-if="segment.events && segment.events.length > 0"
                class="flex items-center gap-2 text-muted-foreground text-[0.6875rem] ml-auto shrink-0">
                <span
                    v-if="joinCount"
                    class="flex items-center gap-0.5"
                    :title="t('view.game_log.filters.OnPlayerJoined')">
                    <UserPlus class="size-3" /> {{ joinCount }}
                </span>
                <span
                    v-if="leftCount"
                    class="flex items-center gap-0.5"
                    :title="t('view.game_log.filters.OnPlayerLeft')">
                    <UserMinus class="size-3" /> {{ leftCount }}
                </span>
                <span v-if="videoCount" class="flex items-center gap-0.5" :title="t('view.game_log.filters.VideoPlay')">
                    <Play class="size-3" /> {{ videoCount }}
                </span>
            </div>
            <div
                v-if="isCoarsePointer"
                class="-my-1 flex shrink-0"
                :class="{ 'ml-auto': !(segment.events && segment.events.length > 0) }">
                <GameLogRowMenu :entry="{ type: 'Location', location: segment.location }" />
            </div>
        </component>

        <!-- Session events list -->
        <div v-if="!collapsed && segment.events && segment.events.length > 0" class="py-1 px-1">
            <GameLogSessionsEvent v-for="(event, idx) in segment.events" :key="idx" :event="event" />
        </div>
    </div>
</template>

<script setup>
    import { computed, ref } from 'vue';
    import { ChevronRight, Play, UserMinus, UserPlus } from 'lucide-vue-next';
    import { useI18n } from 'vue-i18n';

    import { Badge } from '../../../components/ui/badge';
    import { useGameStore } from '../../../stores';
    import { formatDateFilter, timeToText } from '../../../shared/utils';
    import GameLogSessionsEvent from './GameLogSessionsEvent.vue';
    import Location from '../../../components/Location.vue';
    import GameLogRowMenu from './GameLogRowMenu.vue';
    import { useCompactLayout } from '../../../composables/useCompactLayout';

    const { t } = useI18n();
    const { isCompact, isCoarsePointer } = useCompactLayout();
    const gameStore = useGameStore();

    const props = defineProps({
        segment: {
            type: Object,
            required: true
        },
        isLast: {
            type: Boolean,
            default: false
        },
        isLatest: {
            type: Boolean,
            default: false
        }
    });

    const collapsed = ref(false);

    // The PC header is a <button>; on touch tablets a div with the same role, so the world menu can sit inside it.
    const headerAttrs = computed(() =>
        isCoarsePointer.value
            ? { role: 'button', tabindex: 0, 'aria-expanded': String(!collapsed.value) }
            : { type: 'button' }
    );

    // Keyboard activation for the div header (a <button> gets it natively, so desktop keys are left alone).
    function onHeaderKeydown(event) {
        if (!isCoarsePointer.value || event.target !== event.currentTarget) return;
        if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            collapsed.value = !collapsed.value;
        }
    }

    const durationText = computed(() => {
        if (!props.segment.duration || props.segment.duration <= 0) {
            return '';
        }
        return timeToText(props.segment.duration);
    });

    const showCurrentBadge = computed(() => props.isLatest && gameStore.isGameRunning && !durationText.value);

    const joinCount = computed(() => {
        if (!props.segment.events) return 0;
        let n = 0;
        for (const e of props.segment.events) {
            if (e.type === 'OnPlayerJoined') n++;
            else if (e.type === 'JoinGroup') n += e.count;
        }
        return n;
    });

    const leftCount = computed(() => {
        if (!props.segment.events) return 0;
        let n = 0;
        for (const e of props.segment.events) {
            if (e.type === 'OnPlayerLeft') n++;
            else if (e.type === 'LeftGroup') n += e.count;
        }
        return n;
    });

    const videoCount = computed(() => {
        if (!props.segment.events) return 0;
        let n = 0;
        for (const e of props.segment.events) {
            if (e.type === 'VideoPlay') n++;
        }
        return n;
    });

    function formatTime(dateStr) {
        return formatDateFilter(dateStr, 'long');
    }
</script>
