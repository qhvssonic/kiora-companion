package dev.kiora.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class A2aFileIdsTest {
    @Test fun numericIdsReferOnlyToFilesSelectedInThisTurn() {
        assertEquals("selected:0", A2aFileIds.normalize("0", 1))
        assertEquals("selected:1", A2aFileIds.normalize("1", 2))
        assertEquals("0", A2aFileIds.normalize("0", 0))
        assertEquals("1", A2aFileIds.normalize("1", 1))
    }

    @Test fun explicitFileIdsAndWorkspacePathsRemainUnchanged() {
        assertEquals("selected:0", A2aFileIds.normalize("selected:0", 1))
        assertEquals("workspace:0", A2aFileIds.normalize("workspace:0", 1))
        assertEquals("workspace:poem.txt", A2aFileIds.normalize("workspace:poem.txt", 1))
        assertEquals("authorized:folder_1/0", A2aFileIds.normalize("authorized:folder_1/0", 1))
    }

    @Test fun albumTransferCannotSilentlyDropSelectedPhoto() {
        val selected = setOf("selected:0", "selected:1")
        assertThrows(IllegalArgumentException::class.java) {
            A2aFileIds.requireCompleteAlbumTransfer(listOf("selected:0"), selected)
        }
        A2aFileIds.requireCompleteAlbumTransfer(listOf("selected:1", "selected:0"), selected)
    }
}
