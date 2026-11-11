package io.github.bytejuno.thetacore

import io.github.bytejuno.thetacore.plugin.PluginFailureReason
import io.github.bytejuno.thetacore.plugin.PluginPackageException
import org.junit.Assert.*
import org.junit.Test

class UiDiagnosticsTest {
    @Test fun allExpectedReasonsHaveDistinctLocalizedResources() {
        val expected = PluginFailureReason.entries.filter { it != PluginFailureReason.UNKNOWN }
        val texts = expected.map { reason -> uiFailure(PluginPackageException("technical details", reason)) }
        assertTrue(texts.all { it.key != R.string.operation_failed && it.arguments.isEmpty() })
        assertEquals(expected.size, texts.map { it.key }.distinct().size)
        assertEquals(R.string.failure_import_cleanup, failureText(PluginFailureReason.IMPORT_CLEANUP_PENDING).key)
        assertEquals(R.string.failure_digest_mismatch, failureText(PluginFailureReason.PACKAGE_DIGEST_MISMATCH).key)
        assertEquals(R.string.failure_signer_mismatch, failureText(PluginFailureReason.SIGNER_MISMATCH).key)
        assertEquals(R.string.failure_restart_pending, failureText(PluginFailureReason.RESTART_PENDING).key)
    }

    @Test fun unknownMessagesAndClassNamesNeverBecomeUiArguments() {
        listOf(IllegalStateException("private technical detail"),
            PluginPackageException("Package changed while installing"),
            RuntimeException("Import cleanup pending; use Retry import cleanup")).forEach {
            assertEquals(uiText(R.string.operation_failed), uiFailure(it))
        }
    }
}
