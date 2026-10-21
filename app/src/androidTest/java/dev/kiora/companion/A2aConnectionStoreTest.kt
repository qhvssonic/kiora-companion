package dev.kiora.companion

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class A2aConnectionStoreTest {
    @Test fun legacyConnectionSurvivesMigrationAlongsideNewAgents() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferencesName = "test_a2a_connections"
        val secretName = "test_a2a_connections_secrets"
        val prefs = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        val secrets = SecretStore(context, secretName)
        prefs.edit().clear().putString("card_url", "https://old.example/agent-card.json").commit()
        secrets.save("test", "", "old-token")
        val store = A2aConnectionStore(context, preferencesName, secrets)

        val migrated = store.list()
        assertEquals(1, migrated.size)
        assertEquals("old-token", migrated.single().token)
        val second = A2aConnectionConfig("dsh", "DSH 落库", "https://dsh.example/agent-card.json",
            "dsh-token")
        store.upsert(second)
        store.setEnabled("legacy", false)

        val restored = A2aConnectionStore(context, preferencesName, secrets).list()
        assertEquals(listOf("legacy", "dsh"), restored.map { it.id })
        assertFalse(restored.first().enabled)
        assertEquals("dsh-token", restored.last().token)
        assertFalse(prefs.getString("a2a_connections_v1", "").orEmpty().contains("dsh-token"))
        store.remove("dsh")
        assertEquals(listOf("legacy"), store.list().map { it.id })
    }
}
