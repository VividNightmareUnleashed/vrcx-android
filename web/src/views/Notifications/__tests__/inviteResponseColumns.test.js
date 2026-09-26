import { describe, expect, test, vi } from 'vitest';

vi.mock('@/plugins', () => ({ i18n: { global: { t: (key) => key } } }));
vi.mock('@/components/CountdownTimer.vue', () => ({ default: 'CountdownTimer' }));
vi.mock('@/components/ui/button', () => ({ Button: 'Button' }));

import { createColumns as createRequestResponseColumns } from '../dialogs/sendInviteRequestResponseColumns.jsx';
import { createColumns as createResponseColumns } from '../dialogs/sendInviteResponseColumns.jsx';
import {
    getMobileHint,
    getSortableColumns,
    getToggleableColumns,
    isReorderable
} from '../../../components/ui/data-table/dataTableHelpers';

/** Minimal TanStack column instances for the table helpers. */
const toColumns = (defs) =>
    defs.map((columnDef) => ({
        id: columnDef.id ?? columnDef.accessorKey,
        columnDef,
        getCanSort: () => columnDef.enableSorting !== false
    }));

describe.each([
    ['decline with message (invites)', createResponseColumns],
    ['invite request answers', createRequestResponseColumns]
])('invite response message table: %s', (_name, createColumns) => {
    const columns = toColumns(createColumns({ onEdit: vi.fn() }));

    test('PC: the cool-down label added for the phone card adds no column toggle, reorder or sort entry', () => {
        // Upstream has no labelled column here, so its header menu offers nothing; the phone label must not change that.
        expect(getToggleableColumns(columns)).toEqual([]);
        for (const column of columns) {
            expect(isReorderable({ column })).toBe(false);
        }
        const coolDown = columns.find((column) => column.id === 'updatedAt');
        expect(coolDown.columnDef.meta.label()).toBe('table.profile.invite_messages.cool_down');
        expect(coolDown.columnDef.meta.disableVisibilityToggle).toBe(true);
        expect(coolDown.columnDef.meta.disableReorder).toBe(true);
        // Only the phone View options sheet lists sortable labelled columns; the cool-down one may appear there.
        expect(getSortableColumns(columns).map((column) => column.id)).toEqual(['updatedAt']);
    });

    test('phone card: slot number, message, labelled cool-down footer, edit button', () => {
        expect(Object.fromEntries(columns.map((column) => [column.id, getMobileHint(column).slot]))).toEqual({
            slot: 'leading',
            message: 'body',
            updatedAt: 'footer',
            action: 'actions'
        });
        expect(getMobileHint(columns.find((column) => column.id === 'updatedAt')).label).toBe(true);
    });
});
