package io.github.bytejuno.thetacore.plugin

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.bytejuno.thetacore.agent.ledger.AgentDatabase
import java.io.File

internal fun openTestStore(root: File) = AndroidInvocationStore.open(root, BundledSQLiteDriver())
internal fun appPayload(root: File, id: String): ByteArray =
    BundledSQLiteDriver().open(File(root, AgentDatabase.FILE_NAME).path).use { db ->
        val out = java.io.ByteArrayOutputStream()
        db.prepare("SELECT bytes FROM record_chunks WHERE call_id=? ORDER BY ordinal").use {
            it.bindText(1, id); while (it.step()) out.write(it.getBlob(0))
        }
        out.toByteArray()
    }
