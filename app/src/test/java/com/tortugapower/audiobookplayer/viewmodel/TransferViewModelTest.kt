package com.tortugapower.audiobookplayer.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.viewmodel.TransferViewModel.Row
import com.tortugapower.browserdrop.BrowserDropServer
import com.tortugapower.browserdrop.BrowserDropServer.State
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.Socket
import java.nio.file.Files

/** The Transfer session's logic against a real server on 127.0.0.1, and the pure helpers the sheet shows. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class TransferViewModelTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val stagingRoot: File = Files.createTempDirectory("TransferViewModelTest-").toFile()
    private lateinit var server: BrowserDropServer
    private lateinit var lifecycle: LifecycleRegistry
    private val imports = mutableListOf<Pair<String?, suspend () -> File?>>()

    @Before fun setUp() {
        // Robolectric's StatFs reports nothing free unless told: room for the uploads (4 GB in 4 KB blocks).
        org.robolectric.shadows.ShadowStatFs.registerStats(stagingRoot, 1_000_000, 1_000_000, 1_000_000)
        server = BrowserDropServer(
            context,
            BrowserDropServer.Configuration(ports = listOf(0), allowedExtensions = setOf("mp3"), stagingRoot = stagingRoot)
        )
        lifecycle = LifecycleRegistry.createUnsafe(object : LifecycleOwner {
            override val lifecycle: Lifecycle get() = this@TransferViewModelTest.lifecycle
        })
    }

    @After fun tearDown() {
        runBlocking { server.cancel() }
        stagingRoot.deleteRecursively()
    }

    private fun makeViewModel() = TransferViewModel(
        server = server,
        importReceived = { target, receive -> synchronized(imports) { imports += target to receive } },
        appLifecycle = lifecycle,
        sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    )

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        fail("$what didn't happen")
    }

    private fun port() = (server.state.value as State.Running).port

    private fun upload(path: String, body: String) {
        Socket("127.0.0.1", port()).use { socket ->
            val head = "POST /upload?path=${path.replace("/", "%2F")} HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                "Content-Length: ${body.length}\r\n\r\n"
            socket.getOutputStream().write((head + body).toByteArray())
            val response = socket.getInputStream().readBytes().decodeToString()
            assertTrue(response, response.startsWith("HTTP/1.1 201"))
        }
    }

    @Test fun `the session starts with the app in the foreground`() {
        val viewModel = makeViewModel()
        lifecycle.currentState = Lifecycle.State.STARTED
        awaitUntil("running") { viewModel.status.value == TransferViewModel.Status.RUNNING }
        assertTrue(server.state.value is State.Running)
    }

    @Test fun `the background pauses the session and coming back resumes it on the same port`() {
        makeViewModel()
        lifecycle.currentState = Lifecycle.State.STARTED
        awaitUntil("running") { server.state.value is State.Running }
        val port = port()
        upload("a.mp3", "a")

        lifecycle.currentState = Lifecycle.State.CREATED
        awaitUntil("paused") { server.state.value == State.Paused }
        lifecycle.currentState = Lifecycle.State.STARTED
        awaitUntil("running again") { server.state.value is State.Running }

        assertEquals(port, port())
        assertEquals(listOf("a.mp3"), server.receivedFiles.value.map { it.relativePath })
    }

    @Test fun `removing a folder row drops its files`() {
        val viewModel = makeViewModel()
        lifecycle.currentState = Lifecycle.State.STARTED
        awaitUntil("running") { server.state.value is State.Running }
        upload("Book/01.mp3", "a")
        upload("Book/02.mp3", "b")
        upload("loose.mp3", "c")

        viewModel.remove("Book")
        awaitUntil("removed") { server.receivedFiles.value.size == 1 }
        assertEquals(listOf("loose.mp3"), server.receivedFiles.value.map { it.relativePath })
    }

    @Test fun `done hands the received folder to the import, once`() {
        val viewModel = makeViewModel()
        lifecycle.currentState = Lifecycle.State.STARTED
        awaitUntil("running") { server.state.value is State.Running }
        upload("Book/01.mp3", "a")

        viewModel.confirm("Shelf")
        viewModel.confirm("Shelf")
        viewModel.discard()

        val (target, receive) = synchronized(imports) { imports.single() }
        assertEquals("Shelf", target)
        val folder = runBlocking { receive() }!!
        assertEquals("a", File(folder, "Book/01.mp3").readText())
        assertEquals(State.Closed, server.state.value)

        // Ended: the app coming back doesn't start another session.
        lifecycle.currentState = Lifecycle.State.CREATED
        lifecycle.currentState = Lifecycle.State.STARTED
        assertEquals(State.Closed, server.state.value)
    }

    @Test fun `cancel throws the files away`() {
        val viewModel = makeViewModel()
        lifecycle.currentState = Lifecycle.State.STARTED
        awaitUntil("running") { server.state.value is State.Running }
        upload("a.mp3", "a")

        viewModel.discard()
        awaitUntil("closed") { server.state.value == State.Closed }
        assertTrue(synchronized(imports) { imports.isEmpty() })
        awaitUntil("staging deleted") { stagingRoot.list().isNullOrEmpty() }
    }

    // --- Pure helpers ---

    @Test fun `rows show top-level files, and folders with their file count, sorted by title`() {
        val rows = TransferViewModel.rows(
            listOf("Book 10/01.mp3", "b.mp3", "Book 2/Disc 1/01.mp3", "Book 2/Disc 2/01.mp3", "a.mp3")
        )
        assertEquals(
            listOf(
                Row("a.mp3", "a.mp3", isFolder = false),
                Row("b.mp3", "b.mp3", isFolder = false),
                Row("Book 2", "Book 2", isFolder = true, fileCount = 2),
                Row("Book 10", "Book 10", isFolder = true, fileCount = 1)
            ),
            rows
        )
    }

    @Test fun `page strings turn format placeholders into the page's`() {
        assertEquals("{done} of {total} uploaded", TransferViewModel.pageTemplate("%1\$d of %2\$d uploaded"))
        assertEquals("Skipped unsupported files: {count}", TransferViewModel.pageTemplate("Skipped unsupported files: %d"))
        assertEquals("No placeholders", TransferViewModel.pageTemplate("No placeholders"))
    }

    @Test fun `page settings carry every page string, the app's name and language`() {
        val texts = mapOf(
            com.tortugapower.audiobookplayer.R.string.app_name to "BookPlayer",
            com.tortugapower.audiobookplayer.R.string.transfer_web_summary to "%1\$d of %2\$d uploaded",
            com.tortugapower.audiobookplayer.R.string.transfer_server_web_skipped to "Skipped: %d"
        )
        val settings = TransferViewModel.pageSettings({ id -> texts[id] ?: "text" }, "es", 0xFF336699.toInt())

        assertEquals(21, settings.strings.size)
        assertEquals("{done} of {total} uploaded", settings.strings["summary"])
        assertEquals("Skipped: {count}", settings.strings["skipped"])
        assertTrue(settings.strings.containsKey("pathConflict"))
        assertFalse(settings.strings.values.any { "%" in it })
        assertEquals("BookPlayer", settings.title)
        assertEquals("es", settings.language)
        assertEquals(0xFF336699.toInt(), settings.accentColor)
        assertNull(settings.strings["title"]) // The page's title is the app's name, set apart.
    }
}
