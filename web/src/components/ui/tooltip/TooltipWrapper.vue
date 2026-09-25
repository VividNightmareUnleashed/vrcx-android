<script setup>
    import { computed, mergeProps, useAttrs, useSlots } from 'vue';

    import { isAndroid } from '@/shared/utils/platform';

    import { useTouchTooltip } from './useTouchTooltip';

    import Tooltip from './Tooltip.vue';
    import TooltipContent from './TooltipContent.vue';
    import TooltipTrigger from './TooltipTrigger.vue';

    defineOptions({
        inheritAttrs: false
    });

    const props = defineProps({
        content: { type: [String, Number], required: false },
        side: { type: null, required: false },
        align: { type: null, required: false },
        sideOffset: { type: Number, required: false },
        delayDuration: {
            type: Number,
            required: false,
            default() {
                100;
            }
        },
        skipDelayDuration: {
            type: Number,
            required: false,
            default() {
                100;
            }
        },
        disableHoverableContent: { type: Boolean, required: false },
        ignoreNonKeyboardFocus: { type: Boolean, required: false, default: true },
        disabled: { type: Boolean, required: false },
        triggerAsChild: { type: Boolean, required: false, default: true },
        contentClass: { type: null, required: false },
        // Touch: open on tap instead of long-press (information-only triggers are detected automatically).
        tapToOpen: { type: Boolean, required: false, default: false }
    });

    const attrs = useAttrs();
    const slots = useSlots();
    const hasContent = computed(() => Boolean(slots.content) || props.content !== undefined);

    // Android: reka ignores touch, so the wrapper drives `open` (long-press, or tap on info-only triggers).
    // Desktop keeps reka's own uncontrolled behaviour.
    const touch = isAndroid
        ? useTouchTooltip({
              isEnabled: () => !props.disabled && hasContent.value,
              tapToOpen: () => props.tapToOpen
          })
        : null;

    const rootBindings = computed(() => (touch ? { open: touch.open.value, 'onUpdate:open': touch.setOpen } : {}));
    // A function, not a computed: `attrs` is not reactive, so it must be read on every render.
    const getTriggerBindings = () => (touch ? mergeProps(attrs, touch.triggerListeners) : attrs);
</script>

<template>
    <Tooltip
        :delay-duration="delayDuration"
        :disable-hoverable-content="disableHoverableContent"
        :ignore-non-keyboard-focus="ignoreNonKeyboardFocus"
        :disabled="disabled"
        v-bind="rootBindings">
        <TooltipTrigger :as-child="triggerAsChild" v-bind="getTriggerBindings()">
            <slot />
        </TooltipTrigger>
        <TooltipContent
            v-if="hasContent"
            :side="side"
            :align="align"
            :side-offset="sideOffset"
            :class="contentClass"
            class="max-w-screen">
            <slot name="content">
                <span v-if="content !== undefined" class="whitespace-pre-wrap">{{ content }}</span>
            </slot>
        </TooltipContent>
    </Tooltip>
</template>
