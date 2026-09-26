import { beforeEach, describe, expect, test, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

// The VRChat registry and PC screenshots are on the PC: every flow that touches them is skipped on Android,
// including the registry auto-backup whose stamped date would otherwise trigger a restore prompt.
const mocks = vi.hoisted(() => ({
    advancedSettingsStore: {
        vrcRegistryAutoBackup: true,
        vrcRegistryAskRestore: true,
        showConfirmationOnSwitchAvatar: true,
        screenshotHelper: true,
        screenshotHelperCopyToClipboard: true,
        screenshotHelperModifyFilename: false
    },
    modalStore: { alert: vi.fn(), confirm: vi.fn() },
    searchStore: { directAccessParse: vi.fn(), setSearchText: vi.fn() },
    router: { push: vi.fn(), currentRoute: { value: { name: 'feed' } } },
    watchState: { isLoggedIn: false },
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
vi.mock('../search', () => ({ useSearchStore: () => mocks.searchStore }));
vi.mock('../../plugins/router.js', () => ({ router: mocks.router }));
vi.mock('../updateLoop', () => ({ useUpdateLoopStore: () => ({}) }));
vi.mock('../user', () => ({ useUserStore: () => ({ currentUser: { id: 'usr_me', displayName: 'Me' } }) }));
vi.mock('../vrcStatus', () => ({ useVrcStatusStore: () => ({}) }));
vi.mock('../../coordinators/vrcxCoordinator', () => ({ clearVRCXCache: vi.fn() }));
vi.mock('../../coordinators/searchIndexCoordinator', () => ({ resetSearchIndexOnLogin: vi.fn() }));
vi.mock('../../services/watchState', () => ({ watchState: mocks.watchState }));
vi.mock('../../services/config', () => ({ default: mocks.config }));

import { useVrcxStore } from '../vrcx';
import { selectAvatarWithConfirmation, selectAvatarWithoutConfirmation } from '../../coordinators/avatarCoordinator';

describe('useVrcxStore on Android', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        vi.clearAllMocks();
        mocks.watchState.isLoggedIn = false;
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

    describe('Share to VRCX (search/<text>)', () => {
        const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

        beforeEach(() => {
            mocks.watchState.isLoggedIn = true;
            mocks.searchStore.directAccessParse.mockImplementation((input) =>
                /^https:\/\/vrchat\.com\/|^(usr|wrld|avtr|grp)_/.test(input)
            );
        });

        test('opens a shared VRChat URL whole, although it contains slashes', async () => {
            const url = 'https://vrchat.com/home/user/usr_00000000-0000-4000-8000-000000000101';
            useVrcxStore().eventLaunchCommand(`search/${url}`);
            await flush();
            expect(mocks.searchStore.directAccessParse).toHaveBeenCalledWith(url);
            expect(mocks.router.push).not.toHaveBeenCalled();
        });

        test('opens a shared id', async () => {
            useVrcxStore().eventLaunchCommand('search/wrld_00000000-0000-4000-8000-00000000a001');
            await flush();
            expect(mocks.searchStore.directAccessParse).toHaveBeenCalledWith(
                'wrld_00000000-0000-4000-8000-00000000a001'
            );
            expect(mocks.router.push).not.toHaveBeenCalled();
        });

        test('opens Search filled in with shared free text', async () => {
            useVrcxStore().eventLaunchCommand('search/Aurora / friends');
            await flush();
            expect(mocks.searchStore.setSearchText).toHaveBeenCalledWith('Aurora / friends');
            expect(mocks.router.push).toHaveBeenCalledWith({ name: 'search' });
        });

        test('does nothing before login', async () => {
            mocks.watchState.isLoggedIn = false;
            useVrcxStore().eventLaunchCommand('search/Aurora');
            await flush();
            expect(mocks.searchStore.setSearchText).not.toHaveBeenCalled();
        });
    });

    describe('switchavatar', () => {
        const AVATAR_ID = 'avtr_00000000-0000-4000-8000-00000000b001';

        beforeEach(() => {
            mocks.watchState.isLoggedIn = true;
            selectAvatarWithoutConfirmation.mockResolvedValue(undefined);
        });

        test('asks as upstream when the setting says so', () => {
            useVrcxStore().eventLaunchCommand(`switchavatar/${AVATAR_ID}`);
            expect(selectAvatarWithConfirmation).toHaveBeenCalledWith(AVATAR_ID);
            expect(selectAvatarWithoutConfirmation).not.toHaveBeenCalled();
        });

        test('does not ask twice for a command the user already allowed', () => {
            useVrcxStore().eventLaunchCommand(`switchavatar/${AVATAR_ID}`, { confirmed: true });
            expect(selectAvatarWithConfirmation).not.toHaveBeenCalled();
            expect(selectAvatarWithoutConfirmation).toHaveBeenCalledWith(AVATAR_ID);
        });
    });
});
