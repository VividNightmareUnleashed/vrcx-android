package io.github.vrcxandroid.companion

/**
 * A copy of this throwable for logcat: same class name, stack trace and cause chain, but no messages. Messages can
 * echo what was being parsed or written (kotlinx `JSON input: ...` excerpts, a proxy URI, log lines), and logcat ends
 * up in bug reports, so the companion client never logs them.
 */
internal fun Throwable.redacted(): Throwable = RedactedThrowable(this, 0)

/** `SimpleName` of this throwable, for one-line log messages. */
internal val Throwable.kind: String get() = javaClass.simpleName.ifEmpty { javaClass.name }

private class RedactedThrowable(original: Throwable, depth: Int) : Exception(original.javaClass.name) {
    private val originalName = original.javaClass.name

    init {
        stackTrace = original.stackTrace
        val cause = original.cause
        if (cause != null && cause !== original && depth < MAX_DEPTH) initCause(RedactedThrowable(cause, depth + 1))
    }

    // The stack trace is the original's; capturing this constructor's would only add noise.
    override fun fillInStackTrace(): Throwable = this

    override fun toString(): String = "$originalName (message omitted)"

    private companion object {
        const val MAX_DEPTH = 8
    }
}
