import { beforeEach, describe, expect, test, vi } from 'vitest';
import { defineComponent, h } from 'vue';
import { mount } from '@vue/test-utils';

const mocks = vi.hoisted(() => ({
    isCompact: { value: true },
    isAndroid: { value: false },
    shiftHeld: { value: false },
    currentUser: { value: { id: 'usr_00000000-0000-4000-8000-00000000000a' } },
    expired: { value: false }
}));

vi.mock('pinia', async (importOriginal) => ({ ...(await importOriginal()), storeToRefs: (store) => store }));
vi.mock('../../../plugins', () => ({ i18n: { global: { t: (key) => key, te: () => false } } }));
vi.mock('../../../stores', () => ({
    useUserStore: () => ({ showSendBoopDialog: vi.fn(), currentUser: mocks.currentUser }),
    useUiStore: () => ({ shiftHeld: mocks.shiftHeld }),
    useLocationStore: () => ({ lastLocation: { value: { location: '' } } }),
    useGameStore: () => ({ isGameRunning: { value: false } }),
    useNotificationStore: () => ({ isNotificationExpired: () => mocks.expired.value }),
    useInstanceStore: () => ({ cachedInstances: new Map() })
}));
vi.mock('../../../composables/useCompactLayout', () => ({
    useCompactLayout: () => ({ isCompact: mocks.isCompact })
}));
vi.mock('../../../shared/utils/platform', () => ({
    get isAndroid() {
        return mocks.isAndroid.value;
    }
}));
vi.mock('../../../shared/utils', () => ({ formatDateFilter: (value, format) => `${format}:${value}` }));
vi.mock('../../../shared/utils/invite', () => ({ checkCanInvite: () => false }));
vi.mock('../../../coordinators/userCoordinator', () => ({ showUserDialog: vi.fn() }));
vi.mock('../../../coordinators/worldCoordinator', () => ({ showWorldDialog: vi.fn() }));
vi.mock('../../../coordinators/groupCoordinator', () => ({ showGroupDialog: vi.fn() }));
vi.mock('../../../components/Location.vue', () => ({ default: { name: 'Location', render: () => null } }));
vi.mock('../../../components/Emoji.vue', () => ({ default: { name: 'Emoji', render: () => null } }));
vi.mock('../../../components/ui/avatar', () => ({
    Avatar: 'Avatar',
    AvatarFallback: 'AvatarFallback',
    AvatarImage: 'AvatarImage'
}));
vi.mock('../../../components/ui/badge', () => ({ Badge: 'Badge' }));
vi.mock('../../../components/ui/button', () => ({
    Button: {
        name: 'Button',
        inheritAttrs: true,
        render() {
            return h('button', { type: 'button' }, this.$slots.default?.());
        }
    }
}));
vi.mock('../../../components/ui/tooltip', () => {
    const passthrough = (name) => ({
        name,
        render() {
            return h('div', { 'data-stub': name }, this.$slots.default?.());
        }
    });
    return {
        Tooltip: passthrough('Tooltip'),
        TooltipContent: passthrough('TooltipContent'),
        TooltipTrigger: passthrough('TooltipTrigger'),
        TooltipWrapper: passthrough('TooltipWrapper')
    };
});

import { createColumns } from '../columns.jsx';

const ME = 'usr_00000000-0000-4000-8000-00000000000a';
const AURORA = 'usr_00000000-0000-4000-8000-000000000001';

function createHandlers() {
    return {
        getNotificationCreatedAt: (row) => row.created_at,
        getNotificationCreatedAtTs: (row) => Date.parse(row.created_at),
        openNotificationLink: vi.fn(),
        showFullscreenImageDialog: vi.fn(),
        getSmallThumbnailUrl: (url) => url,
        acceptFriendRequestNotification: vi.fn(),
        showSendInviteResponseDialog: vi.fn(),
        showSendInviteRequestResponseDialog: vi.fn(),
        acceptRequestInvite: vi.fn(),
        sendNotificationResponse: vi.fn(),
        hideNotification: vi.fn(),
        hideNotificationPrompt: vi.fn(),
        deleteNotificationLog: vi.fn(),
        deleteNotificationLogPrompt: vi.fn()
    };
}

/** Mounts one cell of a column the way the table and the card list render it. */
function mountCell(columns, id, original) {
    const column = columns.find((col) => (col.id ?? col.accessorKey) === id);
    const Cell = defineComponent({
        render: () => h('div', { 'data-cell': id }, [column.cell({ row: { original, getValue: () => undefined } })])
    });
    return mount(Cell);
}

const actionButtons = (wrapper) =>
    wrapper.findAll('[data-testid="notification-compact-actions"] button').map((button) => ({
        action: button.attributes('data-action'),
        label: button.text(),
        classes: button.classes()
    }));

