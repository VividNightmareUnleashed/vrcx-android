<template>
    <!-- Phones: the same content in a bottom sheet (docs/DESIGN.md §3.4). Desktop: the upstream popover, unchanged. -->
    <Sheet v-if="isCompact" v-model:open="openModel">
        <SheetTrigger as-child>
            <slot name="trigger" />
        </SheetTrigger>
        <SheetContent
            side="bottom"
            class="z-[10001] gap-0 rounded-t-lg p-0"
            overlay-class="z-[10001]"
            data-testid="responsive-popover-sheet">
            <SheetHeader class="border-b pb-3">
                <SheetTitle>{{ title }}</SheetTitle>
                <SheetDescription class="sr-only">{{ description || title }}</SheetDescription>
            </SheetHeader>
            <div :class="['overflow-y-auto p-4', sheetBodyClass]">
                <slot />
            </div>
        </SheetContent>
    </Sheet>
    <Popover v-else v-model:open="openModel">
        <PopoverTrigger as-child>
            <slot name="trigger" />
        </PopoverTrigger>
        <PopoverContent :class="contentClass" :side="side" :align="align">
            <slot />
        </PopoverContent>
    </Popover>
</template>

<script setup>
    // A popover on PC, a bottom sheet in the phone layout. Shared by the views of this area (Feed and Game Log date
    // filters, Friends Locations settings).
    import { computed, onDeactivated, ref } from 'vue';

    import { Popover, PopoverContent, PopoverTrigger } from '../../../components/ui/popover';
    import {
        Sheet,
        SheetContent,
        SheetDescription,
        SheetHeader,
        SheetTitle,
        SheetTrigger
    } from '../../../components/ui/sheet';
    import { useCompactLayout } from '../../../composables/useCompactLayout';

    const props = defineProps({
        open: { type: Boolean, default: undefined },
        /** Sheet title (phones only; the PC popover has no header). */
        title: { type: String, default: '' },
        description: { type: String, default: '' },
        contentClass: { type: null, default: undefined },
        side: { type: String, default: undefined },
        align: { type: String, default: undefined },
        sheetBodyClass: { type: null, default: undefined }
    });

    const emit = defineEmits(['update:open']);

    const { isCompact } = useCompactLayout();

    // Uncontrolled use (no v-model:open) keeps the state here, so the sheet can still be closed below.
    const localOpen = ref(false);

    const openModel = computed({
        get: () => (props.open === undefined ? localOpen.value : props.open),
        set: (value) => {
            localOpen.value = value;
            emit('update:open', value);
        }
    });

    // The sheet is teleported to <body> and the view is kept alive, so a programmatic route change (a notification,
    // a dialog link) would leave it open over the next page. Phones close it when the view is deactivated; the PC
    // popover keeps its upstream behaviour.
    onDeactivated(() => {
        if (isCompact.value && openModel.value) {
            openModel.value = false;
        }
    });
</script>
