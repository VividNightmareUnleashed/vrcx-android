package io.github.vrcxandroid.logwatcher

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * A gap in the byte stream of a mirrored file: the companion client should send
 * `{"t":"fetch","name":name,"fileId":fileId,"fromOffset":fromOffset}` (docs/PROTOCOL.md §5.9). Reported once per
 * gap through [LogWatcher.resyncListener] and kept until contiguous data arrives, see [LogWatcher.pendingResyncs].
 */
data class ResyncRequest(val name: String, val fileId: String, val fromOffset: Long)

/**
 * Persistent byte mirror of one companion's `output_log_*.txt` files (docs/ARCHITECTURE.md §8),
 * under `filesDir/logmirror/<companionId>/`:
 * - `index.json`: `{"v":1,"companionId","info":{...},"nextId","files":[{"name","fileId","local","creationTimeUtcTicks",
 *   "lastWriteTimeUtcTicks","pcLength"}]}`, written atomically (temporary file + rename);
 * - `<local>` data files holding the exact PC bytes. The mirrored length of a file is the size of its data file, so
 *   appends never need an index write and a crash can only lose a valid suffix.
 *
 * Not thread-safe; [LogWatcher] confines it to its thread.
 */
internal class LogMirror(val dir: File, val companionId: String, private val log: LwLog) {
    class Entry(
        val name: String,
        var fileId: String,
        var local: String,
        var creationTicks: Long,
        var lastWriteTicks: Long,
        var pcLength: Long,
        var size: Long,
    )

    enum class AppendResult { APPENDED, DUPLICATE, GAP, REPLACED_AND_APPENDED }

    private val entries = LinkedHashMap<String, Entry>()
    private var nextId = 1L
    private var dirty = false
    private val pendingResync = HashMap<String, ResyncRequest>()

    var info: CompanionInfo? = null
        private set

    fun entries(): Collection<Entry> = entries.values

    fun entry(name: String): Entry? = entries[name]

    fun have(): List<MirroredFile> = entries.values.map { MirroredFile(it.name, it.fileId, it.size) }

    fun pendingResyncs(): List<ResyncRequest> = pendingResync.values.toList()

    val isDirty: Boolean get() = dirty

