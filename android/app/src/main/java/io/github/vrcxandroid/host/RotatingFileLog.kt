package io.github.vrcxandroid.host

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.concurrent.Executor

/**
 * Small append-only text log with size-based rotation (`web.log`, `web.log.1`, ... `web.log.<maxFiles-1>`), written on
 * [executor] so callers (the WebView console callback on the main thread) never touch the disk. The file stays open
 * between writes and is flushed after every line; a write error disables the log instead of throwing.
 */
class RotatingFileLog(
    private val dir: File,
    private val name: String,
    private val maxBytes: Long = 1_000_000,
    private val maxFiles: Int = 3,
    private val executor: Executor,
) {
    private var writer: Writer? = null
    private var size = 0L
    @Volatile
    private var broken = false

    fun append(line: String) {
        if (broken) return
        val text = (if (line.length > MAX_LINE) line.take(MAX_LINE) + "…" else line) + "\n"
        executor.execute { write(text) }
    }

    /** Current log file (for sharing or tests). */
    val file: File get() = File(dir, name)

    private fun write(text: String) {
        try {
            val bytes = text.toByteArray(Charsets.UTF_8).size
            if (writer == null) open()
            if (size > 0 && size + bytes > maxBytes) {
                rotate()
            }
            writer!!.write(text)
            writer!!.flush()
            size += bytes
        } catch (_: Exception) {
            broken = true
            runCatching { writer?.close() }
            writer = null
        }
    }

    private fun open() {
        dir.mkdirs()
        val f = file
        size = if (f.exists()) f.length() else 0L
        writer = OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8)
    }

    private fun rotate() {
        runCatching { writer?.close() }
        writer = null
        for (i in maxFiles - 1 downTo 1) {
            val src = if (i == 1) file else File(dir, "$name.${i - 1}")
            val dst = File(dir, "$name.$i")
            if (src.exists()) {
                dst.delete()
                src.renameTo(dst)
            }
        }
        if (maxFiles <= 1) file.delete()
        size = 0L
        writer = OutputStreamWriter(FileOutputStream(file, false), Charsets.UTF_8)
    }

    companion object {
        const val MAX_LINE = 4000
    }
}
