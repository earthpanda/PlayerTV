package com.ai.playtv.domain

import com.ai.playtv.base.diagnostics.DiagnosticLog
import com.ai.playtv.base.diagnostics.DiagnosticLevel
import com.ai.playtv.base.diagnostics.DiagnosticEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.playtv.model.contract.mvi.UiEffect
import com.ai.playtv.model.contract.mvi.UiEvent
import com.ai.playtv.model.contract.mvi.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Typed MVI execution base; contract definitions belong to model.
 * Subclasses use setState { copy(...) }, setEffect(...), and launchSafe { repository.load() }.
 * Effects have one active UI consumer; buffered delivery is not an exactly-once guarantee.
 */
abstract class BaseMviViewModel<S : UiState, E : UiEvent, F : UiEffect>(initialState: S) : ViewModel() {
    private companion object {
        /** Bound both queued effects and memory use while the single UI consumer is stopped. */
        const val EFFECT_CAPACITY = 64
    }

    /** Internal mutable state is never exposed to callers. */
    private val mutableState = MutableStateFlow(initialState)
    /** Read-only lifecycle-collected state snapshots. */
    val uiState = mutableState.asStateFlow()
    /** Full queues reject new transient effects instead of accumulating suspended senders. */
    private val effects = Channel<F>(EFFECT_CAPACITY)
    /** Transient results are collected separately from renderable state. */
    val effect = effects.receiveAsFlow()
    /** Current snapshot for guards and request parameters. */
    protected val currentState: S get() = mutableState.value

    /** Dispatch an input defined by the feature's model Contract. */
    abstract fun onEvent(event: E)

    /** Atomically update immutable state; reducers must not perform side effects. */
    protected fun setState(reducer: S.() -> S) {
        mutableState.update { it.reducer() }
    }

    /**
     * Try to queue disposable UI feedback without launching a sender coroutine.
     * False means full or closed; important outcomes belong in State or persistent storage.
     * Every rejection logs metadata only, never the effect payload.
     */
    protected fun setEffect(value: F): Boolean {
        val result = effects.trySend(value)
        if (result.isFailure) {
            val reason = if (result.isClosed) "channel_closed" else "buffer_full"
            DiagnosticLog.write(DiagnosticLevel.WARN, DiagnosticEvent("mvi", "effect", reason,
                capacity = EFFECT_CAPACITY, ownerType = this::class.java.simpleName,
                itemType = value::class.java.simpleName))
        }
        return result.isSuccess
    }

    /**
     * Launch on Main, preserving cancellation; repositories choose IO for blocking work.
     * This deliberately does not copy the source project's implicit IO dispatcher.
     */
    protected fun launchSafe(block: suspend CoroutineScope.() -> Unit): Job =
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                handleError(error)
            }
        }

    /** Last-resort technical logging; features define their own recovery and UI results. */
    protected open fun handleError(error: Exception) {
        DiagnosticLog.write(DiagnosticLevel.ERROR, DiagnosticEvent("mvi", "unhandled", "failed",
            errorType = error.javaClass.simpleName, ownerType = this::class.java.simpleName))
    }

    /** Release the transient-result queue when the owner permanently clears this instance. */
    override fun onCleared() {
        effects.close()
        super.onCleared()
    }
}
