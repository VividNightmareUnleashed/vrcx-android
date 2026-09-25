import { beforeEach, describe, expect, test, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
    isCompact: { value: false },
    shiftHeld: { value: false, __v_isRef: true }
}));

vi.mock('pinia', async (importOriginal) => ({ ...(await importOriginal()), storeToRefs: (store) => store }));
vi.mock('../../../plugins', () => ({ i18n: { global: { t: (key) => key } } }));
vi.mock('../../../stores', () => ({
    useInstanceStore: () => ({ showPreviousInstancesInfoDialog: vi.fn() }),
    useUiStore: () => ({ shiftHeld: mocks.shiftHeld })
}));
vi.mock('../../../composables/useCompactLayout', () => ({
    useCompactLayout: () => ({ isCompact: mocks.isCompact })
}));
vi.mock('../../../coordinators/userCoordinator', () => ({ lookupUser: vi.fn() }));
vi.mock('../../../coordinators/worldCoordinator', () => ({ showWorldDialog: vi.fn() }));
vi.mock('../../../shared/utils', () => ({
    copyToClipboard: vi.fn(),
    formatDateFilter: (value, format) => `${format}:${value}`,
    openExternalLink: vi.fn()
}));
vi.mock('../../../components/Location.vue', () => ({ default: 'Location' }));
vi.mock('../components/GameLogRowMenu.vue', () => ({ default: 'GameLogRowMenu' }));
vi.mock('../../../components/ui/badge', () => ({ Badge: 'Badge' }));
vi.mock('../../../components/ui/button', () => ({ Button: 'Button' }));
vi.mock('../../../components/ui/context-menu', () => ({
    ContextMenu: 'ContextMenu',
    ContextMenuContent: 'ContextMenuContent',
    ContextMenuItem: 'ContextMenuItem',
    ContextMenuSeparator: 'ContextMenuSeparator',
    ContextMenuTrigger: 'ContextMenuTrigger'
}));
vi.mock('../../../components/ui/tooltip', () => ({ TooltipWrapper: 'TooltipWrapper' }));

import { createColumns } from '../columns.jsx';

function flatten(node, out = []) {
    if (!node || typeof node !== 'object') return out;
    if (Array.isArray(node)) {
        node.forEach((child) => flatten(child, out));
        return out;
    }
    out.push(node);
    const children = node.children;
    if (Array.isArray(children)) {
        flatten(children, out);
    } else if (children && typeof children === 'object') {
        for (const slot of Object.values(children)) {
            if (typeof slot === 'function') flatten(slot(), out);
        }
    }
    return out;
}

function cellOf(columns, id, original) {
    const column = columns.find((col) => (col.id ?? col.accessorKey) === id);
    return column.cell({ row: { original, getValue: (key) => original[key] } });
}

describe('game log columns in the phone layout', () => {
    let columns;

    beforeEach(() => {
        mocks.isCompact.value = false;
        columns = createColumns({ getCreatedAt: (row) => row.created_at, onDelete: vi.fn(), onDeletePrompt: vi.fn() });
    });

    test('every column places itself in the card', () => {
        const slots = Object.fromEntries(columns.map((col) => [col.id ?? col.accessorKey, col.meta?.mobile?.slot]));
        expect(slots).toEqual({
            spacer: 'hidden',
            created_at: 'trailing',
            type: 'badge',
            displayName: 'title',
            detail: 'body',
            action: 'actions'
        });
    });

    test('the row menu only appears in the phone layout', () => {
        const event = { type: 'Event', data: 'Udon: door opened' };
        const desktop = flatten(cellOf(columns, 'action', event));
        expect(desktop.some((node) => node.type === 'GameLogRowMenu')).toBe(false);

        mocks.isCompact.value = true;
        const phone = flatten(cellOf(columns, 'action', event));
        expect(phone.some((node) => node.type === 'GameLogRowMenu')).toBe(true);
    });

    test('joins and leaves keep an empty action cell on phones', () => {
        mocks.isCompact.value = true;
        expect(cellOf(columns, 'action', { type: 'OnPlayerJoined', displayName: 'Aurora' })).toBeNull();
    });

    test('long log text wraps on phones instead of hiding behind a tooltip', () => {
        const event = { type: 'External', message: 'A long external message' };
        const desktopSpan = flatten(cellOf(columns, 'detail', event)).find((node) => node.type === 'span');
        expect(String(desktopSpan.props.class)).toContain('truncate');

        mocks.isCompact.value = true;
        const phoneSpan = flatten(cellOf(columns, 'detail', event)).find((node) => node.type === 'span');
        expect(String(phoneSpan.props.class)).toContain('whitespace-normal');
        expect(String(phoneSpan.props.class)).not.toContain('truncate');
    });
});
