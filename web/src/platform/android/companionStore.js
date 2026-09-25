// PC companion state mirrored from native (AndroidHost.CompanionGetState + `companion-state` events).
// The phone shell only reads `isPaired`, `isConnected`, `vrchatRunning`.
import { computed, ref } from 'vue';
import { defineStore } from 'pinia';

export const useCompanionStore = defineStore('androidCompanion', () => {
    /** @type {import('vue').Ref<'unpaired'|'idle'|'searching'|'connecting'|'connected'|'error'>} */
    const status = ref('unpaired');
    const activeId = ref(null);
    /** @type {import('vue').Ref<Array<{id:string,name:string,hosts:string[],port:number,fp:string,pairedAt:number,lastSeen:number}>>} */
    const paired = ref([]);
    const machineName = ref(null);
    const tz = ref(null);
    const vrchatRunning = ref(false);
    const steamVrRunning = ref(false);
    const syncing = ref(false);
    const lastError = ref(null);

    const isPaired = computed(() => paired.value.length > 0);
    const isConnected = computed(() => status.value === 'connected');

    /**
     * @param {object} state native CompanionController.state() object
     */
    function applyState(state) {
        if (!state || typeof state !== 'object') return;
        status.value = state.status ?? 'unpaired';
        activeId.value = state.activeId ?? null;
        paired.value = Array.isArray(state.paired) ? state.paired : [];
        machineName.value = state.machineName ?? null;
        tz.value = state.tz ?? null;
        vrchatRunning.value = Boolean(state.vrchatRunning);
        steamVrRunning.value = Boolean(state.steamVrRunning);
        syncing.value = Boolean(state.syncing);
        lastError.value = state.lastError ?? null;
    }

    return {
        status,
        activeId,
        paired,
        machineName,
        tz,
        vrchatRunning,
        steamVrRunning,
        syncing,
        lastError,
        isPaired,
        isConnected,
        applyState
    };
});
