package dev.kiora.companion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionToolSelectionTest {
    private val remoteTool = "a2a_dsh_delegate_task"
    private val available = setOf("read", "write", "shell", remoteTool)

    @Test fun manualReadAloudIsNotAnAgentTool() {
        val selected = CompanionToolSelection(shellEnabled = false, hasFolders = false,
            hasGallery = false, remoteTools = emptySet()).select(available + "speak_text")
        assertFalse("speak_text" in selected)
    }

    @Test fun indexedAlbumCanBeSearchedWithoutFullGalleryBrowsing() {
        val offered = available + AlbumSearchTool.NAME + PhoneResourceTools.BROWSE
        val selected = CompanionToolSelection(shellEnabled = false, hasFolders = false,
            hasGallery = false, hasAlbumSearch = true, remoteTools = emptySet()).select(offered)

        assertTrue(AlbumSearchTool.NAME in selected)
        assertTrue("read" in selected)
        assertFalse(PhoneResourceTools.BROWSE in selected)
        assertTrue(isLowRiskTool(AlbumSearchTool.NAME))
    }

    @Test fun configuredShellIsIndependentOfTaskPhrasing() {
        val selected = CompanionToolSelection(shellEnabled = true, hasFolders = false,
            hasGallery = false, remoteTools = setOf(remoteTool)).select(available)

        assertTrue(remoteTool in selected)
        assertTrue("read" in selected)
        assertTrue("write" in selected)
        assertTrue("shell" in selected)
    }

    @Test fun explicitlyBoundRemoteTargetCannotUsePhoneShell() {
        val selected = CompanionToolSelection(shellEnabled = true, hasFolders = false,
            hasGallery = false, remoteTools = setOf(remoteTool), remoteTargetSelected = true).select(available)

        assertFalse("shell" in selected)
    }

    @Test fun unavailableRemoteTargetDoesNotFallBackToPhoneShell() {
        val selected = CompanionToolSelection(shellEnabled = true, hasFolders = false,
            hasGallery = false, remoteTools = emptySet(), remoteTargetSelected = true).select(available)

        assertFalse("shell" in selected)
    }

    @Test fun genericTaskWordsDoNotSelectAConnection() {
        val profile = A2aConnectionConfig("id", "Business Agent", "http://localhost/card", alias = "business")
        listOf("保存照片", "发送短信", "远程医疗是什么意思", "电脑端有什么软件").forEach {
            assertTrue(RemoteTaskRouting.matchingConnections(it, listOf(profile)).isEmpty())
        }
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
