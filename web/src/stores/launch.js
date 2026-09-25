import { nextTick, ref, watch } from 'vue';
import { defineStore } from 'pinia';
import { toast } from 'vue-sonner';
import { useI18n } from 'vue-i18n';

import { instanceRequest } from '../api';
import { parseLocation } from '../shared/utils';
import { getAndroidHost, isAndroid, onAndroidEvent } from '../shared/utils/platform';
import { watchState } from '../services/watchState';

import configRepository from '../services/config';

/** Minimum time between two `CanLaunchVRChat` checks triggered by the app returning to the foreground. */
const CAN_LAUNCH_RECHECK_MS = 30_000;

/**
 * Arguments for `AppApi.StartGame`. On Android only the `vrchat://launch` URL is passed: PC launch options and
 * `--no-vr` mean nothing to VRChat on a phone.
 *
 * @param {string} launchUrl
 * @param {object} options
 * @param {string | null} [options.launchArguments]
 * @param {boolean} [options.desktopMode]
 * @param {boolean} [options.android]
 * @returns {string[]}
 */
export function buildLaunchArgs(launchUrl, { launchArguments = null, desktopMode = false, android = isAndroid } = {}) {
    const args = [launchUrl];
    if (android) {
        return args;
    }
    if (launchArguments) {
        args.push(launchArguments);
    }
    if (desktopMode) {
        args.push('--no-vr');
    }
    return args;
}