describe('notification cards in the phone layout', () => {
    let handlers;
    let columns;

    beforeEach(() => {
        mocks.isCompact.value = true;
        mocks.isAndroid.value = false;
        mocks.shiftHeld.value = false;
        mocks.expired.value = false;
        handlers = createHandlers();
        columns = createColumns(handlers);
    });

    test('friend requests: a labelled Accept and a Decline set apart, all 40px tall', async () => {
        const original = { id: 'not_1', type: 'friendRequest', senderUserId: AURORA };
        const wrapper = mountCell(columns, 'action', original);
        const buttons = actionButtons(wrapper);

        expect(buttons.map((button) => [button.action, button.label])).toEqual([
            ['accept', 'view.notification.actions.accept'],
            ['decline', 'view.notification.actions.decline']
        ]);
        for (const button of buttons) {
            expect(button.classes).toContain('h-10');
            expect(button.classes).not.toContain('h-8');
        }
        // Decline follows the answers, pushed to the end of the row.
        expect(buttons[1].classes).toContain('ml-auto');
        expect(buttons[0].classes).not.toContain('ml-auto');

        await wrapper.find('[data-action="accept"]').trigger('click');
        expect(handlers.acceptFriendRequestNotification).toHaveBeenCalledWith(original);
    });

    test('invites: decline with message, decline and delete log', async () => {
        const original = { id: 'not_2', type: 'invite', senderUserId: AURORA, details: { worldName: 'Harbor' } };
        const wrapper = mountCell(columns, 'action', original);

        expect(actionButtons(wrapper).map((button) => button.label)).toEqual([
            'view.notification.actions.decline_with_message',
            'view.notification.actions.decline',
            'view.notification.actions.delete_log'
        ]);

        await wrapper.find('[data-action="decline-with-message"]').trigger('click');
        expect(handlers.showSendInviteResponseDialog).toHaveBeenCalledWith(original);
        await wrapper.find('[data-action="decline"]').trigger('click');
        expect(handlers.hideNotificationPrompt).toHaveBeenCalledWith(original);
        expect(handlers.hideNotification).not.toHaveBeenCalled();
    });

    test('responses become labelled buttons', async () => {
        const original = {
            id: 'not_3',
            type: 'boop',
            senderUserId: AURORA,
            responses: [{ type: 'reply', icon: 'reply', text: 'Boop back', data: '' }]
        };
        const wrapper = mountCell(columns, 'action', original);

        expect(actionButtons(wrapper).map((button) => [button.action, button.label])).toEqual([
            ['response:Boop back:reply', 'Boop back'],
            ['delete-log', 'view.notification.actions.delete_log']
        ]);
    });

    test('queue notifications show one Delete log, also once they expired', () => {
        const original = {
            id: 'not_4',
            type: 'group.queueReady',
            senderUserId: 'grp_00000000-0000-4000-8000-000000000001'
        };
        expect(actionButtons(mountCell(columns, 'action', original)).map((button) => button.action)).toEqual([
            'queue-delete-log'
        ]);

        mocks.expired.value = true;
        expect(actionButtons(mountCell(columns, 'action', original)).map((button) => button.action)).toEqual([
            'delete-log'
        ]);
    });

    test('own notifications keep only Delete log', () => {
        const original = { id: 'not_5', type: 'invite', senderUserId: ME };
        expect(actionButtons(mountCell(columns, 'action', original)).map((button) => button.action)).toEqual([
            'delete-log'
        ]);
    });

    test('quick actions (the Shift substitute) turn Decline and Delete log into instant, destructive buttons', async () => {
        mocks.shiftHeld.value = true;
        const original = { id: 'not_6', type: 'invite', senderUserId: AURORA };
        const wrapper = mountCell(columns, 'action', original);
        const buttons = actionButtons(wrapper);

        expect(buttons.find((button) => button.action === 'decline').classes).toContain('text-destructive');
        expect(buttons.find((button) => button.action === 'delete-log').classes).toContain('text-destructive');

        await wrapper.find('[data-action="decline"]').trigger('click');
        expect(handlers.hideNotification).toHaveBeenCalledWith(original);
        await wrapper.find('[data-action="delete-log"]').trigger('click');
        expect(handlers.deleteNotificationLog).toHaveBeenCalledWith(original);
    });

    test('PC keeps its icon buttons, without the phone labels', () => {
        mocks.isCompact.value = false;
        const wrapper = mountCell(columns, 'action', { id: 'not_7', type: 'friendRequest', senderUserId: AURORA });
        expect(wrapper.find('[data-testid="notification-compact-actions"]').exists()).toBe(false);
        expect(wrapper.find('button[aria-label="view.notification.actions.accept"]').exists()).toBe(true);
    });

    test('the date: inline on phones, long-press tooltip on Android tablets, hover tooltip on PC', () => {
        const original = { id: 'not_8', type: 'invite', created_at: '2026-09-26T10:00:00Z' };

        const phone = mountCell(columns, 'created_at', original);
        expect(phone.find('[data-testid="compact-long-date"]').text()).toBe('long:2026-09-26T10:00:00Z');

        mocks.isCompact.value = false;
        mocks.isAndroid.value = true;
        const tablet = mountCell(columns, 'created_at', original);
        expect(tablet.find('[data-stub="TooltipWrapper"]').text()).toBe('short:2026-09-26T10:00:00Z');

        mocks.isAndroid.value = false;
        const pc = mountCell(columns, 'created_at', original);
        expect(pc.find('[data-stub="TooltipWrapper"]').exists()).toBe(false);
        expect(pc.find('[data-stub="TooltipTrigger"]').text()).toBe('short:2026-09-26T10:00:00Z');
        expect(pc.find('[data-stub="TooltipContent"]').text()).toBe('long:2026-09-26T10:00:00Z');
    });
});
