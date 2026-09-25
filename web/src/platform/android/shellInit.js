// Phone shell: compact-layout class, back handler, focus guard, touch helpers.
import './mobile.css';

import { useCompactLayout } from '../../composables/useCompactLayout';
import { createBackHandler, registerBackHandler } from './shell/backHandler';
import { installFocusGuard } from './shell/focusGuard';
import { installImeTracking } from './shell/insets';
import { closeShellPanels, shellState } from './shell/shellState';

/**
 * @param {import('vue').App} app
 */
export async function initAndroidShell(app) {
    const root = document.documentElement;
    // The shim adds it at document start; repeat it so the dev preview harness and old shims behave the same.
    root.classList.add('is-android');

    // Creates the single shared media-query listeners and keeps html.vrcx-compact / vrcx-compact-landscape /
    // vrcx-coarse in sync for the life of the page.
    const { isCompact, isCoarsePointer } = useCompactLayout();

    installFocusGuard({ isActive: () => isCompact.value || isCoarsePointer.value });
    installImeTracking();

    const router = app.config.globalProperties.$router;
    registerBackHandler(
        createBackHandler({
            getRouter: () => router,
            shell: shellState,
            closeShellPanels
        })
    );

    if (isCompact.value) {
        // Warm the phone frame chunk while the app is still logging in, so the shell renders without a gap.
        import('../../views/Layout/CompactFrame.vue').catch(() => {});
    }
}
