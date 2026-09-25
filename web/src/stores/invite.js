import { computed, ref, watch } from 'vue';
import { defineStore } from 'pinia';

import { inviteMessagesRequest } from '../api';
import { useAdvancedSettingsStore } from './settings/advanced';
import { useGameStore } from './game';
import { hasLocalGame } from '../shared/utils/platform';
import { watchState } from '../services/watchState';

export const useInviteStore = defineStore('Invite', () => {
    const gameStore = useGameStore();
    const advancedSettingsStore = useAdvancedSettingsStore();

    const inviteMessageTable = ref({
        data: [],
        layout: 'table',
        visible: false
    });

    const inviteResponseMessageTable = ref({
        data: [],
        layout: 'table',
        visible: false
    });

    const inviteRequestMessageTable = ref({
        data: [],
        layout: 'table',
        visible: false
    });

    const inviteRequestResponseMessageTable = ref({
        data: [],
        layout: 'table',
        visible: false
    });

    watch(
        () => watchState.isLoggedIn,
        () => {
            inviteMessageTable.value.data = [];
            inviteResponseMessageTable.value.data = [];
            inviteRequestMessageTable.value.data = [];
            inviteRequestResponseMessageTable.value.data = [];
            inviteMessageTable.value.visible = false;
            inviteResponseMessageTable.value.visible = false;
            inviteRequestMessageTable.value.visible = false;
            inviteRequestResponseMessageTable.value.visible = false;
        },
        { flush: 'sync' }
    );

    // "Open in-game" talks to a VRChat process on this machine; on Android every path self-invites instead.
    const canOpenInstanceInGame = computed(() => {
        return hasLocalGame && gameStore.isGameRunning && !advancedSettingsStore.selfInviteOverride;
    });

    /**
     * @param {'message' | 'request' | 'response' | 'requestResponse'} mode
     */
    function refreshInviteMessageTableData(mode) {
        inviteMessagesRequest
            .refreshInviteMessageTableData(mode)
            .then(({ json }) => {
                switch (mode) {
                    case 'message':
                        inviteMessageTable.value.data = json;
                        break;
                    case 'response':
                        inviteResponseMessageTable.value.data = json;
                        break;
                    case 'request':
                        inviteRequestMessageTable.value.data = json;
                        break;
                    case 'requestResponse':
                        inviteRequestResponseMessageTable.value.data = json;
                        break;
                }
            })
            .catch((err) => {
                console.error('refreshInviteMessageTableData Failed：', err);
            });
    }

    return {
        inviteMessageTable,
        inviteResponseMessageTable,
        inviteRequestMessageTable,
        inviteRequestResponseMessageTable,
        refreshInviteMessageTableData,
        canOpenInstanceInGame
    };
});
