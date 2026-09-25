import { beforeEach, describe, expect, test, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

// The VRChat registry and PC screenshots are on the PC: every flow that touches them is skipped on Android,
// including the registry auto-backup whose stamped date would otherwise trigger a restore prompt.
const mocks = vi.hoisted(() => ({
    advancedSettingsStore: {
        vrcRegistryAutoBackup: true,
        vrcRegistryAskRestore: true,
        screenshotHelper: true,
        screenshotHelperCopyToClipboard: true,
        screenshotHelperModifyFilename: false
    },
    modalStore: { alert: vi.fn(), confirm: vi.fn() },
    config: {
        getInt: vi.fn(async (_key, fallback) => fallback),
        setInt: vi.fn(),
        getString: vi.fn(async () => '2020-01-01T00:00:00.000Z'),
        setString: vi.fn(),
        getBool: vi.fn(async (_key, fallback) => fallback),
        setBool: vi.fn()
    }
}));

vi.mock('../../shared/utils/platform', async (importOriginal) => ({
    ...(await importOriginal()),
    isAndroid: true,
    hasLocalGame: false,
    hasLocalVrchatFiles: false,
    hasVrOverlay: false,
    hasDesktopShell: false,
    hasDiscordPresence: false
}));
vi.mock('vue-i18n', async (importOriginal) => ({
    ...(await importOriginal()),
    useI18n: () => ({ t: (key) => key })
}));
vi.mock('vue-sonner', () => ({ toast: { error: vi.fn(), success: vi.fn(), warning: vi.fn() } }));
vi.mock('../../api', () => ({ avatarRequest: {}, queryRequest: {} }));
vi.mock('../../shared/utils', () => ({ debounce: (fn) => fn, parseLocation: () => ({}) }));
vi.mock('../../services/appConfig', () => ({ AppDebug: {} }));
vi.mock('../../services/database', () => ({
    database: {
        upgradeDatabaseVersion: vi.fn(),
        vacuum: vi.fn(),
        optimize: vi.fn(),
        setMaxTableSize: vi.fn(),
        setSearchTableSize: vi.fn()
    }
}));
vi.mock('../../shared/utils/base/ui', () => ({ refreshCustomScript: vi.fn() }));
vi.mock('../settings/advanced', () => ({ useAdvancedSettingsStore: () => mocks.advancedSettingsStore }));
vi.mock('../avatarProvider', () => ({ useAvatarProviderStore: () => ({}) }));
vi.mock('../../coordinators/favoriteCoordinator', () => ({
    addLocalWorldFavorite: vi.fn(),
    addLocalAvatarFavorite: vi.fn()
}));
vi.mock('../favorite', () => ({ useFavoriteStore: () => ({}) }));
vi.mock('../gameLog', () => ({ useGameLogStore: () => ({}) }));
vi.mock('../game', () => ({ useGameStore: () => ({}) }));
vi.mock('../../coordinators/groupCoordinator', () => ({ showGroupDialog: vi.fn() }));
vi.mock('../../coordinators/worldCoordinator', () => ({ showWorldDialog: vi.fn() }));
vi.mock('../../coordinators/avatarCoordinator', () => ({
    showAvatarDialog: vi.fn(),
    selectAvatarWithConfirmation: vi.fn(),
    selectAvatarWithoutConfirmation: vi.fn()
}));
vi.mock('../../coordinators/userCoordinator', () => ({ showUserDialog: vi.fn(), addCustomTag: vi.fn() }));
vi.mock('../location', () => ({
    useLocationStore: () => ({ lastLocation: { location: '', name: '', playerList: new Map() } })
}));
vi.mock('../modal', () => ({ useModalStore: () => mocks.modalStore }));
vi.mock('../notification', () => ({ useNotificationStore: () => ({}) }));
vi.mock('../photon', () => ({ usePhotonStore: () => ({}) }));
vi.mock('../search', () => ({ useSearchStore: () => ({}) }));
vi.mock('../updateLoop', () => ({ useUpdateLoopStore: () => ({}) }));
vi.mock('../user', () => ({ useUserStore: () => ({ currentUser: { id: 'usr_me', displayName: 'Me' } }) }));
vi.mock('../vrcStatus', () => ({ useVrcStatusStore: () => ({}) }));
vi.mock('../../coordinators/vrcxCoordinator', () => ({ clearVRCXCache: vi.fn() }));
vi.mock('../../coordinators/searchIndexCoordinator', () => ({ resetSearchIndexOnLogin: vi.fn() }));
vi.mock('../../services/watchState', () => ({ watchState: { isLoggedIn: false } }));
vi.mock('../../services/config', () => ({ default: mocks.config }));

import { useVrcxStore } from '../vrcx';

describe('useVrcxStore on Android', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        vi.clearAllMocks();
        globalThis.AppApi = {
            HasVRChatRegistryFolder: vi.fn().mockResolvedValue(false),
            GetVRChatRegistryJson: vi.fn().mockResolvedValue('{}'),
            FocusWindow: vi.fn(),
            AddScreenshotMetadata: vi.fn().mockResolvedValue('new.png'),
            CopyImageToClipboard: vi.fn()
        };
        globalThis.VRCXStorage = { Get: vi.fn().mockResolvedValue(''), Set: vi.fn() };
    });

    test('never prompts to restore the VRChat registry at start-up', async () => {
        const store = useVrcxStore();
        await store.checkAutoBackupRestoreVrcRegistry();
        expect(globalThis.AppApi.HasVRChatRegistryFolder).not.toHaveBeenCalled();
        expect(mocks.modalStore.alert).not.toHaveBeenCalled();
        expect(store.isRegistryBackupDialogVisible).toBe(false);
    });

    test('never auto-backs up the registry or stamps a backup date', async () => {
        const store = useVrcxStore();
        await store.tryAutoBackupVrcRegistry();
        await store.backupVrcRegistry('Manual');
        expect(globalThis.AppApi.GetVRChatRegistryJson).not.toHaveBeenCalled();
        expect(mocks.config.setString).not.toHaveBeenCalledWith('VRCX_VRChatRegistryLastBackupDate', expect.anything());
    });

    test('the registry backup dialog cannot be opened', () => {
        const store = useVrcxStore();
        store.showRegistryBackupDialog();
        expect(store.isRegistryBackupDialogVisible).toBe(false);
    });

    test('screenshot log lines from the PC are ignored', async () => {
        const store = useVrcxStore();
        await store.processScreenshot('C:\\Users\\me\\Pictures\\VRChat\\shot.png');
        expect(globalThis.AppApi.AddScreenshotMetadata).not.toHaveBeenCalled();
        expect(globalThis.AppApi.CopyImageToClipboard).not.toHaveBeenCalled();
    });
});
