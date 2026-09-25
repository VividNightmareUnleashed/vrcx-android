package io.github.vrcxandroid.bridge.sqlite

/**
 * One SQL statement of a (possibly multi-statement) command text.
 *
 * @property sql the statement text as it appears in the command, without the terminating `;`.
 * @property params parameter names by index: `params[i]` is the name SQLite reports for index `i + 1`
 *   (`sqlite3_bind_parameter_name`), or null for a nameless `?` or an index skipped by `?NNN`.
 * @property firstKeyword the statement's first keyword, upper-case (`SELECT`, `INSERT`, `BEGIN`, ...).
 * @property writesRows true when the statement can change rows (`INSERT`/`UPDATE`/`DELETE`/`REPLACE`, also after a CTE).
 */
internal class ScannedStatement(
    val sql: String,
    val params: List<String?>,
    val firstKeyword: String,
    val writesRows: Boolean,
)

/**
 * Splits command text into statements and lists their parameters the way SQLite's tokenizer does (`sqlite3GetToken`,
 * `sqlite3ExprAssignVarNumber`), so the bundled driver's index-only binding can be driven by name.
 *
 * Skipped: `'...'` literals (with `''`), `"..."`, `` `...` `` and `[...]` identifiers, `-- ...` and `/* ... */` comments.
 * Parameters: `?`, `?NNN`, and `@name`, `:name`, `$name`, `#name` (identifier characters, plus the TCL-style `::`
 * and `(...)` suffixes). A repeated name reuses its index. Statements that contain only whitespace or comments
 * (for example after a trailing `;`) are dropped. `;` inside a `CREATE TRIGGER ... BEGIN ... END` body does not end
 * the statement.
 */
internal object SqlScanner {
    /** SQLite's default `SQLITE_MAX_VARIABLE_NUMBER`. */
    private const val MAX_VARIABLE_NUMBER = 32766

    private val DML = setOf("INSERT", "UPDATE", "DELETE", "REPLACE")

    fun scan(sql: String): List<ScannedStatement> {
        val out = ArrayList<ScannedStatement>(1)
        val n = sql.length
        var i = 0
        var stmtStart = 0
        var state = StatementState()

        fun finish(end: Int) {
            if (state.hasTokens) {
                out.add(ScannedStatement(sql.substring(stmtStart, end), state.paramList(), state.firstKeyword, state.writes))
            }
            state = StatementState()
        }

        while (i < n) {
            val c = sql[i]
            when {
                c == '-' && i + 1 < n && sql[i + 1] == '-' -> {
                    i += 2
                    while (i < n && sql[i] != '\n') i++
                    if (i < n) i++
                }
                c == '/' && i + 1 < n && sql[i + 1] == '*' -> {
                    i += 2
                    while (i < n && !(sql[i] == '*' && i + 1 < n && sql[i + 1] == '/')) i++
                    i = if (i < n) i + 2 else n
                }
                c == '\'' || c == '"' || c == '`' -> {
                    state.hasTokens = true
                    i = skipQuoted(sql, i, c)
                }
                c == '[' -> {
                    state.hasTokens = true
                    val close = sql.indexOf(']', i + 1)
                    i = if (close < 0) n else close + 1
                }
                c == ';' -> {
                    if (state.inTriggerBody) {
                        i++
                    } else {
                        finish(i)
                        i++
                        stmtStart = i
                    }
                }
                c == '?' -> {
                    state.hasTokens = true
                    var j = i + 1
                    while (j < n && sql[j].isAsciiDigit()) j++
                    if (j == i + 1) {
                        state.addUnnamed()
                    } else {
                        val number = sql.substring(i + 1, j).toIntOrNull() ?: 0
                        if (number in 1..MAX_VARIABLE_NUMBER) state.addNumbered(number, sql.substring(i, j))
                    }
                    i = j
                }
                c == '@' || c == ':' || c == '$' || c == '#' -> {
                    state.hasTokens = true
                    val end = variableEnd(sql, i)
                    if (end > 0) {
                        state.addNamed(sql.substring(i, end))
                        i = end
                    } else {
                        i++
                    }
                }
                isIdChar(c) && !c.isAsciiDigit() -> {
                    var j = i + 1
                    while (j < n && isIdChar(sql[j])) j++
                    state.word(sql.substring(i, j).uppercase())
                    i = j
                }
                c.isAsciiDigit() || (c == '.' && i + 1 < n && sql[i + 1].isAsciiDigit()) -> {
                    state.hasTokens = true
                    var j = i + 1
                    while (j < n && (isIdChar(sql[j]) || sql[j] == '.')) j++
                    i = j
                }
                c.isWhitespace() -> i++
                else -> {
                    state.hasTokens = true
                    i++
                }
            }
        }
        finish(n)
        return out
    }

