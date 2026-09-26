<template>
    <div
        class="flex items-center justify-between gap-6 min-h-8 [.is-android_&]:flex-wrap [.is-android_&]:gap-y-1.5 compact:gap-x-4">
        <!-- Android (phones and the narrow tablet page card): the control drops under the label when both do not fit
             on one line (docs/DESIGN.md §3.4). -->
        <div class="flex flex-col gap-0.5 min-w-0 flex-1 [.is-android_&]:basis-40">
            <span class="text-sm leading-snug text-foreground">{{ label }}</span>
            <span v-if="description" class="text-xs leading-tight text-muted-foreground">{{ description }}</span>
        </div>
        <div
            class="flex items-center gap-2 shrink-0 [.is-android_&]:min-w-0 [.is-android_&]:max-w-full [.is-android_&]:flex-wrap [.is-android_&>*]:max-w-full">
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
