package com.tortugapower.audiobookplayer.logic

import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure cover-candidate gathering shared by all artwork surfaces: BOOK → itself,
 * BOUND → its sub-books, FOLDER → a bounded recursive walk (iOS handleDirectory parity). The DAO is
 * injected as a `childrenOf` lambda, so no Android / Room is needed.
 */
class CoverArtResolverTest {

    private fun item(uuid: String, type: ItemType, path: String) =
        LibraryItemEntity(uuid = uuid, title = uuid, relativePath = path, type = type)

    private fun childrenOf(map: Map<String, List<LibraryItemEntity>>): suspend (String) -> List<LibraryItemEntity> =
        { path -> map[path] ?: emptyList() }

    @Test
    fun book_isItself() = runBlocking {
        val book = item("b1", ItemType.BOOK, "b1.m4b")
        assertEquals(listOf("b1"), gatherCoverCandidates(book, 100, 200, childrenOf(emptyMap())).map { it.uuid })
    }

    @Test
    fun bound_returnsOnlyBookSubItems() = runBlocking {
        val bound = item("bd", ItemType.BOUND, "Bound")
        val map = mapOf(
            "Bound" to listOf(
                item("s1", ItemType.BOOK, "Bound/1.mp3"),
                item("stray", ItemType.FOLDER, "Bound/x"), // non-book must be filtered out
                item("s2", ItemType.BOOK, "Bound/2.mp3"),
            ),
        )
        assertEquals(listOf("s1", "s2"), gatherCoverCandidates(bound, 100, 200, childrenOf(map)).map { it.uuid })
    }

    @Test
    fun folder_recursesNestedFoldersAndExpandsBound() = runBlocking {
        val folder = item("f", ItemType.FOLDER, "F")
        val map = mapOf(
            "F" to listOf(
                item("bk", ItemType.BOOK, "F/a.m4b"),
                item("sub", ItemType.FOLDER, "F/Sub"),
                item("bd", ItemType.BOUND, "F/Bound"),
            ),
            "F/Sub" to listOf(item("bk2", ItemType.BOOK, "F/Sub/b.m4b")),
            "F/Bound" to listOf(item("bs1", ItemType.BOOK, "F/Bound/1.mp3")),
        )
        val got = gatherCoverCandidates(folder, 100, 200, childrenOf(map)).map { it.uuid }
        // The direct book, the bound's sub-book (expanded), and the nested folder's book (recursed).
        assertEquals(setOf("bk", "bs1", "bk2"), got.toSet())
    }

    @Test
    fun folder_respectsCandidateCap() = runBlocking {
        val folder = item("f", ItemType.FOLDER, "F")
        val books = (1..10).map { item("b$it", ItemType.BOOK, "F/$it.m4b") }
        val got = gatherCoverCandidates(folder, maxCandidates = 3, maxNodes = 200, childrenOf(mapOf("F" to books)))
        assertEquals(3, got.size)
    }

    @Test
    fun folder_emptyWhenNoChildren() = runBlocking {
        val folder = item("f", ItemType.FOLDER, "F")
        assertTrue(gatherCoverCandidates(folder, 100, 200, childrenOf(emptyMap())).isEmpty())
    }
}
