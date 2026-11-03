package dev.kiora.companion

import dev.kiora.core.*
import org.junit.Assert.*
import org.junit.Test

class ToolTraceVerificationTest {
    private val trace = ToolTrace("c", "shell", "{}")
    private fun result(verification: ToolVerification? = null, error: Boolean = false) =
        AgentMessage.ToolResult("c", "shell", "exit=0", error, emptyList(), null, verification = verification)

    @Test fun verificationStatesSurviveConversationReload() {
        for ((status, state) in listOf(
            ToolVerificationStatus.VERIFIED to ToolTrace.State.VERIFIED,
            ToolVerificationStatus.UNVERIFIED to ToolTrace.State.UNVERIFIED,
            ToolVerificationStatus.FAILED to ToolTrace.State.FAILED,
        )) {
            val completed = trace.completed(result(ToolVerification(status, "observed"), status == ToolVerificationStatus.FAILED))
            assertEquals(state, completed.state)
            assertEquals(completed, ToolTrace.fromStoredText(completed.toStoredText()))
            val message = result(ToolVerification(status, "observed"), status == ToolVerificationStatus.FAILED)
            assertEquals(message, AgentMessageCodec.decode(AgentMessageCodec.encode(message)))
        }
    }

    @Test fun errorsWinAndLegacySuccessDoesNotClaimVerification() {
        assertEquals(ToolTrace.State.FAILED, trace.completed(result(error = true)).state)
        assertEquals(ToolTrace.State.SUCCEEDED, trace.completed(result()).state)
        assertEquals(ToolTrace.State.SUCCEEDED, ToolTrace.fromStoredText(
            """{"callId":"c","name":"shell","arguments":"{}","state":"SUCCEEDED"}""")!!.state)
    }
}
