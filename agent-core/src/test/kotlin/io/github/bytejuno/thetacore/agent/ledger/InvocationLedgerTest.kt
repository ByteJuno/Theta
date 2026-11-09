package io.github.bytejuno.thetacore.agent.ledger

import io.github.bytejuno.thetacore.agent.protocol.ContentPart
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.*

class InvocationLedgerTest {
    @Test fun bindingRoundTripAndConflicts(): Unit = Fixture().use { f ->
        val a = f.input(args = "{\"a\":1}")
        val first = f.record(a)
        assertIs<Mutation.AlreadyApplied>(f.ledger.recordInvocation(a))
        assertIs<Mutation.Conflict>(f.ledger.recordInvocation(a.copy(argsJson = "{}")))
        assertIs<Mutation.Conflict>(f.ledger.recordInvocation(a.copy(definition = requireNotNull(a.definition).copy(version = "2"))))
        val second = f.record(f.input(session = "other"))
        assertEquals(first.input.binding.wireId, second.input.binding.wireId)
        assertNotEquals(first.callId, second.callId)
        f.reopen()
        assertEquals(first, f.ledger.get(first.callId))
    }

    @Test fun canonicalDefinitionAndParameterTypes() {
        assertEquals(canonical(kotlinx.serialization.json.Json.parseToJsonElement("{\"b\": 2, \"a\":1}")),
            canonical(kotlinx.serialization.json.Json.parseToJsonElement("{\"a\":1,\"b\":2}")))
        assertNotEquals(canonical(kotlinx.serialization.json.Json.parseToJsonElement("{\"a\":\"1\"}")),
            canonical(kotlinx.serialization.json.Json.parseToJsonElement("{\"a\":1}")))
        assertNotEquals(definition().digest, definition().copy(description = "changed").digest)
        assertEquals(definition().digest, definition(version = "2").digest)
    }

    @Test fun validationAndTruncationCannotClaim(): Unit = Fixture(validator = ArgumentsValidator { _, args -> args == "{}" }).use { f ->
        for (args in listOf("[1]", "bad", "{\"notAllowed\":1}")) {
            val r = f.prepare(f.input(args = args))
            assertEquals(Status.REJECTED, r.state)
            assertEquals(Effect.NONE, r.result!!.effect)
            assertIs<Mutation.Conflict>(f.ledger.claimExecution(r.callId, r.revision))
        }
        val r = f.record(f.input(cancelled = true))
        assertEquals(Status.CANCELLED, r.state)
        assertEquals(Effect.NONE, r.result!!.effect)
        assertIs<Mutation.Conflict>(f.ledger.claimExecution(r.callId, r.revision))
        assertEquals("{}", f.prepare(f.input(args = "null")).executionArgs)
        assertEquals("{}", f.prepare(f.input(args = "")).executionArgs)
    }

    @Test fun policySourcesSessionAndUnattended(): Unit = Fixture().use { f ->
        assertEquals(Status.READY, f.prepare().state)
        val d = definition(source = Source.THIRD_PARTY)
        val a = f.prepare(f.input(d))
        assertEquals(Status.WAITING_APPROVAL, a.state)
        f.approve(a, Decision.ALLOW_SESSION)
        assertEquals(Status.READY, f.prepare(f.input(d)).state)
        assertEquals(Status.WAITING_APPROVAL, f.prepare(f.input(d, session = "other")).state)
        assertEquals(Status.REJECTED, f.prepare(f.input(d, unattended = true)).state)
        val always = f.prepare(f.input(d, session = "third"))
        f.approve(always, Decision.ALLOW_ALWAYS)
        assertEquals(Status.READY, f.prepare(f.input(d, unattended = true)).state)
        assertEquals(Status.WAITING_APPROVAL, f.prepare(f.input(d.copy(description = "new"))).state)
    }

    @Test fun denyPriorityAndRootSwitch() {
        var denied = false
        Fixture(ApprovalPolicy(denied = { denied })).use { f ->
            val r = f.ready()
            denied = true
            assertEquals(Status.REJECTED, assertIs<Mutation.Applied>(f.ledger.claimExecution(r.callId, r.revision)).record.state)
            assertEquals(Status.REJECTED, f.prepare().state)
        }
        Fixture().use { f ->
            val r = f.prepare(f.input(definition(Risk.ROOT)))
            assertIs<Mutation.Blocked>(f.ledger.resolveApproval(r.callId, r.pending!!, Decision.ALLOW_ALWAYS))
            f.approve(r)
        }
        Fixture(ApprovalPolicy(allowRootLongTerm = { true })).use { f ->
            val d = definition(Risk.ROOT)
            f.approve(f.prepare(f.input(d)), Decision.ALLOW_ALWAYS)
            assertEquals(Status.READY, f.prepare(f.input(d, unattended = true)).state)
        }
    }

