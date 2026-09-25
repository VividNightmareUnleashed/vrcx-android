package io.github.vrcxandroid.bridge.appapi

import java.util.regex.Pattern

/**
 * Port of `AppApi.MakeValidFileName` (upstream Dotnet/AppApi/Common/Utils.cs) with the Windows invalid-character sets
 * of .NET (`Path.GetInvalidPathChars()` and `Path.GetInvalidFileNameChars()`), so names stay valid on FAT/exFAT
 * storage and match the files VRCX on Windows produces.
 */
object FileNames {
    private val invalidPathChars: String = buildString {
        append('|')
        append('\u0000')
        for (c in 1..31) append(c.toChar())
    }

    private val invalidFileNameChars: String = buildString {
        append("\"<>|")
        append('\u0000')
        for (c in 1..31) append(c.toChar())
        append(":*?\\/")
    }

    // UNIX_LINES: like .NET, `$` also matches before a final '\n' only.
    private val folderRegex = Pattern.compile("([${classOf(invalidPathChars)}]*\\.+$)|([${classOf(invalidPathChars)}]+)", Pattern.UNIX_LINES)
    private val fileRegex = Pattern.compile("([${classOf(invalidFileNameChars)}]*\\.+$)|([${classOf(invalidFileNameChars)}]+)", Pattern.UNIX_LINES)

    private fun classOf(chars: String): String = buildString {
        for (c in chars) append("\\x{").append(Integer.toHexString(c.code)).append('}')
    }

    fun makeValidFileName(input: String): String {
        var name = input.replace("/", "").replace("\\", "")
        name = folderRegex.matcher(name).replaceAll("")
        name = fileRegex.matcher(name).replaceAll("")
        return name
    }
}
