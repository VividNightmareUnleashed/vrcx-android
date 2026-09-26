import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

// Android: status.vrchat.com is not polled while VRCX runs in the background (docs/ARCHITECTURE.md §7).
const mocks = vi.hoisted(() => ({ execute: vi.fn(), interval: null, hidden: false }));

vi.mock('../../shared/utils/platform', async (importOriginal) => ({ ...(await importOriginal()), isAndroid: true }));
vi.mock('../../services/webapi', () => ({ default: { execute: (...args) => mocks.execute(...args) } }));
vi.mock('../../shared/utils', () => ({ openExternalLink: vi.fn() }));
vi.mock('worker-timers', () => ({
    setInterval: vi.fn((callback) => {
        mocks.interval = callback;
        return 1;
    }),
    setTimeout: vi.fn()
}));

import { useVrcStatusStore } from '../vrcStatus';

Object.defineProperty(document, 'hidden', { configurable: true, get: () => mocks.hidden });

describe('useVrcStatusStore on Android', () => {
    beforeEach(() => {
        setActivePinia(createPinia());
        vi.clearAllMocks();
        mocks.execute.mockResolvedValue({
            status: 200,
            data: JSON.stringify({
                page: { updated_at: '2026-01-01T00:00:00.000Z' },
                status: { description: 'All Systems Operational' }
            })
        });
    });

    afterEach(() => {
        mocks.hidden = false;
    });

    test('a start in the background (for example after boot) fetches nothing', () => {
        mocks.hidden = true;
        useVrcStatusStore();
        expect(mocks.execute).not.toHaveBeenCalled();

        mocks.interval();
        expect(mocks.execute).not.toHaveBeenCalled();
    });

    test('polling resumes once the app is visible', () => {
        mocks.hidden = true;
        useVrcStatusStore();
        mocks.hidden = false;
        mocks.interval();
        expect(mocks.execute).toHaveBeenCalledTimes(1);
    });

    test('a visible start fetches at once, as upstream', () => {
        useVrcStatusStore();
        expect(mocks.execute).toHaveBeenCalledTimes(1);
    });
});
