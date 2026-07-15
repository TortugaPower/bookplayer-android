package com.tortugapower.audiobookplayer.logic.sort

import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the sort rules, serialization, and location resolution. */
class SortPrimitivesTest {

    private fun book(
        uuid: String,
        title: String = uuid,
        fileName: String? = null,
        lastPlayed: Long? = null,
        path: String? = uuid,
        type: ItemType = ItemType.BOOK,
    ) = LibraryItemEntity(
        uuid = uuid,
        title = title,
        originalFileName = fileName,
        lastPlayDate = lastPlayed,
        relativePath = path,
        type = type,
    )

    // ---- NaturalOrder / SortType.metadataTitle -----------------------------------------------

    @Test fun `title sort is numeric-friendly`() {
        val items = listOf(book("a", "Chapter 10"), book("b", "Chapter 2"), book("c", "Chapter 1"))
        val sorted = SortType.metadataTitle.sorted(items).map { it.title }
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 10"), sorted)
    }

    @Test fun `title sort ignores leading zeros in numeric runs`() {
        assertTrue(NaturalOrder.compare("Track 007", "Track 8") < 0)
        assertEquals(0, NaturalOrder.compare("Track 07", "Track 7"))
    }

    @Test fun `number-prefixed titles sort by number and stay grouped before letters`() {
        // Regression: a broken comparator scattered these (e.g. "30 …" first but "33 …" last).
        val items = listOf(
            book("j", "Jim Butcher - Ghost Story"),
            book("c", "33 The Edge of Dawn"),
            book("a", "30 Fire Emblem Theme (1)"),
            book("b", "9 Prelude"),
        )
        val sorted = SortType.metadataTitle.sorted(items).map { it.title }
        assertEquals(
            listOf("9 Prelude", "30 Fire Emblem Theme (1)", "33 The Edge of Dawn", "Jim Butcher - Ghost Story"),
            sorted,
        )
    }

    @Test fun `comparator is self-consistent across a mixed set (transitive, antisymmetric)`() {
        val samples = listOf(
            "33 The Edge", "30 Fire Emblem", "9 Prelude", "Jim Butcher", "jim adams",
            "Chapter 2", "Chapter 10", "007 Bond", "7 Up", "Éclair", "Zebra", "",
        )
        for (x in samples) for (y in samples) {
            val xy = NaturalOrder.compare(x, y)
            val yx = NaturalOrder.compare(y, x)
            // Antisymmetry: sign(compare(x,y)) == -sign(compare(y,x)).
            assertEquals("antisymmetry for '$x' vs '$y'", Integer.signum(xy), -Integer.signum(yx))
            for (z in samples) {
                if (xy <= 0 && NaturalOrder.compare(y, z) <= 0) {
                    assertTrue("transitivity: '$x'<='$y'<='$z' implies '$x'<='$z'",
                        NaturalOrder.compare(x, z) <= 0)
                }
            }
        }
    }

    // ---- SortType.fileName -------------------------------------------------------------------

    @Test fun `filename sort uses originalFileName ascending`() {
        val items = listOf(
            book("a", title = "Zzz", fileName = "b_file.mp3"),
            book("b", title = "Aaa", fileName = "a_file.mp3"),
        )
        val sorted = SortType.fileName.sorted(items).map { it.uuid }
        assertEquals(listOf("b", "a"), sorted)
    }

    // ---- SortType.mostRecent -----------------------------------------------------------------

    @Test fun `most recent sorts newest first and never-played last`() {
        val items = listOf(
            book("old", lastPlayed = 1_000L),
            book("never", lastPlayed = null),
            book("new", lastPlayed = 9_000L),
        )
        val sorted = SortType.mostRecent.sorted(items).map { it.uuid }
        assertEquals(listOf("new", "old", "never"), sorted)
    }

    // ---- EffectiveSort serialization / validation --------------------------------------------

    @Test fun `effective sort round-trips`() {
        assertEquals("metadataTitle", EffectiveSort.Automatic(SortType.metadataTitle).serialize())
        assertEquals("custom", EffectiveSort.Custom.serialize())
        assertEquals(EffectiveSort.Automatic(SortType.mostRecent), EffectiveSort.deserialize("mostRecent"))
        assertEquals(EffectiveSort.Custom, EffectiveSort.deserialize("custom"))
        assertEquals(EffectiveSort.Custom, EffectiveSort.deserialize(null))
        // Unknown/corrupt local value reads as custom, not a crash.
        assertEquals(EffectiveSort.Custom, EffectiveSort.deserialize("garbage"))
    }

    @Test fun `only known raw values are accepted from sync`() {
        assertTrue(EffectiveSort.isValidRawValue("custom"))
        assertTrue(EffectiveSort.isValidRawValue("fileName"))
        assertFalse(EffectiveSort.isValidRawValue("garbage"))
        assertFalse(EffectiveSort.isValidRawValue(null))
    }

    // ---- SortLocation keys -------------------------------------------------------------------

    @Test fun `store keys are root default and folder uuid`() {
        assertEquals("library_sort:default", SortLocation.Root.storeKey)
        assertEquals("library_sort:abc", SortLocation.Folder("abc").storeKey)
        assertNull(SortLocation.Unresolved.storeKey)
    }

    @Test fun `store key parses back to a location - uuid not path so it survives renames`() {
        assertEquals(SortLocation.Root, SortLocation.fromStoreKey("library_sort:default"))
        assertEquals(SortLocation.Folder("abc"), SortLocation.fromStoreKey("library_sort:abc"))
        assertNull(SortLocation.fromStoreKey("other_key"))
    }

    // ---- SortLocationResolver: the three distinct outcomes -----------------------------------

    @Test fun `resolver returns root, real folder, placeholder, and bound volume`() = runBlocking {
        val folder = book("real", type = ItemType.FOLDER, path = "Series")
        val placeholder = book("place", type = ItemType.FOLDER, path = "Offline")
        val bound = book("vol", type = ItemType.BOUND, path = "Volume")
        val byPath = mapOf("Series" to folder, "Offline" to placeholder, "Volume" to bound)

        val getByPath: suspend (String) -> LibraryItemEntity? = { byPath[it] }
        // "place" is still a local placeholder (not synced).
        val isSynced: suspend (String) -> Boolean = { it != "place" }

        assertEquals(SortLocation.Root, SortLocationResolver.resolve(null, getByPath, isSynced))
        assertEquals(SortLocation.Folder("real"), SortLocationResolver.resolve("Series", getByPath, isSynced))
        assertEquals(SortLocation.Unresolved, SortLocationResolver.resolve("Offline", getByPath, isSynced))
        assertEquals(SortLocation.Unresolved, SortLocationResolver.resolve("Volume", getByPath, isSynced))
        // A path with no matching folder is also unresolved.
        assertEquals(SortLocation.Unresolved, SortLocationResolver.resolve("Ghost", getByPath, isSynced))
    }

}
