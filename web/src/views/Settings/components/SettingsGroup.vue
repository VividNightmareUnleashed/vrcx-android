<template>
    <div class="flex flex-col gap-3.5">
        <div v-if="title || $slots.description" class="flex flex-col gap-1.5 pl-0.5">
            <h3 v-if="title" class="text-base font-semibold text-foreground m-0">{{ title }}</h3>
            <div v-if="$slots.description" class="text-sm text-muted-foreground">
                <slot name="description" />
            </div>
        </div>
        <Card class="p-0">
            <!-- Phones: narrower padding, and wide toggle groups scroll sideways instead of spilling out. -->
            <CardContent
                class="flex flex-col gap-1 py-4.5 px-5.5 compact:gap-3 compact:px-4 compact:[&_[data-slot=toggle-group]]:max-w-full compact:[&_[data-slot=toggle-group]]:overflow-x-auto compact:[&_[data-slot=toggle-group]]:[scrollbar-width:none]">
                <slot />
            </CardContent>
        </Card>
    </div>
</template>

<script setup>
    import { Card, CardContent } from '@/components/ui/card';

    defineProps({
        title: { type: String, default: '' }
    });
</script>
