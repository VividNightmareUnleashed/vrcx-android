<template>
    <div class="flex items-center justify-between gap-6 min-h-8 compact:flex-wrap compact:gap-x-4 compact:gap-y-1.5">
        <!-- Phones: the control drops under the label when both do not fit on one line (docs/DESIGN.md §3.4). -->
        <div class="flex flex-col gap-0.5 min-w-0 flex-1 compact:basis-40">
            <span class="text-sm leading-snug text-foreground">{{ label }}</span>
            <span v-if="description" class="text-xs leading-tight text-muted-foreground">{{ description }}</span>
        </div>
        <div
            class="flex items-center gap-2 shrink-0 compact:min-w-0 compact:max-w-full compact:flex-wrap compact:[&>*]:max-w-full">
            <slot />
        </div>
    </div>
</template>

<script setup>
    defineProps({
        label: { type: String, required: true },
        description: { type: String, default: '' }
    });
</script>
