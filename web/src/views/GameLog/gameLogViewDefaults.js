// Android default for the game log view mode: the sessions timeline reads naturally
// on a phone, so it is the default there until the user picks a mode. PC keeps upstream's `table` default, and a saved
// choice always wins.
import configRepository from '../../services/config';

export const GAME_LOG_VIEW_MODE_KEY = 'VRCX_gameLogViewMode';

let applied = null;

/**
 * Switches the game log to the sessions view when no view mode was ever saved. Runs once per app session.
 *
 * @param {{ sessionsViewMode: string; setSessionsViewMode: (mode: string) => Promise<void> }} gameLogStore
 * @param {{ getString: Function; setString: Function }} [repository]
 * @returns {Promise<boolean>} True when the default was applied
 */
export function applyAndroidGameLogViewDefault(gameLogStore, repository = configRepository) {
    if (!applied) {
        applied = (async () => {
            const saved = await repository.getString(GAME_LOG_VIEW_MODE_KEY, null);
            if (saved === 'sessions' || saved === 'table') {
                return false;
            }
            // Persist first: the store reads the key once at start-up and falls back to `table` when it is unset.
            await repository.setString(GAME_LOG_VIEW_MODE_KEY, 'sessions');
            if (gameLogStore.sessionsViewMode !== 'sessions') {
                await gameLogStore.setSessionsViewMode('sessions');
            }
            return true;
        })().catch((error) => {
            console.error(error);
            applied = null;
            return false;
        });
    }
    return applied;
}

/** Test helper. */
export function resetGameLogViewDefaultForTests() {
    applied = null;
}
