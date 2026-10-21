package dev.kiora.companion

import android.content.ComponentName
import android.content.pm.ActivityInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityRotationTest {
    @Test fun rotationKeepsTheConversationActivityAlive() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java), 0,
        )

        assertTrue(info.configChanges and ActivityInfo.CONFIG_ORIENTATION != 0)
        assertTrue(info.configChanges and ActivityInfo.CONFIG_SCREEN_SIZE != 0)
    }
}
