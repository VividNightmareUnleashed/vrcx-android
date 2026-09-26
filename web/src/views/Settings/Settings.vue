<template>
    <div class="x-container flex w-full flex-col compact:w-auto">
        <!-- Phones: the page card keeps its 6px frame margins (w-full would push its right edge off screen). -->
        <div class="mx-auto flex w-full max-w-2xl flex-col">
            <div class="shrink-0 p-1.5">
                <span class="text-lg font-semibold text-foreground">{{ t('view.settings.header') }}</span>
            </div>
            <TabsUnderline
                v-model="activeTab"
                default-value="system"
                :items="settingsTabs"
                :unmount-on-hide="false"
                fill
                @update:model-value="onTabChange">
                <template #system>
                    <SystemTab />
                </template>
                <template v-if="isAndroid" #companion>
                    <CompanionSettingsTab />
                </template>
                <template #interface>
                    <InterfaceTab />
                </template>
                <template #social>
                    <SocialTab />
                </template>
                <template #notifications>
                    <NotificationsTab />
                </template>
                <template v-if="hasVrOverlay" #vr>
                    <VrTab />
                </template>
                <template #media>
                    <MediaTab />
                </template>
                <template #integrations>
                    <IntegrationsTab />
                </template>
                <template #advanced>
                    <AdvancedTab />
                </template>
            </TabsUnderline>
        </div>
    </div>
</template>

<script setup>
    import { computed, defineAsyncComponent, onBeforeMount, ref, watch } from 'vue';
    import { useRoute, useRouter } from 'vue-router';
    import { TabsUnderline } from '@/components/ui/tabs';
    import { useI18n } from 'vue-i18n';

    import { buildSettingsTabs, resolveSettingsTab } from './settingsTabs';
    import { hasVrOverlay, isAndroid } from '../../shared/utils/platform';

    import AdvancedTab from './components/Tabs/AdvancedTab.vue';
    import InterfaceTab from './components/Tabs/InterfaceTab.vue';
    import IntegrationsTab from './components/Tabs/IntegrationsTab.vue';
    import MediaTab from './components/Tabs/MediaTab.vue';
    import NotificationsTab from './components/Tabs/NotificationsTab.vue';
    import SocialTab from './components/Tabs/SocialTab.vue';
    import SystemTab from './components/Tabs/SystemTab.vue';
    import VrTab from './components/Tabs/VrTab.vue';

    // The Android-only tab is loaded only where it is shown.
    const CompanionSettingsTab = isAndroid
        ? defineAsyncComponent(() => import('../../platform/android/components/settings/CompanionSettingsTab.vue'))
        : null;

    const { t } = useI18n();
    const route = useRoute();
    const router = useRouter();

    const settingsTabs = computed(() => buildSettingsTabs(t));

    // `?tab=<value>` opens a specific tab, for example `?tab=companion` from the companion empty states.
    const activeTab = ref(resolveSettingsTab(route?.query?.tab, settingsTabs.value));

    watch(
        () => route?.query?.tab,
        (tab) => {
            if (tab) {
                activeTab.value = resolveSettingsTab(tab, settingsTabs.value);
            }
        }
    );

    /**
     * @param {string} value
     */
    function onTabChange(value) {
        // Drop a stale `?tab=` so the same link opens that tab again next time.
        if (route?.query?.tab && route.query.tab !== value) {
            const { tab: _tab, ...query } = route.query;
            router?.replace({ query });
        }
    }

    onBeforeMount(() => {
        const menuItem = document.querySelector('li[role="menuitem"].is-active');

        if (menuItem) {
            menuItem.classList.remove('is-active');
        }
    });
</script>
