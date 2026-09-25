<script setup>
    import { HoverCardTrigger, injectHoverCardRootContext } from 'reka-ui';
    import { inject, watch } from 'vue';

    import { HOVER_CARD_TOUCH_KEY, createHoverCardTouchHandlers, isFocusInOtherModal } from './touch';

    const props = defineProps({
        reference: { type: null, required: false },
        asChild: { type: Boolean, required: false },
        as: { type: null, required: false }
    });

    // Touch toggle (docs/DESIGN.md §3.3), see ./touch.js. Without the HoverCard wrapper there is no touch toggle.
    const touch = inject(HOVER_CARD_TOUCH_KEY, null);
    const rootContext = touch ? injectHoverCardRootContext(null) : null;
    const isTouchEnabled = () => Boolean(touch?.enabled.value);
    const touchHandlers = rootContext ? createHoverCardTouchHandlers({ isEnabled: isTouchEnabled, rootContext }) : {};

    if (rootContext) {
        // Only while a touch-enabled card is open: close it when a modal it does not belong to takes focus.
        watch(
            () => rootContext.open.value && isTouchEnabled(),
            (active, _, onCleanup) => {
                if (!active || typeof document === 'undefined') return;
                const onFocusIn = (event) => {
                    if (isFocusInOtherModal(event, rootContext.triggerElement?.value)) {
                        rootContext.onDismiss();
                    }
                };
                document.addEventListener('focusin', onFocusIn, true);
                onCleanup(() => document.removeEventListener('focusin', onFocusIn, true));
            },
            { immediate: true }
        );
    }
</script>

<template>
    <HoverCardTrigger data-slot="hover-card-trigger" v-bind="{ ...props, ...touchHandlers }">
        <slot />
    </HoverCardTrigger>
</template>
