import CountdownTimer from '@/components/CountdownTimer.vue';
import { Button } from '@/components/ui/button';
import { i18n } from '@/plugins';
import { SquarePen } from 'lucide-vue-next';

const { t } = i18n.global;

// meta.mobile: on phones the table is a card list (docs/DESIGN.md §3.1): the slot number, the cooldown and the edit
// button on the first line, the message below it. Desktop keeps the table.
export const createColumns = ({ onEdit }) => [
    {
        accessorKey: 'slot',
        header: () => t('table.profile.invite_messages.slot'),
        size: 70,
        meta: {
            mobile: { slot: 'titleSuffix' }
        },
        cell: ({ row }) => (
            <span class="compact:inline-flex compact:size-6 compact:items-center compact:justify-center compact:rounded-full compact:bg-muted compact:text-xs compact:font-medium compact:tabular-nums">
                {row.original?.slot}
            </span>
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
            mobile: { slot: 'trailing' }
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
            mobile: { slot: 'trailing' }
        },
        cell: ({ row }) => (
            <Button
                size="icon-sm"
                class="w-6 h-6 compact:size-8"
                variant="ghost"
                aria-label={t('table.profile.invite_messages.action')}
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
