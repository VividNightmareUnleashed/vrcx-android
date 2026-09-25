package io.github.vrcxandroid.bridge.appapi

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * `OpenVrcPhotosFolder` without a photos folder: the picker runs off the AppApi lane, the call answers at once, and
 * the picked tree is remembered and opened when the user answers.
 */
@RunWith(AndroidJUnit4::class)
class PhotosFolderPickerTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefsName = "vrcx_appapi_picker_test"
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).also { it.edit().clear().commit() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun cleanUp() {
        scope.cancel()
        context.deleteSharedPreferences(prefsName)
    }

    @Test
    fun openAnswersAtOnceAndOpensThePickedTreeLater() = runBlocking {
        val answer = CompletableDeferred<Uri?>()
        val picks = AtomicInteger()
        val viewed = CompletableDeferred<Pair<Uri, String?>>()
        val library = AndroidPhotosLibrary(
            context,
            prefs,
            view = { uri, type -> viewed.complete(uri to type) },
            picker = BackgroundPicker(scope, { picks.incrementAndGet(); answer.await() }, { _, _ -> }),
        )

        assertEquals("", library.location())
        // no folder yet: "folder missing" right away while the picker waits for the user
        assertFalse(library.open())
        assertFalse(library.open())
        assertFalse(viewed.isCompleted)

        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3APictures%2FVRChat")
        answer.complete(tree)
        val (uri, type) = withTimeout(5_000) { viewed.await() }
        assertEquals(DocumentsContract.buildDocumentUriUsingTree(tree, "primary:Pictures/VRChat"), uri)
        assertEquals(DocumentsContract.Document.MIME_TYPE_DIR, type)
        assertEquals(tree.toString(), prefs.getString("photosTreeUri", null))
        // the second tap did not open a second picker
        assertEquals(1, picks.get())
    }
}
