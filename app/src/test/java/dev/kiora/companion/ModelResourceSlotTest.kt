package dev.kiora.companion

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class ModelResourceSlotTest {
    @Test fun repeatedConfigurationReusesModelAndReplacementClosesOldBeforeLoading() = runBlocking {
        val events = mutableListOf<String>()
        val slot = ModelResourceSlot<String, Any> { events += "close" }
        val first = slot.getOrLoad("a") { events += "load-a"; Any() }
        repeat(3) { assertSame(first, slot.getOrLoad("a") { error("Must reuse") }) }
        assertTrue(slot.matches("a"))
        val second = slot.getOrLoad("b") { events += "load-b"; Any() }
        assertNotSame(first, second)
        assertEquals(listOf("load-a", "close", "load-b"), events)
        slot.close()
        slot.close()
        assertEquals(2, events.count { it == "close" })
        assertFalse(slot.matches("b"))
    }

    @Test fun failedReplacementDoesNotKeepTheOldClosedModel() = runBlocking {
        var closes = 0
        val slot = ModelResourceSlot<String, Any> { closes++ }
        slot.getOrLoad("old") { Any() }
        try { slot.getOrLoad("bad") { error("load failed") }; fail() } catch (_: IllegalStateException) { }
        assertFalse(slot.matches("old"))
        assertFalse(slot.matches("bad"))
        slot.getOrLoad("bad") { Any() }
        assertTrue(slot.matches("bad"))
        slot.close()
        assertEquals(2, closes)
    }

    @Test fun cancelledNativeLoaderCannotPublishAModelAndDisposesItOnce() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var closes = 0
        val slot = ModelResourceSlot<String, Any> { closes++ }
        val work = async {
            slot.getOrLoad("model") {
                // A native loader can finish after cancellation instead of checking the Job.
                withContext(NonCancellable) { started.complete(Unit); finish.await() }
                Any()
            }
        }
        started.await()
        work.cancel()
        finish.complete(Unit)
        try { work.await(); fail() } catch (_: CancellationException) { }
        assertFalse(slot.matches("model"))
        slot.close()
        assertEquals(1, closes)
    }

    @Test fun localModelKeyIgnoresRemoteAndRuntimeSettingsButTracksActualBackendAndFile() {
        val file = File.createTempFile("kiora-model", ".litertlm")
        try {
            file.writeText("first")
            val config = CompanionConfig("unused", "label", "unused", emptyList(),
                providerId = "litert", localModelPath = file.absolutePath, localBackend = "cpu")
            val key = config.chatModelKey()
            assertEquals(key, config.copy(modelUrl = "other", modelId = "new label", apiKey = "other",
                contextWindowTokens = 4096, mnnConfigPath = "other",
                a2aConnections = listOf(A2aConnectionConfig("p", "peer", "https://example.invalid/card"))).chatModelKey())
            assertNotEquals(key, config.copy(localBackend = "gpu").chatModelKey())
            assertNotEquals(key, config.copy(localModelPath = file.path + ".other").chatModelKey())
            file.writeText("a replacement with a different size")
            assertNotEquals(key, config.chatModelKey())
        } finally { file.delete() }
    }

    @Test fun onlineKeyTracksEndpointModelAndCredentialsButNotA2aOrLocalFiles() {
        val config = CompanionConfig("https://api.example.com/v1/chat/completions", "model-a", "key-a",
            emptyList(), providerId = "custom")
        assertEquals(config.chatModelKey(), config.copy(localModelPath = "unused", localBackend = "gpu",
            contextWindowTokens = 8192).chatModelKey())
        assertNotEquals(config.chatModelKey(), config.copy(apiKey = "key-b").chatModelKey())
        assertNotEquals(config.chatModelKey(), config.copy(modelId = "model-b").chatModelKey())
        assertNotEquals(config.chatModelKey(), config.copy(modelUrl = "https://other.example.com").chatModelKey())
    }
}
