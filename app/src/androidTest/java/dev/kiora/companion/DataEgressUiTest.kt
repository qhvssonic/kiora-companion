package dev.kiora.companion

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.kiora.core.ModelRequestRejectedException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Exercises the production approval sheet with synthetic metadata; never calls a network client. */
class DataEgressUiTest {
    @Test fun denialAndRecipientScopedApprovalUseTheRealSheet() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("egressUi") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand("am start -W -n dev.kiora.companion/.MainActivity")
            .use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } }
        var activity: MainActivity? = null
        var sessionId: String? = null
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (sessionId == null) {
            instrumentation.runOnMainSync {
                activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull()
                sessionId = activity?.let { host -> MainActivity::class.java.getDeclaredField("currentConversationId")
                    .apply { isAccessible = true }.get(host) as? String }
            }
            check(SystemClock.elapsedRealtime() < deadline) { "No foreground chat session" }
            delay(50)
        }
        val host = checkNotNull(activity)
        val method = MainActivity::class.java.getDeclaredMethod("confirmDataEgress",
            DataEgressRequest::class.java, Continuation::class.java).apply { isAccessible = true }
        suspend fun show(request: DataEgressRequest): DataEgressChoice =
            suspendCoroutineUninterceptedOrReturn { continuation -> method.invoke(host, request, continuation) }
        suspend fun click(text: String) {
            val until = SystemClock.elapsedRealtime() + 10_000
            while (true) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                val node = root?.findAccessibilityNodeInfosByText(text)
                    ?.firstOrNull { it.text?.toString() == text && it.isClickable }
                if (node != null) {
                    assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    return
                }
                check(SystemClock.elapsedRealtime() < until) { "Approval button unavailable: $text" }
                delay(50)
            }
        }
        val policy = DataEgressPolicy({ LocalDataMode.ASK }, confirm = ::show)
        val request = DataEgressRequest(checkNotNull(sessionId), "测试接收方 https://example.invalid/a2a",
            setOf("合成扫描文字"), "test-account-a")
        val denied = async { runCatching { policy.check(request) }.exceptionOrNull() }
        click("拒绝")
        assertTrue(denied.await() is ModelRequestRejectedException)
        val allowed = async { policy.check(request) }
        click("本会话允许")
        allowed.await()
        policy.check(request.copy(sources = setOf("合成摘要")))
        val other = async { runCatching { policy.check(request.copy(destinationId = "test-account-b")) }.exceptionOrNull() }
        click("拒绝")
        assertTrue(other.await() is ModelRequestRejectedException)
    }
}
