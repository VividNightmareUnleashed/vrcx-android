// Android startup hooks, called from app.js before mount when isAndroid (docs/ARCHITECTURE.md §4).
// The platform layer and the phone shell each have their own init module.
import { initAndroidPlatform } from './platformInit.js';
import { initAndroidShell } from './shellInit.js';

/**
 * @param {import('vue').App} app
 */
export async function initAndroid(app) {
    await initAndroidPlatform(app);
    await initAndroidShell(app);
}