    @Test fun forgedAndRepeatedApproval(): Unit = Fixture().use { f ->
        val r = f.prepare(f.input(definition(Risk.WRITE)))
        val req = r.pending!!
        for (forged in listOf(req.copy(callId = "wrong"), req.copy(digest = "wrong"), req.copy(generation = 99), req.copy(risk = Risk.ROOT)))
            assertIs<Mutation.Conflict>(f.ledger.resolveApproval(r.callId, forged, Decision.ALLOW_ONCE))
        val accepted = f.approve(r)
        assertNull((f.ledger.resolveApproval(r.callId, req, Decision.ALLOW_ONCE) as Mutation.AlreadyApplied).record.pending)
        assertIs<Mutation.Conflict>(f.ledger.resolveApproval(r.callId, req, Decision.DENY))
        assertNull(accepted.instance)
        val denied = f.prepare(f.input(definition(Risk.WRITE)))
        assertEquals(Effect.NONE, f.approve(denied, Decision.DENY).result!!.effect)
    }

    private fun race(a: () -> Mutation, b: () -> Mutation): List<Mutation> {
        val pool = Executors.newFixedThreadPool(2)
        val latch = CountDownLatch(1)
        return try {
            val futures = listOf(a, b).map { action -> pool.submit(Callable { latch.await(); action() }) }
            latch.countDown(); futures.map { it.get() }
        } finally { pool.shutdownNow() }
    }
    @Test fun concurrentAcceptDenyAndClaim(): Unit = Fixture().use { f ->
        val r = f.prepare(f.input(definition(Risk.WRITE)))
        val replies = race({ f.ledger.resolveApproval(r.callId, r.pending!!, Decision.ALLOW_ONCE) },
            { f.ledger.resolveApproval(r.callId, r.pending!!, Decision.DENY) })
        assertEquals(1, replies.count { it is Mutation.Applied })
        assertEquals(1, replies.count { it is Mutation.Conflict })
        val ready = f.ready()
        val claims = race({ f.ledger.claimExecution(ready.callId, ready.revision) }, { f.ledger.claimExecution(ready.callId, ready.revision) })
        assertEquals(1, claims.count { it is Mutation.Applied && it.ticket != null })
        assertEquals(1, claims.count { it is Mutation.Conflict })
        val pending = f.prepare(f.input(definition(Risk.WRITE)))
        val accepts = race({ f.ledger.resolveApproval(pending.callId, pending.pending!!, Decision.ALLOW_ONCE) },
            { f.ledger.resolveApproval(pending.callId, pending.pending!!, Decision.ALLOW_ONCE) })
        assertEquals(1, accepts.count { it is Mutation.Applied })
        assertEquals(1, accepts.count { it is Mutation.AlreadyApplied })
    }

    @Test fun approveCancelAndUpgradeTimeoutRaces(): Unit = Fixture().use { f ->
        val r = f.prepare(f.input(definition(Risk.WRITE)))
        race({ f.ledger.resolveApproval(r.callId, r.pending!!, Decision.ALLOW_ONCE) }, { f.ledger.cancel(r.callId) })
        assertEquals(Status.CANCELLED, f.ledger.get(r.callId)!!.state)
        val running = f.claim(f.ready())
        val upgrade = assertIs<Mutation.Applied>(f.ledger.requestRiskUpgrade(running.record.callId, running.ticket!!.attemptId, Risk.WRITE)).record
        race({ f.ledger.resolveApproval(upgrade.callId, upgrade.pending!!, Decision.ALLOW_ONCE) }, { f.ledger.timeout(upgrade.callId) })
        assertEquals(Status.TIMED_OUT, f.ledger.get(upgrade.callId)!!.state)
        assertIs<Mutation.Conflict>(f.ledger.requestRiskUpgrade(upgrade.callId, running.ticket.attemptId, Risk.ROOT))
    }

