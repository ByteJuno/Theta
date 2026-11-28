package io.github.bytejuno.thetacore.agent.ledger

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.bytejuno.thetacore.agent.execution.ModelToolNames
import io.github.bytejuno.thetacore.agent.run.SqliteRunHistory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class RunCatalogPersistenceTest {
    @get:Rule val temp = TemporaryFolder()

    private fun openDb(path: java.nio.file.Path) = AgentDatabase.open(path, BundledSQLiteDriver())

    private fun def(
        toolKey: String,
        modelName: String,
        description: String = "desc-$toolKey",
        schema: String = """{"type":"object","properties":{}}""",
        version: String = "1",
        generation: Long = 1L,
    ) = FrozenDefinition(
        toolKey = toolKey,
        modelName = modelName,
        name = toolKey.substringAfterLast('/'),
        description = description,
        inputSchema = schema,
        declaredRisk = Risk.READ,
        source = Source.FIRST_PARTY,
        owner = "host",
        version = version,
        generation = generation,
        parallelSafe = false,
        exclusiveResource = null,
        idempotent = false,
        outputSchema = null,
    )

    @Test fun persistsAndRestoresChunkedCatalogsAcrossColdRestartAndEnforcesImmutability() {
        val file = temp.newFolder().toPath().resolve(AgentDatabase.FILE_NAME)
        val largeDescription = "x".repeat(20_000) + "\uD83D" + "tail"
        val allocated = ModelToolNames.allocate(listOf("host/a/b", "host/a_b"))
        val defs = listOf(
            def("host/a/b", allocated.getValue("host/a/b"), description = largeDescription),
            def("host/a_b", allocated.getValue("host/a_b")),
        )
        openDb(file).use { db ->
            val store = SqliteRunCatalogStore(db)
            store.saveRunCatalog("run-1", defs)
            // Idempotent for identical mapping regardless of list order.
            store.saveRunCatalog("run-1", defs.reversed())
            // Empty catalog is valid and persisted.
            store.saveRunCatalog("run-empty", emptyList())
            // Mutating an existing run catalog is rejected.
            assertFailsWith<IllegalStateException> {
                store.saveRunCatalog("run-1", listOf(defs.first()))
            }
        }
        openDb(file).use { db ->
            val store = SqliteRunCatalogStore(db)
            val restored = assertNotNull(store.loadRunCatalog("run-1"))
            assertEquals(defs.sortedBy { it.modelName }, restored)
            assertEquals(largeDescription, restored.first { it.toolKey == "host/a/b" }.description)
            assertEquals(emptyList(), store.loadRunCatalog("run-empty"))
            assertNull(store.loadRunCatalog("run-missing"))
            val all = store.loadAllRunCatalogs()
            assertEquals(setOf("run-1", "run-empty"), all.keys)
            assertEquals(defs.sortedBy { it.modelName }, all.getValue("run-1"))
        }
    }

    @Test fun definitionGateRestorePreventsHijackingToolFromStealingModelName() {
        val allocated = ModelToolNames.allocate(listOf("host/a/b", "host/a_b"))
        val collidedName = allocated.getValue("host/a/b")
        assertEquals("host_a_b_02c2b0e6aca1", collidedName)
        val slashDef = def("host/a/b", collidedName)
        val underscoreDef = def("host/a_b", allocated.getValue("host/a_b"))
        val hijackerDef = def("host/a_b_02c2b0e6aca1", collidedName)

        val gate = DefinitionGate()
        gate.install(slashDef)
        gate.install(underscoreDef)
        gate.install(hijackerDef)
        gate.restoreRun("run-1", listOf(slashDef, underscoreDef))

        val originalInput = InvocationInput(
            BindingValue("p", "r1", null, 0, null, java.util.UUID.randomUUID().toString()),
            "chain", "session", "run-1", "step-1", 0, "{}", slashDef, false, Risk.READ, false, collidedName,
        )
        val hijackedInput = InvocationInput(
            BindingValue("p", "r1", null, 1, null, java.util.UUID.randomUUID().toString()),
            "chain", "session", "run-1", "step-1", 1, "{}", hijackerDef, false, Risk.READ, false, collidedName,
        )
        assertEquals(DefinitionState.CURRENT, gate.check(originalInput))
        assertEquals(DefinitionState.STALE, gate.check(hijackedInput))

        // If the original tool is absent after restart, calling it is STALE, never routed to the hijacker.
        gate.uninstall("host/a/b")
        assertEquals(DefinitionState.STALE, gate.check(originalInput))
        assertEquals(DefinitionState.STALE, gate.check(hijackedInput))
    }

    @Test fun corruptRunCatalogFailsClosedAndUncommittedFaultLeavesNoEntry() {
        val file = temp.newFolder().toPath().resolve(AgentDatabase.FILE_NAME)
        val sample = listOf(def("host/clock", "host_clock"))
        openDb(file).use { db ->
            val faulty = SqliteRunCatalogStore(db) { stage ->
                if (stage == SqliteWriteStage.BEFORE_COMMIT) throw RuntimeException("synthetic crash")
            }
            assertFailsWith<StoreException> { faulty.saveRunCatalog("run-fail", sample) }
        }
        openDb(file).use { db ->
            val store = SqliteRunCatalogStore(db)
            assertNull(store.loadRunCatalog("run-fail"))
            store.saveRunCatalog("run-ok", sample)
            db.connection.execute("UPDATE run_catalog_chunks SET bytes=x'deadbeef' WHERE run_id='run-ok'")
            assertFailsWith<StoreException> { store.loadRunCatalog("run-ok") }
        }
    }

    @Test fun schemaFiveUpgradesToSixAndPreservesExistingData() {
        val file = temp.newFolder().toPath().resolve(AgentDatabase.FILE_NAME)
        openDb(file).use { db ->
            SqliteRunHistory.create(db, "existing").close()
            assertTrue(PrivateImageStore(db).claim("existing", "img-1"))
            db.connection.execute("DROP TABLE document_blobs")
            db.connection.execute("DROP TABLE composer_chunks")
            db.connection.execute("DROP TABLE composer_parts")
            db.connection.execute("DROP TABLE run_catalog_chunks")
            db.connection.execute("DROP TABLE run_catalogs")
            db.connection.execute("PRAGMA user_version=5")
        }
        openDb(file).use { db ->
            assertEquals(AgentDatabase.SCHEMA_VERSION.toLong(), db.connection.longValue("PRAGMA user_version"))
            assertEquals(listOf("existing"), db.sessionIds())
            assertFalse(PrivateImageStore(db).claim("existing", "img-1"))
            val store = SqliteRunCatalogStore(db)
            val sample = listOf(def("host/clock", "host_clock"))
            store.saveRunCatalog("run-upgraded", sample)
            assertEquals(sample, store.loadRunCatalog("run-upgraded"))
        }
    }
}
