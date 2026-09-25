// Every table whose rows have Shift-modified actions (instant delete / hide) shows the touch "quick actions" toggle
// (docs/DESIGN.md §3.3): its DataTableLayout opts in with the `quick-actions` prop. Without it the phone has no way
// to reach the Shift behaviour.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const SRC = resolve(import.meta.dirname, '../../../..');

/** View (the DataTableLayout host) → the column definitions that read ui.shiftHeld. */
const SHIFT_TABLES = {
    'views/FriendLog/FriendLog.vue': 'views/FriendLog/columns.jsx',
    'views/GameLog/GameLog.vue': 'views/GameLog/columns.jsx',
    'views/Moderation/Moderation.vue': 'views/Moderation/columns.jsx',
    'views/Notifications/Notification.vue': 'views/Notifications/columns.jsx',
    'components/dialogs/PreviousInstancesDialog/PreviousInstancesListDialog.vue':
        'components/dialogs/PreviousInstancesDialog/previousInstancesColumns.jsx'
};

function read(file) {
    return readFileSync(resolve(SRC, file), 'utf8');
}

describe('quick actions toggle wiring', () => {
    for (const [view, columns] of Object.entries(SHIFT_TABLES)) {
        it(`${view} opts in`, () => {
            // Still a Shift table: otherwise the toggle would be noise and this entry should go.
            expect(read(columns)).toMatch(/shiftHeld/);

            const tags = read(view).match(/<DataTableLayout\b[^>]*>/g) ?? [];
            expect(tags.length).toBeGreaterThan(0);
            for (const tag of tags) {
                expect(tag).toMatch(/\squick-actions(\s|>|\/)/);
            }
        });
    }
});
