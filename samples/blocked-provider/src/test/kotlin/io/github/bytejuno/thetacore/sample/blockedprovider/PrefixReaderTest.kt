package io.github.bytejuno.thetacore.sample.blockedprovider

import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.*
import org.junit.Test

class PrefixReaderTest {
    private val bytes get() = PrefixArchive.bytes

    @Test fun controlCopiesExactArchiveWithHostSizedReadsAndBoundedMarkers() {
        val events = mutableListOf<String>()
        val reader = PrefixReader(bytes, false, ReadGate(ReadMode.IMMEDIATE), events::add)
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var offset = 0L
        while (true) {
            val count = reader.read(offset, buffer.size, buffer)
            if (count == 0) break
            output.write(buffer, 0, count)
            offset += count
        }
        repeat(20) { assertEquals(0, reader.read(offset, buffer.size, buffer)) }
        assertArrayEquals(bytes, output.toByteArray())
        assertTrue(events.first().startsWith("PREFIX_FIRST_RETURN offset=0 bytes=8192 "))
        assertEquals(12, events.count { it.startsWith("PREFIX_FIRST_RETURN") || it.startsWith("PREFIX_READ_RETURN") })
        assertEquals(1, events.count { it.startsWith("PREFIX_READ_LOG_LIMIT") })
        assertEquals(1, events.count { it.startsWith("PREFIX_EOF") })
        assertFalse(events.any { it.startsWith("PREFIX_WAIT") })
    }

    @Test fun firstReadIsPositiveIncompleteZipEvenForOversizedRequest() {
        val reader = PrefixReader(bytes, true, ReadGate(ReadMode.PREFIX_ABORT))
        val target = ByteArray(bytes.size + 100)
        val count = reader.read(0, target.size, target)
        assertTrue(count > 0 && count < bytes.size)
        assertArrayEquals(bytes.copyOfRange(0, count), target.copyOfRange(0, count))
        reader.close()
        assertEquals("CLOSED", failure { reader.read(count.toLong(), 1, target) })
    }

    @Test fun shortDestinationAndNonzeroOffsetsCopyCorrectRanges() {
        val reader = PrefixReader(bytes, false, ReadGate(ReadMode.IMMEDIATE))
        val target = ByteArray(7)
        assertEquals(7, reader.read(13, 8192, target))
        assertArrayEquals(bytes.copyOfRange(13, 20), target)
        assertEquals(3, reader.read(bytes.size.toLong() - 3, 8192, target))
        assertArrayEquals(bytes.takeLast(3).toByteArray(), target.copyOfRange(0, 3))
        assertEquals(0, reader.read(Long.MAX_VALUE, 8192, target))
    }

    @Test fun probesDoNotConsumePrefixAndInvalidRangesDoNotOverflow() {
        val reader = PrefixReader(bytes, true, ReadGate(ReadMode.PREFIX_ABORT))
        assertEquals(0, reader.read(0, 0, ByteArray(4)))
        assertEquals(0, reader.read(0, 4, ByteArray(0)))
        assertEquals(0, reader.read(Long.MAX_VALUE, 4, ByteArray(4)))
        assertThrows(IllegalArgumentException::class.java) { reader.read(-1, 4, ByteArray(4)) }
        assertThrows(IllegalArgumentException::class.java) { reader.read(0, -1, ByteArray(4)) }
        assertEquals(4, reader.read(0, 4, ByteArray(4)))
        reader.close()
    }

    @Test fun secondReadBlocksAndReleaseNeverConvertsItIntoSuccess() {
        assertWaitThenFailure(firstOffset = 0, secondOffset = 8192, close = false)
    }

    @Test fun nonzeroFirstOffsetAndRepeatedOffsetStillGateOnPositiveReadCount() {
        assertWaitThenFailure(firstOffset = 23, secondOffset = 23, close = false)
    }

    @Test fun eofRequestAfterPrefixCannotBypassGateAndCloseUnblocksWaiter() {
        assertWaitThenFailure(firstOffset = 0, secondOffset = Long.MAX_VALUE, close = true)
    }

