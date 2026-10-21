package dev.kiora.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionToolSelectionTest {
    @Test fun configuredToolsStayAvailableForEveryScene() {
        val selection = CompanionToolSelection(
            shellEnabled = true,
            hasFolders = true,
            hasGallery = true,
            remoteTools = setOf("a2a_file", "a2a_text"),
        )

        assertEquals(
            setOf("read", "write", "edit", "get_local_time", "shell",
                PhoneResourceTools.BROWSE, PhoneResourceTools.MANAGE, "a2a_file", "a2a_text"),
            selection.select(setOf("read", "write", "edit", "get_local_time", "shell",
                PhoneResourceTools.BROWSE, PhoneResourceTools.MANAGE, "a2a_file", "a2a_text")),
        )
    }

    @Test fun unavailableCapabilitiesAreStillExcluded() {
        val selection = CompanionToolSelection(
            shellEnabled = false,
            hasFolders = false,
            hasGallery = false,
            remoteTools = emptySet(),
        )

        assertEquals(
            setOf("read", "write", "edit", "get_local_time"),
            selection.select(setOf("read", "write", "edit", "get_local_time", "shell",
                PhoneResourceTools.BROWSE, PhoneResourceTools.MANAGE, "a2a_file")),
        )
    }

    @Test fun pureImageQuestionUsesTheAttachedVisionInputWithoutTools() {
        val selection = CompanionToolSelection(
            shellEnabled = false,
            hasFolders = false,
            hasGallery = false,
            remoteTools = setOf("a2a_file"),
            imageOnlyQuestion = true,
        )
        assertEquals(emptySet<String>(),
            selection.select(setOf("read", "write", "edit", "get_local_time", "a2a_file")))
    }
}
