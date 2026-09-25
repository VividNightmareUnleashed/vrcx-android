package io.github.vrcxandroid.bridge.sqlite

import io.github.vrcxandroid.bridge.DotNetException

/**
 * Rejections for SQLite failures: `DotNetException("SQLiteException", <sqlite3_errmsg>)`, so the page sees for example
 * `SQLiteException: database is locked` or `SQLiteException: no such table: nosuch` (ARCHITECTURE.md §4.5). The
 * frontend matches substrings of the sqlite message (`duplicate column name`, `database disk image is malformed`, ...),
 * which is passed through verbatim. When the driver gives no message, the `sqlite3_errstr` text
 * of the result code is used instead.
 */
internal object SqliteErrors {
    const val TYPE = "SQLiteException"

    /** The bundled driver throws `android.database.SQLException("Error code: <n>, message: <errmsg>")`. */
    private val DRIVER_MESSAGE = Regex("^Error code: (-?\\d+)(?:, message: (.*))?$", RegexOption.DOT_MATCHES_ALL)

    /**
     * A parameter without a value. The check is System.Data.SQLite's rather than sqlite's, so its documented text is
     * kept.
     */
    fun insufficientParameters() = DotNetException(TYPE, "unknown error\r\nInsufficient parameters supplied to the command")

    /** Returns the .NET-style exception for a driver exception, or null when [t] did not come from SQLite. */
    fun translate(t: Throwable): DotNetException? {
        if (t is DotNetException) return t
        val match = DRIVER_MESSAGE.matchEntire(t.message ?: return null) ?: return null
        val code = match.groupValues[1].toInt()
        val detail = match.groups[2]?.value
        return DotNetException(TYPE, format(code, detail))
    }

    /** The sqlite message verbatim, or the result code's `sqlite3_errstr` text when there is none. */
    fun format(code: Int, detail: String?): String = if (detail.isNullOrBlank()) errorString(code) else detail

    /** `sqlite3_errstr` (primary result codes; extended codes use their low byte like SQLite does). */
    fun errorString(code: Int): String = when (code) {
        -1 -> "unknown error"
        516 -> "abort due to ROLLBACK"
        100 -> "another row available"
        101 -> "no more rows available"
        else -> PRIMARY.getOrNull(code and 0xFF) ?: "unknown error"
    }

    private val PRIMARY = arrayOf(
        /* 0 SQLITE_OK         */ "not an error",
        /* 1 SQLITE_ERROR      */ "SQL logic error",
        /* 2 SQLITE_INTERNAL   */ "unknown error",
        /* 3 SQLITE_PERM       */ "access permission denied",
        /* 4 SQLITE_ABORT      */ "query aborted",
        /* 5 SQLITE_BUSY       */ "database is locked",
        /* 6 SQLITE_LOCKED     */ "database table is locked",
        /* 7 SQLITE_NOMEM      */ "out of memory",
        /* 8 SQLITE_READONLY   */ "attempt to write a readonly database",
        /* 9 SQLITE_INTERRUPT  */ "interrupted",
        /* 10 SQLITE_IOERR     */ "disk I/O error",
        /* 11 SQLITE_CORRUPT   */ "database disk image is malformed",
        /* 12 SQLITE_NOTFOUND  */ "unknown operation",
        /* 13 SQLITE_FULL      */ "database or disk is full",
        /* 14 SQLITE_CANTOPEN  */ "unable to open database file",
        /* 15 SQLITE_PROTOCOL  */ "locking protocol",
        /* 16 SQLITE_EMPTY     */ "unknown error",
        /* 17 SQLITE_SCHEMA    */ "database schema has changed",
        /* 18 SQLITE_TOOBIG    */ "string or blob too big",
        /* 19 SQLITE_CONSTRAINT*/ "constraint failed",
        /* 20 SQLITE_MISMATCH  */ "datatype mismatch",
        /* 21 SQLITE_MISUSE    */ "bad parameter or other API misuse",
        /* 22 SQLITE_NOLFS     */ "unknown error",
        /* 23 SQLITE_AUTH      */ "authorization denied",
        /* 24 SQLITE_FORMAT    */ "unknown error",
        /* 25 SQLITE_RANGE     */ "column index out of range",
        /* 26 SQLITE_NOTADB    */ "file is not a database",
        /* 27 SQLITE_NOTICE    */ "notification message",
        /* 28 SQLITE_WARNING   */ "warning message",
    )
}
