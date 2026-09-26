<template>
    <DropdownMenu>
        <DropdownMenuTrigger as-child>
            <Button
                variant="ghost"
                size="icon-sm"
                data-slot="user-action-menu-button"
                :class="
                    cn(
                        'relative shrink-0 rounded-full text-muted-foreground pointer-coarse:after:absolute pointer-coarse:after:-inset-1',
                        props.class
                    )
                "
                :aria-label="t('android.entity_dialogs.more_actions')"
                @click.stop
                @pointerdown.stop
                @contextmenu.stop>
                <EllipsisVertical />
            </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent
            :align="align"
            class="pointer-coarse:min-w-52 pointer-coarse:[&_[data-slot=dropdown-menu-item]]:py-2.5">
            <UserActionMenuItems variant="dropdown" :user-id="userId" :state="state" :location="location" />
            <slot name="append" />
        </DropdownMenuContent>
    </DropdownMenu>
</template>

<script setup>
    // Touch kebab for user rows: the same quick actions as the long-press menu (UserContextMenu), for rows where the
    // menu holds primary actions (docs/DESIGN.md §3.3). Clicks do not reach the row, so tapping the kebab never opens
    // the user dialog underneath.
    import { EllipsisVertical } from 'lucide-vue-next';
    import { useI18n } from 'vue-i18n';

    import { Button } from './ui/button';
    import { DropdownMenu, DropdownMenuContent, DropdownMenuTrigger } from './ui/dropdown-menu';
    import { cn } from '@/lib/utils';

    import UserActionMenuItems from './UserActionMenuItems.vue';

    const props = defineProps({
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
        },
        align: {
            type: String,
            default: 'end'
        },
        class: { type: null, required: false }
    });

    const { t } = useI18n();
</script>
