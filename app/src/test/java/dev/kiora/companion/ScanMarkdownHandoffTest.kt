package dev.kiora.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ScanMarkdownHandoffTest {
    @Test fun requiresLocalMarkdownBeforeRemoteHandoff() {
        val handoff = ScanMarkdownHandoff(setOf("selected:0"))
        assertNotNull(handoff.validationError())
        assertEquals(emptyList<String>(), handoff.boundFileIds(listOf("selected:0")))

        handoff.recordSuccessfulWrite("notes.txt")
        assertNotNull(handoff.validationError())
        handoff.recordSuccessfulWrite("参考文献.md")

        assertNull(handoff.validationError())
        assertEquals(listOf("workspace:参考文献.md"), handoff.boundFileIds(listOf("selected:0")))
    }

    @Test fun preservesOtherExplicitAttachmentsButNeverTheScanImage() {
        val handoff = ScanMarkdownHandoff(setOf("selected:0"))
        handoff.recordSuccessfulWrite("draft.MD")
        assertEquals(listOf("selected:1", "workspace:draft.MD"),
            handoff.boundFileIds(listOf("selected:0", "selected:1")))
    }
}
