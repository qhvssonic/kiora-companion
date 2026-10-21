package dev.kiora.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamMarkdownTest {
    @Test fun commitsOnlyAfterTheNextBlockHasStarted() {
        val text = "First paragraph.\n\n# Next heading\n"
        assertEquals("First paragraph.\n\n".length, StreamMarkdown.completedPrefixEnd(text))
        assertEquals(0, StreamMarkdown.completedPrefixEnd("First paragraph.\n\n"))
    }

    @Test fun keepsBlankLinesInsideFencedCodeInTheMutableTail() {
        val text = "```kotlin\nval a = 1\n\nval b = 2\n```\n\nAfterwards\n"
        assertEquals("```kotlin\nval a = 1\n\nval b = 2\n```\n\n".length,
            StreamMarkdown.completedPrefixEnd(text))
    }

    @Test fun keepsLooseListTogether() {
        val text = "1. First\n\n2. Second\n\nDone\n"
        assertEquals("1. First\n\n2. Second\n\n".length, StreamMarkdown.completedPrefixEnd(text))
    }
}
