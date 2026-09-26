<template>
    <!-- Phones: the submenu's items inline under a label; a side submenu would open off-screen. -->
    <template v-if="isCompact">
        <DropdownMenuSeparator v-if="separator" />
        <DropdownMenuLabel class="flex items-center gap-2 text-xs font-medium text-muted-foreground" data-flattened-sub>
            <component :is="icon" v-if="icon" class="size-4" />
            <span>{{ label }}</span>
        </DropdownMenuLabel>
        <DropdownMenuGroup>
            <slot />
        </DropdownMenuGroup>
        <DropdownMenuSeparator v-if="separatorAfter" />
    </template>
    <DropdownMenuSub v-else>
        <DropdownMenuSubTrigger @pointerdown="onTriggerPointerDown" @click="onTriggerClick">
            <component :is="icon" v-if="icon" class="size-4 mr-2" />
            <span>{{ label }}</span>
        </DropdownMenuSubTrigger>
        <DropdownMenuSubContent
            :side="side"
            :align="align"
            :class="[contentClass, 'pointer-coarse:[&_[data-slot=dropdown-menu-item]]:py-2.5']">
            <slot />
        </DropdownMenuSubContent>
    </DropdownMenuSub>
</template>

<script setup>
    // A DropdownMenuSub that the phone layout flattens: in compact layout the
    // items follow a label inside the parent menu. Elsewhere it is the PC submenu, whose trigger may also run an
    // action on click (Share copies the URL); on touch that tap only opens the submenu, because the same tap cannot
    // mean both "open" and "copy".
    import {
        DropdownMenuGroup,
        DropdownMenuLabel,
        DropdownMenuSeparator,
        DropdownMenuSub,
        DropdownMenuSubContent,
        DropdownMenuSubTrigger
    } from './ui/dropdown-menu';
    import { useCompactLayout } from '../composables/useCompactLayout';

    defineProps({
        label: {
            type: String,
            required: true
        },
        icon: {
            type: [Object, Function],
            default: null
        },
        side: {
            type: String,
            default: 'right'
        },
        align: {
            type: String,
            default: undefined
        },
        contentClass: {
            type: null,
            default: 'w-56'
        },
        // Compact only: a separator above the inline group.
        separator: {
            type: Boolean,
            default: true
        },
        // Compact only: a separator below the inline group, when the parent menu has none there.
        separatorAfter: {
            type: Boolean,
            default: false
        }
    });

    const emit = defineEmits(['trigger-click']);

    const { isCompact } = useCompactLayout();

    let lastPointerType = '';

    function onTriggerPointerDown(event) {
        lastPointerType = event?.pointerType ?? '';
    }

    /**
     * @param {MouseEvent & { pointerType?: string }} event
     */
    function onTriggerClick(event) {
        const pointerType = event?.pointerType || lastPointerType;
        lastPointerType = '';
        if (pointerType === 'touch' || pointerType === 'pen') {
            return;
        }
        emit('trigger-click', event);
    }
</script>