    @Test fun staleRefreshAndGenerationAreDistinct(): Unit = Fixture().use { f ->
        val d = definition(Risk.WRITE)
        val ready = f.ready(f.input(d))
        f.gate.beginRefresh(d.toolKey)
        assertIs<Mutation.Blocked>(f.ledger.claimExecution(ready.callId, ready.revision))
        assertIs<Mutation.Blocked>(f.ledger.claimExecution(ready.callId, ready.revision))
        f.gate.install(d.copy(generation = 2))
        val rejected = assertIs<Mutation.Applied>(f.ledger.claimExecution(ready.callId, ready.revision)).record
        assertEquals(Status.REJECTED, rejected.state)
        val next = f.ready(f.input(d))
        f.gate.uninstall(d.toolKey)
        assertEquals(Status.REJECTED, assertIs<Mutation.Applied>(f.ledger.claimExecution(next.callId, next.revision)).record.state)
    }

    @Test fun effectMatrixAndReadContradictions() {
        for (risk in Risk.entries) for (status in listOf(Status.SUCCEEDED, Status.FAILED, Status.CANCELLED, Status.TIMED_OUT, Status.LOST)) {
            Fixture().use { f ->
                val running = f.claim(f.ready(f.input(definition(risk))))
                val r = running.record
                when (status) {
                    Status.SUCCEEDED, Status.FAILED -> f.ledger.complete(r.callId, running.ticket!!.attemptId, status, "result")
                    Status.CANCELLED -> f.ledger.cancel(r.callId)
                    Status.TIMED_OUT -> f.ledger.timeout(r.callId)
                    Status.LOST -> f.reopen()
                    else -> error("test")
                }
                val result = f.ledger.get(r.callId)!!.result!!
                assertEquals(status, result.status)
                assertEquals(if (risk == Risk.READ) Effect.NONE else if (status == Status.SUCCEEDED) Effect.APPLIED else Effect.UNKNOWN, result.effect)
            }
        }
        for (effect in listOf(Effect.APPLIED, Effect.UNKNOWN)) Fixture().use { f ->
            val r = f.claim(f.ready())
            val done = assertIs<Mutation.Applied>(f.ledger.complete(r.record.callId, r.ticket!!.attemptId, Status.SUCCEEDED, "explicit", effect)).record
            assertEquals(effect, done.result!!.effect)
            assertTrue(done.result.inconsistentRead)
        }
    }

    @Test fun riskUpgradePersistencePrecedesPermission(): Unit = Fixture().use { f ->
        val r = f.claim(f.ready())
        val pending = assertIs<Mutation.Applied>(f.ledger.requestRiskUpgrade(r.record.callId, r.ticket!!.attemptId, Risk.WRITE)).record
        assertEquals(Risk.READ, pending.effectiveRisk)
        f.stage = WriteStage.BEFORE_REPLACE
        assertIs<Mutation.StorageFailure>(f.ledger.resolveApproval(pending.callId, pending.pending!!, Decision.ALLOW_ONCE))
        assertEquals(Risk.READ, f.ledger.get(pending.callId)!!.effectiveRisk)
        f.stage = null
        assertEquals(Risk.WRITE, f.approve(pending).effectiveRisk)
        f.ledger.timeout(pending.callId)
        assertEquals(Effect.UNKNOWN, f.ledger.get(pending.callId)!!.result!!.effect)
    }

    @Test fun resourceAndBarrierRequireActualExit(): Unit = Fixture().use { f ->
        val d = definition(resource = "screen")
        val first = f.claim(f.ready(f.input(d)))
        val second = f.ready(f.input(d, session = "other"))
        f.ledger.timeout(first.record.callId)
        assertIs<Mutation.Blocked>(f.ledger.claimExecution(second.callId, second.revision))
        f.ledger.recordActualExit(first.record.callId, first.ticket!!.attemptId, "worker exited")
        f.claim(second)
        val serial = definition(parallel = false)
        val a = f.input(serial)
        val b = f.input(serial, run = a.runId, order = 1)
        val active = f.claim(f.ready(a))
        val queued = f.ready(b)
        f.ledger.cancel(active.record.callId)
        assertIs<Mutation.Blocked>(f.ledger.claimExecution(queued.callId, queued.revision))
        f.ledger.recordActualExit(active.record.callId, active.ticket!!.attemptId, "exit")
        f.claim(queued)
    }

