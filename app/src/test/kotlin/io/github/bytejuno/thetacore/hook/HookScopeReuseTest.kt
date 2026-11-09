package io.github.bytejuno.thetacore.hook

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

class HookScopeReuseTest {
    private val target = "sample.target"

    @Test fun existingScopeNeverRequestsOrReservesTicket() = runBlocking {
        val exhausted = HookScopeBudget(0)
        ensureHookScope(target, { listOf(target) }, {}) { exhausted.reserve(target); fail("No request expected") }
    }
    @Test fun missingScopeRequestsAndRechecksFramework() = runBlocking {
        var scope = emptyList<String>()
        var requests = 0
        ensureHookScope(target, { scope }, {}) { requests++; scope = listOf(target) }
        assertEquals(1, requests)
    }
    @Test fun callbackWithoutFrameworkGrantDoesNotApprove() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { ensureHookScope(target, { emptyList() }, {}) {} }
        }
    }
    @Test fun revokedScopeAfterInitialReadFails() {
        var reads = 0
        assertThrows(IllegalStateException::class.java) {
            runBlocking { ensureHookScope(target, { if (reads++ == 0) listOf(target) else emptyList() }, {}) { fail() } }
        }
    }
    @Test fun failedScopeQueryIsNotApprovalOrNewRequest() {
        val failure = IllegalStateException("IPC")
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            runBlocking { ensureHookScope(target, { throw failure }, {}) { fail() } }
        })
    }
    @Test fun supersededActionFailsEvenWithExistingScope() {
        val order = HookHostOrder()
        val action = order.next()
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                ensureHookScope(target, { order.next(); listOf(target) }, { order.checkCurrent(action) }) { fail() }
            }
        }
    }
    @Test fun missingScopeFailureIsPreserved() {
        val denied = IllegalStateException("denied")
        assertSame(denied, assertThrows(IllegalStateException::class.java) {
            runBlocking { ensureHookScope(target, { emptyList() }, {}) { throw denied } }
        })
    }
    @Test fun cancellationDuringGrantedScopeReadCannotReturnGrant() {
        for (cancelOnRead in 1..2) {
            var returned = false
            val job = Job()
            var reads = 0
            assertThrows(CancellationException::class.java) {
                runBlocking {
                    withContext(job) {
                        ensureHookScope(target, {
                            if (++reads == cancelOnRead) job.cancel()
                            listOf(target)
                        }, {}) { fail("Existing scope must not request") }
                        returned = true
                    }
                }
            }
            assertFalse(returned)
        }
    }
    @Test fun cancellationDuringMissingScopeReadCannotSubmitRequest() {
        val job = Job()
        assertThrows(CancellationException::class.java) {
            runBlocking {
                withContext(job) {
                    ensureHookScope(target, { job.cancel(); emptyList() }, {}) {
                        fail("Cancelled query must not submit a request")
                    }
                }
            }
        }
    }
}
