package dev.kiora.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolTraceAlbumTest {
    @Test fun searchResultShowsOnlyValidPhotosAndActualCandidateCount() {
        val trace = ToolTrace("call", AlbumSearchTool.NAME, "{}", """
            {"indexedCount":59,"items":[
              {"photoId":"gallery_5011","name":"cup.jpg"},
              {"photoId":"gallery_4979","name":"second.png"},
              {"photoId":"invalid","name":"bad.png"},
              {"photoId":"gallery_4990","name":"third.png"}
            ]}
        """.trimIndent(), ToolTrace.State.SUCCEEDED)

        assertEquals(listOf(5011L, 4979L, 4990L), trace.albumPhotos().map { it.id })
        assertTrue(trace.displayResult().contains("3 张"))
        assertTrue(!trace.displayResult().contains("59"))
    }
}
