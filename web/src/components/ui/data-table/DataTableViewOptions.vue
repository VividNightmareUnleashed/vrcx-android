<template>
    <Sheet v-model:open="open">
        <SheetTrigger as-child>
            <Button
                variant="outline"
                size="icon-sm"
                class="shrink-0"
                data-slot="data-table-view-options"
                :aria-label="t('android.shell.view_options.title')">
                <SlidersHorizontal />
            </Button>
        </SheetTrigger>
        <!-- Above dialogs (the modal portal root is z-10000): tables inside full-screen dialogs have this button too.
             Below floating content (z-12000), so its selects still open on top. -->
        <SheetContent side="bottom" class="z-[10001] gap-0 rounded-t-lg p-0" overlay-class="z-[10001]">
            <SheetHeader class="border-b pb-3">
                <SheetTitle>{{ t('android.shell.view_options.title') }}</SheetTitle>
                <SheetDescription class="sr-only">{{ t('android.shell.view_options.title') }}</SheetDescription>
            </SheetHeader>

            <div class="flex flex-col gap-5 overflow-y-auto p-4 text-sm">
                <section v-if="sortableColumns.length" class="flex flex-col gap-2">
                    <span class="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
                        {{ t('android.shell.view_options.sort') }}
                    </span>
                    <div class="flex items-center gap-2">
                        <Select :model-value="sortColumnId" @update:model-value="setSortColumn">
                            <SelectTrigger class="min-w-0 flex-1" data-slot="view-options-sort">
                                <SelectValue />
                            </SelectTrigger>
                            <SelectContent>
                                <SelectItem :value="NO_SORT">{{
                                    t('android.shell.view_options.sort_none')
                                }}</SelectItem>
                                <SelectItem v-for="col in sortableColumns" :key="col.id" :value="col.id">
                                    {{ resolveHeaderLabel(col) }}
                                </SelectItem>
                            </SelectContent>
                        </Select>
                        <Button
                            variant="outline"
                            class="shrink-0"
                            :disabled="sortColumnId === NO_SORT"
                            :aria-label="
                                sortDesc
                                    ? t('android.shell.view_options.descending')
                                    : t('android.shell.view_options.ascending')
                            "
                            @click="toggleSortDirection">
                            <ArrowDownWideNarrow v-if="sortDesc" />
                            <ArrowUpNarrowWide v-else />
                            {{
                                sortDesc
                                    ? t('android.shell.view_options.descending')
                                    : t('android.shell.view_options.ascending')
                            }}
                        </Button>
                    </div>
                </section>

                <section v-if="toggleableColumns.length" class="flex flex-col gap-1">
                    <span class="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
                        {{ t('android.shell.view_options.columns') }}
                    </span>
                    <label
                        v-for="col in toggleableColumns"
                        :key="col.id"
                        class="flex min-h-10 cursor-pointer items-center gap-3 rounded-md px-1 active:bg-muted/50">
                        <Checkbox
                            :model-value="col.getIsVisible()"
                            @update:model-value="col.toggleVisibility(!!$event)" />
                        <span class="min-w-0 truncate">{{ resolveHeaderLabel(col) }}</span>
                    </label>
                </section>

                <section v-if="pageSizes.length > 1" class="flex flex-col gap-2">
                    <span class="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
                        {{ t('table.pagination.rows_per_page') }}
                    </span>
                    <Select :model-value="String(pageSize)" @update:model-value="(value) => setPageSize(Number(value))">
                        <SelectTrigger class="w-full" data-slot="view-options-page-size">
                            <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                            <SelectItem v-for="size in pageSizes" :key="String(size)" :value="String(size)">
                                {{ size }}
                            </SelectItem>
                        </SelectContent>
                    </Select>
                </section>

                <Button v-if="resetAll" variant="outline" class="self-start" @click="handleReset">
                    <RotateCcw />
                    {{ t('table.header_menu.reset_all') }}
                </Button>
            </div>
        </SheetContent>
    </Sheet>
</template>

<script setup>
    // Phone replacement for the PC table header context menu (docs/DESIGN.md §3.1): sort, columns, rows per page,
    // reset. Opens as a bottom sheet from a toolbar button.
    import { computed, ref } from 'vue';
    import { ArrowDownWideNarrow, ArrowUpNarrowWide, RotateCcw, SlidersHorizontal } from 'lucide-vue-next';
    import { useI18n } from 'vue-i18n';

    import { Button } from '../button';
    import { Checkbox } from '../checkbox';
    import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../select';
    import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle, SheetTrigger } from '../sheet';
    import { getSortableColumns, getToggleableColumns, resolveHeaderLabel } from './dataTableHelpers.js';

    const NO_SORT = '__none__';

    const props = defineProps({
        table: {
            type: Object,
            required: true
        },
        pageSizes: {
            type: Array,
            default: () => []
        },
        pageSize: {
            type: Number,
            default: 0
        },
        setPageSize: {
            type: Function,
            required: true
        },
        enableColumnVisibility: {
            type: Boolean,
            default: true
        },
        resetAll: {
            type: Function,
            default: null
        }
    });

    const { t } = useI18n();
    const open = ref(false);

    const allColumns = computed(() => props.table?.getAllLeafColumns?.() ?? []);
    const sortableColumns = computed(() => getSortableColumns(allColumns.value));
    const toggleableColumns = computed(() =>
        props.enableColumnVisibility ? getToggleableColumns(allColumns.value) : []
    );

    const sorting = computed(() => props.table?.getState?.().sorting ?? []);
    const sortColumnId = computed(() => sorting.value[0]?.id ?? NO_SORT);
    const sortDesc = computed(() => Boolean(sorting.value[0]?.desc));

    function setSortColumn(id) {
        if (!id || id === NO_SORT) {
            props.table.setSorting([]);
            return;
        }
        props.table.setSorting([{ id, desc: sortDesc.value }]);
    }

    function toggleSortDirection() {
        const id = sortColumnId.value;
        if (id === NO_SORT) return;
        props.table.setSorting([{ id, desc: !sortDesc.value }]);
    }

    function handleReset() {
        props.resetAll?.();
        open.value = false;
    }

    defineExpose({ open });
</script>
