package io.github.vrcxandroid.bridge.sqlite

import io.github.vrcxandroid.bridge.errorText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SqliteErrorsTest {
    private fun translate(message: String) = errorText(SqliteErrors.translate(RuntimeException(message))!!)

    @Test
    fun matchesSystemDataSqliteFormat() {
        assertEquals("SQLiteException: SQL logic error\r\nduplicate column name: a", translate("Error code: 1, message: duplicate column name: a"))
        assertEquals("SQLiteException: SQL logic error\r\nno such column: b", translate("Error code: 1, message: no such column: b"))
        assertEquals("SQLiteException: database is locked\r\ndatabase is locked", translate("Error code: 5, message: database is locked"))
        assertEquals(
            "SQLiteException: database disk image is malformed\r\ndatabase disk image is malformed",
            translate("Error code: 11, message: database disk image is malformed"),
        )
        assertEquals("SQLiteException: database or disk is full\r\ndatabase or disk is full", translate("Error code: 13, message: database or disk is full"))
        assertEquals("SQLiteException: disk I/O error\r\ndisk I/O error", translate("Error code: 10, message: disk I/O error"))
        assertEquals(
            "SQLiteException: attempt to write a readonly database\r\nattempt to write a readonly database",
            translate("Error code: 8, message: attempt to write a readonly database"),
        )
    }

    @Test
    fun extendedCodesUseTheirPrimaryText() {
        // SQLITE_CONSTRAINT_UNIQUE = 2067, SQLITE_IOERR_WRITE = 778
        assertEquals("SQLiteException: constraint failed\r\nUNIQUE constraint failed: t.a", translate("Error code: 2067, message: UNIQUE constraint failed: t.a"))
        assertEquals("disk I/O error", SqliteErrors.errorString(778))
    }

    @Test
    fun messageWithoutDetailAndMultilineDetail() {
        assertEquals("SQLiteException: SQL logic error", translate("Error code: 1"))
        assertEquals("SQLiteException: SQL logic error\r\nnear \"x\":\nsyntax error", translate("Error code: 1, message: near \"x\":\nsyntax error"))
    }

    @Test
    fun otherExceptionsAreNotTranslated() {
        assertNull(SqliteErrors.translate(IllegalStateException("connection is closed")))
        assertNull(SqliteErrors.translate(RuntimeException()))
    }
}
