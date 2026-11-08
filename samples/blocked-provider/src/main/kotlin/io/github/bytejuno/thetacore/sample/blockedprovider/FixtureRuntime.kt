package io.github.bytejuno.thetacore.sample.blockedprovider

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.FileNotFoundException
import java.util.ArrayDeque

/** Same process for Activity and Provider. No service, disk state, receiver or remote control API. */
internal object FixtureRuntime {
    const val TAG = "ThetaBlockedSource"
    const val CAPACITY = 3
    const val LIFETIME_MS = 120_000L
    private val main = Handler(Looper.getMainLooper())
    private val sessions = linkedMapOf<Long, Session>()
    private val events = ArrayDeque<String>()
    private var nextId = 0L
    private var sequence = 0L

    internal class Session(val id: Long, val document: FixtureDocument, val uid: Int) {
        val gate = ReadGate(document.mode, lifetimeMillis = LIFETIME_MS)
        val prefixReader = if (document.isPrefixFixture) PrefixReader(
            document.bytes, document.mode == ReadMode.PREFIX_ABORT, gate,
        ) { marker -> event(this, marker) } else null
        val thread = HandlerThread("fixture-source-$id")
        var firstRead = true // callback-thread confined
        val watchdog = Runnable {
            gate.abort("TIMEOUT")
            event(this, "DEADLINE_ABORT")
        }
    }

    @Synchronized fun open(document: FixtureDocument, uid: Int): Session {
        if (sessions.size >= CAPACITY) {
            event(null, "BUSY_REJECT doc=${document.key} uid=$uid")
            throw FileNotFoundException("Fixture busy: $CAPACITY descriptors retained; release/reset and close clients")
        }
        val session = Session(++nextId, document, uid)
        sessions[session.id] = session
        event(session, "OPEN_BEGIN")
        main.postDelayed(session.watchdog, LIFETIME_MS)
        return session
    }

    @Synchronized fun event(session: Session?, marker: String) {
        val line = "seq=${++sequence} t=${SystemClock.elapsedRealtime()} pid=${Process.myPid()} " +
            "sid=${session?.id ?: 0} doc=${session?.document?.key ?: "-"} uid=${session?.uid ?: -1} $marker"
        if (events.size == 160) events.removeFirst()
        events.addLast(line)
        Log.i(TAG, line)
    }

    @Synchronized fun finish(session: Session, marker: String) {
        if (sessions.remove(session.id) == null) return
        main.removeCallbacks(session.watchdog)
        session.prefixReader?.close()
        event(session, marker)
        session.thread.quitSafely()
    }

    @Synchronized fun releaseAll() {
        // Snapshot action only: future opens have a fresh, closed gate.
        event(null, "UI_RELEASE count=${sessions.size}")
        sessions.values.forEach {
            event(it, if (it.document.mode == ReadMode.PREFIX_ABORT) "RELEASE_IGNORED_PREFIX_ABORT" else "RELEASE_REQUEST")
            it.gate.release()
        }
    }

    @Synchronized fun reset() {
        event(null, "UI_RESET count=${sessions.size}")
        sessions.values.forEach { event(it, "RESET_ABORT"); it.gate.abort("RESET") }
        // Do not pretend a client closed: onRelease owns slot/thread retirement.
    }

    @Synchronized fun snapshot(): String = buildString {
        append("pid=${Process.myPid()} active=${sessions.size}/$CAPACITY\n")
        sessions.values.forEach {
            append("sid=${it.id} ${it.document.key} uid=${it.uid} failure=${it.gate.failure() ?: "none"}\n")
        }
        append("\n")
        events.forEach { append(it).append('\n') }
    }
}
