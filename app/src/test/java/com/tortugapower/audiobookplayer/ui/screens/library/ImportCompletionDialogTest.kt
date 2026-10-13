package com.tortugapower.audiobookplayer.ui.screens.library

import com.tortugapower.audiobookplayer.database.entities.ItemType
import com.tortugapower.audiobookplayer.database.entities.LibraryItemEntity
import com.tortugapower.audiobookplayer.logic.ImportCompletion
import org.junit.Assert.assertEquals
import org.junit.Test

/** The post-import "New folder" name can't be a path the import already took. */
class ImportCompletionDialogTest {

    @Test fun `a new folder can't take the name of the volume just downloaded`() {
        // A downloaded volume sits at its name's path, which is also the suggested folder name.
        val completion = ImportCompletion(
            items = listOf(LibraryItemEntity(uuid = "v", title = "Golf", relativePath = "Shelf/Golf", type = ItemType.BOUND)),
            suggestedName = "Golf",
            basePath = "Shelf",
        )
        val otherFolder = LibraryItemEntity(uuid = "f", title = "Other", relativePath = "Shelf/Other", type = ItemType.FOLDER)

        assertEquals(listOf("Other", "Golf"), newFolderTakenNames(completion, listOf(otherFolder)))
    }
}
