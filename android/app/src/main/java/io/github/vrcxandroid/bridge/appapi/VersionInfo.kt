package io.github.vrcxandroid.bridge.appapi

import java.util.Locale

/** Version and culture strings as upstream formats them. */
object VersionInfo {
    /**
     * Upstream `Program.GetVersion()`: `"VRCX Nightly <version>"` when the last `-` separated segment of the version
     * file is 7 characters long (a git hash), otherwise `"VRCX <version>"`.
     */
    fun versionString(versionFile: String): String {
        val version = versionFile.trim()
        val parts = version.split('-')
        return if (parts.isNotEmpty() && parts.last().length == 7) "VRCX Nightly $version" else "VRCX $version"
    }

    /**
     * BCP-47 tag like a .NET culture name: Unicode/private-use extensions (Android 14 regional preferences such as
     * `-u-mu-celsius`) are dropped; an undetermined locale becomes `en-US` (upstream `CurrentCulture` fallback).
     */
    fun cultureTag(locale: Locale?): String {
        val tag = locale?.toLanguageTag().orEmpty()
        val base = tag.split('-').takeWhile { it.length != 1 }.joinToString("-")
        return if (base.isEmpty() || base == "und") "en-US" else base
    }
}
