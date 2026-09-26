import { describe, expect, test, vi } from 'vitest';

vi.mock('pinia', async (importOriginal) => ({ ...(await importOriginal()), storeToRefs: (store) => store }));
vi.mock('../../../plugins', () => ({ i18n: { global: { t: (key) => key, te: () => false } } }));
vi.mock('../../../stores', () => ({
    useUiStore: () => ({ shiftHeld: { value: false } }),
    useUserStore: () => ({ currentUser: { value: { id: 'usr_me' } } })
}));
vi.mock('../../../coordinators/userCoordinator', () => ({ showUserDialog: vi.fn() }));
vi.mock('../../../shared/utils', () => ({ formatDateFilter: (value) => String(value) }));
vi.mock('../../../components/ui/badge', () => ({ Badge: 'Badge' }));
vi.mock('../../../components/ui/button', () => ({ Button: 'Button' }));
vi.mock('../../../components/ui/tooltip', () => ({
    Tooltip: 'Tooltip',
    TooltipContent: 'TooltipContent',
    TooltipTrigger: 'TooltipTrigger'
}));

import { createColumns as createFriendLogColumns } from '../../FriendLog/columns.jsx';
import { createColumns as createModerationColumns } from '../columns.jsx';

const slotsOf = (columns) =>
    Object.fromEntries(columns.map((col) => [col.id ?? col.accessorKey, col.meta?.mobile?.slot]));

describe('phone card hints (docs/DESIGN.md §3.1)', () => {
    test('moderation cards: type heading, target line, labelled source, delete action', () => {
        const columns = createModerationColumns({ onDelete: vi.fn(), onDeletePrompt: vi.fn() });
        expect(slotsOf(columns)).toEqual({
            spacer: 'hidden',
            created: 'trailing',
            type: 'title',
            sourceDisplayName: 'footer',
            targetDisplayName: 'body',
            action: 'actions',
            trailing: 'hidden'
        });
        const source = columns.find((col) => col.accessorKey === 'sourceDisplayName');
        expect(source.meta.mobile.label).toBe(true);
    });

    test('friend log cards: type heading, names line, delete action', () => {
        const columns = createFriendLogColumns({ onDelete: vi.fn(), onDeletePrompt: vi.fn() });
        expect(slotsOf(columns)).toEqual({
            spacer: 'hidden',
            created_at: 'trailing',
            type: 'title',
            displayName: 'body',
            action: 'actions',
            trailing: 'hidden'
        });
    });

    test('hints do not make PC columns reorderable or toggleable', () => {
        for (const columns of [
            createModerationColumns({ onDelete: vi.fn(), onDeletePrompt: vi.fn() }),
            createFriendLogColumns({ onDelete: vi.fn(), onDeletePrompt: vi.fn() })
        ]) {
            for (const id of ['spacer', 'trailing']) {
                const column = columns.find((col) => col.id === id);
                expect(column.meta.label).toBeUndefined();
            }
        }
    });
});
