import { beforeEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h } from 'vue';
import { mount } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';

const router = vi.hoisted(() => ({ push: null }));
vi.mock('vue-router', () => ({ useRouter: () => router }));

import { i18n } from '../../../../plugins/i18n';
import { TooltipProvider } from '../../../../components/ui/tooltip';
import { useCompanionStore } from '../../companionStore';
import GameIndicator from '../GameIndicator.vue';

const PAIRED = [{ id: 'pc', name: 'PC', hosts: [], port: 1, fp: '', pairedAt: 0, lastSeen: 0 }];

function mountIndicator(state) {
    const pinia = createPinia();
    setActivePinia(pinia);
    useCompanionStore().applyState(state);
    const Host = defineComponent({ setup: () => () => h(TooltipProvider, null, () => h(GameIndicator)) });
    return mount(Host, { global: { plugins: [pinia, i18n] } });
}

describe('GameIndicator (DESIGN.md §2.1)', () => {
    beforeEach(() => {
        router.push = vi.fn();
    });

    it('is hidden while no PC companion is paired', () => {
        const wrapper = mountIndicator({ status: 'unpaired', paired: [] });
        expect(wrapper.find('.vrcx-game-indicator').exists()).toBe(false);
    });

    it('shows running, stopped and disconnected states', () => {
        const cases = [
            [{ status: 'connected', paired: PAIRED, vrchatRunning: true }, 'running', 'bg-status-online'],
            [{ status: 'connected', paired: PAIRED, vrchatRunning: false }, 'stopped', 'bg-status-offline-alt'],
            [{ status: 'idle', paired: PAIRED, vrchatRunning: true }, 'disconnected', 'bg-transparent']
        ];
        for (const [state, expected, dotClass] of cases) {
            const wrapper = mountIndicator(state);
            const button = wrapper.find('.vrcx-game-indicator');
            expect(button.attributes('data-game-state')).toBe(expected);
            expect(button.find('span').classes()).toContain(dotClass);
            wrapper.unmount();
        }
    });

    it('opens Settings → PC companion on tap', async () => {
        const wrapper = mountIndicator({ status: 'connected', paired: PAIRED, vrchatRunning: true });
        await wrapper.find('.vrcx-game-indicator').trigger('click');
        expect(router.push).toHaveBeenCalledWith({ name: 'settings', query: { section: 'companion' } });
    });
});
