package com.ai.playtv.model.manager.source

import com.ai.playtv.model.bean.domain.source.SourceDiscovery
import com.ai.playtv.model.repository.source.SourceRepository
import com.ai.playtv.model.repository.source.sourceHttpStatus
import com.ai.playtv.model.repository.source.isGithubRateLimited
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SourceDiscoverySessionState(
    val result: SourceDiscovery? = null,
    val running: Boolean = true,
    val error: String? = null,
    val cancelled: Boolean = false,
)

/** One in-memory scan; the session owner closes it when playback ends. */
class SourceDiscoverySession(
    repository: SourceRepository,
    input: String,
    private val release: () -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val stateLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState = MutableStateFlow(SourceDiscoverySessionState())
    val state = mutableState.asStateFlow()

    init {
        scope.launch {
            try {
                val result = repository.discover(input) { update ->
                    synchronized(stateLock) {
                        if (!closed.get()) mutableState.value = SourceDiscoverySessionState(update)
                    }
                }
                synchronized(stateLock) {
                    if (!closed.get()) mutableState.value = SourceDiscoverySessionState(result, running = false,
                        error = if (result.rateLimited) "RATE_LIMIT" else null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                synchronized(stateLock) {
                    if (!closed.get()) mutableState.value = mutableState.value.copy(
                        running = false,
                        error = when {
                            error.isGithubRateLimited() -> "RATE_LIMIT"
                            error.sourceHttpStatus() == 403 -> "HTTP_403"
                            else -> "FAILED"
                        },
                    )
                }
            }
        }
    }

    override fun close() {
        synchronized(stateLock) {
            if (!closed.compareAndSet(false, true)) return
            val previous = mutableState.value
            mutableState.value = previous.copy(running = false, cancelled = previous.running)
        }
        // Retrofit cancellation may close a socket synchronously, so do it away from Main.
        CoroutineScope(dispatcher).launch {
            try { scope.cancel() } finally { release() }
        }
    }
}
