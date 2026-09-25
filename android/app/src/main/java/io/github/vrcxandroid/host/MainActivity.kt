package io.github.vrcxandroid.host

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.vrcxandroid.BuildConfig

/**
 * The WebView host Activity (docs/ARCHITECTURE.md §6). `singleTask`, handles its own configuration changes, draws
 * edge-to-edge and sends the insets to the page. It attaches the app-scoped WebView and detaches it again without
 * destroying it, so the page (and its VRChat websocket) keeps running while the Activity is gone.
 */
class MainActivity : ComponentActivity() {
    /** Used by [ActivityPickers]; registered in every instance so results survive recreation. */
    val resultLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { ActivityPickers.onActivityResult(it) }
    val permissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ActivityPickers.onPermissionResult(this, it) }

    private var hasWebView = false

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val webView = WebViewHolder.current
            if (!hasWebView || webView == null || !VrcxHost.events.isPageConnected) {
                moveTaskToBack(true)
                return
            }
            // DESIGN.md §6: the page decides; false means "nothing to close" and the app goes to the background.
            webView.evaluateJavascript(BACK_SCRIPT) { result ->
                if (result != "true") moveTaskToBack(true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HostTheme.applyStartup(this)
        VrcxHost.onActivityCreated(this)
        onBackPressedDispatcher.addCallback(this, backCallback)

        val gate = WebViewGate.check(this)
        if (!gate.ok) {
            setContentView(WebViewGate.view(this, gate))
            return
        }
        val root = FrameLayout(this)
        setContentView(root)
        WebViewHolder.attach(this, root)
        hasWebView = true
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            VrcxHost.updateInsets(
                InsetsPayload.fromPixels(bars.top, bars.right, bars.bottom, bars.left, ime.bottom, resources.displayMetrics.density),
            )
            // The page pads its own chrome from the `insets` event; the WebView itself fills the window.
            WindowInsetsCompat.CONSUMED
        }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        HostTheme.onSystemNightChanged(this)
    }

    override fun onStart() {
        super.onStart()
        VrcxHost.onActivityStarted(this)
    }

    override fun onResume() {
        super.onResume()
        // electron.onBrowserFocus (refreshes the VRChat status banner when stale).
        VrcxHost.emit("focus")
    }

    override fun onStop() {
        VrcxHost.onActivityStopped(this)
        super.onStop()
    }

    override fun onDestroy() {
        if (hasWebView) WebViewHolder.detach(this)
        VrcxHost.onActivityDestroyed(this)
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val command = LaunchCommands.fromIntent(intent.action, intent.dataString, intent.getCharSequenceExtra(Intent.EXTRA_TEXT))
        if (command != null) VrcxHost.deliverLaunchCommand(command)
        if (BuildConfig.DEBUG) DebugSelfTest.onIntent(intent)
    }

    companion object {
        private const val BACK_SCRIPT =
            "(function(){try{var a=window.__vrcxAndroid;return !!(a&&typeof a.handleBack==='function'&&a.handleBack());}catch(e){return false;}})()"
    }
}