    @Test fun isolationAndLateEvidenceDoNotRewriteResult(): Unit = Fixture().use { f ->
        val d = definition(Risk.WRITE, resource = "screen")
        val active = f.claim(f.ready(f.input(d)))
        val terminal = assertIs<Mutation.Applied>(f.ledger.timeout(active.record.callId)).record
        assertIs<Mutation.Blocked>(f.ledger.resolveRecovery(terminal.callId, RecoveryResolution.ACCEPTED_UNCERTAINTY))
        val late = assertIs<Mutation.Applied>(f.ledger.complete(terminal.callId, active.ticket!!.attemptId, Status.SUCCEEDED, "late", Effect.APPLIED)).record
        assertEquals(terminal.result, late.result)
        assertEquals(1, late.evidence.size)
        assertEquals(InstanceState.EXIT_CONFIRMED, late.instance!!.state)
        assertIs<Mutation.Conflict>(f.ledger.recordActualExit(late.callId, "old-attempt", "forged"))
        val queued = f.ready(f.input(d, session = "other", chain = "other-chain"))
        assertIs<Mutation.Blocked>(f.ledger.claimExecution(queued.callId, queued.revision))
        f.ledger.resolveRecovery(late.callId, RecoveryResolution.ACCEPTED_UNCERTAINTY)
        assertIs<Mutation.Blocked>(f.ledger.claimExecution(queued.callId, queued.revision))
        f.ledger.resolveRecovery(late.callId, RecoveryResolution.VERIFIED_CLEAR)
        f.claim(queued)
    }

    @Test fun recoveryPreservesTerminalAndExternalIsolation(): Unit = Fixture().use { f ->
        val active = f.claim(f.ready(f.input(definition(Risk.WRITE, resource = "screen"))))
        f.ledger.cancel(active.record.callId)
        val before = f.ledger.get(active.record.callId)!!
        f.ledger.markRestartRequired(active.record.callId, active.ticket!!.attemptId)
        val ready = f.ready(f.input(definition()))
        f.reopen()
        val after = f.ledger.get(active.record.callId)!!
        assertEquals(before.result, after.result)
        assertNotNull(after.isolation)
        assertEquals(InstanceState.EXIT_CONFIRMED, after.instance!!.state)
        assertFalse(after.restartRequired)
        assertEquals(Status.READY, f.ledger.get(ready.callId)!!.state)
        f.ledger.recoverColdStart("epoch-2")
        assertEquals(after, f.ledger.get(after.callId))
    }

    @Test fun unknownRetryCrossesRunsAndAlwaysGrant(): Unit = Fixture().use { f ->
        val d = definition(Risk.WRITE)
        val first = f.approve(f.prepare(f.input(d, args = "{\"a\":1,\"b\":2}")), Decision.ALLOW_ALWAYS)
        val active = f.claim(first)
        f.ledger.timeout(first.callId)
        val retry = f.prepare(f.input(d, args = "{ \"b\": 2, \"a\": 1 }"))
        assertEquals(Status.WAITING_APPROVAL, retry.state)
        assertEquals(listOf(first.callId), retry.pending!!.unknownCalls)
        assertEquals(Status.REJECTED, f.prepare(f.input(d, args = first.input.argsJson, unattended = true)).state)
        assertEquals(Status.READY, f.prepare(f.input(d, args = "{\"a\":\"1\",\"b\":2}")).state)
        f.approve(retry)
        assertEquals(InstanceState.UNCONFIRMED, f.ledger.get(active.record.callId)!!.instance!!.state)
    }

    @Test fun changedUnknownSetRenewsInsteadOfApprovingStaleRequest(): Unit = Fixture().use { f ->
        val d = definition(Risk.WRITE)
        val active = f.claim(f.ready(f.input(d)))
        val pending = f.prepare(f.input(d))
        f.ledger.timeout(active.record.callId)
        val renewal = assertIs<Mutation.Applied>(f.ledger.resolveApproval(pending.callId, pending.pending!!, Decision.ALLOW_ONCE)).record
        assertEquals(Status.WAITING_APPROVAL, renewal.state)
        assertNotEquals(pending.pending.id, renewal.pending!!.id)
        assertTrue(renewal.receipts.isEmpty())
        f.approve(renewal)
    }

