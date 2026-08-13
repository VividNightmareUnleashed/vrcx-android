package com.vrcx.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.vrcx.android.service.BootReconnectWorker
import com.vrcx.android.service.NotificationHelper
import com.vrcx.android.service.WebSocketForegroundService
import com.vrcx.android.ui.VrcxApp
import com.vrcx.android.ui.navigation.DeepLinkSection
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Normalize incoming deep-link intents BEFORE setContent so
        // NavController sees the canonical form on first composition.
        normalizeDeepLinkIntent(intent)
        enableEdgeToEdge()
        requestNotificationPermission()
        setContent {
            VrcxApp()
        }
        // A WorkManager round trip plus a binder call, for a worker and a
        // notification that are usually not even there. Nothing in the first
        // composition depends on either, so they come after it.
        BootReconnectWorker.cancel(this)
        NotificationHelper(this).cancelBootReconnectRequired()
    }

    /**
     * The activity is `singleTop`, so a notification tap or a browser link that
     * arrives while we are already running lands here instead of stacking a
     * second app shell on top of the first.
     *
     * Normalize before [setIntent] so everything downstream sees the canonical
     * form — including the listener `VrcxApp` registers to hand the link to the
     * live NavController, which `super` dispatches to.
     */
    override fun onNewIntent(intent: Intent) {
        normalizeDeepLinkIntent(intent)
        setIntent(intent)
        super.onNewIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        WebSocketForegroundService.restartAfterTimeoutIfNeeded(this)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun normalizeDeepLinkIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        intent.data = normalizeVrchatDeepLink(uri) ?: return
    }
}

/**
 * Collapse VRChat web URLs with arbitrary trailing path segments down to the
 * canonical `/home/{section}/{id}` form that the NavGraph's deep-link pattern
 * matches. Returns null when the URL needs no rewriting.
 *
 * AndroidManifest registers `pathPrefix="/home/user/"`, `"/home/world/"`,
 * `"/home/avatar/"`, and `"/home/group/"`, so the OS routes any URL under those
 * prefixes to us — including deeper nests like
 * `/home/group/{id}/posts/{postId}/comments/{commentId}`. Navigation's `{arg}`
 * placeholders only match a single path segment, so without this normalization
 * the extra tail segments kill route matching and the user ends up on the
 * default screen.
 *
 * Non-VRChat URLs — including our own `vrcx://` scheme, which is already
 * well-formed — are left alone.
 */
internal fun normalizeVrchatDeepLink(uri: Uri): Uri? {
    if (uri.scheme != "https" || uri.host != DeepLinkSection.WEB_HOST) return null
    val segments = uri.pathSegments
    if (segments.size < 3 || segments[0] != "home") return null
    val section = DeepLinkSection.fromSegment(segments[1]) ?: return null
    val id = segments[2]
    if (id.isEmpty()) return null
    // pathSegments hands back percent-DECODED text. Rebuilding by string
    // interpolation would turn an id carrying an encoded '?' or '/' into
    // structure — a query, or two segments the route pattern can't match — so
    // let the builder put the encoding back.
    return Uri.Builder()
        .scheme("https")
        .authority(DeepLinkSection.WEB_HOST)
        .appendPath("home")
        .appendPath(section.segment)
        .appendPath(id)
        .build()
}
