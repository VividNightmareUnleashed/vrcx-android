<template>
    <ContextMenu>
        <ContextMenuTrigger as-child>
            <slot />
        </ContextMenuTrigger>
        <ContextMenuContent>
            <UserActionMenuItems variant="context" :user-id="userId" :state="state" :location="location" />
            <slot name="append" />
        </ContextMenuContent>
    </ContextMenu>
</template>

<script setup>
    // The items live in UserActionMenuItems.vue, shared with the touch kebab (UserActionMenuButton.vue).
    import { ContextMenu, ContextMenuContent, ContextMenuTrigger } from './ui/context-menu';

    import UserActionMenuItems from './UserActionMenuItems.vue';

    defineProps({
        userId: {
            type: String,
            required: true
        },
        state: {
            type: String,
            default: ''
        },
        location: {
            type: String,
            default: ''
        }
    });
</script>
