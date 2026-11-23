package io.github.bytejuno.thetacore.hook

import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.UiText
import io.github.bytejuno.thetacore.uiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Status belongs to an intent, not to the lifetime of its UI continuation. */
internal class HookActionStatus {
    private val mutableStatus = MutableStateFlow(uiText(R.string.status_framework_unavailable))
    val status = mutableStatus.asStateFlow()
    private var action = 0L
    private var finished = true

    @Synchronized fun begin(ticket: Long) {
        if (ticket <= action) return
        action = ticket
        finished = false
    }

    @Synchronized fun waiting(ticket: Long) {
        if (ticket == action && !finished) mutableStatus.value = uiText(R.string.status_scope_requested)
    }

    @Synchronized fun complete(ticket: Long, text: UiText) {
        if (ticket != action || finished) return
        finished = true
        mutableStatus.value = text
    }

    @Synchronized fun cancel(ticket: Long) {
        complete(ticket, uiText(R.string.status_hook_action_cancelled))
    }

    @Synchronized fun connection(ticket: Long, text: UiText) {
        if (ticket <= action) return
        begin(ticket)
        complete(ticket, text)
    }

    @Synchronized fun report(text: UiText) { mutableStatus.value = text }

    suspend fun run(
        ticket: Long,
        dispatcher: CoroutineDispatcher,
        onFailure: (Throwable) -> Unit,
        operation: suspend () -> Unit,
    ) {
        try {
            withContext(dispatcher) {
                // Record failures before returning to a UI continuation that may be cancelled.
                try {
                    operation()
                } catch (error: TimeoutCancellationException) {
                    if (!currentCoroutineContext().isActive) throw error
                    complete(ticket, uiText(R.string.operation_failed, error.javaClass.simpleName))
                    onFailure(error)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    complete(ticket, uiText(R.string.operation_failed, error.javaClass.simpleName))
                    onFailure(error)
                }
            }
        } catch (error: CancellationException) {
            cancel(ticket)
            throw error
        } catch (error: Throwable) {
            complete(ticket, uiText(R.string.operation_failed, error.javaClass.simpleName))
            onFailure(error)
        }
    }
}
