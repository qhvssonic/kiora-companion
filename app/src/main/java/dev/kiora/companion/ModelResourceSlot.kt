package dev.kiora.companion

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One model owned by an agent, serialized by that agent's configuration/send lock.
 * UI navigation and tool reconfiguration must not replace an unchanged model.
 */
internal class ModelResourceSlot<K, T : Any>(private val dispose: suspend (T) -> Unit) {
    private var key: K? = null
    private var value: T? = null

    fun matches(candidate: K): Boolean = value != null && key == candidate

    suspend fun getOrLoad(candidate: K, load: suspend () -> T): T {
        value?.let { if (key == candidate) return it }
        close()
        currentCoroutineContext().ensureActive()
        val loaded = load()
        try {
            currentCoroutineContext().ensureActive()
        } catch (error: Throwable) {
            withContext(NonCancellable) { dispose(loaded) }
            throw error
        }
        key = candidate
        value = loaded
        return loaded
    }

    suspend fun close() {
        val old = value
        value = null
        key = null
        if (old != null) withContext(NonCancellable) { dispose(old) }
    }
}
