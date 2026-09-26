package io.github.vrcxandroid.bridge

/**
 * Logging helpers for data that may hold secrets (docs/ARCHITECTURE.md §6: logs end up in bug reports). Exception
 * messages there can quote their input: kotlinx.serialization adds a `JSON input: ...` excerpt (cookie values, the
 * bridge arguments of a login call, VRCX.json with the proxy credentials), URISyntaxException the whole proxy URI.
 * Such paths log the exception class name, or a [redacted] copy that keeps the stack frames but no message.
 */
object SafeLog {
    /** `(ClassName)` for a log line. */
    fun kind(t: Throwable): String = "(${t.javaClass.simpleName})"

    /** A copy of [t] and its causes with class names and stack frames only; no message is kept. */
    fun redacted(t: Throwable): Throwable = redact(t, 0)

    private fun redact(t: Throwable, depth: Int): Throwable {
        val cause = t.cause?.takeIf { it !== t && depth < MAX_CAUSES }?.let { redact(it, depth + 1) }
        return Redacted(t.javaClass.name, cause).also { it.stackTrace = t.stackTrace }
    }

    private class Redacted(private val className: String, cause: Throwable?) : Throwable(className, cause) {
        override fun toString(): String = className
    }

    private const val MAX_CAUSES = 8
}
