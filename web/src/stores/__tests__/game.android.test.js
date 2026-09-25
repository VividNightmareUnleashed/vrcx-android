import { beforeEach, describe, expect, test, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

vi.mock('../../shared/utils/platform', async (importOriginal) => ({
    ...(await importOriginal()),
    isAndroid: true,
    hasLocalVrchatFiles: false
}));
vi.mock('../../services/config.js', () => ({
    default: {
        getBool: vi.fn().mockResolvedValue(false),
        getString: vi.fn().mockResolvedValue(null)
    }
}));

import { useGameStore } from '../game';

describe('useGameStore on Android', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        globalThis.AppApi = {
            GetVRChatRegistryKeyString: vi.fn().mockResolvedValue('1'),
            GetVRChatRegistryKey: vi.fn().mockResolvedValue('1')
        };
    });

    test('never reads the VRChat registry', async () => {
        const store = useGameStore();
        await expect(store.getVRChatRegistryKey('LOGGING_ENABLED')).resolves.toBeNull();
        expect(globalThis.AppApi.GetVRChatRegistryKeyString).not.toHaveBeenCalled();
        expect(globalThis.AppApi.GetVRChatRegistryKey).not.toHaveBeenCalled();
    });
});
