import { defineStore } from 'pinia';
import { onScopeDispose, watch } from 'vue';

import { database } from '../services/database';
import { groupRequest } from '../api';
import { runRefreshFriendsListFlow } from '../coordinators/friendSyncCoordinator';
import { runUpdateIsGameRunningFlow } from '../coordinators/gameCoordinator';
import { addGameLogEvent, runAndroidGameLogFlow } from '../coordinators/gameLogCoordinator';
import { runRefreshPlayerModerationsFlow } from '../coordinators/moderationCoordinator';
import { clearVRCXCache } from '../coordinators/vrcxCoordinator';
import { hasVrOverlay, isAndroid } from '../shared/utils/platform';
import { createHiddenLoopController } from '../platform/android/hiddenLoop';
import { useAuthStore } from './auth';
import { useDiscordPresenceSettingsStore } from './settings/discordPresence';
import { useFriendStore } from './friend';
import { handleGroupUserInstances } from '../coordinators/groupCoordinator';
import { getCurrentUser, updateAutoStateChange } from '../coordinators/userCoordinator';
import { useUserStore } from './user';
import { useVRCXUpdaterStore } from './vrcxUpdater';
import { useVrStore } from './vr';
import { useVrcxStore } from './vrcx';
import { watchState } from '../services/watchState';

import * as workerTimers from 'worker-timers';