export const useLaunchStore = defineStore('Launch', () => {
    const isLaunchOptionsDialogVisible = ref(false);
    const isOpeningInstance = ref(false);
    const { t } = useI18n();
    const launchDialogData = ref({
        visible: false,
        loading: false,
        tag: '',
        shortName: ''
    });
    /**
     * Whether the Launch buttons are shown. Always true on desktop. On Android only when an installed app handles
     * `vrchat://launch` (AndroidHost.CanLaunchVRChat), re-checked when the app returns to the foreground.
     */
    const canLaunchGame = ref(!isAndroid);
    let lastCanLaunchCheck = 0;

    /**
     * @param {boolean} [force] Ignore the foreground re-check throttle
     * @returns {Promise<boolean>}
     */
    async function refreshCanLaunchGame(force = false) {
        if (!isAndroid) {
            return true;
        }
        const now = Date.now();
        if (!force && lastCanLaunchCheck && now - lastCanLaunchCheck < CAN_LAUNCH_RECHECK_MS) {
            return canLaunchGame.value;
        }
        lastCanLaunchCheck = now;
        const host = getAndroidHost();
        if (!host) {
            canLaunchGame.value = false;
            return false;
        }
        try {
            canLaunchGame.value = (await host.CanLaunchVRChat()) === true;
        } catch (e) {
            console.error('CanLaunchVRChat failed', e);
            canLaunchGame.value = false;
        }
        return canLaunchGame.value;
    }

    if (isAndroid) {
        refreshCanLaunchGame(true);
        // VRChat may be installed or removed while VRCX is in the background.
        onAndroidEvent('focus', () => refreshCanLaunchGame());
        onAndroidEvent('visibility', (payload) => {
            if (payload?.visible !== false) {
                refreshCanLaunchGame();
            }
        });
    }

    watch(
        () => watchState.isLoggedIn,
        () => {
            isLaunchOptionsDialogVisible.value = false;
        },
        { flush: 'sync' }
    );

    function showLaunchOptions() {
        if (isAndroid) {
            // Launch options (--fps, custom path) are for the PC client.
            return;
        }
        isLaunchOptionsDialogVisible.value = true;
    }

    /**
     * @param {string} tag
     * @param {string} shortName
     * @returns {Promise<void>}
     */
    async function showLaunchDialog(tag, shortName = null) {
        launchDialogData.value = {
            visible: true,
            // flag, use for trigger adjustDialogZ
            loading: true,
            tag,
            shortName
        };
        nextTick(() => (launchDialogData.value.loading = false));
    }

    /**
     * @param {string} location
     * @param {string} shortName
     * @returns {Promise<string>} LaunchUrl
     */
    async function getLaunchUrl(location, shortName) {
        const L = parseLocation(location);
        if (shortName && L.instanceType !== 'public' && L.groupAccessType !== 'public') {
            return `vrchat://launch?ref=vrcx.app&id=${location}&shortName=${shortName}`;
        }

        // fetch shortName
        let newShortName = '';
        const response = await instanceRequest.getInstanceShortName({
            worldId: L.worldId,
            instanceId: L.instanceId
        });
        if (response.json) {
            if (response.json.shortName) {
                newShortName = response.json.shortName;
            } else {
                newShortName = response.json.secureName;
            }
        }
        if (newShortName) {
            return `vrchat://launch?ref=vrcx.app&id=${location}&shortName=${newShortName}`;
        }
        return `vrchat://launch?ref=vrcx.app&id=${location}`;
    }

    /**
     * Launch.exe &attach=1
     *
     * @param {string} location
     * @param {string} shortName
     * @returns {Promise<void>}
     */
    async function tryOpenInstanceInVrc(location, shortName) {
        if (isOpeningInstance.value) {
            return;
        }
        isOpeningInstance.value = true;
        let launchUrl = '';
        let result = false;
        try {
            launchUrl = await getLaunchUrl(location, shortName);
            result = await AppApi.TryOpenInstanceInVrc(launchUrl);
        } catch (e) {
            console.error(e);
        }
        console.log('Attach Game', launchUrl, result);
        if (!result) {
            toast.warning('Failed open instance in VRChat, falling back to self invite');
            // self invite fallback
            try {
                const L = parseLocation(location);
                await instanceRequest.selfInvite({
                    instanceId: L.instanceId,
                    worldId: L.worldId,
                    shortName
                });
                toast.success(t('message.invite.self_sent'));
            } catch (e) {
                console.error(e);
            }
        }
        setTimeout(() => {
            isOpeningInstance.value = false;
        }, 1000);
    }

    /**
     * @param {string} location
     * @param {string} shortName
     * @param {boolean} desktopMode
     * @returns {Promise<void>}
     */
    async function launchGame(location, shortName, desktopMode) {
        const launchUrl = await getLaunchUrl(location, shortName);
        const launchArguments = await configRepository.getString('launchArguments');
        const vrcLaunchPathOverride = await configRepository.getString('vrcLaunchPathOverride');
        const args = buildLaunchArgs(launchUrl, { launchArguments, desktopMode });
        if (isAndroid) {
            // StartGame opens the vrchat://launch link in the VRChat app on this device.
            try {
                const result = await AppApi.StartGame(args.join(' '));
                if (!result) {
                    toast.error(t('android.launch.failed'));
                } else {
                    toast.success(t('android.launch.launched'));
                }
            } catch (e) {
                console.error(e);
                toast.error(t('android.launch.failed'));
            }
            console.log('Launch Game', args.join(' '));
            return;
        }
        try {
            if (vrcLaunchPathOverride && !LINUX) {
                const result = await AppApi.StartGameFromPath(vrcLaunchPathOverride, args.join(' '));
                if (!result) {
                    toast.error('Failed to launch VRChat, invalid custom path set');
                } else {
                    toast.success('VRChat launched');
                }
            } else {
                const result = await AppApi.StartGame(args.join(' '));
                if (!result) {
                    toast.error('Failed to find VRChat, set a custom path in launch options');
                } else {
                    toast.success('VRChat launched');
                }
            }
        } catch (e) {
            console.error(e);
            toast.error(`Failed to launch VRChat: ${e.message}`);
        }
        console.log('Launch Game', args.join(' '), desktopMode);
    }

    return {
        isLaunchOptionsDialogVisible,
        isOpeningInstance,
        launchDialogData,
        canLaunchGame,
        refreshCanLaunchGame,
        showLaunchOptions,
        showLaunchDialog,
        getLaunchUrl,
        launchGame,
        tryOpenInstanceInVrc
    };
});
