<script setup>
    import { HoverCardRoot, useForwardPropsEmits } from 'reka-ui';
    import { computed, provide } from 'vue';
    import { reactiveOmit } from '@vueuse/core';

    import { isAndroid } from '@/shared/utils/platform';

    import { HOVER_CARD_TOUCH_KEY } from './touch';

    const props = defineProps({
        defaultOpen: { type: Boolean, required: false },
        open: { type: Boolean, required: false },
        openDelay: { type: Number, required: false },
        closeDelay: { type: Number, required: false },
        // Tap toggles the card on touch screens (docs/DESIGN.md §3.3). On by default in the Android build. The toggle
        // is done by HoverCardTrigger (./touch.js), which leaves taps on buttons and links inside the trigger alone;
        // reka's own touch toggle stays off.
        enableTouch: { type: Boolean, required: false, default: () => isAndroid }
    });
    const emits = defineEmits(['update:open']);

    const forwarded = useForwardPropsEmits(reactiveOmit(props, 'enableTouch'), emits);

    provide(HOVER_CARD_TOUCH_KEY, { enabled: computed(() => props.enableTouch) });
</script>

<template>
    <HoverCardRoot v-slot="slotProps" data-slot="hover-card" v-bind="forwarded" :enable-touch="false">
        <slot v-bind="slotProps" />
    </HoverCardRoot>
</template>
