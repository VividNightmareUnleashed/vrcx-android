<template>
    <div data-slot="data-table-cards" class="vrcx-card-list text-[13px] in-[.is-compact-table]:text-[12px]" role="list">
        <template v-if="getRows().length">
            <template v-for="(row, rowIndex) in getRows()" :key="row.id">
                <ContextMenu v-if="$slots['row-context-menu']">
                    <ContextMenuTrigger as-child>
                        <DataTableCardRow
                            :row="row"
                            :row-class="getRowClass(row, rowIndex)"
                            :expanded-renderer="expandedRenderer"
                            :has-expanded-slot="Boolean($slots.expanded)"
                            @row-click="handleRowClick">
                            <template v-if="$slots.expanded" #expanded="slotProps">
                                <slot name="expanded" v-bind="slotProps" />
                            </template>
                        </DataTableCardRow>
                    </ContextMenuTrigger>
                    <slot name="row-context-menu" :row="row" />
                </ContextMenu>
                <DataTableCardRow
                    v-else
                    :row="row"
                    :row-class="getRowClass(row, rowIndex)"
                    :expanded-renderer="expandedRenderer"
                    :has-expanded-slot="Boolean($slots.expanded)"
                    @row-click="handleRowClick">
                    <template v-if="$slots.expanded" #expanded="slotProps">
                        <slot name="expanded" v-bind="slotProps" />
                    </template>
                </DataTableCardRow>
            </template>
        </template>
        <div v-else class="flex min-h-24 items-center justify-center p-2 text-center" role="listitem">
            <slot name="empty" />
        </div>
    </div>
</template>

<script setup>
    // Card rendering of a DataTableLayout table for phones (docs/DESIGN.md §3.1). Renders only the current page of
    // rows (table.getRowModel()) and reuses every column's own cell renderer.
    import { ContextMenu, ContextMenuTrigger } from '../context-menu';

    import DataTableCardRow from './DataTableCardRow.vue';

    const props = defineProps({
        table: {
            type: Object,
            required: true
        },
        rowClass: {
            type: Function,
            default: null
        },
        onRowClick: {
            type: Function,
            default: null
        },
        striped: {
            type: Boolean,
            default: false
        },
        expandedRenderer: {
            type: [Function, Object],
            default: null
        }
    });

    // Read during render (like DataTableLayout's table body) so TanStack's reactive state is tracked.
    const getRows = () => props.table.getRowModel().rows ?? [];

    const getRowClass = (row, rowIndex) => [
        props.striped && rowIndex % 2 === 1 ? 'bg-muted/20' : '',
        props.rowClass?.(row) ?? ''
    ];

    // PC parity: the row click handler when the table has one; otherwise a tap expands expandable rows (the PC
    // expander chevron is too small to hit).
    function handleRowClick(row, { interactive }) {
        if (props.onRowClick) {
            props.onRowClick(row);
            return;
        }
        if (!interactive && row.getCanExpand?.()) {
            row.toggleExpanded();
        }
    }
</script>
