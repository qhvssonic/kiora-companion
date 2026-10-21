package dev.kiora.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class A2aConnectQrTest {
    @Test fun parsesComputerQrWithoutExposingMetadataAsCapabilities() {
        val payload = A2aConnectQr.parse("""{"type":"a2a-agent-connect","v":1,
            "agentCardUrl":"http://192.168.1.5:8000/.well-known/agent-card.json",
            "token":"secret-token","scopes":["rag:search"],"user":"zhangsan"}""")
        assertEquals("http://192.168.1.5:8000/.well-known/agent-card.json", payload.agentCardUrl)
        assertEquals("secret-token", payload.token)
        assertEquals(listOf("rag:search"), payload.scopes)
        assertEquals("zhangsan", payload.user)
    }

    @Test fun rejectsUnknownVersionAndNonHttpTargets() {
        assertThrows(IllegalArgumentException::class.java) {
            A2aConnectQr.parse("""{"type":"a2a-agent-connect","v":2,
                "agentCardUrl":"https://example.com/card","token":"x"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            A2aConnectQr.parse("""{"type":"a2a-agent-connect","v":1,
                "agentCardUrl":"file:///etc/passwd","token":"x"}""")
        }
    }

    @Test fun rejectsMalformedTokenAndWrongType() {
        assertThrows(IllegalArgumentException::class.java) {
            A2aConnectQr.parse("""{"type":"a2a-agent-connect","v":1,
                "agentCardUrl":"https://example.com/card","token":"bad\nheader"}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            A2aConnectQr.parse("""{"type":"wrong","v":1,
                "agentCardUrl":"https://example.com/card","token":"x"}""")
        }
    }
}
