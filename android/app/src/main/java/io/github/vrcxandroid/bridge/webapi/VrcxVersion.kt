package io.github.vrcxandroid.bridge.webapi

import io.github.vrcxandroid.BuildConfig

/**
 * Upstream `Program.GetVersion()`: `"VRCX <Version>"`, or `"VRCX Nightly <Version>"`
 * when the last `-`-separated segment of the Version file is a 7-character git hash. This is also the exact
 * User-Agent of every native HTTP request (§9.3, C14).
 */
object VrcxVersion {
    fun format(versionFile: String): String {
        val version = versionFile.trim()
        if (version.isEmpty()) return "VRCX Nightly Build"
        return if (version.split('-').last().length == 7) "VRCX Nightly $version" else "VRCX $version"
    }

    /** The string for this build, from `web/Version` via [BuildConfig.VRCX_VERSION]. */
    val current: String by lazy { format(BuildConfig.VRCX_VERSION) }
}
