import CountdownTimer from '@/components/CountdownTimer.vue';
import { Button } from '@/components/ui/button';
import { i18n } from '@/plugins';
import { SquarePen } from 'lucide-vue-next';

import { useCompactLayout } from '@/composables/useCompactLayout';

const { t } = i18n.global;

export const createColumns = ({ onEdit }) => [
    {
        accessorKey: 'slot',
        header: () => t('table.profile.invite_messages.slot'),
        size: 70,
        // Phone cards (docs/DESIGN.md §3.1): slot number, message, cool-down footer, edit button.
        meta: { mobile: { slot: 'leading' } },
        // The card's leading slot takes no classes, so the phone number styles itself; PC keeps TanStack's default
        // cell.
        cell: (props) =>
            useCompactLayout().isCompact.value ? (
                <span class="inline-block min-w-4 text-muted-foreground tabular-nums">{props.getValue()}</span>
            ) : (
                (props.renderValue()?.toString?.() ?? null)
            )
    },
    {
        accessorKey: 'message',
        header: () => t('table.profile.invite_messages.message'),
        meta: {
            stretch: true,
            mobile: { slot: 'body' }
        }
    },
    {
        accessorKey: 'updatedAt',
        header: () => t('table.profile.invite_messages.cool_down'),
        size: 110,
        meta: {
            tdClass: 'text-right',
            label: () => t('table.profile.invite_messages.cool_down'),
            // Label only for the phone card footer; PC keeps the column fixed and always visible.
            disableVisibilityToggle: true,
            disableReorder: true,
            mobile: { slot: 'footer', label: true }
        },
        cell: ({ row }) => <CountdownTimer datetime={row.original?.updatedAt} hours={1} />
    },
    {
        id: 'action',
        header: () => t('table.profile.invite_messages.action'),
        size: 70,
        enableSorting: false,
        meta: {
            tdClass: 'text-right',
            mobile: { slot: 'actions' }
        },
        cell: ({ row }) => (
            <Button
                size="icon-sm"
                variant="ghost"
                onClick={(e) => {
                    e.stopPropagation();
                    onEdit?.(row.original);
                }}
            >
                <SquarePen />
            </Button>
        )
    }
];