export const useUpdateLoopStore = defineStore('UpdateLoop', () => {
    const authStore = useAuthStore();
    const userStore = useUserStore();
    const friendStore = useFriendStore();
    const vrcxStore = useVrcxStore();
    const discordPresenceSettingsStore = useDiscordPresenceSettingsStore();
    const vrcxUpdaterStore = useVRCXUpdaterStore();
    const vrStore = useVrStore();
    const state = {
        nextCurrentUserRefresh: 300,
        nextFriendsRefresh: 3600,
        nextGroupInstanceRefresh: 0,
        nextAppUpdateCheck: 3600,
        ipcTimeout: 0,
        nextClearVRCXCacheCheck: 86400,
        nextDiscordUpdate: 0,
        nextAutoStateChange: 0,
        nextGetLogCheck: 0,
        nextGameRunningCheck: 0,
        nextDatabaseOptimize: 3600
    };

    // Android: slower ticks while the app is in the background, and the game log read on the host's signal
    // (docs/ARCHITECTURE.md §7).
    const androidLoop = isAndroid
        ? createHiddenLoopController({
              runGameLogFlow: runAndroidGameLogFlow,
              isLoggedIn: () => watchState.isLoggedIn
          })
        : null;
    if (androidLoop) {
        onScopeDispose(() => androidLoop.dispose());
    }

    watch(
        () => watchState.isLoggedIn,
        () => {
            state.nextCurrentUserRefresh = 300;
            state.nextFriendsRefresh = 3600;
            state.nextGroupInstanceRefresh = 0;
        },
        { flush: 'sync' }
    );

    const nextGroupInstanceRefresh = state.nextGroupInstanceRefresh;

    const nextCurrentUserRefresh = state.nextCurrentUserRefresh;

    const nextDiscordUpdate = state.nextDiscordUpdate;

    const ipcTimeout = state.ipcTimeout;

    async function updateLoop() {
        androidLoop?.catchUp(state, watchState.isLoggedIn);
        try {
            if (watchState.isLoggedIn) {
                if (--state.nextCurrentUserRefresh <= 0) {
                    state.nextCurrentUserRefresh = 300; // 5min
                    getCurrentUser();
                }
                if (--state.nextFriendsRefresh <= 0) {
                    state.nextFriendsRefresh = 3600; // 1hour
                    runRefreshFriendsListFlow();
                    authStore.updateStoredUser(userStore.currentUser);
                    if (
                        userStore.currentUser.last_activity &&
                        new Date(userStore.currentUser.last_activity) > new Date(Date.now() - 3600 * 1000) // 1hour
                    ) {
                        runRefreshPlayerModerationsFlow();
                    }
                }
                if (--state.nextGroupInstanceRefresh <= 0) {
                    if (watchState.isFriendsLoaded) {
                        state.nextGroupInstanceRefresh = 300; // 5min
                        const args = await groupRequest.getUsersGroupInstances();
                        handleGroupUserInstances(args);
                    }
                    AppApi.CheckGameRunning();
                }
                if (--state.nextAppUpdateCheck <= 0) {
                    state.nextAppUpdateCheck = 3600; // 1hour
                    if (vrcxUpdaterStore.autoUpdateVRCX !== 'Off') {
                        vrcxUpdaterStore.checkForVRCXUpdate();
                    }
                    vrcxStore.tryAutoBackupVrcRegistry();
                }
                if (--state.ipcTimeout <= 0) {
                    vrcxStore.setIpcEnabled(false);
                }
                if (--state.nextClearVRCXCacheCheck <= 0 && vrcxStore.clearVRCXCacheFrequency > 0) {
                    state.nextClearVRCXCacheCheck = vrcxStore.clearVRCXCacheFrequency / 2;
                    clearVRCXCache();
                }
                if (--state.nextDiscordUpdate <= 0) {
                    state.nextDiscordUpdate = 3;
                    if (discordPresenceSettingsStore.discordActive) {
                        discordPresenceSettingsStore.updateDiscord();
                    }
                }
                if (--state.nextAutoStateChange <= 0) {
                    state.nextAutoStateChange = 3;
                    updateAutoStateChange();
                }
                if (LINUX && --state.nextGetLogCheck <= 0) {
                    state.nextGetLogCheck = 0.5;
                    if (isAndroid) {
                        // Reads the game state with the lines: the PC companion orders them differently. While the
                        // app is hidden, the host's log-available and game-state signals run it instead.
                        if (!androidLoop.isHidden()) {
                            await androidLoop.runGameLog();
                        }
                    } else {
                        const logLines = await LogWatcher.GetLogLines();
                        if (logLines) {
                            logLines.forEach((logLine) => {
                                addGameLogEvent(logLine);
                            });
                        }
                    }
                }
                if (LINUX && --state.nextGameRunningCheck <= 0) {
                    state.nextGameRunningCheck = 1;
                    if (!isAndroid) {
                        await runUpdateIsGameRunningFlow(await AppApi.IsGameRunning(), await AppApi.IsSteamVRRunning());
                    }
                    if (hasVrOverlay) {
                        vrStore.vrInit(); // TODO: make this event based
                    }
                }
                if (--state.nextDatabaseOptimize <= 0) {
                    state.nextDatabaseOptimize = 86400; // 1 day
                    database.optimize().catch(console.error);
                }
            }
        } catch (err) {
            friendStore.setIsRefreshFriendsLoading(false);
            console.error(err);
        }
        if (androidLoop) {
            androidLoop.schedule(() => updateLoop());
        } else {
            workerTimers.setTimeout(() => updateLoop(), 1000);
        }
    }

    /**
     * @param value
     */
    function setNextClearVRCXCacheCheck(value) {
        state.nextClearVRCXCacheCheck = value;
    }

    /**
     * @param value
     */
    function setNextGroupInstanceRefresh(value) {
        state.nextGroupInstanceRefresh = value;
    }

    /**
     * @param value
     */
    function setNextDiscordUpdate(value) {
        state.nextDiscordUpdate = value;
    }

    /**
     * @param value
     */
    function setIpcTimeout(value) {
        state.ipcTimeout = value;
    }

    /**
     * @param value
     */
    function setNextCurrentUserRefresh(value) {
        state.nextCurrentUserRefresh = value;
    }

    return {
        // state,

        nextGroupInstanceRefresh,
        nextCurrentUserRefresh,
        nextDiscordUpdate,
        ipcTimeout,
        updateLoop,
        setIpcTimeout,
        setNextCurrentUserRefresh,
        setNextDiscordUpdate,
        setNextGroupInstanceRefresh,
        setNextClearVRCXCacheCheck
    };
});