    fun load() {
        dir.mkdirs()
        val index = File(dir, INDEX)
        if (!index.exists()) return
        try {
            val root = Json.parseToJsonElement(index.readText()).jsonObject
            info = (root["info"] as? JsonObject)?.let(::infoFromJson)
            nextId = (root["nextId"] as? JsonPrimitive)?.longOrNull ?: 1L
            for (f in root["files"]?.jsonArray ?: JsonArray(emptyList())) {
                val o = f.jsonObject
                val name = o["name"]?.jsonPrimitive?.contentOrNull ?: continue
                val local = o["local"]?.jsonPrimitive?.contentOrNull ?: continue
                val data = File(dir, local)
                entries[name] = Entry(
                    name = name,
                    fileId = o["fileId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    local = local,
                    creationTicks = o["creationTimeUtcTicks"]?.jsonPrimitive?.longOrNull ?: 0,
                    lastWriteTicks = o["lastWriteTimeUtcTicks"]?.jsonPrimitive?.longOrNull ?: 0,
                    pcLength = o["pcLength"]?.jsonPrimitive?.longOrNull ?: 0,
                    size = if (data.exists()) data.length() else 0,
                )
            }
        } catch (e: Exception) {
            log.log(LwLog.WARN, "log mirror index unreadable, starting over: ${e.message}", e)
            entries.clear()
            info = null
        }
        // Data files the index does not know about (a crash between creating a file and writing the index).
        val known = entries.values.mapTo(HashSet()) { it.local }
        dir.listFiles()?.forEach { f ->
            if (f.name != INDEX && f.name !in known) f.delete()
        }
    }

    fun setInfo(value: CompanionInfo) {
        if (value == info) return
        info = value
        writeIndex()
    }

    /**
     * Applies a `snapshot` (PROTOCOL.md §5.6): files missing from it are deleted, a known name with a different
     * `fileId` is a replaced file (its bytes are dropped and it starts empty), and the PC metadata of every file is
     * updated. Returns the names whose previous content is gone (deleted or replaced).
     */
    fun applySnapshot(files: List<PcFileMeta>): List<String> {
        val gone = ArrayList<String>()
        val names = files.mapTo(HashSet()) { it.name }
        val it = entries.values.iterator()
        var structural = false
        while (it.hasNext()) {
            val e = it.next()
            if (e.name !in names) {
                File(dir, e.local).delete()
                pendingResync.remove(e.name)
                it.remove()
                gone += e.name
                structural = true
            }
        }
        for (f in files) {
            var e = entries[f.name]
            if (e != null && e.fileId != f.fileId) {
                replace(e, f.fileId)
                gone += f.name
                structural = true
            }
            if (e == null) {
                e = Entry(f.name, f.fileId, newLocal(), f.creationTimeUtcTicks, f.lastWriteTimeUtcTicks, f.length, 0)
                entries[f.name] = e
                structural = true
            }
            if (e.creationTicks != f.creationTimeUtcTicks || e.lastWriteTicks != f.lastWriteTimeUtcTicks ||
                e.pcLength != f.length
            ) {
                e.creationTicks = f.creationTimeUtcTicks
                e.lastWriteTicks = f.lastWriteTimeUtcTicks
                e.pcLength = f.length
                dirty = true
            }
        }
        if (structural) writeIndex()
        return gone
    }

    /**
     * Appends `data` bytes at [offset]. Only contiguous data is written: bytes already mirrored are skipped, and an
     * offset beyond the mirrored length records a [ResyncRequest] (returned as [AppendResult.GAP]). Data for a known
     * name with a new `fileId` at offset 0 replaces the file.
     */
    fun append(name: String, fileId: String, offset: Long, bytes: ByteArray): AppendResult {
        var e = entries[name]
        var replaced = false
        if (e == null) {
            // Data before any snapshot entry: keep it; the next snapshot supplies the metadata (or deletes it).
            e = Entry(name, fileId, newLocal(), 0, 0, 0, 0)
            entries[name] = e
            writeIndex()
        } else if (e.fileId != fileId) {
            if (offset != 0L) return gap(name, fileId, 0)
            replace(e, fileId)
            writeIndex()
            replaced = true
        }
        if (offset > e.size) return gap(name, fileId, e.size)
        val skip = e.size - offset
        if (skip >= bytes.size) return AppendResult.DUPLICATE
        FileOutputStream(File(dir, e.local), true).use { out ->
            out.write(bytes, skip.toInt(), bytes.size - skip.toInt())
        }
        e.size += bytes.size - skip
        pendingResync.remove(name)
        return if (replaced) AppendResult.REPLACED_AND_APPENDED else AppendResult.APPENDED
    }

    /** `truncate` (PROTOCOL.md §5.7). Returns true when the mirror shrank. */
    fun truncate(name: String, fileId: String, newLength: Long): Boolean {
        val e = entries[name] ?: return false
        if (e.fileId != fileId || newLength < 0 || newLength >= e.size) return false
        RandomAccessFile(File(dir, e.local), "rw").use { it.setLength(newLength) }
        e.size = newLength
        pendingResync.remove(name)
        return true
    }

    fun openRead(e: Entry): RandomAccessFile {
        val f = File(dir, e.local)
        if (!f.exists()) f.createNewFile()
        return RandomAccessFile(f, "r")
    }

    /** Writes the index if metadata changed since the last write. */
    fun flushIndex() {
        if (dirty) writeIndex()
    }

    fun deleteAll() {
        entries.clear()
        pendingResync.clear()
        dir.deleteRecursively()
    }

    private fun gap(name: String, fileId: String, from: Long): AppendResult {
        pendingResync[name] = ResyncRequest(name, fileId, from)
        return AppendResult.GAP
    }

    private fun replace(e: Entry, fileId: String) {
        File(dir, e.local).delete()
        e.fileId = fileId
        e.local = newLocal()
        e.size = 0
        pendingResync.remove(e.name)
    }

    private fun newLocal(): String = "f${nextId++}.log"

    private fun writeIndex() {
        dirty = false
        val root = buildJsonObject {
            put("v", 1)
            put("companionId", companionId)
            info?.let { put("info", infoToJson(it)) }
            put("nextId", nextId)
            put("files", buildJsonArray {
                for (e in entries.values) add(buildJsonObject {
                    put("name", e.name)
                    put("fileId", e.fileId)
                    put("local", e.local)
                    put("creationTimeUtcTicks", e.creationTicks)
                    put("lastWriteTimeUtcTicks", e.lastWriteTicks)
                    put("pcLength", e.pcLength)
                })
            })
        }
        try {
            dir.mkdirs()
            writeAtomically(File(dir, INDEX), root.toString())
        } catch (e: IOException) {
            dirty = true
            log.log(LwLog.WARN, "log mirror index write failed: ${e.message}", e)
        }
    }

    companion object {
        const val INDEX = "index.json"

        fun writeAtomically(target: File, text: String) {
            val tmp = File(target.parentFile, target.name + ".tmp")
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                // Some file systems refuse to rename over an existing file.
                target.delete()
                if (!tmp.renameTo(target)) throw IOException("cannot replace $target")
            }
        }

        fun infoToJson(i: CompanionInfo): JsonObject = buildJsonObject {
            put("companionVersion", i.companionVersion)
            put("machineName", i.machineName)
            put("pcUtcNowMs", i.pcUtcNowMs)
            put("tzWindowsId", i.tzWindowsId)
            put("tzIanaId", i.tzIanaId)
            put("tzSupportsDst", i.tzSupportsDst)
            put("tzBaseUtcOffsetMin", i.tzBaseUtcOffsetMin)
            put("tzCurrentUtcOffsetMin", i.tzCurrentUtcOffsetMin)
            put("logDir", i.logDir)
            put("dirExists", i.dirExists)
        }

        fun infoFromJson(o: JsonObject): CompanionInfo = CompanionInfo(
            companionVersion = o["companionVersion"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            machineName = o["machineName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            pcUtcNowMs = o["pcUtcNowMs"]?.jsonPrimitive?.longOrNull ?: 0,
            tzWindowsId = o["tzWindowsId"]?.jsonPrimitive?.contentOrNull,
            tzIanaId = o["tzIanaId"]?.jsonPrimitive?.contentOrNull,
            tzSupportsDst = o["tzSupportsDst"]?.jsonPrimitive?.booleanOrNull ?: false,
            tzBaseUtcOffsetMin = o["tzBaseUtcOffsetMin"]?.jsonPrimitive?.intOrNull ?: 0,
            tzCurrentUtcOffsetMin = o["tzCurrentUtcOffsetMin"]?.jsonPrimitive?.intOrNull ?: 0,
            logDir = o["logDir"]?.jsonPrimitive?.contentOrNull,
            dirExists = o["dirExists"]?.jsonPrimitive?.booleanOrNull ?: true,
        )
    }
}
