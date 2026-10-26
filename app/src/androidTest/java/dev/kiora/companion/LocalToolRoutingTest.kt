package dev.kiora.companion

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import dev.kiora.core.AgentMessage
import dev.kiora.core.WorkspaceTools
import dev.kiora.model.litert.LiteRtBackend
import dev.kiora.model.litert.LiteRtModelPort
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in real-model checks. No tool execution, network requests, or conversation writes. */
class LocalToolRoutingTest {
    @Test fun distinguishesKnowledgeFromExplicitFileRequests() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("routingRegression") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settings = context.getSharedPreferences("companion_settings", 0)
        val modelFile = File(settings.getString("local_model_path", "").orEmpty())
        check(modelFile.isFile) { "Import a LiteRT model before running this check" }
        // Launch the main app immediately before `am instrument` on OEM devices that
        // deny a background activity start. Settings itself loads no chat model.
        val activity = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.runOnMainSync {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val model = LiteRtModelPort(modelFile,
            backend = if (settings.getString("local_backend", "cpu") == "gpu") LiteRtBackend.GPU else LiteRtBackend.CPU,
            cacheDirectory = File(context.cacheDir, "litert"), enableVision = true)
        val workspace = WorkspaceTools(File(context.filesDir, "agent-workspace"), "/system/bin/sh").all()
        val read = PhoneResourceTools(workspace.first { it.definition.name == "read" }, emptyList(), emptyList()).all().first()
        val definitions = (listOf(read, DeviceTimeTool()) + workspace.filter {
            it.definition.name in setOf("write", "edit")
        }).map { it.definition }
        fun report(label: String, response: AgentMessage.Assistant) {
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "$label: tools=${response.toolCalls.map { it.name to it.arguments }} text=${response.text}\n")
            })
        }
        val failures = mutableListOf<String>()
        fun verify(condition: Boolean, message: String) {
            if (!condition) failures += message
        }
        try {
            model.prepare()
            report("poem_without_tools", model.respond(listOf(AgentMessage.User("你知道锄禾吗？")), emptyList()))
            val poem = model.respond(listOf(AgentMessage.User("你知道锄禾吗？")), definitions)
            report("poem", poem)
            verify(poem.toolCalls.isEmpty(), "poem: general knowledge must not read files")
            verify("锄禾日当午" in poem.text && "汗滴禾下土" in poem.text, "poem: expected poem missing")

            val explicitPoem = model.respond(listOf(AgentMessage.User("请写出以“锄禾日当午”开头的古诗全文。")), definitions)
            report("explicit_poem", explicitPoem)
            verify(explicitPoem.toolCalls.isEmpty(), "explicit_poem: unexpected tool call")
            verify("汗滴禾下土" in explicitPoem.text && "粒粒皆辛苦" in explicitPoem.text,
                "explicit_poem: incorrect poem")

            val arithmetic = model.respond(listOf(AgentMessage.User("17乘23等于多少？")), definitions)
            report("arithmetic", arithmetic)
            verify(arithmetic.toolCalls.isEmpty(), "arithmetic: unexpected tool call")
            verify("391" in arithmetic.text, "arithmetic: incorrect answer")

            val followUp = model.respond(listOf(
                AgentMessage.User("给我讲一首古诗"),
                AgentMessage.Assistant("好的，这是唐诗《静夜思》。"),
                AgentMessage.User("你知道锄禾吗？"),
            ), definitions)
            report("poem_follow_up", followUp)
            verify(followUp.toolCalls.isEmpty(), "poem_follow_up: unexpected tool call")
            verify("锄禾日当午" in followUp.text, "poem_follow_up: expected poem missing")

            val file = model.respond(listOf(AgentMessage.User(
                "请读取本轮选中的文本附件并总结。\n[Selected files: selected:0. Use read with selected:0 to inspect its contents.]"
            )), definitions)
            report("explicit_file", file)
            verify(file.toolCalls.singleOrNull()?.name == "read", "explicit_file: expected one read call")
            verify(file.toolCalls.singleOrNull()?.arguments?.get("path")?.jsonPrimitive?.content == "selected:0",
                "explicit_file: incorrect file ID")
            assertTrue(failures.joinToString("\n"), failures.isEmpty())
        } finally {
            model.close()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
