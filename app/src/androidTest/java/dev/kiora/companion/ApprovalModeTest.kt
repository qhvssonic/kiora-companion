package dev.kiora.companion

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApprovalModeTest {
    @Test fun assistAutomaticallyAllowsKnownReadOnlyTools() {
        for (name in listOf(
            "get_local_time", "read", PhoneResourceTools.BROWSE,
            "list_authorized_folders", "list_authorized_photos",
            "list_authorized_files", "search_authorized_files", "inspect_authorized_file",
            "read_authorized_text", "list_gallery_photos", "describe_gallery_photo",
        )) {
            assertFalse(name, shouldAskForTool(ApprovalMode.ASSIST, name, false))
        }
    }

    @Test fun assistStillAsksForChangesCommandsUploadsAndUnknownTools() {
        for (name in listOf(
            "write", "edit", "shell", PhoneResourceTools.MANAGE,
            "copy_authorized_file", "move_authorized_file",
            "rename_authorized_file", "delete_authorized_file",
            "a2a_unknown",
        )) {
            assertTrue(name, shouldAskForTool(ApprovalMode.ASSIST, name, false))
        }
        assertFalse(shouldAskForTool(ApprovalMode.ASSIST, "a2a_unknown", true))
        assertTrue(shouldAskForTool(ApprovalMode.ASSIST, "shell", true))
    }

    @Test fun explicitModesOverrideDefaultPolicy() {
        assertTrue(shouldAskForTool(ApprovalMode.ASK, "read", true))
        assertFalse(shouldAskForTool(ApprovalMode.FULL, "shell", false))
    }
}
