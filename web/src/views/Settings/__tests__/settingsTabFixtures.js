import { ref } from 'vue';
import { vi } from 'vitest';

// Shared store fakes for the Settings tab gate tests (SettingsTabs.android.test.js / SettingsTabs.desktop.test.js).
// Each store is a Proxy: listed state is returned as refs (the tests mock `storeToRefs` as identity), and any other
// property is a vi.fn, which covers the actions the tabs destructure.

/**
 * @param {Record<string, unknown>} state
 * @param {Record<string, unknown>} [extra] Non-ref members (plain values or functions)
 */
function fakeStore(state, extra = {}) {
    const target = { ...extra };
    for (const [key, value] of Object.entries(state)) {
        target[key] = ref(value);
    }
    return new Proxy(target, {
        get(obj, key) {
            if (typeof key !== 'string') {
                return undefined;
            }
            if (!(key in obj)) {
                obj[key] = vi.fn();
            }
            return obj[key];
        }
    });
}

/**
 * Builds the `@/stores` module the Settings tabs import.
 *
 * @param {{ noUpdater?: boolean }} [options]
 */
export function createStoresModule({ noUpdater = false } = {}) {
    const general = fakeStore({
        isStartAtWindowsStartup: false,
        isStartAsMinimizedState: false,
        isCloseToTray: true,
        disableGpuAcceleration: false,
        disableVrOverlayGpuAcceleration: false,
        udonExceptionLogging: false,
        logResourceLoad: false,
        autoLoginDelayEnabled: false
    });
    const updater = fakeStore({
        appVersion: 'VRCX 2026.01.01',
        autoUpdateVRCX: 'Off',
        latestAppVersion: '',
        noUpdater,
        branch: 'Stable'
    });
    const notificationsSettings = fakeStore(
        {
            desktopToast: 'Always',
            afkDesktopToast: false,
            imageNotifications: true,
            notificationTTS: 'Never',
            notificationTTSNickName: false,
            isTestTTSVisible: false,
            notificationTTSTest: '',
            TTSvoices: [],
            notificationLayout: 'notification-center'
        },
        { getTTSVoiceName: () => '' }
    );
    const advanced = fakeStore({
        enablePrimaryPassword: false,
        relaunchVRChatAfterCrash: false,
        vrcQuitFix: true,
        autoSweepVRChatCache: false,
        selfInviteOverride: false,
        enableAppLauncher: true,
        enableAppLauncherAutoClose: true,
        enableAppLauncherRunProcessOnce: true,
        showConfirmationOnSwitchAvatar: false,
        gameLogDisabled: false,
        sqliteTableSizes: {},
        avatarAutoCleanup: 'Off',
        purgeInProgress: false,
        sentryErrorReporting: false,
        screenshotHelper: true,
        screenshotHelperModifyFilename: false,
        screenshotHelperCopyToClipboard: false,
        autoDeleteOldPrints: false,
        saveInstancePrints: false,
        cropInstancePrints: false,
        saveInstanceStickers: false,
        saveInstanceEmoji: false,
        ugcFolderPath: '',
        avatarRemoteDatabase: true,
        youTubeApi: false,
        translationApi: false
    });
    const discord = fakeStore({
        discordActive: false,
        discordInstance: true,
        discordHideInvite: true,
        discordJoinButton: false,
        discordHideImage: false,
        discordShowPlatform: true,
        discordWorldIntegration: true,
        discordWorldNameAsDiscordStatus: false
    });
    const emptyMaps = {
        cachedUsers: new Map(),
        cachedWorlds: new Map(),
        cachedAvatars: new Map(),
        cachedAvatarNames: new Map(),
        cachedGroups: new Map(),
        cachedInstances: new Map()
    };

    return {
        useGeneralSettingsStore: () => general,
        useVRCXUpdaterStore: () => updater,
        useNotificationsSettingsStore: () => notificationsSettings,
        useNotificationStore: () => fakeStore({}),
        useAdvancedSettingsStore: () => advanced,
        useDiscordPresenceSettingsStore: () => discord,
        useAvatarProviderStore: () => fakeStore({ isAvatarProviderDialogVisible: false }),
        useVrStore: () => fakeStore({}),
        useAuthStore: () => fakeStore({ cachedConfig: {} }),
        useUiStore: () => fakeStore({}),
        useUserStore: () => fakeStore({}, emptyMaps),
        useWorldStore: () => fakeStore({}, emptyMaps),
        useAvatarStore: () => fakeStore({}, emptyMaps),
        useGroupStore: () => fakeStore({}, emptyMaps),
        useInstanceStore: () => fakeStore({}, emptyMaps),
        usePhotonStore: () => fakeStore({ photonLoggingEnabled: false }),
        useAppearanceSettingsStore: () => fakeStore({ isDarkMode: false })
    };
}

/** A component stub that renders a marker, so tests can see whether a gated dialog was rendered. */
export function markerStub(testId) {
    return { default: { name: testId, template: `<div data-testid="${testId}" />` } };
}

/** `@/components/ui/dialog` without portals or stores: content renders only while open. */
export const dialogModule = {
    Dialog: { props: ['open'], template: '<div v-if="open"><slot /></div>' },
    DialogContent: { template: '<div><slot /></div>' },
    DialogHeader: { template: '<div><slot /></div>' },
    DialogTitle: { template: '<div><slot /></div>' },
    DialogDescription: { template: '<div><slot /></div>' },
    DialogFooter: { template: '<div><slot /></div>' }
};
