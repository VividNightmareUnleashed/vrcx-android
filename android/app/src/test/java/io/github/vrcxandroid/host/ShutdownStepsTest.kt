package io.github.vrcxandroid.host

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShutdownStepsTest {
    private val calls = ArrayList<String>()
    private val warnings = ArrayList<String>()

    private fun steps(
        storage: suspend () -> Unit = { calls += "storage" },
        cookies: suspend () -> Unit = { calls += "cookies" },
    ) = ShutdownSteps(
        drain = { calls += "drain" },
        flushStorage = storage,
        flushCookies = cookies,
        closeDatabase = { calls += "database" },
        warn = { message, _ -> warnings += message },
    )

    @Test
    fun restartAndQuitSaveEverythingInOrder() = runTest {
        steps().beforeExit(saveState = true)
        assertEquals(listOf("drain", "storage", "cookies", "database"), calls)
    }

    @Test
    fun restartAfterAnImportDoesNotWriteTheOldStateOverTheImportedFiles() = runTest {
        steps().beforeExit(saveState = false)
        assertEquals(listOf("drain", "database"), calls)
    }

    @Test
    fun beforeAnImportTheQueuedCallsFinishAndTheStateIsSavedButTheDatabaseStaysOpen() = runTest {
        steps().beforeImport()
        assertEquals(listOf("drain", "storage", "cookies"), calls)
    }

    @Test
    fun aHangingStepTimesOutAndTheRestStillRun() = runTest {
        steps(storage = { awaitCancellation() }).beforeExit(saveState = true)
        assertEquals(listOf("drain", "cookies", "database"), calls)
        assertEquals(listOf("storage timed out"), warnings)
        assertEquals(ShutdownSteps.FLUSH_TIMEOUT_MS, currentTime)
    }

    @Test
    fun aFailingStepIsReportedAndTheRestStillRun() = runTest {
        steps(cookies = { error("disk full") }).beforeExit(saveState = true)
        assertEquals(listOf("drain", "storage", "database"), calls)
        assertEquals(listOf("cookies failed"), warnings)
    }
}

class DatabaseImportFlowTest {
    private val calls = ArrayList<String>()

    private fun flow(
        database: String? = "db",
        json: String? = "json",
        prepared: Boolean = true,
        import: suspend (String, String?) -> String = { d, j -> "ok:$d+$j" },
    ) = DatabaseImportFlow<String, String>(
        pickDatabase = {
            calls += "pick database"
            database
        },
        pickJson = {
            calls += "pick json"
            json
        },
        prepare = {
            calls += "prepare"
            prepared
        },
        import = { d, j ->
            calls += "import"
            import(d, j)
        },
        succeeded = { it.startsWith("ok") },
        resume = { calls += "resume" },
        restartAfterImport = { calls += "restart" },
    )

    @Test
    fun successRestartsWithoutLettingHeldCallsThrough() = runTest {
        val result = flow().run()
        assertEquals(DatabaseImportFlow.Result.Done("ok:db+json"), result)
        assertEquals(listOf("pick database", "pick json", "prepare", "import", "restart"), calls)
    }

    @Test
    fun theJsonFileIsOptional() = runTest {
        assertEquals(DatabaseImportFlow.Result.Done("ok:db+null"), flow(json = null).run())
        assertEquals(listOf("pick database", "pick json", "prepare", "import", "restart"), calls)
    }

    @Test
    fun failureLetsTheHeldCallsThroughAndDoesNotRestart() = runTest {
        val result = flow(import = { _, _ -> "failed: integrity check" }).run()
        assertEquals(DatabaseImportFlow.Result.Done("failed: integrity check"), result)
        assertEquals(listOf("pick database", "pick json", "prepare", "import", "resume"), calls)
    }

    @Test
    fun cancellingTheFirstPickerTouchesNothing() = runTest {
        assertEquals(DatabaseImportFlow.Result.Cancelled, flow(database = null).run())
        assertEquals(listOf("pick database"), calls)
    }

    @Test
    fun aRestartInProgressSkipsTheImport() = runTest {
        assertEquals(DatabaseImportFlow.Result.Busy, flow(prepared = false).run())
        assertEquals(listOf("pick database", "pick json", "prepare"), calls)
    }

    @Test
    fun anImportThatThrowsStillReleasesTheHeldCalls() = runTest {
        val thrown = runCatching { flow(import = { _, _ -> error("copy failed") }).run() }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertEquals(listOf("pick database", "pick json", "prepare", "import", "resume"), calls)
    }
}
