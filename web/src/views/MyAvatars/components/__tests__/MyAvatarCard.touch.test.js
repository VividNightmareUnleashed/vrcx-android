// The touch "more" button of a My Avatars grid card opens the card's real (reka) context menu.
import { afterEach, describe, expect, test, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { ref } from 'vue';

const coarsePointer = ref(true);

vi.mock('vue-i18n', () => ({
    useI18n: () => ({
        t: (key) => key
    })
}));

vi.mock('@/composables/useCompactLayout', () => ({
    useCompactLayout: () => ({ isCompact: ref(true), isCompactLandscape: ref(false), isCoarsePointer: coarsePointer })
}));

vi.mock('../../../../shared/utils', () => ({
    formatDateFilter: () => 'formatted-date',
    getAvailablePlatforms: () => ({ isPC: true, isQuest: false, isIos: false }),
    getPlatformInfo: () => ({}),
    timeToText: () => '1h'
}));

vi.mock('../../../../shared/constants', () => ({
    getTagColor: () => ({ name: 'blue', bg: 'transparent', text: 'inherit' })
}));

// The info hover card is not under test; the context menu is the real component.
vi.mock('@/components/ui/hover-card', () => ({
    HoverCard: { props: ['open'], emits: ['update:open'], template: '<div><slot /></div>' },
    HoverCardTrigger: { template: '<div><slot /></div>' },
    HoverCardContent: { template: '<div><slot /></div>' }
}));

import MyAvatarCard from '../MyAvatarCard.vue';

function mountCard() {
    return mount(MyAvatarCard, {
        attachTo: document.body,
        props: {
            avatar: {
                id: 'avtr_00000000-0000-4000-8000-000000000001',
                name: 'Avatar One',
                thumbnailImageUrl: '',
                releaseStatus: 'private',
                unityPackages: [],
                $tags: [],
                updated_at: '2025-01-01T00:00:00.000Z',
                created_at: '2024-01-01T00:00:00.000Z',
                version: 1
            }
        }
    });
}

function openMenuContent() {
    return document.body.querySelector('[data-slot="context-menu-content"]');
}

describe('MyAvatarCard touch menu button', () => {
    afterEach(() => {
        document.body.innerHTML = '';
        coarsePointer.value = true;
    });

    test('opens the card context menu without wearing the avatar', async () => {
        const wrapper = mountCard();
        expect(openMenuContent()).toBeNull();

        await wrapper.get('button[aria-label="nav_tooltip.manage"]').trigger('click');
        await flushPromises();

        const content = openMenuContent();
        expect(content).not.toBeNull();
        expect(content.textContent).toContain('dialog.avatar.actions.view_details');
        expect(content.textContent).toContain('dialog.avatar.actions.change_image');
        // The tap on the button must not reach the card, which wears the avatar.
        expect(wrapper.emitted('click')).toBeUndefined();
        wrapper.unmount();
    });

    test('is not rendered for a mouse', () => {
        coarsePointer.value = false;
        const wrapper = mountCard();
        expect(wrapper.find('button[aria-label="nav_tooltip.manage"]').exists()).toBe(false);
        wrapper.unmount();
    });
});
