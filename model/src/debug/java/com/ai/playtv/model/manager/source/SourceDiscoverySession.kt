package com.ai.playtv.model.manager.source

import com.ai.playtv.model.bean.domain.source.SourceDiscovery
import com.ai.playtv.model.repository.source.SourceRepository
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
)

/** One in-memory scan; the session owner closes it when playback ends. */
class SourceDiscoverySession(
    repository: SourceRepository,
    input: String,
    private val release: () -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState = MutableStateFlow(SourceDiscoverySessionState())
    val state = mutableState.asStateFlow()

    init {
        scope.launch {
            try {
                val result = repository.discover(input) { update ->
                    if (!closed.get()) mutableState.value = SourceDiscoverySessionState(update)
                }
                if (!closed.get()) mutableState.value = SourceDiscoverySessionState(result, running = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!closed.get()) mutableState.value = mutableState.value.copy(
                    running = false,
                    error = if (error is retrofit2.HttpException && error.code() == 403) "RATE_LIMIT" else "FAILED",
                )
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        mutableState.value = mutableState.value.copy(running = false)
        // Retrofit cancellation may close a socket synchronously, so do it away from Main.
        CoroutineScope(dispatcher).launch {
            try { scope.cancel() } finally { release() }
        }
    }
}
