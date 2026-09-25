package io.github.vrcxandroid.bridge.appapi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.security.MessageDigest

/** Test vectors produced by the upstream .NET code (see src/test/resources/appapi/README.md). */
object Vectors {
    val root: JsonObject by lazy {
        val text = Vectors::class.java.classLoader!!.getResourceAsStream("appapi/vectors.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        Json.parseToJsonElement(text).jsonObject
    }

    val screenshotDir: File by lazy {
        File(Vectors::class.java.classLoader!!.getResource("appapi/screenshots")!!.toURI())
    }

    fun fixture(name: String): ByteArray = File(screenshotDir, name).readBytes()

    /** Copies every fixture into [target]. */
    fun copyFixtures(target: File) {
        target.mkdirs()
        screenshotDir.listFiles()!!.forEach { it.copyTo(File(target, it.name), overwrite = true) }
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun pattern(n: Int) = ByteArray(n) { ((it * 7) and 0xFF).toByte() }

    fun lcg(n: Int, seed: Long): ByteArray {
        var s = seed and 0xFFFFFFFFL
        return ByteArray(n) {
            s = (s * 1664525 + 1013904223) and 0xFFFFFFFFL
            (s ushr 24).toByte()
        }
    }
}
