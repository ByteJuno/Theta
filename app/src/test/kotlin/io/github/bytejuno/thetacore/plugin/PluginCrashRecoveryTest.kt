package io.github.bytejuno.thetacore.plugin

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PluginCrashRecoveryTest {
    private class Clock : PluginRecoveryClock {
        var wall = 1_000_000L
        var elapsed = 100_000L
        override fun wallMillis() = wall
        override fun elapsedMillis() = elapsed
        fun advance(ms: Long) { wall += ms; elapsed += ms }
    }
    private class Storage : PluginRecoveryStorage {
        var state: ByteArray? = null
        var crash: ByteArray? = null
        var failWrites = false
        var writes = 0
        override fun readState() = state?.clone()
        override fun writeState(bytes: ByteArray) {
            if (failWrites) throw IOException("disk full")
            state = bytes.clone(); writes++
        }
        override fun readCrash() = crash?.clone()
        override fun writeCrash(bytes: ByteArray) { crash = bytes.clone() }
        override fun clearCrash() { crash = null }
    }
    private class Rig {
        val clock = Clock()
        val storage = Storage()
        var serial = 0
        var exits = emptyList<PluginProcessExit>()
        fun boot(): PluginCrashRecovery {
            serial++
            return PluginCrashRecovery(storage, clock, PluginExitHistory { exits }, serial, "process-$serial")
        }
        fun crash(recovery: PluginCrashRecovery, ms: Long = 100) { clock.advance(ms); recovery.recordUncaughtCrash() }
        fun safeMode(): PluginCrashRecovery {
            crash(boot()); crash(boot())
            return boot()
        }
    }

    @Test fun twoShortCrashesThenSafeModeCrashEscalatesToData() {
        val r = Rig()
        val first = r.boot()
        assertFalse(first.restorePolicy().skipCode)
        r.crash(first)
        val second = r.boot()
        assertFalse(second.restorePolicy().skipCode)
        r.crash(second)
        val third = r.boot()
        assertTrue(third.restorePolicy().skipCode)
        assertFalse(third.policy.skipData)
        r.crash(third)
        assertTrue(r.boot().policy.skipData)
    }

    @Test fun backwardWallClockStillCountsShortCrashesUsingMonotonicAge() {
        val r = Rig()
        repeat(2) {
            val process = r.boot()
            assertFalse(process.policy.skipCode)
            r.clock.wall -= 100_000
            r.clock.elapsed += 100
            process.recordUncaughtCrash()
        }
        val safe = r.boot()
        assertTrue(safe.policy.skipCode)
        assertFalse(safe.policy.skipData)
    }

    @Test fun backwardWallClockCrashOverridesResumeEscalatesAndRetainsSuspect() {
        val r = Rig()
        val safe = r.safeMode()
        assertTrue(safe.policy.skipCode)
        assertFalse(safe.policy.skipData)
        assertTrue(safe.enterPluginPage("example.safeData"))
        assertTrue(safe.requestResumeNextColdStart())
        r.clock.wall -= 100_000
        r.clock.elapsed += 100
        safe.recordUncaughtCrash()
        val next = r.boot()
        assertTrue(next.policy.skipCode)
        assertTrue(next.policy.skipData)
        assertEquals(setOf("example.safeData"), next.policy.suspects)
        assertFalse(next.resumeRequested)
    }

    @Test fun backwardWallClockDoesNotMakeLongLivedReceiptAShortCrash() {
        val r = Rig()
        r.crash(r.boot())
        val second = r.boot()
        r.clock.wall -= 100_000
        r.clock.elapsed += PLUGIN_STABLE_WINDOW_MS
        second.recordUncaughtCrash()
        assertFalse(r.boot().policy.skipCode)
    }

    @Test fun duplicateReceiptAndOsHistoryCountOnlyOneCrash() {
        val r = Rig()
        val first = r.boot()
        r.crash(first)
        val receipt = r.storage.crash!!.clone()
        first.recordUncaughtCrash()
        assertArrayEquals(receipt, r.storage.crash)
        r.exits = listOf(PluginProcessExit(1, r.clock.wall, PluginExitReason.CRASH))
        val second = r.boot()
        assertFalse(second.policy.skipCode)
        // Replay stale receipt/history, not a new crash of process 2.
        r.storage.crash = receipt
        assertFalse(r.boot().policy.skipCode)
    }

    @Test fun stableProcessResetsStreakAndDoesNotExitSafeMode() {
        val r = Rig()
        r.crash(r.boot())
        val second = r.boot()
        r.clock.advance(PLUGIN_STABLE_WINDOW_MS)
        second.markStableIfDue()
        r.crash(second)
        val third = r.boot()
        assertFalse(third.policy.skipCode)
        r.crash(third)
        assertFalse(r.boot().policy.skipCode)
        val safeRig = Rig()
        val safe = safeRig.safeMode()
        safeRig.clock.advance(PLUGIN_STABLE_WINDOW_MS)
        safe.markStableIfDue()
        assertTrue(safeRig.boot().policy.skipCode)
    }

    @Test fun longLivedCrashWithoutTimerDoesNotCountAsShortCrash() {
        val r = Rig()
        r.crash(r.boot())
        r.crash(r.boot(), PLUGIN_STABLE_WINDOW_MS)
        assertFalse(r.boot().policy.skipCode)
    }

    @Test fun missingProcessAndForceStopAreNotCrashes() {
        val r = Rig()
        r.crash(r.boot())
        r.boot()
        r.clock.advance(100)
        r.exits = listOf(PluginProcessExit(2, r.clock.wall, PluginExitReason.OTHER))
        val afterStop = r.boot()
        assertFalse(afterStop.policy.skipCode)
        r.crash(afterStop)
        assertFalse(r.boot().policy.skipCode)
        repeat(5) { assertFalse(r.boot().policy.skipCode) }
    }

    @Test fun nativeHistoryUsesPidAndTimeAndOnlyRealCrashReasons() {
        val r = Rig()
        r.boot().enterPluginPage("example.native")
        r.clock.advance(100)
        r.exits = listOf(PluginProcessExit(1, r.clock.wall, PluginExitReason.NATIVE_CRASH))
        val second = r.boot()
        assertEquals(setOf("example.native"), second.policy.suspects)
        assertFalse(second.policy.skipCode)
        r.clock.advance(100)
        r.exits = r.exits + PluginProcessExit(2, r.clock.wall, PluginExitReason.NATIVE_CRASH)
        assertTrue(r.boot().policy.skipCode)
        val stale = Rig()
        stale.boot()
        stale.exits = listOf(
            PluginProcessExit(1, stale.clock.wall - 1, PluginExitReason.CRASH),
            PluginProcessExit(999, stale.clock.wall, PluginExitReason.CRASH),
        )
        assertFalse(stale.boot().policy.skipCode)
    }

    @Test fun pageAttributionIsDurableBeforeCrashAndClearedOnLeave() {
        val r = Rig()
        val first = r.boot()
        val before = r.storage.state!!.clone()
        assertTrue(first.enterPluginPage("example.page"))
        assertFalse(before.contentEquals(r.storage.state!!))
        r.crash(first)
        val second = r.boot()
        assertEquals(setOf("example.page"), second.policy.suspects)
        assertTrue(second.enterPluginPage("example.notTheCulprit"))
        second.leavePluginPage()
        r.crash(second)
        assertEquals(setOf("example.page"), r.boot().policy.suspects)
    }

    @Test fun explicitResumeAppliesOnlyNextColdStartAndKeepsSuspects() {
        val r = Rig()
        val first = r.boot()
        first.enterPluginPage("example.suspect")
        r.crash(first); r.crash(r.boot())
        val safe = r.boot()
        assertTrue(safe.requestResumeNextColdStart())
        assertTrue(safe.resumeRequested)
        assertTrue(safe.restorePolicy().skipCode)
        val resumed = r.boot()
        assertFalse(resumed.policy.skipCode)
        assertFalse(resumed.policy.skipData)
        assertEquals(setOf("example.suspect"), resumed.policy.suspects)
        assertTrue(resumed.acknowledgeSuspect("example.suspect"))
        assertTrue(r.boot().policy.suspects.isEmpty())
    }

    @Test fun newCrashOverridesResumeAndManualSkipOnlyLatchesOnce() {
        val r = Rig()
        val safe = r.safeMode()
        safe.requestResumeNextColdStart()
        r.crash(safe)
        assertTrue(r.boot().policy.skipData)
        val manual = Rig()
        val first = manual.boot()
        assertFalse(first.policy.skipCode) // Preview must not claim admission.
        assertTrue(first.restorePolicy(true).skipData)
        assertTrue(first.restorePolicy(false).skipCode)
        val second = manual.boot()
        assertFalse(second.restorePolicy(false).skipCode)
        assertFalse(second.restorePolicy(true).skipCode) // warm request cannot unload code
    }

    @Test fun corruptStateAndReceiptFailSafeButResumeCanRecoverJournal() {
        for (corruptReceipt in listOf(false, true)) {
            val r = Rig()
            r.boot()
            if (corruptReceipt) r.storage.crash = byteArrayOf(1, 2, 3)
            else r.storage.state = byteArrayOf(1, 2, 3)
            val safe = r.boot()
            assertTrue(safe.policy.skipData)
            assertTrue(safe.requestResumeNextColdStart())
            assertFalse(r.boot().policy.skipCode)
        }
    }

    @Test fun writeFailureBlocksPageAndResumeAndSuspectAcknowledgement() {
        val r = Rig()
        val first = r.boot()
        first.enterPluginPage("example.suspect")
        r.crash(first)
        val second = r.boot()
        r.storage.failWrites = true
        assertFalse(second.acknowledgeSuspect("example.suspect"))
        assertEquals(setOf("example.suspect"), second.policy.suspects)
        assertTrue(second.policy.skipData)
        assertFalse(second.enterPluginPage("example.page"))
        assertFalse(second.requestResumeNextColdStart())
    }

    @Test fun repeatedRestoreDoesNotCreateSessionsOrResetTimer() {
        val r = Rig()
        val first = r.boot()
        val before = r.storage.writes
        repeat(10) { first.restorePolicy() }
        assertEquals(before, r.storage.writes)
        r.crash(first)
        r.crash(r.boot())
        assertTrue(r.boot().policy.skipCode)
    }

    @Test fun handlerDoesNotInspectThrowableAndForwardsOnceEvenOnRecursion() {
        val r = Rig()
        val recovery = r.boot()
        var forwards = 0
        var terminations = 0
        val hostile = object : RuntimeException() {
            override fun toString(): String = throw AssertionError("plugin toString")
            override fun getStackTrace(): Array<StackTraceElement> = throw AssertionError("plugin stack")
        }
        lateinit var handler: PluginRecoveryUncaughtHandler
        handler = PluginRecoveryUncaughtHandler(recovery, Thread.UncaughtExceptionHandler { thread, error ->
            assertSame(hostile, error)
            assertNotNull(r.storage.crash)
            forwards++
            handler.uncaughtException(thread, error)
        }, { terminations++ })
        handler.uncaughtException(Thread.currentThread(), hostile)
        assertEquals(1, forwards)
        assertEquals(2, terminations)
    }

    @Test fun safeModeCrashEscalatesEvenAfterStableWindow() {
        val r = Rig()
        val safe = r.safeMode()
        r.clock.advance(PLUGIN_STABLE_WINDOW_MS)
        safe.markStableIfDue()
        r.crash(safe)
        assertTrue(r.boot().policy.skipData)
    }

    @Test fun unavailableHistoryDoesNotInventCrashes() {
        val r = Rig()
        r.boot()
        val recovery = PluginCrashRecovery(r.storage, r.clock,
            PluginExitHistory { throw SecurityException("history unavailable") }, 2, "second")
        assertFalse(recovery.policy.skipCode)
        assertTrue(recovery.policy.suspects.isEmpty())
    }

    @Test fun interruptedReceiptFailsSafeAndIsRetiredBeforeResume() {
        val dir = Files.createTempDirectory("theta-recovery-pending").toFile()
        try {
            val clock = Clock()
            val store = FilePluginRecoveryStorage(dir)
            PluginCrashRecovery(store, clock, pid = 1, token = "first")
            File(dir, "crash.bin.pending").writeBytes(byteArrayOf(1))
            val safe = PluginCrashRecovery(store, clock, pid = 2, token = "second")
            assertTrue(safe.policy.skipData)
            assertTrue(safe.requestResumeNextColdStart())
            assertFalse(PluginCrashRecovery(store, clock, pid = 3, token = "third").policy.skipCode)
        } finally { dir.deleteRecursively() }
    }

    @Test fun boundedFileJournalLeavesUnrelatedStateAlone() {
        val dir = Files.createTempDirectory("theta-recovery-test").toFile()
        try {
            val unrelated = File(dir, "plugin-state.json").also { it.writeText("unchanged") }
            val store = FilePluginRecoveryStorage(File(dir, "recovery"))
            val clock = Clock()
            PluginCrashRecovery(store, clock, pid = 1, token = "first").recordUncaughtCrash()
            assertNotNull(store.readCrash())
            assertFalse(PluginCrashRecovery(store, clock, pid = 2, token = "second").policy.skipCode)
            File(dir, "recovery/state.bin").writeBytes(ByteArray(32_769))
            assertTrue(PluginCrashRecovery(store, clock, pid = 3, token = "third").policy.skipData)
            assertEquals("unchanged", unrelated.readText())
        } finally { dir.deleteRecursively() }
    }
}
