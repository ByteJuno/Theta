package io.github.bytejuno.thetacore.agent.ledger

import org.junit.Test
import kotlin.test.*

class RunToolAliasTest {
    @Test fun runAliasDoesNotInvalidateRegistrationButMustMatchFrozenDirectory() {
        Fixture().use { f ->
            val registered = definition()
            val aliased = registered.copy(modelName = "plugin_tool_123456abcdef")
            val input = f.input(aliased)
            f.gate.install(registered)
            assertEquals(DefinitionState.CURRENT, f.gate.check(input))
            assertEquals(Status.READY, f.prepare(input).state)
            assertEquals(DefinitionState.STALE, f.gate.check(input.copy(definition = registered, modelName = registered.modelName)))
            assertFailsWith<IllegalArgumentException> { f.gate.freezeRun(input.runId, listOf(registered)) }
        }
    }

    @Test fun aliasesDoNotHideAnyRegistrationChangeOrRevocation() {
        val d = definition()
        val changed = listOf(d.copy(toolKey = "other/tool"), d.copy(name = "new"), d.copy(description = "new"),
            d.copy(inputSchema = "{}"), d.copy(declaredRisk = Risk.ROOT), d.copy(source = Source.THIRD_PARTY),
            d.copy(owner = "other"), d.copy(version = "2"), d.copy(generation = 2),
            d.copy(parallelSafe = !d.parallelSafe), d.copy(exclusiveResource = "resource"),
            d.copy(idempotent = !d.idempotent), d.copy(outputSchema = "{}"), d.copy(timeoutMillis = 99))
        Fixture().use { f ->
            val input = f.input(d.copy(modelName = "run_alias"))
            changed.forEach { replacement ->
                assertFalse(d.sameRegistrationAs(replacement))
                f.gate.uninstall(d.toolKey)
                f.gate.install(replacement)
                assertEquals(DefinitionState.STALE, f.gate.check(input))
            }
            f.gate.install(d)
            f.gate.beginRefresh(d.toolKey)
            assertEquals(DefinitionState.REFRESH_PENDING, f.gate.check(input))
            f.gate.uninstall(d.toolKey)
            assertEquals(DefinitionState.STALE, f.gate.check(input))
        }
    }
}
