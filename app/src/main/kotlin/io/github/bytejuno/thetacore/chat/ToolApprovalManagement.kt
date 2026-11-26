package io.github.bytejuno.thetacore.chat

import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.plugin.HostToolExecutionService

internal data class ToolApprovalSnapshot(
    val tools: List<FrozenDefinition>,
    val grants: List<PermanentToolGrant>,
)

/** Takes only detached host metadata. No plugin callback or audit mutation. */
internal fun toolApprovalSnapshot(service: HostToolExecutionService, settings: ChatApprovalSettings): ToolApprovalSnapshot =
    service.catalog.gate.withGate {
        val registered = service.catalog.registeredDefinitions().associateBy { it.toolKey }
        val tools = (service.ledger.approvalTools().associateBy { it.toolKey } + registered).values.sortedBy { it.toolKey }
        val grants = service.ledger.permanentGrants().filter { grant ->
            val current = registered[grant.definition.toolKey]
            !settings.rulesUnavailable && grant.receiptId !in settings.revokedReceipts &&
                grant.definition.toolKey !in settings.deniedTools &&
                current != null && current.digest == grant.definition.digest &&
                (current.source != Source.MCP || current.version == grant.definition.version) &&
                (maxOf(current.initialRisk, settings.riskOverrides[current.toolKey] ?: Risk.READ) != Risk.ROOT ||
                    settings.allowRootLongTerm) &&
                grant.risk >= maxOf(current.initialRisk,
                    settings.riskOverrides[grant.definition.toolKey] ?: Risk.READ)
        }
        ToolApprovalSnapshot(tools, grants)
    }
