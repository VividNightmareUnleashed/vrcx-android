import { describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';

const mocks = vi.hoisted(() => ({
    sendBoop: vi.fn(),
    fetch: vi.fn(async () => ({ ref: { displayName: 'User A' } })),
    boopDialog: { value: { visible: true, userId: 'usr_1' } },
    showGalleryPage: vi.fn(),
    closeMainDialog: vi.fn(),
    isCompact: { value: false }
}));

vi.mock('pinia', async (i) => ({ ...(await i()), storeToRefs: (s) => s }));
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (k) => k }) }));
vi.mock('../../../api', () => ({
    miscRequest: { sendBoop: (...a) => mocks.sendBoop(...a) },
    notificationRequest: { hideNotificationV2: vi.fn() },
    queryRequest: { fetch: (...a) => mocks.fetch(...a) }
}));
vi.mock('../../../stores', () => ({
    useUserStore: () => ({
        sendBoopDialog: mocks.boopDialog,
        isLocalUserVrcPlusSupporter: { value: false }
    }),
    useNotificationStore: () => ({
        notificationTable: { value: { data: [] } },
        isNotificationExpired: () => false,
        handleNotificationV2Hide: vi.fn()
    }),
    useUiStore: () => ({ closeMainDialog: (...a) => mocks.closeMainDialog(...a) }),
    useGalleryStore: () => ({
        showGalleryPage: (...a) => mocks.showGalleryPage(...a),
        refreshEmojiTable: vi.fn(),
        emojiTable: { value: [] }
    })
}));
vi.mock('../../../shared/constants/photon.js', () => ({
    photonEmojis: ['Wave']
}));
vi.mock('@/components/ui/dialog', () => ({
    Dialog: { template: '<div><slot /></div>' },
    DialogContent: { template: '<div><slot /></div>' },
    DialogHeader: { template: '<div><slot /></div>' },
    DialogTitle: { template: '<div><slot /></div>' },
    DialogFooter: { template: '<div><slot /></div>' }
}));
vi.mock('@/components/ui/button', () => ({
    Button: {
        emits: ['click'],
        template: '<button data-testid="btn" @click="$emit(\'click\')"><slot /></button>'
    }
}));
vi.mock('../../ui/virtual-combobox', () => ({
    VirtualCombobox: { template: '<div />' }
}));
vi.mock('../../Emoji.vue', () => ({ default: { template: '<div />' } }));
vi.mock('lucide-vue-next', () => ({ Check: { template: '<i />' } }));
vi.mock('../../../composables/useCompactLayout', () => ({ useCompactLayout: () => ({ isCompact: mocks.isCompact }) }));

import SendBoopDialog from '../SendBoopDialog.vue';

describe('SendBoopDialog.vue', () => {
    it('renders boop dialog content', async () => {
        const wrapper = mount(SendBoopDialog);
        expect(wrapper.text()).toContain('dialog.boop_dialog.header');
    });

    function emojiManagerButton(wrapper) {
        return wrapper
            .findAll('[data-testid="btn"]')
            .find((button) => button.text().includes('dialog.boop_dialog.emoji_manager'));
    }

    it('opens the Gallery page over the open dialogs on desktop, as upstream', async () => {
        mocks.isCompact.value = false;
        mocks.boopDialog.value.visible = true;
        mocks.showGalleryPage.mockClear();
        mocks.closeMainDialog.mockClear();
        const wrapper = mount(SendBoopDialog);

        await emojiManagerButton(wrapper).trigger('click');

        expect(mocks.showGalleryPage).toHaveBeenCalledTimes(1);
        expect(mocks.boopDialog.value.visible).toBe(true);
        expect(mocks.closeMainDialog).not.toHaveBeenCalled();
    });

    it('closes the full-screen dialogs before opening the Gallery page on phones', async () => {
        mocks.isCompact.value = true;
        mocks.boopDialog.value.visible = true;
        mocks.showGalleryPage.mockClear();
        mocks.closeMainDialog.mockClear();
        const wrapper = mount(SendBoopDialog);

        await emojiManagerButton(wrapper).trigger('click');

        expect(mocks.boopDialog.value.visible).toBe(false);
        expect(mocks.closeMainDialog).toHaveBeenCalledTimes(1);
        expect(mocks.showGalleryPage).toHaveBeenCalledTimes(1);
        mocks.isCompact.value = false;
    });
});
