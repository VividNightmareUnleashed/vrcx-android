package io.github.vrcxandroid.bridge.sqlite

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Opening, validating, importing and exporting the VRCX database file (ARCHITECTURE.md §9
 * "Import PC data").
 */
internal object VrcxDatabase {
    /** Page-cache friendly, crash-safe settings for a single-process app. */
    private val OPEN_PRAGMAS = listOf(
        "PRAGMA journal_mode=WAL",
        "PRAGMA busy_timeout=5000",
        // WAL + NORMAL: no fsync per commit (the frontend commits many single-row writes), still corruption-safe.
        // Android's framework SQLite uses the same default for WAL databases.
        "PRAGMA synchronous=NORMAL",
        "PRAGMA optimize=0x10002",
    )

    private val driver by lazy { BundledSQLiteDriver() }

    /** Opens (creating if needed) [file] with the bundled SQLite and applies the connection pragmas. */
    fun open(file: File): SQLiteConnection {
        file.absoluteFile.parentFile?.mkdirs()
        val connection = driver.open(file.absolutePath, SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE)
        try {
            for (pragma in OPEN_PRAGMAS) exec(connection, pragma)
        } catch (e: Exception) {
            connection.close()
            throw e
        }
        return connection
    }

    fun exec(connection: SQLiteConnection, sql: String) {
        connection.prepare(sql).use { while (it.step()) Unit }
    }

    /** `PRAGMA wal_checkpoint(TRUNCATE)`: moves the WAL into the main file so the file alone is a complete copy. */
    fun checkpoint(session: SqliteSession) {
        session.queryRows("PRAGMA wal_checkpoint(TRUNCATE)")
    }

    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    fun hasSqliteHeader(file: File): Boolean {
        if (file.length() < 100) return false
        val header = ByteArray(SQLITE_HEADER.size)
        file.inputStream().use { input ->
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n < 0) return false
                read += n
            }
        }
        return header.contentEquals(SQLITE_HEADER)
    }

    /**
     * Checks that [file] is an intact VRCX database: SQLite header, `PRAGMA integrity_check` = `ok`, and a table the
     * VRCX frontend always creates (`configs`, or the game log). Returns null when valid, else a user-facing reason.
     */
    fun validate(file: File): String? {
        if (!hasSqliteHeader(file)) return "The selected file is not a SQLite database."
        val connection = try {
            driver.open(file.absolutePath, SQLITE_OPEN_READWRITE)
        } catch (e: Exception) {
            return "The selected database could not be opened: ${e.message}"
        }
        try {
            val session = SqliteSession { connection }
            val integrity = try {
                session.queryRows("PRAGMA integrity_check").map { it.firstOrNull()?.toString().orEmpty() }
            } catch (e: Exception) {
                return "The selected database failed the integrity check: ${e.message}"
            }
            if (integrity != listOf("ok")) {
                return "The selected database failed the integrity check: ${integrity.take(3).joinToString("; ")}"
            }
            val vrcxTables = session.queryScalar(
                "SELECT count(*) FROM sqlite_schema WHERE type = 'table' AND name IN ('configs', 'gamelog_location')",
            ) as? Long ?: 0L
            if (vrcxTables == 0L) return "The selected database is not a VRCX database."
            return null
        } finally {
            connection.close()
        }
    }

    /** Deletes the rollback journal, WAL and shared-memory files that belong to [file]. */
    fun deleteSidecars(file: File) {
        for (suffix in listOf("-wal", "-shm", "-journal")) File(file.path + suffix).delete()
    }

    /** Moves [source] over [target] (same directory), atomically where the file system allows. */
    fun replace(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun copy(input: InputStream, output: OutputStream) {
        input.copyTo(output, 1 shl 16)
    }
}
