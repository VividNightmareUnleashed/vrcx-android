<template>
    <div
        ref="rowEl"
        role="listitem"
        data-card-row=""
        :data-state="expanded ? 'expanded' : 'collapsed'"
        :class="[
            'group/row flex flex-col border-b border-border last:border-b-0 select-none [-webkit-touch-callout:none] transition-colors active:bg-muted/50',
            rowClass
        ]"
        @click="handleClick">
        <div class="flex min-h-(--touch-min,40px) gap-2.5 px-2.5 py-2">
            <div v-if="groups.leading.length" class="flex shrink-0 items-start gap-1 pt-px">
                <!-- A hint class gets its own box; without one the cell stays a direct flex item. -->
                <div
                    v-for="entry in groups.leading"
                    :key="entry.cell.id"
                    :class="entry.hint.class || 'contents'"
                    data-card-slot="leading">
                    <FlexRender :render="entry.cell.column.columnDef.cell" :props="entry.cell.getContext()" />
                </div>
            </div>

            <div class="flex min-w-0 flex-1 flex-col gap-1">
                <div
                    v-if="groups.title.length || groups.titleSuffix.length || groups.trailing.length"
                    class="flex min-w-0 items-center gap-2">
                    <div class="flex min-w-0 flex-1 items-center gap-1.5">
                        <div v-if="groups.title.length" class="min-w-0 truncate font-medium">
                            <span
                                v-for="entry in groups.title"
                                :key="entry.cell.id"
                                :class="entry.hint.class || 'contents'"
                                data-card-slot="title">
                                <FlexRender
                                    :render="entry.cell.column.columnDef.cell"
                                    :props="entry.cell.getContext()" />
                            </span>
                        </div>
                        <div
                            v-for="entry in groups.titleSuffix"
                            :key="entry.cell.id"
                            :class="cn('flex shrink-0 items-center text-muted-foreground', entry.hint.class)">
                            <FlexRender :render="entry.cell.column.columnDef.cell" :props="entry.cell.getContext()" />
                        </div>
                    </div>
                    <div
                        v-for="entry in groups.trailing"
                        :key="entry.cell.id"
                        :class="cn('shrink-0 text-xs text-muted-foreground tabular-nums', entry.hint.class)">
                        <FlexRender :render="entry.cell.column.columnDef.cell" :props="entry.cell.getContext()" />
                    </div>
                </div>

                <div v-if="groups.badge.length" class="flex min-w-0 flex-wrap items-center gap-1">
                    <div v-for="entry in groups.badge" :key="entry.cell.id" :class="cn('min-w-0', entry.hint.class)">
                        <FlexRender :render="entry.cell.column.columnDef.cell" :props="entry.cell.getContext()" />
                    </div>
                </div>

                <div
                    v-for="entry in groups.body"
                    :key="entry.cell.id"
                    :class="cn('min-w-0 line-clamp-2 break-words', entry.hint.class)">
                    <FlexRender :render="entry.cell.column.columnDef.cell" :props="entry.cell.getContext()" />
                </div>

                <!-- The footer keeps at least 10rem beside the actions: a group of several controls that does not fit
                     next to it wraps onto its own line under the card body instead of squeezing the footer. -->
                <div
                    v-if="groups.footer.length || groups.actions.length"
                    class="flex min-w-0 flex-wrap items-end gap-x-2 gap-y-1">
                    <div
                        v-if="groups.footer.length"
                        class="flex min-w-0 flex-1 basis-40 flex-wrap items-center gap-x-2 gap-y-0.5 text-[11px] text-muted-foreground">
                        <div
                            v-for="entry in groups.footer"
                            :key="entry.cell.id"
                            :class="cn('flex min-w-0 max-w-full items-center gap-1', entry.hint.class)">
                            <span v-if="entry.hint.label" class="shrink-0"
                                >{{ resolveHeaderLabel(entry.cell.column) }}:</span
                            >
                            <div class="min-w-0 truncate">
                                <FlexRender
                                    :render="entry.cell.column.columnDef.cell"
                                    :props="entry.cell.getContext()" />
                            </div>
                        </div>
                    </div>
                    <div
                        v-if="groups.actions.length"
                        data-card-slot="actions"
                        class="ml-auto flex max-w-full shrink-0 flex-wrap items-center justify-end gap-1 pointer-coarse:[&_button]:min-h-10 pointer-coarse:[&_button]:min-w-10"
                        @click.stop>
                        <div v-for="entry in groups.actions" :key="entry.cell.id" :class="entry.hint.class">
                            <FlexRender :render="entry.cell.column.columnDef.cell" :props="entry.cell.getContext()" />
                        </div>
                    </div>
                </div>

                <template v-if="expanded">
                    <div v-for="entry in groups.detail" :key="entry.cell.id" :class="cn('min-w-0', entry.hint.class)">
                        <FlexRender :render="entry.cell.column.columnDef.cell" :props="entry.cell.getContext()" />
                    </div>
                </template>
            </div>
        </div>

        <div v-if="expanded && (hasExpandedSlot || expandedRenderer)" class="overflow-x-auto px-2.5 pb-2">
            <slot v-if="hasExpandedSlot" name="expanded" :row="row" />
            <FlexRender v-else :render="expandedRenderer" :props="{ row }" />
        </div>
    </div>
</template>

<script setup>
    // One card of DataTableCardList: the row's visible cells placed by their column's meta.mobile hint. A hint's
    // `class` is merged over the slot's own classes (cn), so it can override them.
    import { computed, ref } from 'vue';
    import { FlexRender } from '@tanstack/vue-table';

    import { cn } from '@/lib/utils';

    import { groupCellsBySlot, isInteractiveTarget, resolveHeaderLabel } from './dataTableHelpers.js';

    const props = defineProps({
        row: {
            type: Object,
            required: true
        },
        rowClass: {
            type: [String, Array, Object],
            default: ''
        },
        expandedRenderer: {
            type: [Function, Object],
            default: null
        },
        hasExpandedSlot: {
            type: Boolean,
            default: false
        }
    });

    const emit = defineEmits(['row-click']);

    const rowEl = ref(null);

    // Recomputed on render: column visibility and expansion live in TanStack's reactive table state.
    const groups = computed(() => groupCellsBySlot(props.row.getVisibleCells()));
    const expanded = computed(() => Boolean(props.row.getIsExpanded?.()));

    function handleClick(event) {
        emit('row-click', props.row, { interactive: isInteractiveTarget(event.target, rowEl.value) });
    }
</script>
