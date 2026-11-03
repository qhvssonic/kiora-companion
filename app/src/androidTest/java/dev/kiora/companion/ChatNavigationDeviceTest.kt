package dev.kiora.companion

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in regression against the installed local model. No chat messages or A2A tasks are sent.
 * The existing A2A cards may be fetched when deliberately retrying the connection.
 */
class ChatNavigationDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun field(host: Any, name: String): Any? = host.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(host)

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use {
            java.io.FileInputStream(it.fileDescriptor).use { input -> input.readBytes() }
        }
    }

    private suspend fun resumed(type: Class<out Activity>): Activity {
        val until = SystemClock.elapsedRealtime() + 60_000
        while (true) {
            var found: Activity? = null
            instrumentation.runOnMainSync {
                found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .firstOrNull { type.isInstance(it) }
            }
            if (found != null) return found!!
            check(SystemClock.elapsedRealtime() < until) { "Requested screen not resumed" }
            delay(50)
        }
    }

    private suspend fun ready(host: MainActivity) {
        val until = SystemClock.elapsedRealtime() + 90_000
        while (true) {
            var done = false
            instrumentation.runOnMainSync {
                done = field(host, "modelReady") == true && field(host, "connecting") == false
            }
            if (done) return
            check(SystemClock.elapsedRealtime() < until) { "Chat model did not become ready" }
            delay(50)
        }
    }

    @Test fun navigationAndRuntimeUpdatesKeepTheSamePreparedModel() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("modelNavigation") == "true")
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("companion_settings", 0)
        assumeTrue("Import and select a local chat model first", prefs.getString("provider_id", "") in listOf("litert", "mnn"))
        shell("am start -W -n ${context.packageName}/.MainActivity")
        val host = resumed(MainActivity::class.java) as MainActivity
        ready(host)
        lateinit var agent: CompanionAgent
        lateinit var model: Any
        var initialRequest = 0
        instrumentation.runOnMainSync {
            agent = field(host, "agent") as CompanionAgent
            model = checkNotNull(field(agent, "localModel") ?: field(agent, "mnnModel"))
            initialRequest = field(host, "connectionRequest") as Int
        }
        fun assertModel() = instrumentation.runOnMainSync {
            assertSame(model, field(agent, "localModel") ?: field(agent, "mnnModel"))
            assertTrue(agent.hasModel)
            assertEquals(true, field(host, "modelReady"))
        }

        repeat(2) {
            instrumentation.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("showSettings", Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(host, false)
            }
            val settings = resumed(SettingsActivity::class.java)
            instrumentation.runOnMainSync { settings.finish() }
            assertSame(host, resumed(MainActivity::class.java))
            ready(host)
            assertModel()
            instrumentation.runOnMainSync { assertEquals(initialRequest, field(host, "connectionRequest")) }
        }

        for (type in listOf(FaceActivity::class.java, AlbumIndexActivity::class.java)) {
            instrumentation.runOnMainSync { host.startActivity(Intent(host, type)) }
            val other = resumed(type)
            instrumentation.runOnMainSync { other.finish() }
            assertSame(host, resumed(MainActivity::class.java))
            ready(host)
            assertModel()
        }

        shell("input keyevent KEYCODE_HOME")
        shell("am start -W -n ${context.packageName}/.MainActivity")
        assertSame(host, resumed(MainActivity::class.java))
        ready(host)
        assertModel()

        val hadWindow = prefs.contains("context_window_tokens")
        val originalWindow = prefs.getInt("context_window_tokens", 0)
        val settingsResult = MainActivity::class.java.getDeclaredMethod("onActivityResult",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java)
            .apply { isAccessible = true }
        val settingsRequest = MainActivity::class.java.getDeclaredField("SETTINGS")
            .apply { isAccessible = true }.getInt(null)
        try {
            // Runtime budgeting changes and an explicit A2A retry may rebuild tools, not weights.
            check(prefs.edit().putInt("context_window_tokens", if (originalWindow == 4096) 4608 else 4096).commit())
            instrumentation.runOnMainSync { settingsResult.invoke(host, settingsRequest, Activity.RESULT_OK, Intent()) }
            ready(host)
            assertModel()
        } finally {
            val edit = prefs.edit()
            if (hadWindow) edit.putInt("context_window_tokens", originalWindow) else edit.remove("context_window_tokens")
            check(edit.commit())
            instrumentation.runOnMainSync { settingsResult.invoke(host, settingsRequest, Activity.RESULT_OK, Intent()) }
            ready(host)
        }
        assertModel()
        println("Navigation verified: 2 settings visits, face screen, album screen, background return and runtime settings update reused the prepared model")
    }
}
