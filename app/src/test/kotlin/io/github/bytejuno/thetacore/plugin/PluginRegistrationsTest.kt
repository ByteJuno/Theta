package io.github.bytejuno.thetacore.plugin

import io.github.bytejuno.thetacore.sdk.PluginClosedException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class PluginRegistrationsTest {
    @Test fun rejectsDuplicateIdsUntilRevoked() {
        var visible = emptyList<String>()
        val registry = PluginRegistrations<String> { visible = it }
        val revoke = registry.register("page", "first")
        assertThrows(IllegalArgumentException::class.java) { registry.register("page", "second") }
        assertEquals(listOf("first"), visible)
        revoke()
        revoke()
        registry.register("page", "second")
        assertEquals(listOf("second"), visible)
    }

    @Test fun closesAdmissionBeforeCancellationAndCleanup() {
        var visible = emptyList<String>()
        val events = mutableListOf<String>()
        val registry = PluginRegistrations<String> { visible = it }
        registry.register("page", "page")
        registry.onCleanup { events += "first" }
        registry.onCleanup { events += "second" }
        assertTrue(registry.close {
            assertTrue(visible.isEmpty())
            assertThrows(PluginClosedException::class.java) { registry.register("late", "late") }
            assertThrows(PluginClosedException::class.java) { registry.onCleanup {} }
            events += "cancel"
        }.isEmpty())
        assertEquals(listOf("cancel", "second", "first"), events)
        assertTrue(registry.close { fail("closed twice") }.isEmpty())
    }

    @Test fun revokedCleanupRunsOnlyOnce() {
        val registry = PluginRegistrations<String>()
        var calls = 0
        val revoke = registry.onCleanup { calls++ }
        revoke()
        revoke()
        registry.close()
        assertEquals(1, calls)
    }

    @Test fun cleanupFailuresDoNotPreventLaterCleanup() {
        val registry = PluginRegistrations<String>()
        var cleaned = false
        registry.onCleanup { cleaned = true }
        val cleanupFailure = IllegalStateException("cleanup")
        val cancelFailure = IllegalArgumentException("cancel")
        registry.onCleanup { throw cleanupFailure }
        assertEquals(listOf(cancelFailure, cleanupFailure), registry.close { throw cancelFailure })
        assertTrue(cleaned)
    }

    @Test fun revokedThrowingCleanupIsNotRetried() {
        val registry = PluginRegistrations<String>()
        var calls = 0
        val revoke = registry.onCleanup { calls++; error("failed") }
        assertThrows(IllegalStateException::class.java) { revoke() }
        revoke()
        assertTrue(registry.close().isEmpty())
        assertEquals(1, calls)
    }

    @Test fun cleanupCallbacksRunOutsideRegistryLock() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val registry = PluginRegistrations<String>()
            registry.onCleanup {
                executor.submit(Callable {
                    assertThrows(PluginClosedException::class.java) { registry.register("late", "late") }
                }).get(2, TimeUnit.SECONDS)
            }
            assertTrue(registry.close().isEmpty())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun racingRegistrationIsRejectedOrIncludedInClose() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(200) {
                val registry = PluginRegistrations<String>()
                val start = CountDownLatch(1)
                val cleaned = AtomicInteger()
                val registration = executor.submit(Callable {
                    start.await()
                    try {
                        registry.onCleanup { cleaned.incrementAndGet() }
                        true
                    } catch (_: PluginClosedException) {
                        false
                    }
                })
                val closing = executor.submit(Callable { start.await(); registry.close() })
                start.countDown()
                val accepted = registration.get(2, TimeUnit.SECONDS)
                assertTrue(closing.get(2, TimeUnit.SECONDS).isEmpty())
                assertEquals(if (accepted) 1 else 0, cleaned.get())
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun concurrentRevokeAndCloseNeverDoubleClean() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(200) {
                val registry = PluginRegistrations<String>()
                val calls = AtomicInteger()
                val revoke = registry.onCleanup { calls.incrementAndGet() }
                val start = CountDownLatch(1)
                val revoking = executor.submit(Callable { start.await(); revoke() })
                val closing = executor.submit(Callable { start.await(); registry.close() })
                start.countDown()
                revoking.get(2, TimeUnit.SECONDS)
                closing.get(2, TimeUnit.SECONDS)
                assertEquals(1, calls.get())
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
