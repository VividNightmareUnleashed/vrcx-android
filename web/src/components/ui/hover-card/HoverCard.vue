<script setup>
    import { HoverCardRoot, useForwardPropsEmits } from 'reka-ui';

    import { isAndroid } from '@/shared/utils/platform';

    const props = defineProps({
        defaultOpen: { type: Boolean, required: false },
        open: { type: Boolean, required: false },
        openDelay: { type: Number, required: false },
        closeDelay: { type: Number, required: false },
        // Tap toggles the card on touch screens (docs/DESIGN.md §3.3). On by default in the Android build.
        enableTouch: { type: Boolean, required: false, default: isAndroid }
    });
    const emits = defineEmits(['update:open']);

    const forwarded = useForwardPropsEmits(props, emits);
</script>

<template>
    <HoverCardRoot v-slot="slotProps" data-slot="hover-card" v-bind="forwarded">
        <slot v-bind="slotProps" />
    </HoverCardRoot>
</template>
