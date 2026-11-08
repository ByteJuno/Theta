package io.github.bytejuno.thetacore.hook

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class HookHostLifecycleTest {
    private val first = "theta-hook-00000000-0000-0000-0000-000000000001.apk"
    private val second = "theta-hook-00000000-0000-0000-0000-000000000002.apk"
    private val third = "theta-hook-00000000-0000-0000-0000-000000000003.apk"

    private class Directory : HookPayloadDirectory {
        val files = linkedSetOf<String>()
        var selection: String? = null
        var selectionUnreadable = false
        var rejectDeletes = false
        override fun list() = files.toList()
        override fun selected(): String? {
            check(!selectionUnreadable)
            return selection
        }
        override fun delete(name: String) {
            if (!rejectDeletes) files.remove(name)
        }
    }

    @Test fun consecutiveDotIdIsAcceptedButNeverUsedAsRemoteFilename() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use {
            it.putNextEntry(ZipEntry("assets/theta-hook.json"))
            it.write("""{"schema":1,"id":"sample..hook","version":1,"target":"$HOOK_TARGET","entry":"sample.Entry"}""".toByteArray())
            it.closeEntry()
        }
        assertEquals("sample..hook", HookDescriptor.read(output.toByteArray()).id)
        val name = newHookPayloadName()
        assertTrue(validHookRemoteName(name))
        assertTrue(ownedHookPayload(name))
        assertFalse(name.contains("sample..hook"))
        assertFalse(name.contains(".."))
    }

    @Test fun cleanupPreservesDisabledSelectionAndUnrelatedFiles() {
        val directory = Directory().apply {
            selection = first
            files += listOf(first, second, "other-module.apk", "notes.txt")
        }
        HookPayloadRetention(directory).prepare(third)
        assertEquals(setOf(first, "other-module.apk", "notes.txt"), directory.files)
    }

    @Test fun repeatedPublicationHasOnlySelectedAndOneStagingPayload() {
        val directory = Directory()
        val retention = HookPayloadRetention(directory)
        repeat(100) {
            val name = newHookPayloadName()
            retention.prepare(name)
            directory.files += name
            assertTrue(directory.files.size <= 2)
            directory.selection = name
            retention.clean()
            assertEquals(setOf(name), directory.files)
        }
    }

    @Test fun failedReadbackDeletesStagingButNeverPriorSelection() {
        val directory = Directory().apply { selection = first; files += listOf(first, second) }
        HookPayloadRetention(directory).clean()
        assertEquals(setOf(first), directory.files)
    }

    @Test fun failedCommitWithOptimisticallyUpdatedSdkCachePreservesBothCandidates() {
        val directory = Directory().apply { selection = second; files += listOf(first, second) }
        val fence = HookSelectionFence()
        fence.beforeCommit() // SDK cache says second; framework might still select first.
        val retention = HookPayloadRetention(directory, fence)
        assertThrows(IllegalStateException::class.java) { retention.clean() }
        assertThrows(IllegalStateException::class.java) { retention.prepare(third) }
        assertEquals(setOf(first, second), directory.files)
        // Restart loads actual framework selection, whether old OR new.
        directory.selection = first
        HookPayloadRetention(directory).clean()
        assertEquals(setOf(first), directory.files)
    }

    @Test fun acknowledgedCommitAllowsReclaimingOnlyPriorPayload() {
        val directory = Directory().apply { selection = second; files += listOf(first, second) }
        val fence = HookSelectionFence()
        fence.beforeCommit()
        fence.committed()
        HookPayloadRetention(directory, fence).clean()
        assertEquals(setOf(second), directory.files)
    }

    @Test fun unreadableSelectionDoesNotDeleteAnything() {
        val directory = Directory().apply { files += listOf(first, second); selectionUnreadable = true }
        assertThrows(IllegalStateException::class.java) { HookPayloadRetention(directory).prepare(third) }
        assertEquals(setOf(first, second), directory.files)
    }

    @Test fun malformedSelectionBlocksCleanupWithoutDeletingAnything() {
        val directory = Directory().apply { files += listOf(first, second); selection = "../selected.apk" }
        assertThrows(IllegalStateException::class.java) { HookPayloadRetention(directory).clean() }
        assertEquals(setOf(first, second), directory.files)
    }

    @Test fun failedCleanupBlocksAllocationAndPreservesCurrentPayload() {
        val directory = Directory().apply { files += listOf(first, second); selection = second; rejectDeletes = true }
        assertThrows(IllegalStateException::class.java) { HookPayloadRetention(directory).prepare(third) }
        assertEquals(setOf(first, second), directory.files)
        directory.rejectDeletes = false
        HookPayloadRetention(directory).prepare(third)
        assertEquals(setOf(second), directory.files)
    }

    @Test fun legacyGeneratedNamesAreReclaimedButLookalikesAreNot() {
        assertTrue(ownedHookPayload("sample.hook-12-00000000-0000-0000-0000-000000000001.apk"))
        assertFalse(ownedHookPayload("other.apk"))
        assertFalse(ownedHookPayload("theta-hook-important.apk"))
        assertFalse(ownedHookPayload("../$first"))
    }

    @Test fun existingFilenameCannotBeOverwritten() {
        val directory = Directory().apply { selection = first; files += first }
        assertThrows(IllegalStateException::class.java) { HookPayloadRetention(directory).prepare(first) }
        assertEquals(setOf(first), directory.files)
    }

    @Test fun detachedScopeTicketDropsWaiterAndIgnoresLateApproval() {
        val ticket = HookScopeBudget().reserve(HOOK_TARGET)
        val outcome = ticket.outcome()
        assertTrue(ticket.hasWaiter())
        ticket.detach() // Same cleanup used for timeout, cancellation and synchronous SDK failure.
        assertTrue(outcome.isCancelled)
        assertFalse(ticket.hasWaiter())
        ticket.finish(true)
        assertTrue(outcome.isCancelled)
        assertFalse(ticket.hasWaiter())
        // SDK listener only has this ticket; there is no APK, service, or Context field.
        assertTrue(HookScopeTicket::class.java.declaredFields.filterNot {
            java.lang.reflect.Modifier.isStatic(it.modifiers)
        }.all { it.type == String::class.java || it.type.name == "kotlinx.coroutines.CompletableDeferred" })
    }

    @Test fun scopeBudgetCannotBeReplenishedByTimeoutOrCallbackRaces() {
        val budget = HookScopeBudget(2)
        val first = budget.reserve(HOOK_TARGET)
        first.detach()
        val second = budget.reserve(HOOK_TARGET)
        second.finish(true)
        second.detach()
        assertThrows(IllegalStateException::class.java) { budget.reserve(HOOK_TARGET) }
        first.finish(true)
        assertThrows(IllegalStateException::class.java) { budget.reserve(HOOK_TARGET) }
    }

    @Test fun scopeApprovalAndRejectionReachOnlyActiveWaiter() = runBlocking {
        val approved = HookScopeTicket(HOOK_TARGET)
        val approval = approved.outcome()
        approved.finish(true)
        assertTrue(approval.await())
        approved.detach()
        val denied = HookScopeTicket(HOOK_TARGET)
        val rejection = denied.outcome()
        denied.finish(false)
        assertFalse(rejection.await())
        denied.detach()
    }

    @Test fun disableInvalidatesEarlierApprovalWithoutWaitingForRemoteIo() {
        val order = HookHostOrder()
        val publishing = order.next()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val worker = pool.submit {
                order.exclusive {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    assertThrows(IllegalStateException::class.java) { order.checkCurrent(publishing) }
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val disable = pool.submit<Long> { order.next() }.get(5, TimeUnit.SECONDS)
            // This must complete while publication is still blocked, as must Main's click.
            assertTrue(disable > publishing)
            release.countDown()
            worker.get(5, TimeUnit.SECONDS)
            order.exclusive {
                assertTrue(order.shouldDisable(disable))
                order.disabled(disable)
            }
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun delayedDisableCannotUndoNewerSuccessfulPublication() {
        val order = HookHostOrder()
        val disable = order.next()
        val publish = order.next()
        order.exclusive {
            order.checkCurrent(publish)
            order.published(publish)
        }
        order.exclusive { assertFalse(order.shouldDisable(disable)) }
    }

    @Test fun disableStillRunsIfNewerPublicationFailedOrIsOnlyAwaitingScope() {
        val order = HookHostOrder()
        val disable = order.next()
        order.next() // New explicit publication has not committed.
        order.exclusive { assertTrue(order.shouldDisable(disable)); order.disabled(disable) }
    }

    @Test fun disableDuringSelectionCommitIsDetectedBeforeSuccess() {
        val order = HookHostOrder()
        val publish = order.next()
        order.exclusive {
            order.checkCurrent(publish)
            val disable = order.next() // Simulate intent arriving while IPC commit is in flight.
            assertThrows(IllegalStateException::class.java) { order.checkCurrent(publish) }
            assertTrue(order.shouldDisable(disable))
            order.disabled(disable)
        }
    }
}
