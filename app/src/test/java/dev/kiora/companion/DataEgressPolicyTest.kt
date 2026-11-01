package dev.kiora.companion

import dev.kiora.core.ModelRequestRejectedException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DataEgressPolicyTest {
    @Test fun grantsAreScopedToSessionAndDestinationAndCannotOverrideLocalOnly() = runBlocking {
        var mode = LocalDataMode.ASK
        var prompts = 0
        val policy = DataEgressPolicy({ mode }) { prompts++; DataEgressChoice.SESSION }
        val request = DataEgressRequest("s", "model A", setOf("OCR"))
        policy.check(request)
        policy.check(request.copy(sources = setOf("derived summary")))
        assertEquals(1, prompts)
        policy.check(request.copy(destination = "A2A B"))
        policy.check(request.copy(sessionId = "other"))
        assertEquals(3, prompts)
        mode = LocalDataMode.LOCAL_ONLY
        try { policy.check(request); fail("must reject") } catch (_: ModelRequestRejectedException) {}
        assertEquals(3, prompts)
        mode = LocalDataMode.ASK
        policy.clear()
        policy.check(request)
        assertEquals(4, prompts)
    }

    @Test fun onceAndDeniedChoicesAreNotRemembered() = runBlocking {
        var prompts = 0
        val policy = DataEgressPolicy({ LocalDataMode.ASK }) {
            if (++prompts == 1) DataEgressChoice.DENY else DataEgressChoice.ONCE
        }
        val request = DataEgressRequest("s", "model", setOf("file"))
        try { policy.check(request); fail("must reject") } catch (_: ModelRequestRejectedException) {}
        policy.check(request); policy.check(request)
        assertEquals(3, prompts)
        policy.check(request.copy(sources = emptySet()))
        assertEquals(3, prompts)
    }

    @Test fun displayedEndpointDoesNotExposeCredentials() {
        assertEquals("https://example.com/v1/chat/completions",
            egressEndpoint("https://user:secret@example.com/v1/chat/completions?key=secret#token"))
    }

    @Test fun distinctConnectionsAtSameEndpointDoNotShareGrants() = runBlocking {
        var prompts = 0
        val policy = DataEgressPolicy({ LocalDataMode.ASK }) { prompts++; DataEgressChoice.SESSION }
        val request = DataEgressRequest("s", "https://example.com/a2a", setOf("file"), "account-a")
        policy.check(request)
        policy.check(request.copy(destinationId = "account-b"))
        policy.check(request)
        assertEquals(2, prompts)
    }

    @Test fun policyChangedWhileApprovalWasOpenCannotGrantStalePermission() = runBlocking {
        var mode = LocalDataMode.ASK
        val decisions = mutableListOf<String>()
        val policy = DataEgressPolicy({ mode }, record = { _, decision -> decisions.add(decision) }) {
            mode = LocalDataMode.LOCAL_ONLY
            DataEgressChoice.SESSION
        }
        try {
            policy.check(DataEgressRequest("s", "model", setOf("image")))
            fail("must reject stale approval")
        } catch (_: ModelRequestRejectedException) {}
        assertEquals(listOf("仅本地 · 已阻止"), decisions)
    }
}
