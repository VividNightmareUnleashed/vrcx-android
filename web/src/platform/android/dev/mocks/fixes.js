// Preview-harness mocks for the player list after a late PC companion start and the Previous Instances chart.
// Dev only (see ../README.md).
//
// Query parameters:
//   gamestart=<s>  VRChat is reported running only <s> seconds after the page loaded, as when the PC companion
//                  connects after start-up (AppApiElectron.IsGameRunning answers false until then). The player list is
//                  then rebuilt when the start arrives. Use more than 60 s to see it without the views-a mock's own
//                  reload, which gives up after a minute.
//
// Always on: Previous Instances rows for the current instance (the info dialog's table and chart), with long display
// names. Open it with $pinia.instance.showPreviousInstancesInfoDialog(PREVIOUS_INSTANCE_LOCATION).

const MINUTE = 60 * 1000;
const NOW = Date.now();

const params = new URLSearchParams(globalThis.location?.search ?? '');
const gameStartDelayMs = Number(params.get('gamestart') ?? 0) * 1000;

/** The fixture user's current instance (fixtures.json). */
export const PREVIOUS_INSTANCE_LOCATION = 'wrld_00000000-0000-4000-8000-00000000a001:12345~region(eu)';

/**
 * @param {string} method AppApiElectron method name
 * @param {number} elapsedMs Time since the page loaded
 * @param {number} delayMs The `gamestart` delay
 * @returns {false | undefined} False while the game start is still to come, otherwise undefined (fall through)
 */
export function lateGameStart(method, elapsedMs, delayMs) {
    if (!(delayMs > 0) || elapsedMs >= delayMs) {
        return undefined;
    }
    return method === 'IsGameRunning' || method === 'IsSteamVRRunning' ? false : undefined;
}

/**
 * @param {string} method
 * @returns {unknown}
 */
export function appApi(method) {
    return lateGameStart(method, Date.now() - NOW, gameStartDelayMs);
}

// Stays in the instance: [display name, user id suffix, joined minutes ago, left minutes ago (null: still there)].
const VISITS = [
    ['Preview User', '000000000001', 150, null],
    ['Aurora', '000000000101', 140, 20],
    ['Aurora', '000000000101', 12, null],
    ['Ember_the_Lantern_Keeper', '000000000106', 135, 60],
    ['Kestrel', '000000000111', 120, null],
    ['CobaltBlueberryPancakes', '000000000103', 110, 70],
    ['Harbor Night Watch Crew', '000000000201', 95, 40],
    ['Mistral', '000000000202', 80, 30],
    ['SleepyOtterInAHammock', '000000000203', 60, 5],
    ['Juniper', '000000000204', 45, null],
    ['Quartz', '000000000205', 30, 10],
    ['VeryLongDisplayNameForTesting', '000000000206', 25, null]
];

function iso(minutesAgo) {
    return new Date(NOW - minutesAgo * MINUTE).toISOString();
}

/**
 * Gamelog_join_leave rows of the current instance, oldest first.
 *
 * @returns {{ created_at: string; display_name: string; user_id: string; time: number; type: string }[]}
 */
export function previousInstanceRows() {
    const rows = [];
    for (const [name, suffix, joined, left] of VISITS) {
        const userId = `usr_00000000-0000-4000-8000-${suffix}`;
        rows.push({ created_at: iso(joined), display_name: name, user_id: userId, time: 0, type: 'OnPlayerJoined' });
        // A player still in the instance gets the leave row VRCX writes when the location changes; the chart needs it.
        const leftAgo = left ?? 0;
        rows.push({
            created_at: iso(leftAgo),
            display_name: name,
            user_id: userId,
            time: (joined - leftAgo) * MINUTE,
            type: 'OnPlayerLeft'
        });
    }
    return rows.sort((a, b) => a.created_at.localeCompare(b.created_at));
}

/**
 * @param {string} sql
 * @param {Map<string, unknown>} args
 * @returns {unknown[][] | undefined}
 */
export function sqlite(sql, args) {
    const text = String(sql).replace(/\s+/g, ' ').trim();
    if (
        !text.startsWith('SELECT created_at, display_name, user_id, time') ||
        !text.includes('FROM gamelog_join_leave')
    ) {
        return undefined;
    }
    if (!/WHERE location = @location/.test(text) || args?.get?.('@location') !== PREVIOUS_INSTANCE_LOCATION) {
        return undefined;
    }
    const rows = previousInstanceRows();
    if (text.includes("type = 'OnPlayerLeft'")) {
        // PreviousInstancesInfoChart (database.getPlayerDetailFromInstance).
        return rows
            .filter((row) => row.type === 'OnPlayerLeft')
            .map((row) => [row.created_at, row.display_name, row.user_id, row.time]);
    }
    if (text.startsWith('SELECT created_at, display_name, user_id, time, type FROM')) {
        // PreviousInstancesInfoDialog's table (database.getPlayersFromInstance).
        return rows.map((row) => [row.created_at, row.display_name, row.user_id, row.time, row.type]);
    }
    return undefined;
}
