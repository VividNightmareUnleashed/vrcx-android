<template>
    <div class="flex flex-col gap-10 py-2">
        <SettingsGroup :title="t('android.companion.title')">
            <template #description>{{ t('android.companion.intro') }}</template>

            <SettingsItem :label="t('android.companion.status_label')" :description="statusText">
                <Spinner v-if="isBusyStatus" class="text-muted-foreground" />
                <span
                    class="inline-block size-2.5 rounded-full shrink-0"
                    :class="statusDotClass"
                    data-testid="companion-status-dot" />
            </SettingsItem>

            <template v-if="companion.isConnected">
                <SettingsItem :label="t('android.companion.vrchat_label')">
                    <span class="text-sm text-muted-foreground">{{
                        companion.vrchatRunning ? t('android.companion.running') : t('android.companion.not_running')
                    }}</span>
                </SettingsItem>
                <SettingsItem :label="t('android.companion.steamvr_label')">
                    <span class="text-sm text-muted-foreground">{{
                        companion.steamVrRunning ? t('android.companion.running') : t('android.companion.not_running')
                    }}</span>
                </SettingsItem>
            </template>

            <SettingsItem
                v-if="companion.syncing"
                :label="t('android.companion.syncing_label')"
                :description="t('android.companion.syncing_description')">
                <Spinner class="text-muted-foreground" />
            </SettingsItem>

            <SettingsItem
                v-if="timeZoneText"
                :label="t('android.companion.time_zone_label')"
                :description="t('android.companion.time_zone_description')">
                <span class="text-sm text-muted-foreground" data-testid="companion-time-zone">{{ timeZoneText }}</span>
            </SettingsItem>

            <div v-if="companion.lastError" class="text-xs text-destructive" data-testid="companion-last-error">
                {{ t('android.companion.last_error_label') }}: {{ companion.lastError }}
            </div>
        </SettingsGroup>

        <SettingsGroup :title="t('android.companion.paired_header')">
            <div v-if="!companion.paired.length" class="text-sm text-muted-foreground">
                {{ t('android.companion.paired_empty') }}
            </div>
            <SettingsItem
                v-for="pc in companion.paired"
                :key="pc.id"
                :label="pc.name || formatCompanionEndpoint(pc)"
                :description="pairedDescription(pc)"
                data-testid="companion-paired-row">
                <Badge v-if="pc.id === companion.activeId" variant="secondary">{{
                    t('android.companion.active')
                }}</Badge>
                <Button
                    v-else
                    size="sm"
                    variant="outline"
                    :disabled="Boolean(companion.pending)"
                    @click="handleSetActive(pc)"
                    >{{ t('android.companion.set_active') }}</Button
                >
                <Button
                    size="sm"
                    variant="outline"
                    :disabled="Boolean(companion.pending)"
                    @click="handleForget(pc)"
                    >{{ t('android.companion.forget') }}</Button
                >
            </SettingsItem>
        </SettingsGroup>

        <SettingsGroup :title="t('android.companion.pair_header')">
            <template #description>{{ t('android.companion.pair_description') }}</template>

            <SettingsItem
                :label="t('android.companion.scan_label')"
                :description="t('android.companion.scan_description')">
                <Button
                    size="sm"
                    variant="outline"
                    :disabled="Boolean(companion.pending)"
                    data-testid="companion-scan"
                    @click="handleScan">
                    <Spinner v-if="companion.pending === 'scan'" />
                    <QrCode v-else />
                    {{ t('android.companion.scan_button') }}
                </Button>
            </SettingsItem>

            <SettingsItem
                :label="t('android.companion.discover_label')"
                :description="t('android.companion.discover_description')">
                <Button
                    size="sm"
                    variant="outline"
                    :disabled="Boolean(companion.pending)"
                    data-testid="companion-discover"
                    @click="handleDiscover">
                    <Spinner v-if="companion.pending === 'discover'" />
                    <Search v-else />
                    {{
                        companion.pending === 'discover'
                            ? t('android.companion.discover_searching')
                            : t('android.companion.discover_button')
                    }}
                </Button>
            </SettingsItem>

            <template v-if="discovered">
                <div v-if="!discovered.length" class="text-sm text-muted-foreground pl-3">
                    {{ t('android.companion.discover_none') }}
                </div>
                <SettingsItem
                    v-for="pc in discovered"
                    :key="`${pc.id}-${pc.host}`"
                    class="pl-3"
                    :label="pc.name || formatCompanionEndpoint(pc)"
                    :description="discoveredDescription(pc)"
                    data-testid="companion-discovered-row">
                    <Button
                        size="sm"
                        variant="outline"
                        :disabled="Boolean(companion.pending)"
                        @click="handlePairDiscovered(pc)"
                        >{{ t('android.companion.pair_button') }}</Button
                    >
                </SettingsItem>
            </template>

            <SettingsItem
                :label="t('android.companion.manual_label')"
                :description="t('android.companion.manual_description')">
                <Button
                    size="sm"
                    variant="outline"
                    :disabled="Boolean(companion.pending)"
                    data-testid="companion-manual"
                    @click="handleManual">
                    <Spinner v-if="companion.pending === 'pair'" />
                    {{ t('android.companion.manual_button') }}
                </Button>
            </SettingsItem>
        </SettingsGroup>

        <SettingsGroup :title="t('android.companion.data_header')">
            <template #description>{{ t('android.companion.data_description') }}</template>

            <SettingsItem
                :label="t('android.companion.import_label')"
                :description="t('android.companion.import_description')">
                <Button size="sm" variant="outline" :disabled="dataBusy" data-testid="companion-import" @click="handleImport">
                    {{ t('android.companion.import_button') }}
                </Button>
            </SettingsItem>

            <SettingsItem
                :label="t('android.companion.export_label')"
                :description="t('android.companion.export_description')">
                <Button size="sm" variant="outline" :disabled="dataBusy" data-testid="companion-export" @click="handleExport">
                    {{ t('android.companion.export_button') }}
                </Button>
            </SettingsItem>
        </SettingsGroup>
    </div>
