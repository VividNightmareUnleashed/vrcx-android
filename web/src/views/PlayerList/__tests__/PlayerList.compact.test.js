import { beforeEach, describe, expect, test, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { ref } from 'vue';

const mocks = vi.hoisted(() => ({
    isCompact: require('vue').ref(true),
    currentInstanceWorld: null,
    currentInstanceUsersData: null
}));

vi.mock('pinia', async (importOriginal) => ({ ...(await importOriginal()), storeToRefs: (store) => store }));
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key) => key, locale: require('vue').ref('en') }) }));
vi.mock('../../../composables/useCompactLayout', () => ({
    useCompactLayout: () => ({ isCompact: mocks.isCompact })
}));
vi.mock('../../../stores', () => ({
    useAppearanceSettingsStore: () => ({ randomUserColours: ref(false) }),
    usePhotonStore: () => ({
        photonLoggingEnabled: ref(false),
        chatboxUserBlacklist: ref(new Map()),
        saveChatboxUserBlacklist: vi.fn()
    }),
    useUserStore: () => ({ currentUser: ref({ id: 'usr_00000000-0000-4000-8000-00000000000a', $homeLocation: null }) }),
    useLocationStore: () => ({ lastLocation: ref({ playerList: new Set(), friendList: new Set(), date: null }) }),
    useInstanceStore: () => ({
        currentInstanceLocation: ref({}),
        currentInstanceWorld: mocks.currentInstanceWorld,
        currentInstanceUsersData: mocks.currentInstanceUsersData,
        getCurrentInstanceUserList: vi.fn()
    }),
    useGalleryStore: () => ({ showFullscreenImageDialog: vi.fn() })
}));
vi.mock('../../../coordinators/userCoordinator', () => ({ showUserDialog: vi.fn(), lookupUser: vi.fn() }));
vi.mock('../../../coordinators/worldCoordinator', () => ({ showWorldDialog: vi.fn() }));
vi.mock('../../../composables/useUserDisplay', () => ({ useUserDisplay: () => ({ userImage: () => '' }) }));
vi.mock('../../../lib/table/useVrcxVueTable', () => ({
    useVrcxVueTable: () => ({
        table: { setOptions: vi.fn(), getColumn: () => null, getRowModel: () => ({ rows: [] }) }
    })
}));
vi.mock('../columns.jsx', () => ({ createColumns: () => [] }));
vi.mock('../../../shared/utils', () => ({
    commaNumber: (value) => String(value ?? ''),
    formatDateFilter: (value, format) => `${format}:${value}`
}));
vi.mock('../../../components/ui/data-table', () => ({
    DataTableLayout: {
        props: ['autoHeight', 'onRowClick'],
        template:
            '<div data-testid="table" :data-auto-height="String(autoHeight)"><slot name="toolbar" /><slot name="empty" /></div>'
    }
}));
vi.mock('../../../components/ui/badge', () => ({ Badge: { template: '<span><slot /></span>' } }));
vi.mock('../../../components/ui/tooltip', () => ({ TooltipWrapper: { template: '<div><slot /></div>' } }));
vi.mock('../../../components/LocationWorld.vue', () => ({ default: { template: '<div />' } }));
vi.mock('../../../components/Timer.vue', () => ({ default: { template: '<span />' } }));
vi.mock('../components/PhotonEventTable.vue', () => ({ default: { template: '<div />' } }));
vi.mock('../dialogs/ChatboxBlacklistDialog.vue', () => ({
    default: { props: ['chatboxBlacklistDialog'], template: '<div />' }
}));

import PlayerList from '../PlayerList.vue';

const WORLD = {
    ref: {
        id: 'wrld_00000000-0000-4000-8000-000000000001',
        thumbnailImageUrl: 'thumb.png',
        imageUrl: 'image.png',
        name: 'Lantern Harbor',
        authorId: 'usr_00000000-0000-4000-8000-000000000002',
        authorName: 'Preview Studio',
        releaseStatus: 'public',
        description: 'A quiet harbor at dusk.',
        recommendedCapacity: 16,
        capacity: 32,
        created_at: '2022-05-01T14:00:00Z'
    },
    fileAnalysis: { standalonewindows: { created_at: '2026-09-01T10:00:00Z' } },
    isPC: false,
    isQuest: false,
    isIos: false
};

describe('Player List in the phone layout', () => {
    beforeEach(() => {
        mocks.isCompact.value = true;
        mocks.currentInstanceWorld = ref(JSON.parse(JSON.stringify(WORLD)));
        mocks.currentInstanceUsersData = ref([
            { ref: { id: 'usr_00000000-0000-4000-8000-000000000003' }, isFriend: true },
            { ref: { id: 'usr_00000000-0000-4000-8000-000000000004' }, isChatBoxMuted: true }
        ]);
    });

    test('stacks the header: image beside the name, then location, description and a stats grid', () => {
        const wrapper = mount(PlayerList);
        const header = wrapper.find('[data-layout="compact"]');

        expect(header.exists()).toBe(true);
        expect(header.attributes('style')).toBeUndefined();
        expect(header.classes()).toEqual(
            expect.arrayContaining(['compact:grid', 'compact:grid-cols-[auto_minmax(0,1fr)]'])
        );
        expect(wrapper.find('img').attributes('style')).toContain('width: 120px');
    });

    test('exact dates in the stats grid wrap instead of being cut off', () => {
        const wrapper = mount(PlayerList);
        const values = wrapper.findAll('span.text-xs').filter((span) => /long:/.test(span.text()));

        expect(values.map((span) => span.text())).toEqual(['long:2026-09-01T10:00:00Z', 'long:2022-05-01T14:00:00Z']);
        for (const value of values) {
            expect(value.classes()).toEqual(
                expect.arrayContaining(['compact:whitespace-normal', 'compact:break-words'])
            );
        }
    });

    test('the player cards fill the page: the table wrapper becomes a block and the list takes its height', () => {
        const wrapper = mount(PlayerList);
        const tableWrapper = wrapper.find('[data-testid="player-list-table"]');

        expect(tableWrapper.classes()).toEqual(expect.arrayContaining(['compact:block', 'compact:flex-none']));
        expect(wrapper.find('[data-testid="table"]').attributes('data-auto-height')).toBe('false');
    });

    test('the icon legend explains the icons on screen', () => {
        const wrapper = mount(PlayerList);
        const legend = wrapper.find('[data-testid="player-icon-legend"]');

        expect(legend.exists()).toBe(true);
        expect(legend.text()).toContain('android.main_views.player_icons.friend');
        expect(legend.text()).toContain('android.main_views.player_icons.chatbox_muted');
        expect(legend.text()).not.toContain('android.main_views.player_icons.master');
    });

    test('PC keeps its header, auto-height table and no legend', () => {
        mocks.isCompact.value = false;
        const wrapper = mount(PlayerList);

        expect(wrapper.find('[data-layout="compact"]').exists()).toBe(false);
        expect(wrapper.find('[data-testid="table"]').attributes('data-auto-height')).toBe('true');
        expect(wrapper.find('[data-testid="player-icon-legend"]').exists()).toBe(false);
        expect(wrapper.find('img').attributes('style')).toContain('width: 160px');
    });
});
