package io.github.bytejuno.thetacore.agent.ledger

import io.github.bytejuno.thetacore.agent.protocol.callBinding
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertIs

internal fun definition(risk: Risk = Risk.READ, source: Source = Source.FIRST_PARTY,
    parallel: Boolean = true, resource: String? = null, version: String = "1", generation: Long = 1) =
    FrozenDefinition("plugin/tool", "plugin_tool", "tool", "description", "{\"type\":\"object\"}", risk,
        source, "plugin", version, generation, parallel, resource, false, null)

internal class Fixture(
    val policy: ApprovalPolicy = ApprovalPolicy(),
    validator: ArgumentsValidator = ArgumentsValidator { _, _ -> true },
    val maxClaims: Int = Int.MAX_VALUE,
) : AutoCloseable {
    val directory = Files.createTempDirectory("theta-ledger-test-")
    var stage: WriteStage? = null
    var store = JsonInvocationStore.create(directory, Durability.PROCESS_CRASH_ONLY) { if (it == stage) error("injected $it") }
    val gate = DefinitionGate()
    var ledger = InvocationLedger(store, gate, validator, policy, maxClaims)
    init { ledger.recoverColdStart("epoch-1") }
    fun input(d: FrozenDefinition = definition(), session: String = "session", run: String = UUID.randomUUID().toString(),
        order: Int = 0, args: String = "{}", unattended: Boolean = false, chain: String = "chain",
        cancelled: Boolean = false): InvocationInput {
        gate.install(d); gate.freezeRun(run, listOf(d))
        return InvocationInput(BindingValue.copyOf(callBinding("provider", "request", "response", order, "wire")),
            chain, session, run, "step", order, args, d, unattended, maxOf(d.declaredRisk, if (d.source == Source.MCP) Risk.WRITE else Risk.READ), cancelled)
    }
    fun record(input: InvocationInput = input()): InvocationRecord = assertIs<Mutation.Applied>(ledger.recordInvocation(input)).record
    fun prepare(input: InvocationInput = input()): InvocationRecord {
        record(input)
        return assertIs<Mutation.Applied>(ledger.validateAndPrepare(input.binding.callId)).record
    }
    fun approve(r: InvocationRecord, decision: Decision = Decision.ALLOW_ONCE): InvocationRecord =
        assertIs<Mutation.Applied>(ledger.resolveApproval(r.callId, r.pending!!, decision)).record
    fun ready(input: InvocationInput = input()): InvocationRecord {
        val r = prepare(input)
        return if (r.pending != null) approve(r) else r
    }
    fun claim(r: InvocationRecord): Mutation.Applied = assertIs<Mutation.Applied>(ledger.claimExecution(r.callId, r.revision))
    fun reopen(epoch: String = "epoch-2") {
        store.close()
        store = JsonInvocationStore.open(directory, Durability.PROCESS_CRASH_ONLY)
        ledger = InvocationLedger(store, gate, ArgumentsValidator { _, _ -> true }, policy, maxClaims)
        ledger.recoverColdStart(epoch)
    }
    override fun close() { store.close(); directory.toFile().deleteRecursively() }
}
