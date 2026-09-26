// The VRChat photos folder on Android (docs/ARCHITECTURE.md §5.1, §9). The Screenshot Manager's Search and "Last
// screenshot" read the folder the user chose with AppApi.OpenVrcPhotosFolder, which opens the chosen folder, or the
// system folder picker while none is chosen (it then answers false: the choice arrives later).
import { toast } from 'vue-sonner';

import { getAndroidHost } from '../../shared/utils/platform.js';

/**
 * @param {typeof getAndroidHost} [getHost]
 * @returns {Promise<string>} Display name of the chosen folder, or '' when none is chosen (or the host is too old)
 */
export async function getPhotosFolder(getHost = getAndroidHost) {
    const host = getHost();
    if (!host) return '';
    try {
        const name = await host.GetPhotosFolder();
        return typeof name === 'string' ? name : '';
    } catch (error) {
        console.warn('GetPhotosFolder failed', error);
        return '';
    }
}

/**
 * Opens the photos folder, or lets the user choose it when none is chosen yet.
 *
 * @param {object} deps
 * @param {(key: string, params?: object) => string} deps.t
 * @param {{ OpenVrcPhotosFolder: () => Promise<boolean> }} [deps.appApi]
 * @param {typeof getAndroidHost} [deps.getHost]
 * @param {typeof toast} [deps.notify]
 * @returns {Promise<'opened' | 'choosing' | 'failed'>}
 */
export async function openPhotosFolder({
    t,
    appApi = /** @type {any} */ (globalThis).AppApi,
    getHost = getAndroidHost,
    notify = toast
}) {
    const chosen = await getPhotosFolder(getHost);
    let opened = false;
    try {
        opened = Boolean(await appApi.OpenVrcPhotosFolder());
    } catch (error) {
        console.error('OpenVrcPhotosFolder failed', error);
    }
    if (opened) {
        notify.success(t('message.file.folder_opened'));
        return 'opened';
    }
    if (!chosen) {
        notify(t('android.photos_folder.picker_opened'));
        return 'choosing';
    }
    notify.error(t('android.photos_folder.open_failed'));
    return 'failed';
}

/**
 * Tool action (shared/constants/tools.js, `type: 'android'`) of the Photos folder tool.
 *
 * @param {{ handler: string }} action
 * @param {{ t: (key: string, params?: object) => string }} deps
 */
export async function runAndroidToolAction(action, { t }) {
    if (action?.handler === 'photos-folder') {
        await openPhotosFolder({ t });
    }
}
