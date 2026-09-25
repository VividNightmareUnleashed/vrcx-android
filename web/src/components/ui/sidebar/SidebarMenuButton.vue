<script setup>
    import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip';
    import { computed } from 'vue';
    import { isAndroid } from '@/shared/utils/platform';
    import { reactiveOmit } from '@vueuse/core';

    import { useSidebar } from './utils';

    import SidebarMenuButtonChild from './SidebarMenuButtonChild.vue';

    defineOptions({
        inheritAttrs: false
    });

    const props = defineProps({
        as: {
            type: [String, Object, Function],
            default: 'button'
        },
        asChild: {
            type: Boolean,
            default: false
        },
        variant: {
            type: String,
            default: 'default'
        },
        size: {
            type: String,
            default: 'default'
        },
        isActive: {
            type: Boolean,
            default: false
        },
        tooltip: {
            type: [String, Object, Function],
            default: undefined
        },
        class: {
            type: [String, Array, Object],
            default: undefined
        }
    });

    const { isMobile, state } = useSidebar();

    const delegatedProps = reactiveOmit(props, 'tooltip');

    // Android: a tooltip that could never show (expanded nav, phone sheet) is not rendered at all. Upstream keeps it
    // mounted with `hidden`, which still opens an invisible dismissable layer when focus returns to the button after a
    // menu closes, and that layer would swallow the next Android back press.
    const hasTooltip = computed(
        () => Boolean(props.tooltip) && (!isAndroid || (state.value === 'collapsed' && !isMobile.value))
    );
</script>

<template>
    <SidebarMenuButtonChild v-if="!hasTooltip" v-bind="{ ...delegatedProps, ...$attrs }">
        <slot />
    </SidebarMenuButtonChild>

    <Tooltip v-else ignore-non-keyboard-focus>
        <TooltipTrigger as-child>
            <SidebarMenuButtonChild v-bind="{ ...delegatedProps, ...$attrs }">
                <slot />
            </SidebarMenuButtonChild>
        </TooltipTrigger>
        <TooltipContent side="right" align="center" :hidden="state !== 'collapsed' || isMobile">
            <template v-if="typeof tooltip === 'string'">
                {{ tooltip }}
            </template>
            <component :is="tooltip" v-else />
        </TooltipContent>
    </Tooltip>
</template>
