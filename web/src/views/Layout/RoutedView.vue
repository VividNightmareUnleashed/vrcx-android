<template>
    <RouterView v-slot="{ Component }">
        <KeepAlive :exclude="KEEP_ALIVE_EXCLUDE" :max="max">
            <component :is="Component" />
        </KeepAlive>
    </RouterView>
</template>

<script setup>
    // The routed page and its KeepAlive contract, shared by the desktop and the phone frame of MainLayout.
    import { KEEP_ALIVE_EXCLUDE } from './keepAlive';

    defineProps({
        // undefined = unlimited (upstream behaviour)
        max: {
            type: Number,
            default: undefined
        }
    });
</script>
