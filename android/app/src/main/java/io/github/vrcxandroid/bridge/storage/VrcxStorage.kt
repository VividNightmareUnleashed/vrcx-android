package io.github.vrcxandroid.bridge.storage

import android.util.Log
import io.github.vrcxandroid.bridge.SafeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.CoroutineContext

/**
 * Port of upstream `VRCXStorage`: a flat string → string map persisted to
 * `VRCX.json`.
 *
 * - The file is read on first access (not in the constructor) so no disk I/O happens on the main thread at startup
 *   unless a caller there asks for a value.
 * - Every change schedules a save [debounceMs] after the last change (upstream restarts a 500 ms one-shot timer).
 * - [save] writes immediately (the frontend calls `Save()` after proxy changes). Writes go to `<name>.tmp`, are fsynced,
 *   then renamed over the file, so a crash never leaves a truncated `VRCX.json`. A failed save leaves the previous file
 *   untouched and the map dirty.
 * - A file that cannot be parsed is kept as `<name>.corrupt` before it is replaced (upstream silently starts empty).
 *
 * Thread-safe: bridge calls arrive on the VRCXStorage lane, native callers use [get]/[set] from any thread.
 */
class VrcxStorage(
    val file: File,
    private val scope: CoroutineScope,
    private val debounceMs: Long = 500,
    private val ioContext: CoroutineContext = Dispatchers.IO,
    private val fileSteps: AtomicWriteSteps = FileSystemSteps,
) {
    private val lock = Any()
    private val saveLock = Any()
    private var data: LinkedHashMap<String, String>? = null
    private var generation = 0L
    private var savedGeneration = 0L
    private var pendingSave: Job? = null

    /** Re-reads the file, replacing the in-memory map (upstream `Load()`). */
    fun load() {
        synchronized(lock) { data = readFile() }
    }

    fun get(key: String): String = synchronized(lock) { map()[key] ?: "" }

    fun set(key: String, value: String) {
        synchronized(lock) {
            map()[key] = value
            generation++
        }
        scheduleSave()
    }

    fun remove(key: String): Boolean {
        val removed = synchronized(lock) {
            (map().remove(key) != null).also { if (it) generation++ }
        }
        if (removed) scheduleSave()
        return removed
    }

    fun clear() {
        val changed = synchronized(lock) {
            val m = map()
            if (m.isEmpty()) false else {
                m.clear()
                generation++
                true
            }
        }
        if (changed) scheduleSave()
    }

    /** `System.Text.Json` serialization of the whole map, e.g. `{"a":"b"}`. */
    fun getAll(): String = synchronized(lock) { JsonText.systemTextJsonObject(map()) }

    fun snapshot(): Map<String, String> = synchronized(lock) { LinkedHashMap(map()) }

    /** Replaces every entry (database import with a VRCX.json) and writes the file now. */
    fun replaceAll(entries: Map<String, String>) {
        synchronized(lock) {
            data = LinkedHashMap(entries)
            generation++
        }
        save()
    }

    /** True when changes have not been written yet. */
    val isDirty: Boolean get() = synchronized(lock) { generation != savedGeneration }

    /** Writes the current map now (upstream `Save()`). Errors are logged and swallowed like `JsonFileSerializer`. */
    fun save() {
        synchronized(saveLock) {
            val (snapshot, gen) = synchronized(lock) {
                pendingSave?.cancel()
                pendingSave = null
                LinkedHashMap(map()) to generation
            }
            try {
                writeAtomically(VrcxJsonFormat.encode(snapshot))
                synchronized(lock) { if (gen > savedGeneration) savedGeneration = gen }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save ${file.name} ${SafeLog.kind(e)}")
            }
        }
    }

    /** Writes pending changes now, if any. */
    fun flush() {
        if (isDirty) save()
    }

    private fun scheduleSave() {
        synchronized(lock) {
            pendingSave?.cancel()
            pendingSave = scope.launch(ioContext) {
                delay(debounceMs)
                save()
            }
        }
    }

    private fun map(): LinkedHashMap<String, String> = data ?: readFile().also { data = it }

    private fun readFile(): LinkedHashMap<String, String> {
        if (!file.isFile) return LinkedHashMap()
        return try {
            VrcxJsonFormat.decode(file.readBytes())
        } catch (e: Exception) {
            // The parser quotes the file (it holds the proxy credentials): class name only.
            Log.e(TAG, "Failed to read ${file.name} ${SafeLog.kind(e)}; starting empty")
            try {
                file.copyTo(File(file.parentFile, file.name + ".corrupt"), overwrite = true)
            } catch (_: Exception) {
            }
            LinkedHashMap()
        }
    }

    private fun writeAtomically(bytes: ByteArray) {
        val dir = file.absoluteFile.parentFile
        dir?.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        try {
            // The file is only replaced once the new content is complete and on disk.
            fileSteps.writeAndSync(tmp, bytes)
            fileSteps.replace(tmp, file)
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    /** The two steps of an atomic save. Separate so tests can check their order and inject failures. */
    interface AtomicWriteSteps {
        /** Writes [bytes] to [tmp] and forces them to disk. */
        fun writeAndSync(tmp: File, bytes: ByteArray)

        /** Replaces [target] with [tmp] in one rename. */
        fun replace(tmp: File, target: File)
    }

    /** Real file system: `write` + `fsync`, then an atomic rename (a plain replace where the file system has none). */
    object FileSystemSteps : AtomicWriteSteps {
        override fun writeAndSync(tmp: File, bytes: ByteArray) {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
        }

        override fun replace(tmp: File, target: File) {
            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private companion object {
        const val TAG = "VRCXStorage"
    }
}
