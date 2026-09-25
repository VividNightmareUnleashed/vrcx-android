package io.github.vrcxandroid.host

import android.content.Intent
import android.util.Log
import io.github.vrcxandroid.BuildConfig

/**
 * Debug builds only: a bridge self-test run inside the page, triggered with
 * `adb shell am start -n io.github.vrcxandroid/.host.MainActivity --ez vrcx.selftest true` (add
 * `--ez vrcx.selftest.notify true` to also post a desktop notification through `electron.desktopNotification`, and
 * `--ez vrcx.selftest.back true` to install a back handler that consumes one back press). Results are logged by the
 * page as `VRCX_SELFTEST ...` console lines (logcat tag VRCXWeb).
 */
object DebugSelfTest {
    private const val TAG = "VRCXSelfTest"
    const val EXTRA_RUN = "vrcx.selftest"
    const val EXTRA_NOTIFY = "vrcx.selftest.notify"
    const val EXTRA_BACK = "vrcx.selftest.back"

    private var pending: String? = null

    fun onIntent(intent: Intent) {
        if (!BuildConfig.DEBUG || !intent.getBooleanExtra(EXTRA_RUN, false)) return
        val script = buildScript(intent.getBooleanExtra(EXTRA_NOTIFY, false), intent.getBooleanExtra(EXTRA_BACK, false))
        if (VrcxHost.events.isPageConnected) run(script) else pending = script
    }

    fun onPageConnected() {
        val script = pending ?: return
        pending = null
        // Give the page's module scripts a moment to evaluate before probing.
        VrcxHost.main.postDelayed({ run(script) }, 1500)
    }

    private fun run(script: String) {
        Log.i(TAG, "running the page self-test")
        WebViewHolder.evaluate(script)
    }

    private fun buildScript(notify: Boolean, back: Boolean): String = """
        (async function () {
            const out = {};
            const log = (k, v) => console.log('VRCX_SELFTEST ' + k + ' ' + (typeof v === 'string' ? v : JSON.stringify(v)));
            try {
                out.android = window.ANDROID === true;
                out.linux = window.LINUX === true;
                out.windows = window.WINDOWS === false;
                out.isAndroidClass = document.documentElement.classList.contains('is-android');
                out.electron = typeof window.electron === 'object' && typeof window.electron.getArch === 'function';
                out.arch = await window.electron.getArch();
                out.noUpdater = await window.electron.getNoUpdater();
                out.handleBack = typeof window.__vrcxAndroid.handleBack === 'function';
                out.safeTop = getComputedStyle(document.documentElement).getPropertyValue('--safe-top').trim();
                out.speech = typeof speechSynthesis.getVoices === 'function' && Array.isArray(speechSynthesis.getVoices());
                out.href = location.href;
                out.ua = navigator.userAgent.includes(' VRCX/');
                out.deviceInfo = await window.interopApi.callDotNetMethod('AndroidHost', 'GetDeviceInfo', []);
                out.overlayNoop = (await window.interopApi.callDotNetMethod('AppApiElectron', 'ExecuteVrOverlayFunction', ['x', '{}'])) === undefined;
                try {
                    await window.interopApi.callDotNetMethod('NoSuchClass', 'NoSuchMethod', []);
                    out.missing = 'resolved?!';
                } catch (e) {
                    out.missing = e.message;
                }
                out.backgroundMode = await window.interopApi.callDotNetMethod('AndroidHost', 'GetBackgroundMode', []);
                out.notificationPermission = await window.interopApi.callDotNetMethod('AndroidHost', 'GetNotificationPermission', []);
                out.canLaunchVRChat = await window.interopApi.callDotNetMethod('AndroidHost', 'CanLaunchVRChat', []);
                window.__vrcxAndroid.on('launch-command', (c) => log('launch-command', c));
                window.__vrcxAndroid.on('network-changed', (d) => log('network-changed', d));
                window.__vrcxAndroid.on('visibility', (d) => log('visibility', d));
                ${if (back) "window.__vrcxAndroid.backHandler = () => { log('back', 'handled'); window.__vrcxAndroid.backHandler = null; return true; };" else ""}
                ${if (notify) "await window.electron.desktopNotification('VRCX self-test', 'Posted through window.electron.desktopNotification', ''); out.notified = true;" else ""}
            } catch (e) {
                out.error = String(e && e.stack || e);
            }
            log('result', out);
        })();
    """.trimIndent()
}
