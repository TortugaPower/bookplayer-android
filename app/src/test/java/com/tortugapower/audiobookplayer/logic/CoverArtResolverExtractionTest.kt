package com.tortugapower.audiobookplayer.logic

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.tortugapower.audiobookplayer.database.AppDatabase
import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * [CoverArtResolver] resolves covers through `ArtworkManager.saveEmbeddedArtwork` (a located byte
 * range decoded via a stream — never the cover-sized `embeddedPicture` allocation). This pins the
 * wiring end to end on a local BOOK: a cover lands in the shared store; no cover is remembered as
 * definitive so the item isn't re-probed on every rebind.
 */
@RunWith(RobolectricTestRunner::class)
class CoverArtResolverExtractionTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun processedFile(name: String, bytes: ByteArray): File =
        File(File(context.filesDir, "Processed"), name).apply { parentFile!!.mkdirs(); writeBytes(bytes) }

    private fun book(uuid: String, relativePath: String) =
        LibraryItemEntity(uuid = uuid, title = uuid, relativePath = relativePath, type = ItemType.BOOK)

    @Test
    fun localBookWithEmbeddedCover_isWrittenToTheSharedStore() = runBlocking {
        processedFile("cover.m4b", MiniMp4.withCover(MiniMp4.tinyPng(24, 24)))
        val item = book("book-with-cover", "cover.m4b")

        val file = CoverArtResolver.resolveCoverFile(context, db.libraryDao(), item, includeRemote = true)

        assertNotNull(file)
        assertEquals(CoverArtResolver.cacheFile(context, item.uuid), file)
        assertTrue("cover should have been written", file!!.isFile && file.length() > 0)
    }

    @Test
    fun localBookWithoutCover_yieldsNothingAndIsRememberedAsArtless() = runBlocking {
        processedFile("plain.m4b", MiniMp4.withoutCover())
        val item = book("book-without-cover", "plain.m4b")

        assertNull(CoverArtResolver.resolveCoverFile(context, db.libraryDao(), item, includeRemote = true))
        assertTrue(CoverArtResolver.isKnownArtless(item.uuid))
    }
}

/** Just enough MP4 for the cover locator: `ftyp` + `moov { mvhd, udta { meta { ilst { covr { data } } } } }`. */
private object MiniMp4 {
    private fun u32(v: Long): ByteArray = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun box(type: String, vararg payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(u32(8L + payload.sumOf { it.size }))
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        payload.forEach(out::write)
        return out.toByteArray()
    }
    private val ftyp = box("ftyp", "M4A ".toByteArray(Charsets.ISO_8859_1), u32(0), "M4A mp42isom".toByteArray(Charsets.ISO_8859_1))
    private val mvhd = box("mvhd", ByteArray(100))

    fun withoutCover(): ByteArray = ftyp + box("moov", mvhd)

    fun withCover(png: ByteArray): ByteArray {
        val data = box("data", u32(14) /* PNG */, u32(0) /* locale */, png)
        val udta = box("udta", box("meta", ByteArray(4) /* FullBox version + flags */, box("ilst", box("covr", data))))
        return ftyp + box("moov", mvhd, udta)
    }

    /** A real, decodable [width]x[height] RGB PNG (solid colour). */
    fun tinyPng(width: Int, height: Int): ByteArray {
        val raw = ByteArray((1 + width * 3) * height)
        for (y in 0 until height) for (x in 0 until width) {
            val i = y * (1 + width * 3) + 1 + x * 3
            raw[i] = 0x20; raw[i + 1] = 0x80.toByte(); raw[i + 2] = 0xC0.toByte()
        }
        val deflater = Deflater().apply { setInput(raw); finish() }
        val compressed = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) compressed.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        fun chunk(type: String, body: ByteArray): ByteArray {
            val t = type.toByteArray(Charsets.ISO_8859_1)
            val crc = CRC32().apply { update(t); update(body) }
            return u32(body.size.toLong()) + t + body + u32(crc.value)
        }
        val signature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
        val ihdr = u32(width.toLong()) + u32(height.toLong()) + byteArrayOf(8, 2, 0, 0, 0)
        return signature + chunk("IHDR", ihdr) + chunk("IDAT", compressed.toByteArray()) + chunk("IEND", ByteArray(0))
    }
}
