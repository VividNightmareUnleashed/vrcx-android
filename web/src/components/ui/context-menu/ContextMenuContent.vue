<script setup>
    import { shallowRef, watch } from 'vue';
    import { reactiveOmit } from '@vueuse/core';
    import { ContextMenuContent, ContextMenuPortal, injectContextMenuRootContext, useForwardPropsEmits } from 'reka-ui';
    import { cn } from '@/lib/utils';
    import { isAndroid } from '@/shared/utils/platform';

    import { getTouchMenuPositioning } from './touchPlacement';

    defineOptions({
        inheritAttrs: false
    });

    const props = defineProps({
        forceMount: { type: Boolean, required: false },
        loop: { type: Boolean, required: false },
        sideFlip: { type: Boolean, required: false },
        alignOffset: { type: Number, required: false },
        alignFlip: { type: Boolean, required: false },
        avoidCollisions: { type: Boolean, required: false },
        collisionBoundary: { type: null, required: false },
        collisionPadding: { type: [Number, Object], required: false },
        hideShiftedArrow: { type: Boolean, required: false },
        sticky: { type: String, required: false },
        hideWhenDetached: { type: Boolean, required: false },
        positionStrategy: { type: String, required: false },
        disableUpdateOnLayoutShift: { type: Boolean, required: false },
        prioritizePosition: { type: Boolean, required: false },
        reference: { type: null, required: false },
        asChild: { type: Boolean, required: false },
        as: { type: null, required: false },
        class: { type: null, required: false }
    });
    const emits = defineEmits([
        'escapeKeyDown',
        'pointerDownOutside',
        'focusOutside',
        'interactOutside',
        'closeAutoFocus'
    ]);

    const delegatedProps = reactiveOmit(props, 'class');

    const forwarded = useForwardPropsEmits(delegatedProps, emits);

    // Android: menus opened by a long-press stay whole and on screen near the edges (touchPlacement.js). The insets
    // are read again each time the menu opens (rotation, keyboard). Props a caller passes win; desktop builds keep
    // reka's defaults.
    const touchPositioning = shallowRef(isAndroid ? getTouchMenuPositioning() : null);
    if (isAndroid) {
        const rootContext = injectContextMenuRootContext(null);
        if (rootContext) {
            watch(rootContext.open, (open) => {
                if (open) {
                    touchPositioning.value = getTouchMenuPositioning();
                }
            });
        }
    }
</script>

<template>
    <ContextMenuPortal>
        <ContextMenuContent
            data-slot="context-menu-content"
            v-bind="{ ...$attrs, ...touchPositioning, ...forwarded }"
            :class="
                cn(
                    'bg-popover text-popover-foreground data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95 data-[side=bottom]:slide-in-from-top-2 data-[side=left]:slide-in-from-right-2 data-[side=right]:slide-in-from-left-2 data-[side=top]:slide-in-from-bottom-2 z-50 max-h-(--reka-context-menu-content-available-height) min-w-[8rem] overflow-x-hidden overflow-y-auto rounded-md border p-1 shadow-md',
                    props.class
                )
            ">
            <slot />
        </ContextMenuContent>
    </ContextMenuPortal>
</template>
