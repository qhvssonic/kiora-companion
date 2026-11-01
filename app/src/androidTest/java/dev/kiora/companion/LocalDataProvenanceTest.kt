package dev.kiora.companion

import androidx.test.platform.app.InstrumentationRegistry
import dev.kiora.core.AgentMessage
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class LocalDataProvenanceTest {
    @Test fun sourceSurvivesRecreationAndSummaryWhilePlainNewSessionStaysUnmarked() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "test-provenance-${UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, 0)
        try {
            val source = LocalDataProvenance(context, name)
            source.inheritHistory("new", emptyList())
            source.inheritHistory("new", listOf(AgentMessage.User("hello"), AgentMessage.Assistant("hello")))
            assertTrue(source.sources("new").isEmpty())
            source.mark("private", "扫描文字与原图")
            val restored = LocalDataProvenance(context, name)
            restored.inheritHistory("private", listOf(AgentMessage.ContextSummary("derived answer")))
            assertTrue("扫描文字与原图" in restored.sources("private"))
            restored.inheritHistory("legacy", listOf(AgentMessage.User("older OCR may appear as plain text")))
            assertTrue(restored.sources("legacy").isNotEmpty())
        } finally { prefs.edit().clear().commit() }
    }
}
