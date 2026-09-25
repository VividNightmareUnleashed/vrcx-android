package io.github.vrcxandroid.bridge.sqlite

import io.github.vrcxandroid.bridge.errorText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SqliteErrorsTest {
    private fun translate(message: String) = errorText(SqliteErrors.translate(RuntimeException(message))!!)

    @Test
    fun rejectionIsSqliteExceptionWithTheSqliteMessageVerbatim() {
        // ARCHITECTURE.md §4.5: "SQLiteException: database is locked".
        assertEquals("SQLiteException: database is locked", translate("Error code: 5, message: database is locked"))
        assertEquals("SQLiteException: duplicate column name: a", translate("Error code: 1, message: duplicate column name: a"))
        assertEquals("SQLiteException: no such column: b", translate("Error code: 1, message: no such column: b"))
        assertEquals("SQLiteException: no such table: nosuch", translate("Error code: 1, message: no such table: nosuch"))
        assertEquals(
            "SQLiteException: database disk image is malformed",
            translate("Error code: 11, message: database disk image is malformed"),
        )
        assertEquals("SQLiteException: database or disk is full", translate("Error code: 13, message: database or disk is full"))
        assertEquals("SQLiteException: disk I/O error", translate("Error code: 10, message: disk I/O error"))
        assertEquals(
            "SQLiteException: attempt to write a readonly database",
            translate("Error code: 8, message: attempt to write a readonly database"),
        )
        // Extended result codes carry their own message.
        assertEquals("SQLiteException: UNIQUE constraint failed: t.a", translate("Error code: 2067, message: UNIQUE constraint failed: t.a"))
    }

    @Test
    fun messageIsNotTrimmedOrPrefixed() {
        assertEquals("SQLiteException: near \"x\":\nsyntax error", translate("Error code: 1, message: near \"x\":\nsyntax error"))
    }

    @Test
    fun missingMessageFallsBackToTheResultCodeText() {
        assertEquals("SQLiteException: SQL logic error", translate("Error code: 1"))
        assertEquals("SQLiteException: database is locked", translate("Error code: 5, message: "))
        // SQLITE_IOERR_WRITE = 778 uses the text of its primary code.
        assertEquals("disk I/O error", SqliteErrors.errorString(778))
        assertEquals("constraint failed", SqliteErrors.errorString(2067))
    }

    @Test
    fun insufficientParametersKeepsTheDocumentedText() {
        assertEquals(
            "SQLiteException: unknown error\r\nInsufficient parameters supplied to the command",
            errorText(SqliteErrors.insufficientParameters()),
        )
    }

    @Test
    fun otherExceptionsAreNotTranslated() {
        assertNull(SqliteErrors.translate(IllegalStateException("connection is closed")))
        assertNull(SqliteErrors.translate(RuntimeException()))
    }
}
