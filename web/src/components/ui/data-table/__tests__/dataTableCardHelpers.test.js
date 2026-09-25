import { describe, expect, it } from 'vitest';

import {
    MOBILE_SLOTS,
    getMobileHint,
    getSortableColumns,
    groupCellsBySlot,
    hasMobileHint,
    isInteractiveTarget
} from '../dataTableHelpers';

const col = (id, meta = {}, extra = {}) => ({ id, columnDef: { meta }, ...extra });
const cell = (column) => ({ id: `r_${column.id}`, column });

describe('card hints', () => {
    it('normalises string and object hints', () => {
        expect(getMobileHint(col('a', { mobile: 'title' }))).toEqual({
            slot: 'title',
            label: false,
            order: 0,
            class: ''
        });
        expect(getMobileHint(col('b', { mobile: { slot: 'footer', label: true, order: 2, class: 'x' } }))).toEqual({
            slot: 'footer',
            label: true,
            order: 2,
            class: 'x'
        });
    });

    it('sends unhinted columns to the footer (labelled when they have a label) and hides spacers', () => {
        expect(getMobileHint(col('note', { label: 'Note' }))).toMatchObject({ slot: 'footer', label: true });
        expect(getMobileHint(col('raw'))).toMatchObject({ slot: 'footer', label: false });
        expect(getMobileHint(col('__spacer', { mobile: 'title' })).slot).toBe('hidden');
    });

    it('falls back to the footer for unknown slots', () => {
        expect(getMobileHint(col('a', { mobile: 'sidebar' })).slot).toBe('footer');
        expect(getMobileHint(col('a', { mobile: { slot: 'nope' } })).slot).toBe('footer');
    });

    it('detects tables that opt into card mode', () => {
        expect(hasMobileHint(col('a', { mobile: 'title' }))).toBe(true);
        expect(hasMobileHint(col('a', { label: 'A' }))).toBe(false);
    });

    it('groups cells per slot, keeping column order unless `order` says otherwise', () => {
        const groups = groupCellsBySlot([
            cell(col('a', { mobile: { slot: 'footer', order: 2 } })),
            cell(col('b', { mobile: 'title' })),
            cell(col('c', { mobile: { slot: 'footer', order: 1 } })),
            cell(col('d', { mobile: 'footer' }))
        ]);
        expect(Object.keys(groups)).toEqual([...MOBILE_SLOTS]);
        expect(groups.title.map((entry) => entry.cell.column.id)).toEqual(['b']);
        expect(groups.footer.map((entry) => entry.cell.column.id)).toEqual(['d', 'c', 'a']);
        expect(groupCellsBySlot(null).title).toEqual([]);
    });
});

describe('View options helpers', () => {
    it('offers labelled, sortable columns only', () => {
        const cols = [
            col('date', { label: 'Date' }, { getCanSort: () => true }),
            col('icon', {}, { getCanSort: () => true }),
            col('detail', { label: 'Detail' }, { getCanSort: () => false }),
            col('__spacer', { label: 'x' }, { getCanSort: () => true })
        ];
        expect(getSortableColumns(cols).map((c) => c.id)).toEqual(['date']);
        expect(getSortableColumns(undefined)).toEqual([]);
    });
});

describe('isInteractiveTarget', () => {
    it('recognises buttons, links and clickable names inside a card', () => {
        const row = document.createElement('div');
        row.innerHTML =
            '<span class="plain">text</span><span class="cursor-pointer name"><b>Bob</b></span><button><i></i></button>';
        expect(isInteractiveTarget(row.querySelector('.plain'), row)).toBe(false);
        expect(isInteractiveTarget(row.querySelector('.name b'), row)).toBe(true);
        expect(isInteractiveTarget(row.querySelector('button i'), row)).toBe(true);
        expect(isInteractiveTarget(null, row)).toBe(false);
    });
});