    @Test fun refreshIdenticalSnapshotResumesAndUnlistedToolRejects(): Unit = Fixture().use { f ->
        val d = definition()
        val ready = f.ready(f.input(d))
        f.gate.beginRefresh(d.toolKey)
        assertIs<Mutation.Blocked>(f.ledger.claimExecution(ready.callId, ready.revision))
        f.gate.install(d)
        f.claim(ready)
        val unlisted = f.input().copy(runId = "no-frozen-catalog")
        assertEquals(Status.REJECTED, f.prepare(unlisted).state)
    }

    @Test fun taskChainBudgetSurvivesRunAndRestart(): Unit = Fixture(maxClaims = 1).use { f ->
        val first = f.claim(f.ready())
        f.ledger.complete(first.record.callId, first.ticket!!.attemptId, Status.SUCCEEDED, "done")
        f.reopen()
        val next = f.ready()
        assertIs<Mutation.Blocked>(f.ledger.claimExecution(next.callId, next.revision))
        f.claim(f.ready(f.input(chain = "another-chain")))
    }

    @Test fun graceRestartBlockEndsAtActualExitWithoutChangingStepBarrier(): Unit = Fixture().use { f ->
        val serial = definition(parallel = false)
        val active = f.claim(f.ready(f.input(serial)))
        val otherStep = f.claim(f.ready(f.input(serial, session = "other")))
        val timedOut = assertIs<Mutation.Applied>(f.ledger.timeout(active.record.callId)).record
        f.ledger.markRestartRequired(active.record.callId, active.ticket!!.attemptId, untilActualExit = true)
        val next = f.ready(f.input(serial))
        assertIs<Mutation.Blocked>(f.ledger.claimExecution(next.callId, next.revision))
        val exited = assertIs<Mutation.Applied>(f.ledger.recordActualExit(active.record.callId, active.ticket.attemptId, "stopped")).record
        assertFalse(exited.restartRequired)
        assertEquals(timedOut.result, exited.result)
        assertEquals(InstanceState.UNCONFIRMED, f.ledger.get(otherStep.record.callId)!!.instance!!.state)
        f.claim(next)
    }

    @Test fun graceRestrictionOnlyAcceptsMatchingCancelledOrTimedOutAttempt(): Unit = Fixture().use { f ->
        val active = f.claim(f.ready())
        val id = active.record.callId; val attempt = active.ticket!!.attemptId
        assertIs<Mutation.Conflict>(f.ledger.markRestartRequired(id, attempt, untilActualExit = true))
        f.ledger.timeout(id)
        assertIs<Mutation.Conflict>(f.ledger.markRestartRequired(id, "wrong", untilActualExit = true))
        assertIs<Mutation.Applied>(f.ledger.markRestartRequired(id, attempt, untilActualExit = true))
        assertIs<Mutation.AlreadyApplied>(f.ledger.markRestartRequired(id, attempt, untilActualExit = true))
        assertIs<Mutation.Conflict>(f.ledger.recordActualExit(id, "wrong", "stopped"))
        assertTrue(f.ledger.get(id)!!.restartRequired)
        assertIs<Mutation.Applied>(f.ledger.recordActualExit(id, attempt, "stopped"))
        assertIs<Mutation.AlreadyApplied>(f.ledger.recordActualExit(id, attempt, "stopped"))
        assertIs<Mutation.Conflict>(f.ledger.markRestartRequired(id, attempt, untilActualExit = true))
    }

    @Test fun completionClearsGraceOnlyIncludingPreviouslyRecordedEvidence(): Unit {
        for (returnedFirst in listOf(false, true)) Fixture().use { f ->
            val active = f.claim(f.ready())
            val id = active.record.callId; val attempt = active.ticket!!.attemptId
            val timedOut = assertIs<Mutation.Applied>(f.ledger.timeout(id)).record
            f.ledger.markRestartRequired(id, attempt, untilActualExit = true)
            if (returnedFirst) f.ledger.recordOutcome(id, attempt, Status.SUCCEEDED, "late", Effect.UNKNOWN)
            val exited = assertIs<Mutation.Applied>(f.ledger.complete(id, attempt, Status.SUCCEEDED, "late", Effect.UNKNOWN)).record
            assertFalse(exited.restartRequired)
            assertFalse(exited.restartRequiredUntilExit)
            assertEquals(timedOut.result, exited.result)
            assertNotNull(exited.isolation)
            assertEquals(1, exited.evidence.size)
            assertIs<Mutation.AlreadyApplied>(f.ledger.complete(id, attempt, Status.SUCCEEDED, "late", Effect.UNKNOWN))
            f.reopen()
            assertEquals(exited, f.ledger.get(id))
        }
    }

