package dev.kiora.companion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecretStoreTest {
    @Test fun encryptedCredentialsSurviveNewStoreInstanceAndCanBeCleared() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefsName = "test_companion_secrets"
        val alias = "dev.kiora.companion.test.credentials"
        context.getSharedPreferences(prefsName, 0).edit().clear().commit()
        try {
            val first = SecretStore(context, prefsName, alias)
            first.save("openai", "test-api-key", "test-bearer-token")
            first.save("deepseek", "other-api-key", "test-bearer-token")

            val second = SecretStore(context, prefsName, alias)
            assertEquals("test-api-key", second.apiKey("openai"))
            assertEquals("other-api-key", second.apiKey("deepseek"))
            assertEquals("test-bearer-token", second.bearerToken())
            assertFalse(context.getSharedPreferences(prefsName, 0).all.values
                .any { it.toString().contains("test-api-key") || it.toString().contains("test-bearer-token") })

            second.save("openai", "", "")
            assertEquals("", SecretStore(context, prefsName, alias).apiKey("openai"))
            assertEquals("other-api-key", second.apiKey("deepseek"))
            assertEquals("", second.bearerToken())
        } finally {
            context.getSharedPreferences(prefsName, 0).edit().clear().commit()
        }
    }
}
