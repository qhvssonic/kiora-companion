package dev.kiora.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileMarkdownTablesTest {
    @Test fun longTableBecomesReadableRowsWithoutLosingCells() {
        val input = "# 标题\n\n| 序号 | 场景 | 详细要求 |\n| :--- | --- | ---: |\n" +
            "| 1 | 在岗检测 | 识别人员<br>记录反馈 |\n| 2 | 客流 | 预测峰值 |"
        val result = MobileMarkdownTables.format(input)

        assertTrue(result.startsWith("# 标题"))
        assertTrue(result.contains("**第 1 行**"))
        assertTrue(result.contains("- **详细要求**：识别人员；记录反馈"))
        assertTrue(result.contains("**第 2 行**"))
        assertTrue(result.contains("- **场景**：客流"))
    }

    @Test fun fencedCodeAndEscapedPipesRemainIntact() {
        val code = "```markdown\n| A | B |\n| --- | --- |\n| 1 | 2 |\n```"
        assertEquals(code, MobileMarkdownTables.format(code))

        val table = "| A | B |\n| --- | --- |\n| left\\|right | value |"
        assertTrue(MobileMarkdownTables.format(table).contains("- **A**：left|right"))
    }
}
