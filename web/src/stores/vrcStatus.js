import { computed, ref, watch } from 'vue';
import { defineStore } from 'pinia';
import { useVrcxStore } from './vrcx';

import { openExternalLink } from '../shared/utils';
import { isAndroid } from '../shared/utils/platform';

import webApiService from '../services/webapi';

import * as workerTimers from 'worker-timers';

export const useVrcStatusStore = defineStore('VrcStatus', () => {
    const vrcxStore = useVrcxStore();

    const vrcStatusApiUrl = 'https://status.vrchat.com/api/v2';
    const fastPollingInterval = ref(2 * 60 * 1000); // 2 minutes
    const slowPollingInterval = ref(15 * 60 * 1000); // 15 minutes

    const lastStatus = ref('');
    const lastStatusIndicator = ref('');
    const lastStatusTime = ref(null);
    const lastStatusSummary = ref('');
    const lastTimeFetched = ref(0);
    const pollingInterval = ref(0);

    const statusText = computed(() => {
        if (lastStatus.value && lastStatusSummary.value) {
            return `${lastStatus.value}: ${lastStatusSummary.value}`;
        }
        return lastStatus.value;
    });

    const hasIssue = computed(() => !!lastStatus.value);

    const isMajor = computed(() => lastStatusIndicator.value === 'major');

    /**
     * @returns {void}
     */
    function openStatusPage() {
        openExternalLink('https://status.vrchat.com');
    }

    /**
     * @returns {Promise<void>}
     */
    async function getVrcStatus() {
        const response = await webApiService.execute({
            url: `${vrcStatusApiUrl}/status.json`,
            method: 'GET',
            headers: {
                Referer: 'https://vrcx.app'
            }
        });
        lastTimeFetched.value = Date.now();
        if (response.status !== 200) {
            console.error('Failed to fetch VRChat status', response);
            lastStatus.value = 'Failed to fetch VRC status';
            pollingInterval.value = fastPollingInterval.value; // 2 minutes
            return;
        }
        const data = JSON.parse(response.data);
        lastStatusTime.value = new Date(data.page.updated_at);
        if (data.status.description === 'All Systems Operational') {
            lastStatus.value = '';
            lastStatusIndicator.value = '';
            pollingInterval.value = slowPollingInterval.value; // 15 minutes
            return;
        }
        lastStatus.value = data.status.description;
        lastStatusIndicator.value = data.status.indicator || '';
        pollingInterval.value = fastPollingInterval.value; // 2 minutes
        getVrcStatusSummary();
    }

    /**
     * @returns {Promise<void>}
     */
    async function getVrcStatusSummary() {
        const response = await webApiService.execute({
            url: `${vrcStatusApiUrl}/summary.json`,
            method: 'GET',
            headers: {
                Referer: 'https://vrcx.app'
            }
        });
        if (response.status !== 200) {
            console.error('Failed to fetch VRChat status summary', response);
            return;
        }
        const data = JSON.parse(response.data);
        let summary = '';
        for (const component of data.components) {
            if (component.status !== 'operational') {
                summary += `${component.name}, `;
            }
        }
        if (summary.endsWith(', ')) {
            summary = summary.slice(0, -2);
        }
        lastStatusSummary.value = summary;
    }

    watch(
        () => vrcxStore.isBrowserFocused,
        (newValue) => {
            if (newValue) {
                if (Date.now() - lastTimeFetched.value > 60 * 1000) {
                    getVrcStatus();
                }
            }
        }
    );

    /**
     * @returns {void}
     */
    function init() {
        // Android: nothing is polled while VRCX runs in the background; the check below and the isBrowserFocused
        // watch catch up once it is open again (docs/ARCHITECTURE.md §7).
        const isBackground = () => isAndroid && document.hidden;
        if (!isBackground()) {
            getVrcStatus();
        }
        workerTimers.setInterval(() => {
            if (isBackground()) {
                return;
            }
            const interval = vrcxStore.isBrowserFocused ? pollingInterval.value : slowPollingInterval.value;
            if (Date.now() - lastTimeFetched.value > interval) {
                getVrcStatus();
            }
        }, fastPollingInterval.value);
    }

    init();

    return {
        lastStatus,
        lastStatusIndicator,
        lastStatusTime,
        lastStatusSummary,
        statusText,
        hasIssue,
        isMajor,
        openStatusPage,
        getVrcStatus
    };
});
