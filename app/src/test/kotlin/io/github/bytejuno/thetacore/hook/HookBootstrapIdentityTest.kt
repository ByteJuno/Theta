package io.github.bytejuno.thetacore.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class HookBootstrapIdentityTest {
    @Test fun ordinaryShellAndOtherUserSendersCannotReachTheBootstrapDelegate() {
        for (uid in listOf(-1, 999, 1001, 2000, 10000, 10123, 101000, 200000, Int.MAX_VALUE)) {
            var forwarded = false
            assertThrows(SecurityException::class.java) {
                withTrustedHookBootstrapSender(uid) {
                    forwarded = true
                    error("Untrusted binder must not be inspected")
                }
            }
            assertEquals("Unexpected forwarding for UID $uid", false, forwarded)
        }
    }

    @Test fun platformSendersForwardOnceAndPreserveTheOfficialResult() {
        for (uid in listOf(0, 1000)) {
            val result = Any()
            var calls = 0
            assertSame(result, withTrustedHookBootstrapSender(uid) { calls++; result })
            assertEquals(1, calls)
        }
    }

    @Test fun delegateFailureDoesNotFallBackToAnUnauthenticatedPath() {
        val failure = IllegalStateException("framework unavailable")
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            withTrustedHookBootstrapSender(1000) { throw failure }
        })
    }
}
