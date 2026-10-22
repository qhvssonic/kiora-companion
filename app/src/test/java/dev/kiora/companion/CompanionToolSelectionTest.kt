package dev.kiora.companion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionToolSelectionTest {
    private val remoteTool = "a2a_dsh_delegate_task"
    private val available = setOf("read", "write", "shell", remoteTool)

    @Test fun indexedAlbumCanBeSearchedWithoutFullGalleryBrowsing() {
        val offered = available + AlbumSearchTool.NAME + PhoneResourceTools.BROWSE
        val selected = CompanionToolSelection(shellEnabled = false, hasFolders = false,
            hasGallery = false, hasAlbumSearch = true, remoteTools = emptySet()).select(offered)

        assertTrue(AlbumSearchTool.NAME in selected)
        assertTrue("read" in selected)
        assertFalse(PhoneResourceTools.BROWSE in selected)
        assertTrue(isLowRiskTool(AlbumSearchTool.NAME))
    }

    @Test fun desktopRequestKeepsA2aAndWorkspaceToolsButHidesPhoneShell() {
        val selected = CompanionToolSelection(shellEnabled = true, hasFolders = false,
            hasGallery = false, remoteTools = setOf(remoteTool),
            remoteAgentRequested = true).select(available)

        assertTrue(remoteTool in selected)
        assertTrue("read" in selected)
        assertTrue("write" in selected)
        assertFalse("shell" in selected)
    }

    @Test fun explicitPhoneShellRequestCanStillUseShell() {
        val selected = CompanionToolSelection(shellEnabled = true, hasFolders = false,
            hasGallery = false, remoteTools = setOf(remoteTool),
            remoteAgentRequested = true, phoneShellRequested = true).select(available)

        assertTrue("shell" in selected)
    }

    @Test fun disconnectedDesktopRequestDoesNotFallBackToPhoneShell() {
        val selected = CompanionToolSelection(shellEnabled = true, hasFolders = false,
            hasGallery = false, remoteTools = emptySet(),
            remoteAgentRequested = true).select(available)

        assertFalse("shell" in selected)
    }

    @Test fun routingRecognizesDesktopIntentWithoutMatchingOrdinarySending() {
        assertTrue(RemoteTaskRouting.requestsRemoteAgent("通过 DSH 把工作区文件发送到电脑端"))
        assertFalse(RemoteTaskRouting.requestsPhoneShell("通过 DSH 把工作区文件发送到电脑端"))
        assertFalse(RemoteTaskRouting.requestsRemoteAgent("发送短信"))
        assertTrue(RemoteTaskRouting.requestsPhoneShell("先在手机上运行命令，再交给 DSH"))
    }

    @Test fun aliasSelectsOneConnectionAndProducesRemoteOnlyInstruction() {
        val dsh = A2aConnectionConfig("dsh-id", "DSH Agent", "http://localhost/card", alias = "dsh")
        val other = A2aConnectionConfig("other-id", "Other Agent", "http://localhost/other", alias = "other")
        val request = "把刚才的文件通过dsh发送到桌面"

        assertEquals(listOf(dsh), RemoteTaskRouting.matchingConnections(request, listOf(dsh, other)))
        assertTrue(RemoteTaskRouting.containsRoutePhrase(request, "dsh"))
        assertFalse(RemoteTaskRouting.matchingConnections("文件名report_dsh.md", listOf(dsh)).isNotEmpty())
        assertEquals("dsh", RemoteTaskRouting.suggestAlias("DSH Agent"))
    }

    @Test fun differentFileReferencesRouteToAgentWithoutHardCodedFileResolution() {
        val dsh = A2aConnectionConfig("dsh-id", "DSH Agent", "http://localhost/card", alias = "dsh")
        listOf("把这个文件通过DSH发送到桌面。", "让dsh分析这份文档", "问dsh刚才的文件路径在哪？")
            .forEach { assertEquals(listOf(dsh), RemoteTaskRouting.matchingConnections(it, listOf(dsh))) }
    }
}