</template>

<script setup>
    import { computed, ref } from 'vue';
    import { QrCode, Search } from 'lucide-vue-next';
    import { toast } from 'vue-sonner';
    import { useI18n } from 'vue-i18n';

    import { Badge } from '@/components/ui/badge';
    import { Button } from '@/components/ui/button';
    import { Spinner } from '@/components/ui/spinner';
    import { useModalStore } from '@/stores/modal';
    import { formatDateFilter } from '@/shared/utils';
    import { getAndroidHost, parseBridgeError } from '@/shared/utils/platform';
    import SettingsGroup from '@/views/Settings/components/SettingsGroup.vue';
    import SettingsItem from '@/views/Settings/components/SettingsItem.vue';

    import {
        companionStatusKey,
        formatCompanionEndpoint,
        formatCompanionTimeZone,
        isCancelledError,
        isValidPairingCode,
        parseCompanionAddress,
        useCompanionStore
    } from '../../companionStore.js';

    const { t } = useI18n();
    const companion = useCompanionStore();
    const modalStore = useModalStore();

    /** @type {import('vue').Ref<null | Array<{id:string,name:string,host:string,port:number,fp:string,pairing:boolean}>>} */
    const discovered = ref(null);
    const dataBusy = ref(false);

    const statusText = computed(() => {
        const name = companion.machineName || companion.activePc?.name || '';
        return t(companionStatusKey({ status: companion.status }), { name });
    });

    const isBusyStatus = computed(() => companion.status === 'connecting' || companion.status === 'searching');

    const statusDotClass = computed(() => {
        switch (companion.status) {
            case 'connected':
                return 'bg-status-online';
            case 'connecting':
            case 'searching':
                return 'bg-status-askme';
            case 'error':
                return 'bg-destructive';
            default:
                return 'bg-status-offline-alt';
        }
    });

    const timeZoneText = computed(() => formatCompanionTimeZone(companion.tz));

    /**
     * @param {{ hosts?: string[], port?: number, lastSeen?: number }} pc
     */
    function pairedDescription(pc) {
        const endpoint = formatCompanionEndpoint(pc);
        const seen = pc.lastSeen
            ? t('android.companion.last_seen', { time: formatDateFilter(pc.lastSeen, 'long') })
            : t('android.companion.never_seen');
        return endpoint ? `${endpoint} · ${seen}` : seen;
    }

    /**
     * @param {{ host: string, port: number, pairing: boolean }} pc
     */
    function discoveredDescription(pc) {
        const state = pc.pairing ? t('android.companion.pairing_open') : t('android.companion.pairing_closed');
        return `${formatCompanionEndpoint(pc)} · ${state}`;
    }

    /**
     * @param {{ ok: boolean, cancelled?: boolean, error?: { key: string, detail: string } }} outcome
     * @param {string} [successMessage]
     */
    function report(outcome, successMessage) {
        if (outcome.ok) {
            if (successMessage) toast.success(successMessage);
            return;
        }
        if (outcome.cancelled || !outcome.error) return;
        toast.error(t(outcome.error.key, { detail: outcome.error.detail }));
    }

    function pairedToast() {
        const name = companion.machineName || companion.activePc?.name || '';
        return t('android.companion.paired_toast', { name });
    }

    async function handleScan() {
        const outcome = await companion.scanQr();
        report(outcome, outcome.ok ? pairedToast() : '');
    }

    async function handleDiscover() {
        const outcome = await companion.discover();
        if (outcome.ok) {
            discovered.value = outcome.result ?? [];
            return;
        }
        report(outcome);
    }

    /**
     * Asks for the pairing code shown on the PC.
     *
     * @param {string} name
     * @returns {Promise<string | null>}
     */
    async function promptPairingCode(name) {
        const { ok, value } = await modalStore
            .prompt({
                title: t('android.companion.code_prompt_title', { name }),
                description: t('android.companion.code_prompt_description'),
                confirmText: t('android.companion.pair_button'),
                cancelText: t('android.companion.cancel'),
                inputValue: '',
                pattern: /^\s*[0-9A-Za-z]{5}[\s-]?[0-9A-Za-z]{5}\s*$/,
                errorMessage: t('android.companion.code_invalid')
            })
            .catch(() => ({ ok: false, value: '' }));
        if (!ok) return null;
        if (!isValidPairingCode(value)) {
            toast.error(t('android.companion.code_invalid'));
            return null;
        }
        return value;
    }

    /**
     * @param {{ host: string, port: number, fp?: string, id?: string, name?: string }} target
     */
    async function pairWith(target) {
        const code = await promptPairingCode(target.name || formatCompanionEndpoint(target));
        if (!code) return;
        const outcome = await companion.pair(target, code);
        report(outcome, outcome.ok ? pairedToast() : '');
        if (outcome.ok) {
            discovered.value = null;
        }
    }

    /**
     * @param {{ id: string, name: string, host: string, port: number, fp: string }} pc
     */
    function handlePairDiscovered(pc) {
        return pairWith({ host: pc.host, port: pc.port, fp: pc.fp, id: pc.id, name: pc.name });
    }

    async function handleManual() {
        const { ok, value } = await modalStore
            .prompt({
                title: t('android.companion.address_prompt_title'),
                description: t('android.companion.address_prompt_description'),
                confirmText: t('android.companion.continue'),
                cancelText: t('android.companion.cancel'),
                inputValue: '',
                errorMessage: t('android.companion.address_invalid')
            })
            .catch(() => ({ ok: false, value: '' }));
        if (!ok) return;
        const address = parseCompanionAddress(value);
        if (!address) {
            toast.error(t('android.companion.address_invalid'));
            return;
        }
        await pairWith(address);
    }

    /**
     * @param {{ id: string, name: string }} pc
     */
    async function handleSetActive(pc) {
        report(await companion.setActive(pc.id));
    }

    /**
     * @param {{ id: string, name: string }} pc
     */
    async function handleForget(pc) {
        const { ok } = await modalStore
            .confirm({
                title: t('android.companion.forget_confirm_title', { name: pc.name || '' }),
                description: t('android.companion.forget_confirm_description'),
                confirmText: t('android.companion.forget'),
                cancelText: t('android.companion.cancel')
            })
            .catch(() => ({ ok: false }));
        if (!ok) return;
        report(await companion.forget(pc.id));
    }

    async function handleImport() {
        const { ok } = await modalStore
            .confirm({
                title: t('android.companion.import_confirm_title'),
                description: t('android.companion.import_confirm_description'),
                confirmText: t('android.companion.import_confirm'),
                cancelText: t('android.companion.cancel')
            })
            .catch(() => ({ ok: false }));
        if (!ok) return;
        const host = getAndroidHost();
        if (!host) return;
        dataBusy.value = true;
        try {
            // On success native restarts the app; the result only matters when nothing was imported.
            const result = await host.ImportDatabase();
            if (result && result.ok === false && result.message) {
                toast.error(t('android.companion.import_failed', { message: result.message }));
            }
        } catch (error) {
            if (!isCancelledError(error)) {
                toast.error(t('android.companion.import_failed', { message: parseBridgeError(error).detail }));
            }
        } finally {
            dataBusy.value = false;
        }
    }

    async function handleExport() {
        const host = getAndroidHost();
        if (!host) return;
        dataBusy.value = true;
        try {
            if ((await host.ExportDatabase()) === true) {
                toast.success(t('android.companion.export_done'));
            }
        } catch (error) {
            if (!isCancelledError(error)) {
                toast.error(t('android.companion.export_failed', { message: parseBridgeError(error).detail }));
            }
        } finally {
            dataBusy.value = false;
        }
    }
</script>
