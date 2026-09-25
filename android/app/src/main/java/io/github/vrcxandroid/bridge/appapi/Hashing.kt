package io.github.vrcxandroid.bridge.appapi

import io.github.vrcxandroid.bridge.DotNetException
import java.security.MessageDigest
import java.util.Base64

/** Byte-exact ports of the small hashing helpers of upstream Dotnet/AppApi/Common (AppApiCommon.cs, ImageSaving.cs). */
object Hashing {
    /** `GetColourFromUserID`: MD5 over the UTF-8 bytes, `(hash[3] << 8) | hash[4]` with unsigned bytes. */
    fun colourFromUserId(userId: String): Int {
        val hash = MessageDigest.getInstance("MD5").digest(userId.toByteArray(Charsets.UTF_8))
        return ((hash[3].toInt() and 0xFF) shl 8) or (hash[4].toInt() and 0xFF)
    }

    /**
     * `GetColourBulk`: `[userId, hue]` pairs in input order. Like the .NET `Dictionary.Add` it rejects a duplicate id.
     */
    fun colourBulk(userIds: List<String>): List<Pair<String, Int>> {
        val seen = HashSet<String>()
        return userIds.map { id ->
            if (!seen.add(id)) {
                throw DotNetException("ArgumentException", "An item with the same key has already been added. Key: $id")
            }
            id to colourFromUserId(id)
        }
    }

    /** `MD5File`: base64(MD5(base64decode(blob))). */
    fun md5File(blob: String): String =
        encodeBase64(MessageDigest.getInstance("MD5").digest(decodeBase64(blob)))

    /** `FileLength`: decimal byte count of the decoded blob, as a string. */
    fun fileLength(blob: String): String = decodeBase64(blob).size.toString()

    /** `SignFile`: base64 of the librsync BLAKE2 signature of the decoded blob. */
    fun signFile(blob: String): String = encodeBase64(RsyncSignature.compute(decodeBase64(blob)))

    fun encodeBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /**
     * `Convert.FromBase64String`: white space (space, tab, CR, LF) is ignored; anything else that is not valid base64
     * throws a FormatException with the .NET message.
     */
    fun decodeBase64(blob: String): ByteArray {
        val clean = if (blob.any { it == ' ' || it == '\t' || it == '\r' || it == '\n' }) {
            blob.filterNot { it == ' ' || it == '\t' || it == '\r' || it == '\n' }
        } else {
            blob
        }
        if (clean.length % 4 != 0) throw invalidBase64()
        return try {
            Base64.getDecoder().decode(clean)
        } catch (e: IllegalArgumentException) {
            throw invalidBase64()
        }
    }

    private fun invalidBase64() = DotNetException(
        "FormatException",
        "The input is not a valid Base-64 string as it contains a non-base 64 character, more than two padding " +
            "characters, or an illegal character among the padding characters.",
    )
}
