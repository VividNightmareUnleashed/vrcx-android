package com.vrcx.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.vrcx.android.ui.navigation.DeepLinkSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainActivityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a deep nest collapses to the single segment the route pattern matches`() {
        assertEquals(
            Uri.parse("https://vrchat.com/home/group/grp_x"),
            normalizeVrchatDeepLink(
                Uri.parse("https://vrchat.com/home/group/grp_x/posts/p1/comments/c1"),
            ),
        )
    }

    @Test
    fun `an ordinary id is rebuilt byte-identically`() {
        // If the rebuild changed a single character, every existing web link
        // would stop matching its destination.
        val original = "https://vrchat.com/home/user/usr_9f3e1b2c-0000-4a11-8bcd-1234567890ab"
        assertEquals(original, normalizeVrchatDeepLink(Uri.parse(original)).toString())
    }

    @Test
    fun `URLs that need no rewriting are left alone`() {
        val untouched = listOf(
            // Our own scheme is already canonical.
            "vrcx://user/usr_x",
            "vrcx://group/grp_x/posts/p1",
            // Too few segments to carry an id.
            "https://vrchat.com/home",
            "https://vrchat.com/home/user",
            // A section we have no destination for.
            "https://vrchat.com/home/instance/wrld_x",
            // Not under /home at all.
            "https://vrchat.com/download/user/usr_x",
            "https://example.com/home/user/usr_x",
        )
        for (url in untouched) {
            assertNull("$url must not be rewritten", normalizeVrchatDeepLink(Uri.parse(url)))
        }
    }

    @Test
    fun `an encoded separator in the id stays inside one path segment`() {
        // getPathSegments decodes, so rebuilding by interpolation would turn
        // this into four segments and the deep link would match nothing.
        val rebuilt = normalizeVrchatDeepLink(
            Uri.parse("https://vrchat.com/home/user/usr_victim%2Fx"),
        )
        assertNotNull(rebuilt)
        assertEquals(listOf("home", "user", "usr_victim/x"), rebuilt!!.pathSegments)
        assertEquals("https://vrchat.com/home/user/usr_victim%2Fx", rebuilt.toString())
    }

    @Test
    fun `an encoded question mark in the id does not become a query`() {
        val rebuilt = normalizeVrchatDeepLink(
            Uri.parse("https://vrchat.com/home/user/usr_victim%3Fx%3Dy"),
        )
        assertNotNull(rebuilt)
        assertNull("the id must not turn into structure", rebuilt!!.query)
        assertEquals(3, rebuilt.pathSegments.size)
        assertEquals("usr_victim?x=y", rebuilt.pathSegments[2])
    }

    @Test
    fun `MainActivity is singleTop so a notification tap cannot stack a second shell`() {
        val info = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java),
            0,
        )
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, info.launchMode)
    }

    @Test
    fun `the manifest routes every deep-link section to us`() {
        // The manifest can't read DeepLinkSection, so adding a section without
        // adding its pathPrefix has to fail here instead of in the field.
        for (section in DeepLinkSection.values()) {
            val urls = listOf(
                "${DeepLinkSection.APP_SCHEME}://${section.segment}/id_1",
                "https://${DeepLinkSection.WEB_HOST}/home/${section.segment}/id_1",
            )
            for (url in urls) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                val targets = context.packageManager
                    .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                    .map { it.activityInfo.name }
                assertTrue("$url is not routed to MainActivity", MainActivity::class.java.name in targets)
            }
        }
    }
}