    /** Index after the closing quote (a doubled quote is an escaped quote). Unterminated: end of text. */
    private fun skipQuoted(sql: String, start: Int, quote: Char): Int {
        var i = start + 1
        val n = sql.length
        while (i < n) {
            if (sql[i] == quote) {
                if (i + 1 < n && sql[i + 1] == quote) {
                    i += 2
                    continue
                }
                return i + 1
            }
            i++
        }
        return n
    }

    /** End of a `@name`-style variable starting at [start], or -1 when no identifier characters follow (illegal token). */
    private fun variableEnd(sql: String, start: Int): Int {
        val n = sql.length
        var i = start + 1
        var count = 0
        while (i < n) {
            val c = sql[i]
            if (isIdChar(c)) {
                count++
                i++
            } else if (c == '(' && count > 0) {
                var j = i + 1
                while (j < n && !sql[j].isWhitespace() && sql[j] != ')') j++
                return if (j < n && sql[j] == ')') j + 1 else -1
            } else if (c == ':' && i + 1 < n && sql[i + 1] == ':') {
                i += 2
            } else {
                break
            }
        }
        return if (count == 0) -1 else i
    }

    /** SQLite `IdChar`: ASCII letters and digits, `_`, `$`, and every non-ASCII character. */
    private fun isIdChar(c: Char): Boolean =
        (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '_' || c == '$' || c.code >= 0x80

    private fun Char.isAsciiDigit() = this in '0'..'9'

    private class StatementState {
        var hasTokens = false
        var firstKeyword = ""
        var writes = false
        private val names = HashMap<String, Int>()
        private val indexNames = HashMap<Int, String>()
        private var count = 0

        // CREATE [TEMP|TEMPORARY] TRIGGER bodies: BEGIN ... END, where CASE ... END nests.
        private var wordIndex = 0
        private var isTrigger = false
        var inTriggerBody = false
            private set
        private var caseDepth = 0

        fun word(upper: String) {
            hasTokens = true
            when (wordIndex) {
                0 -> firstKeyword = upper
                1, 2 -> if (firstKeyword == "CREATE" && upper == "TRIGGER") isTrigger = true
            }
            wordIndex++
            if (upper in DML && (wordIndex == 1 || firstKeyword == "WITH")) writes = true
            if (isTrigger) {
                when {
                    upper == "BEGIN" && !inTriggerBody -> inTriggerBody = true
                    upper == "CASE" && inTriggerBody -> caseDepth++
                    upper == "END" && inTriggerBody -> if (caseDepth > 0) caseDepth-- else inTriggerBody = false
                }
            }
        }

        fun addUnnamed() {
            count++
        }

        fun addNumbered(number: Int, text: String) {
            if (number > count) count = number
            if (!indexNames.containsKey(number)) {
                indexNames[number] = text
                names.putIfAbsent(text, number)
            }
        }

        fun addNamed(name: String) {
            if (names.containsKey(name)) return
            count++
            names[name] = count
            indexNames[count] = name
        }

        fun paramList(): List<String?> = List(count) { indexNames[it + 1] }
    }
}