    @Test fun graceRestrictionPersistsAndColdLossStillRequiresRecovery(): Unit = Fixture().use { f ->
        val active = f.claim(f.ready())
        val id = active.record.callId; val attempt = active.ticket!!.attemptId
        f.ledger.timeout(id)
        val marked = assertIs<Mutation.Applied>(f.ledger.markRestartRequired(id, attempt, untilActualExit = true)).record
        val encoded = kotlinx.serialization.json.Json.encodeToString(InvocationRecord.serializer(), marked)
        assertEquals(marked, kotlinx.serialization.json.Json.decodeFromString(InvocationRecord.serializer(), encoded))
        // Existing records lacking the additive flag remain conservatively sticky.
        val legacy = kotlinx.serialization.json.Json.parseToJsonElement(encoded) as kotlinx.serialization.json.JsonObject
        val decoded = kotlinx.serialization.json.Json.decodeFromString(InvocationRecord.serializer(),
            kotlinx.serialization.json.JsonObject(legacy.filterKeys { it != "restartRequiredUntilExit" }).toString())
        assertTrue(decoded.restartRequired)
        assertFalse(decoded.restartRequiredUntilExit)
        f.reopen()
        val recovered = f.ledger.get(id)!!
        assertFalse(recovered.restartRequired)
        assertFalse(recovered.restartRequiredUntilExit)
        assertTrue(recovered.recoveryPending)
        assertNotNull(recovered.isolation)
        assertEquals(marked.result, recovered.result)
        val next = f.ready()
        assertIs<Mutation.Blocked>(f.ledger.claimExecution(next.callId, next.revision))
        f.ledger.resolveRecovery(id, RecoveryResolution.ACCEPTED_UNCERTAINTY)
        f.claim(next)
    }

    @Test fun graceExitCannotClearAnotherAttemptsStickyRestartCause(): Unit = Fixture().use { f ->
        val grace = f.claim(f.ready())
        val sticky = f.claim(f.ready())
        f.ledger.timeout(grace.record.callId)
        f.ledger.markRestartRequired(grace.record.callId, grace.ticket!!.attemptId, untilActualExit = true)
        f.ledger.markRestartRequired(sticky.record.callId, sticky.ticket!!.attemptId)
        f.ledger.recordActualExit(grace.record.callId, grace.ticket.attemptId, "late exit")
        f.ledger.complete(sticky.record.callId, sticky.ticket.attemptId, Status.FAILED, "cleanup failed")
        assertFalse(f.ledger.get(grace.record.callId)!!.restartRequired)
        assertTrue(f.ledger.get(sticky.record.callId)!!.restartRequired)
        val next = f.ready()
        assertEquals("Plugin restart required", assertIs<Mutation.Blocked>(f.ledger.claimExecution(next.callId, next.revision)).reason)
    }

    @Test fun otherRestartCauseStillRequiresColdRecovery(): Unit = Fixture().use { f ->
        val serial = definition(parallel = false)
        val active = f.claim(f.ready(f.input(serial)))
        // No resource: a serial tool is a STEP barrier, not a global host mutex.
        val otherStep = f.ready(f.input(serial, session = "other"))
        f.claim(otherStep)
        f.ledger.markRestartRequired(active.record.callId, active.ticket!!.attemptId)
        val next = f.ready(f.input(serial))
        assertIs<Mutation.Blocked>(f.ledger.claimExecution(next.callId, next.revision))
        f.ledger.recordActualExit(active.record.callId, active.ticket.attemptId, "stopped")
        assertIs<Mutation.Blocked>(f.ledger.claimExecution(next.callId, next.revision))
        f.reopen()
        assertIs<Mutation.Blocked>(f.ledger.claimExecution(next.callId, next.revision))
        f.ledger.resolveRecovery(active.record.callId, RecoveryResolution.ACCEPTED_UNCERTAINTY)
        f.ledger.resolveRecovery(otherStep.callId, RecoveryResolution.ACCEPTED_UNCERTAINTY)
        f.claim(next)
    }

