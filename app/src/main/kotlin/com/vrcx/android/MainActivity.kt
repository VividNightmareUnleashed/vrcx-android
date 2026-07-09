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
import com.vrcx.android.ui.VrcxApp
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BootReconnectWorker.cancel(this)
        NotificationHelper(this).cancelBootReconnectRequired()
        // Normalize incoming deep-link intents BEFORE setContent so
        // NavController sees the canonical form on first composition.
        normalizeDeepLinkIntent(intent)
        enableEdgeToEdge()
        requestNotificationPermission()
        setContent {
            VrcxApp()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * Collapse VRChat web URLs with arbitrary trailing path segments down to
     * the canonical `/home/{section}/{id}` form that the NavGraph's deep-link
     * pattern matches.
     *
     * AndroidManifest registers `pathPrefix="/home/user/"`,
     * `"/home/world/"`, `"/home/avatar/"`, and `"/home/group/"`, so the OS
     * routes any URL under those prefixes to us — including deeper nests like
     * `/home/group/{id}/posts/{postId}/comments/{commentId}`. Navigation's
     * `{arg}` placeholders only match a single path segment, so without this
     * normalization the extra tail segments kill route matching and the user
     * ends up on the default screen. Rewriting the intent data in place here
     * keeps the NavGraph declarations simple and handles any depth of tail
     * we'll encounter now or in the future.
     *
     * Non-VRChat URLs (including our own `vrcx://` scheme, which is already
     * well-formed) pass through untouched.
     */
    private fun normalizeDeepLinkIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        if (uri.scheme != "https" || uri.host != "vrchat.com") return
        val segments = uri.pathSegments
        if (segments.size < 3 || segments[0] != "home") return
        val section = segments[1]
        if (section !in KNOWN_DETAIL_SECTIONS) return
        val id = segments[2]
        if (id.isEmpty()) return
        // Build a canonical URL that matches the single-segment pattern.
        intent.data = Uri.parse("https://vrchat.com/home/$section/$id")
    }

    private companion object {
        val KNOWN_DETAIL_SECTIONS = setOf("user", "world", "avatar", "group")
    }
}
