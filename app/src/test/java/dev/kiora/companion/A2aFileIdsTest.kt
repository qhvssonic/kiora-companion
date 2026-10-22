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

    @Test fun selectedGalleryPhotoIdResolvesToItsAttachment() {
        val aliases = A2aFileIds.selectedAliases(
            listOf("content://media/external/images/media/1000005024",
                "content://media/external/images/media/1000005025"),
            listOf("杯子1.png", "杯子2.png"),
        )
        val normalized = listOf("gallery_1000005024", "gallery_1000005025")
            .map { A2aFileIds.normalize(it, 2, aliases) }
        assertEquals(listOf("selected:0", "selected:1"), normalized)
        A2aFileIds.requireCompleteAlbumTransfer(normalized, setOf("selected:0", "selected:1"))
    }

    @Test fun unselectedGalleryPhotoIdCannotBeSent() {
        val aliases = A2aFileIds.selectedAliases(
            listOf("content://media/external/images/media/1000005024",
                "content://other/external/images/media/1000005025"),
            listOf("杯子1.png", "杯子2.png"),
        )
        assertEquals("gallery_1000005025", A2aFileIds.normalize("gallery_1000005025", 2, aliases))
        assertEquals("gallery_1000005026", A2aFileIds.normalize("gallery_1000005026", 2, aliases))
        assertThrows(IllegalArgumentException::class.java) {
            A2aFileIds.requireCompleteAlbumTransfer(listOf("gallery_1000005026"), setOf("selected:0"))
        }
    }

    @Test fun selectedPhotoFilenameResolvesToItsAttachment() {
        val aliases = A2aFileIds.selectedAliases(
            listOf("content://media/picker/photo/123"), listOf("杯子1.png"),
        )
        assertEquals("selected:0", A2aFileIds.normalize("杯子1.png", 1, aliases))
        assertEquals("别的照片.png", A2aFileIds.normalize("别的照片.png", 1, aliases))
    }

    @Test fun duplicateSelectedFilenamesRequireExplicitId() {
        val aliases = A2aFileIds.selectedAliases(
            listOf("content://media/external/images/media/1", "content://media/external/images/media/2"),
            listOf("同名.png", "同名.png"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            A2aFileIds.normalize("同名.png", 2, aliases)
        }
        assertEquals("selected:1", A2aFileIds.normalize("selected:1", 2, aliases))
    }

    @Test fun hostBindsExactlyTheUserSelectedFiles() {
        assertEquals(listOf("selected:0", "selected:1"), A2aFileIds.boundIds(2, null))
        assertEquals(listOf("selected:1"), A2aFileIds.boundIds(2, setOf("selected:1")))
        assertThrows(IllegalArgumentException::class.java) {
            A2aFileIds.boundIds(2, setOf("selected:2"))
        }
    }
}