    @Test fun coldClaimWindowAndReadExternalResourceRemainIsolated(): Unit = Fixture().use { f ->
        val d = definition(resource = "screen")
        val active = f.claim(f.ready(f.input(d))) // no executor/start callback at all
        f.reopen()
        val lost = f.ledger.get(active.record.callId)!!
        assertEquals(Status.LOST, lost.state)
        assertEquals(Effect.NONE, lost.result!!.effect)
        assertNotNull(lost.isolation)
        val next = f.ready(f.input(d))
        assertIs<Mutation.Blocked>(f.ledger.claimExecution(next.callId, next.revision))
        f.ledger.resolveRecovery(lost.callId, RecoveryResolution.VERIFIED_CLEAR)
        f.claim(next)
    }

    @Test fun lateUnknownReadEvidenceRequiresExplicitRetry(): Unit = Fixture().use { f ->
        val active = f.claim(f.ready())
        val timedOut = assertIs<Mutation.Applied>(f.ledger.timeout(active.record.callId)).record
        f.ledger.complete(active.record.callId, active.ticket!!.attemptId, Status.FAILED, "late unknown", Effect.UNKNOWN)
        assertEquals(timedOut.result, f.ledger.get(active.record.callId)!!.result)
        val retry = f.prepare()
        assertEquals(Status.WAITING_APPROVAL, retry.state)
        assertEquals(listOf(active.record.callId), retry.pending!!.unknownCalls)
    }

    @Test fun claimRechecksNewUnknownAndUnattendedInternalUpgrade(): Unit = Fixture().use { f ->
        val pendingReady = f.ready()
        val active = f.claim(f.ready())
        f.ledger.complete(active.record.callId, active.ticket!!.attemptId, Status.FAILED, "unknown", Effect.UNKNOWN)
        val recheck = assertIs<Mutation.Applied>(f.ledger.claimExecution(pendingReady.callId, pendingReady.revision))
        assertNull(recheck.ticket)
        assertEquals(Status.WAITING_APPROVAL, recheck.record.state)
        val unattended = f.claim(f.ready(f.input(unattended = true, chain = "separate")))
        assertIs<Mutation.Blocked>(f.ledger.requestRiskUpgrade(unattended.record.callId, unattended.ticket!!.attemptId, Risk.WRITE))
        assertNull(f.ledger.get(unattended.record.callId)!!.pending)
    }

    @Test fun unknownApprovalDoesNotReleaseAliveResource(): Unit = Fixture().use { f ->
        val d = definition(Risk.WRITE, resource = "screen")
        val active = f.claim(f.ready(f.input(d)))
        f.ledger.timeout(active.record.callId)
        val retry = f.approve(f.prepare(f.input(d)))
        assertIs<Mutation.Blocked>(f.ledger.claimExecution(retry.callId, retry.revision))
        f.ledger.recordActualExit(active.record.callId, active.ticket!!.attemptId, "exit")
        assertIs<Mutation.Blocked>(f.ledger.claimExecution(retry.callId, retry.revision))
        f.ledger.resolveRecovery(active.record.callId, RecoveryResolution.VERIFIED_CLEAR)
        f.claim(retry)
    }

    @Test fun orderedApprovalAndProjection(): Unit = Fixture().use { f ->
        val d = definition(Risk.WRITE)
        val a = f.input(d)
        val b = f.input(d, run = a.runId, order = 1)
        f.record(a); f.record(b)
        assertIs<Mutation.Blocked>(f.ledger.validateAndPrepare(b.binding.callId))
        val ap = assertIs<Mutation.Applied>(f.ledger.validateAndPrepare(a.binding.callId)).record
        val ar = f.claim(f.approve(ap))
        val bp = assertIs<Mutation.Applied>(f.ledger.validateAndPrepare(b.binding.callId)).record
        val br = f.claim(f.approve(bp))
        val ids = listOf(ar.record.callId, br.record.callId)
        f.ledger.complete(br.record.callId, br.ticket!!.attemptId, Status.SUCCEEDED, "b")
        assertNull(f.ledger.resultsForStep(a.sessionId, a.runId, a.stepId, ids))
        f.ledger.complete(ar.record.callId, ar.ticket!!.attemptId, Status.SUCCEEDED, "a")
        repeat(2) {
            val batch = f.ledger.resultsForStep(a.sessionId, a.runId, a.stepId, ids)!!
            assertEquals(ids, batch.parts.map { (it as ContentPart.ToolResult).callId })
        }
        assertNull(f.ledger.resultsForStep(a.sessionId, a.runId, a.stepId, ids + "missing"))
    }
}
