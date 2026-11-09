package io.github.bytejuno.thetacore.hook

import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class HookDocumentPickerTest {
    @Test fun prefersSystemDocumentsUi() {
        assertEquals("com.android.documentsui", selectHookDocumentPicker(listOf(
            HookPickerCandidate("vendor.picker", true),
            HookPickerCandidate("com.google.android.documentsui", true),
            HookPickerCandidate("com.android.documentsui", true),
        )))
    }
    @Test fun acceptsGoogleSystemVariant() {
        assertEquals("com.google.android.documentsui", selectHookDocumentPicker(listOf(
            HookPickerCandidate("com.android.documentsui", false),
            HookPickerCandidate("com.google.android.documentsui", true),
        )))
    }
    @Test fun absentOrNonSystemFallsBackToImplicitPicker() {
        assertNull(selectHookDocumentPicker(emptyList()))
        assertNull(selectHookDocumentPicker(listOf(HookPickerCandidate("com.android.documentsui", false))))
    }
    @Test fun openFailurePreservesCauseAndActionableMessage() {
        val cause = FileNotFoundException("ENOENT")
        val error = assertThrows(HookSourceReadException::class.java) { readHookSource { throw cause } }
        assertSame(cause, error.cause)
        assertTrue(error.message!!.contains("system document picker"))
    }
    @Test fun nullStreamIsReadableFailure() {
        assertThrows(HookSourceReadException::class.java) { readHookSource { null } }
    }
    @Test fun readAndCloseFailuresPreserveBothCauses() {
        val read = IOException("read")
        val close = IOException("close")
        val error = assertThrows(HookSourceReadException::class.java) {
            readHookSource { object : InputStream() {
                override fun read(): Int = throw read
                override fun close() { throw close }
            } }
        }
        assertSame(read, error.cause)
        assertSame(close, read.suppressed.single())
    }
    @Test fun readsExactBytesAndCloses() {
        var closed = false
        val expected = byteArrayOf(1, 2, 3)
        val actual = readHookSource { object : java.io.ByteArrayInputStream(expected) {
            override fun close() { closed = true; super.close() }
        } }
        assertArrayEquals(expected, actual)
        assertTrue(closed)
    }
    @Test fun sizeLimitStillRejectsPayload() {
        assertThrows(IllegalArgumentException::class.java) { readHookSource { ByteArray(HOOK_LIMIT + 1).inputStream() } }
        assertEquals(HOOK_LIMIT, readHookSource { ByteArray(HOOK_LIMIT).inputStream() }.size)
    }
    @Test fun cancellationIsNotReclassified() {
        val cancellation = CancellationException("cancel")
        val actual = assertThrows(CancellationException::class.java) { readHookSource { throw cancellation } }
        assertSame(cancellation, actual)
    }
    @Test fun revokedGrantIsSourceFailure() {
        val denied = SecurityException("revoked")
        assertSame(denied, assertThrows(HookSourceReadException::class.java) {
            readHookSource { throw denied }
        }.cause)
    }
}
