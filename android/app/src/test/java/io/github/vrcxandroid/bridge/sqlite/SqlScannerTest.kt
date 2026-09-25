package io.github.vrcxandroid.bridge.sqlite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SqlScannerTest {
    private fun single(sql: String): ScannedStatement = SqlScanner.scan(sql).single()

    @Test
    fun namedParametersInOrderOfFirstAppearance() {
        val s = single("SELECT * FROM configs WHERE key = @key AND value = @value")
        assertEquals(listOf("@key", "@value"), s.params)
        assertEquals("SELECT", s.firstKeyword)
        assertFalse(s.writesRows)
    }

    @Test
    fun repeatedNameReusesItsIndex() {
        val s = single("SELECT @a, @b, @a, @b, @c")
        assertEquals(listOf("@a", "@b", "@c"), s.params)
    }

    @Test
    fun namesAreCaseSensitiveAndPrefixIsPartOfTheName() {
        val s = single("SELECT @a, @A, :a, \$a")
        assertEquals(listOf("@a", "@A", ":a", "\$a"), s.params)
    }

    @Test
    fun literalsIdentifiersAndCommentsAreSkipped() {
        val sql = """
            SELECT '@notParam', 'it''s @still text', "@quotedIdent", `@tick`, [@bracket], x'40'
            -- @lineComment ;
            /* @blockComment ; */
            FROM t WHERE a = @real
        """.trimIndent()
        val s = single(sql)
        assertEquals(listOf("@real"), s.params)
    }

    @Test
    fun emailLikeTextInsideStringIsNotAParameter() {
        assertEquals(emptyList<String?>(), single("SELECT 'user@example.com'").params)
    }

    @Test
    fun identifierCharactersIncludeDollarAndNonAscii() {
        val s = single("SELECT foo\$bar, @naïve_1, @x2")
        assertEquals(listOf("@naïve_1", "@x2"), s.params)
    }

    @Test
    fun questionMarkAndNumberedParameters() {
        val s = single("SELECT ?, ?, ?5, @n, ?2")
        // ? → 1, ? → 2, ?5 → 5 (3 and 4 unnamed), @n → 6, ?2 reuses index 2 (already unnamed, gets the name ?2)
        assertEquals(listOf(null, "?2", null, null, "?5", "@n"), s.params)
    }

    @Test
    fun tclStyleSuffixes() {
        val s = single("SELECT \$a::b, @c(x y), @d(e)")
        // "@c(x" has a space before ')' → illegal token; SQLite would fail to prepare. The scanner just moves on.
        assertTrue(s.params.contains("\$a::b"))
        assertTrue(s.params.contains("@d(e)"))
    }

    @Test
    fun trailingSemicolonAndWhitespaceYieldOneStatement() {
        val list = SqlScanner.scan("DELETE FROM t WHERE id = @id;  \n\t ")
        assertEquals(1, list.size)
        assertEquals("DELETE FROM t WHERE id = @id", list[0].sql)
        assertTrue(list[0].writesRows)
    }

    @Test
    fun trailingCommentAfterSemicolonIsDropped() {
        assertEquals(1, SqlScanner.scan("SELECT 1; -- done").size)
        assertEquals(1, SqlScanner.scan("SELECT 1; /* done */").size)
        assertEquals(0, SqlScanner.scan("  -- nothing\n  ").size)
    }

    @Test
    fun multipleStatementsHaveTheirOwnParameterNumbering() {
        val list = SqlScanner.scan("INSERT INTO a VALUES (@x, @y); UPDATE b SET c = @y WHERE d = @z")
        assertEquals(2, list.size)
        assertEquals(listOf("@x", "@y"), list[0].params)
        assertEquals(listOf("@y", "@z"), list[1].params)
        assertEquals("UPDATE", list[1].firstKeyword)
    }

    @Test
    fun semicolonInsideLiteralDoesNotSplit() {
        val list = SqlScanner.scan("INSERT INTO t VALUES ('a;b', \"c;d\"); SELECT 1")
        assertEquals(2, list.size)
        assertEquals("INSERT INTO t VALUES ('a;b', \"c;d\")", list[0].sql)
    }

    @Test
    fun triggerBodyKeepsItsSemicolons() {
        val sql = """
            CREATE TRIGGER tr AFTER INSERT ON t BEGIN
              UPDATE t SET v = CASE WHEN new.v IS NULL THEN 0 ELSE new.v END;
              DELETE FROM u;
            END;
            SELECT 1
        """.trimIndent()
        val list = SqlScanner.scan(sql)
        assertEquals(2, list.size)
        assertTrue(list[0].sql.trimEnd().endsWith("END"))
        assertEquals("SELECT", list[1].firstKeyword)
    }

    @Test
    fun cteWithInsertCountsAsWrite() {
        assertTrue(single("WITH x AS (SELECT 1) INSERT INTO t SELECT * FROM x").writesRows)
        assertFalse(single("WITH x AS (SELECT 1) SELECT * FROM x").writesRows)
        assertFalse(single("BEGIN").writesRows)
        assertTrue(single("INSERT OR REPLACE INTO configs (key, value) VALUES (@key, @value)").writesRows)
        assertTrue(single("REPLACE INTO t VALUES (1)").writesRows)
    }

    @Test
    fun activityV2InsertWith1250Parameters() {
        val rows = (0 until 250).joinToString(", ") { n ->
            "(@userId_$n, @startAt_$n, @endAt_$n, @isOpenTail_$n, @sourceRevision_$n)"
        }
        val s = single("INSERT OR REPLACE INTO usr_activity_sessions_v2 (user_id, start_at, end_at, is_open_tail, source_revision) VALUES $rows")
        assertEquals(1250, s.params.size)
        assertEquals("@userId_0", s.params[0])
        assertEquals("@sourceRevision_249", s.params[1249])
    }

    @Test
    fun unterminatedLiteralConsumesTheRest() {
        assertEquals(emptyList<String?>(), single("SELECT 'abc @x").params)
    }
}
