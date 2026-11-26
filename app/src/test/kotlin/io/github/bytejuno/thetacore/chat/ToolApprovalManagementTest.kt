package io.github.bytejuno.thetacore.chat

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.bytejuno.thetacore.agent.ledger.*
import io.github.bytejuno.thetacore.plugin.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ToolApprovalManagementTest {
    @Test fun rulesPersistArbitraryKeysAndFailedSaveKeepsPreviousSnapshot() {
        val root = Files.createTempDirectory("theta-tool-rules").toFile()
        try {
            val store = ChatApprovalSettingsStore(root)
            val key = "mcp/本地/with = spaces:工具"
            store.setToolRule(key, true, Risk.ROOT)
            // MCP names accept these UTF-16 units; persisted rules must keep the exact key.
            store.setToolRule("mcp/local/high\uD800-low\uDC00-emoji😀-literal\\ud800", true, Risk.DEVICE_CONTROL)
            store.revoke(setOf("receipt-one", "receipt-two"))
            store.setMode(ApprovalMode.FULL_AUTO)
            store.setAllowRootLongTerm(true)
            assertEquals(store.current(), ChatApprovalSettingsStore(root).current())
            val before = store.current()
            val file = File(root, "${ChatApprovalSettingsStore.DIRECTORY}/settings.properties")
            file.delete(); file.mkdir()
            assertTrue(runCatching { store.setToolRule(key, false, null) }.isFailure)
            assertEquals(before, store.current())
            assertTrue(ChatApprovalSettingsStore(root).current().rulesUnavailable)
        } finally { root.deleteRecursively() }
    }

    @Test fun restoredRulesReloadAfterReadFailureWithoutTreatingMissingFileAsFreshInstall() {
        val root = Files.createTempDirectory("theta-restored-rules").toFile()
        try {
            val original = ChatApprovalSettingsStore(root)
            original.setToolRule("host/clock", true, Risk.ROOT)
            original.revoke(setOf("saved-receipt"))
            val file = File(root, "${ChatApprovalSettingsStore.DIRECTORY}/settings.properties")
            val saved = file.readBytes()
            assertTrue(file.delete())
            assertTrue(file.mkdir())
            val store = ChatApprovalSettingsStore(root)
            assertTrue(store.ensureLoaded().rulesUnavailable)
            assertTrue(file.delete())
            assertTrue(store.ensureLoaded().rulesUnavailable)
            assertTrue(runCatching { store.setMode(ApprovalMode.FULL_AUTO) }.isFailure)
            file.writeBytes(saved)
            assertEquals(original.current(), store.ensureLoaded())
            store.setMode(ApprovalMode.STRICT)
            assertEquals(original.current().deniedTools, store.current().deniedTools)
            assertEquals(original.current().revokedReceipts, store.current().revokedReceipts)
            assertEquals(store.current(), ChatApprovalSettingsStore(root).current())
        } finally { root.deleteRecursively() }
    }

    @Test fun malformedRulesCannotRestoreOldGrantsOrBeOverwrittenByModeSave() {
        val root = Files.createTempDirectory("theta-corrupt-rules").toFile()
        try {
            val directory = File(root, ChatApprovalSettingsStore.DIRECTORY).apply { mkdirs() }
            val file = File(directory, "settings.properties")
            file.writeText("mode=FULL_AUTO\nallowRootLongTerm=true\ntoolRules=broken\n")
            val store = ChatApprovalSettingsStore(root)
            assertTrue(store.current().rulesUnavailable)
            assertTrue(runCatching { store.setMode(ApprovalMode.FULL_AUTO) }.isFailure)
            assertTrue(file.readText().contains("toolRules=broken"))
            file.writeText("toolRulesVersion=1\nmode=BALANCED\nallowRootLongTerm=false\n")
            assertTrue(ChatApprovalSettingsStore(root).current().rulesUnavailable)
        } finally { root.deleteRecursively() }
    }

    @Test fun sqliteRuntimeUsesFrozenRiskAndRevocationWithoutChangingHistoricalReceipts(): Unit = runBlocking {
        val root = Files.createTempDirectory("theta-rule-runtime").toFile()
        val settings = ChatApprovalSettingsStore(File(root, "settings"))
        val database = AndroidAgentStore.open(File(root, "database").apply { mkdirs() }, BundledSQLiteDriver())
        try {
            settings.setToolRule("host/clock", false, Risk.DEVICE_CONTROL)
            val service = HostToolBridgeRoot(PluginExecutionBudget()).initialize(policy = settings.policy()) { database }
            fun input(run: String): InvocationInput {
                val d = service.catalog.freeze(run).values.single { it.toolKey == "host/clock" }
                return InvocationInput(BindingValue("test", run, null, 0, null, UUID.randomUUID().toString()),
                    run, "session", run, "step", 0, "{}", d, false, d.initialRisk)
            }
            val first = input("first")
            val waiting = (service.prepare(first) as Mutation.Applied).record
            assertEquals(Risk.DEVICE_CONTROL, waiting.pending!!.risk)
            service.decide(waiting.callId, waiting.pending!!.id, Decision.ALLOW_ALWAYS)
            val receipt = service.ledger.get(waiting.callId)!!.receipts.single()
            assertEquals(1, toolApprovalSnapshot(service, settings.current()).grants.size)
            assertTrue(toolApprovalSnapshot(service, settings.current()).tools.any { it.toolKey == "host/clock" })
            settings.revoke(setOf(receipt.request.id))
            assertTrue(toolApprovalSnapshot(service, settings.current()).grants.isEmpty())
            assertEquals(listOf(receipt), service.ledger.get(waiting.callId)!!.receipts)
            val second = (service.prepare(input("second")) as Mutation.Applied).record
            assertEquals(Status.WAITING_APPROVAL, second.state)
            service.decide(second.callId, second.pending!!.id, Decision.ALLOW_ALWAYS)
            assertEquals(Status.READY, (service.prepare(input("third")) as Mutation.Applied).record.state)
            settings.setToolRule("host/clock", true, Risk.READ)
            assertEquals(Risk.DEVICE_CONTROL, service.catalog.freeze("first").values.single { it.toolKey == "host/clock" }.initialRisk)
            assertEquals(Status.REJECTED, (service.prepare(input("denied")) as Mutation.Applied).record.state)
            assertEquals(first.definition, database.runCatalogStore()!!.loadRunCatalog("first")!!.single { it.toolKey == "host/clock" })
        } finally { database.close(); root.deleteRecursively() }
    }
}