    private fun assertWaitThenFailure(firstOffset: Long, secondOffset: Long, close: Boolean) {
        val gate = ReadGate(ReadMode.PREFIX_ABORT)
        val entered = CountDownLatch(1)
        val reader = PrefixReader(bytes, true, gate) {
            if (it.startsWith("PREFIX_WAIT")) entered.countDown()
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            gate.release() // Even Release before first read cannot arm a success path.
            val first = ByteArray(8192)
            assertEquals(first.size, reader.read(firstOffset, first.size, first))
            assertArrayEquals(bytes.copyOfRange(firstOffset.toInt(), firstOffset.toInt() + first.size), first)
            assertEquals(0, reader.read(secondOffset, 0, ByteArray(1)))
            val pending = executor.submit<String> {
                failure { reader.read(secondOffset, 8192, ByteArray(8192)) }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            gate.release()
            try {
                pending.get(50, TimeUnit.MILLISECONDS)
                fail("Release must not resume interrupted copy")
            } catch (_: TimeoutException) { /* still waiting */ }
            if (close) reader.close() else gate.abort("RESET")
            val expected = if (close) "CLOSED" else "RESET"
            assertEquals(expected, pending.get(2, TimeUnit.SECONDS))
            gate.release()
            assertEquals(expected, failure { reader.read(0, 1, ByteArray(1)) })
        } finally {
            reader.close()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun abortBeforeFirstReadReturnsNoPrefixAndKeepsFirstFailure() {
        val events = mutableListOf<String>()
        val gate = ReadGate(ReadMode.PREFIX_ABORT)
        val reader = PrefixReader(bytes, true, gate, events::add)
        gate.abort("RESET")
        reader.close()
        gate.release()
        repeat(20) { assertEquals("RESET", failure { reader.read(0, 8192, ByteArray(8192)) }) }
        assertEquals(1, events.size)
        assertTrue(events.single().contains("reads=0 returned=0 errno=EIO"))
    }

    @Test fun deadlineAfterPositivePrefixIsBoundedAbortWithoutWatchdogOrRelease() {
        val source = bytes // Generate archive before starting short test deadline.
        val gate = ReadGate(ReadMode.PREFIX_ABORT, lifetimeMillis = 500)
        val reader = PrefixReader(source, true, gate)
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertEquals(8192, reader.read(0, 8192, ByteArray(8192)))
            val pending = executor.submit<String> { failure { reader.read(8192, 8192, ByteArray(8192)) } }
            assertEquals("TIMEOUT", pending.get(2, TimeUnit.SECONDS))
            gate.release()
            assertEquals("TIMEOUT", failure { reader.read(0, 1, ByteArray(1)) })
        } finally {
            reader.close()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    @Test fun closeBeforeFirstReadRejectsEvenEmptyProbesButNextSessionIsFresh() {
        val oldGate = ReadGate(ReadMode.PREFIX_ABORT)
        val oldReader = PrefixReader(bytes, true, oldGate)
        oldReader.close()
        oldGate.release()
        assertEquals("CLOSED", failure { oldReader.read(0, 0, ByteArray(0)) })
        val next = PrefixReader(bytes, true, ReadGate(ReadMode.PREFIX_ABORT))
        assertEquals(4, next.read(0, 4, ByteArray(4)))
        next.close()
    }

    @Test fun expiredBeforeFirstReadCannotReturnPrefix() {
        val source = bytes
        val gate = ReadGate(ReadMode.PREFIX_ABORT, lifetimeMillis = 5)
        assertEquals("TIMEOUT", gate.await())
        assertEquals("TIMEOUT", failure { PrefixReader(source, true, gate).read(0, 1, ByteArray(1)) })
    }

    private fun failure(block: () -> Int): String {
        try { block(); throw AssertionError("Expected source-copy failure") }
        catch (error: SourceReadAborted) { return error.reason }
    }
}
