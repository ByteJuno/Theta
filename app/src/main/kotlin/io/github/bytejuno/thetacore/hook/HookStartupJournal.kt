package io.github.bytejuno.thetacore.hook

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One admitted main process per ordinary target. Stored in target device-protected files.
 * Counts incomplete startups conservatively (force-stop can count too), never claims causality.
 * Version-keyed quarantine persists until target/plugin update or explicit target-data reset.
 */
internal class HookStartupJournal(directory: File, key: String) {
    init { require(key.matches(Regex("[0-9a-f]{64}"))) }
    private val file = File(directory, "$key.state")
    private val pending = File(directory, "$key.pending")
    private var admitted = false
    private var quarantined = false

    @Synchronized fun begin(): Boolean {
        admitted = false
        if (quarantined) return false
        require(!pending.exists()) { "Interrupted Hook journal; refusing admission" }
        val count = if (!file.exists()) 0 else {
            require(file.length() in 1..3)
            file.readText().toInt().also { require(it in 0..3) }
        }
        if (count >= 3) return false
        write(count + 1)
        admitted = true
        return true
    }
    @Synchronized fun stable() {
        // Only this admitted run may acknowledge its pending startup. In particular,
        // neither a late timer nor a fresh journal may erase an explicit quarantine.
        if (admitted && !quarantined) write(0)
    }
    @Synchronized fun quarantine() {
        // Latch before IO too: even a failed quarantine write must not be followed
        // by a successful stable write from a timer already in flight.
        quarantined = true
        write(3)
    }
    private fun write(count: Int) {
        require(file.parentFile.isDirectory || file.parentFile.mkdirs())
        pending.outputStream().use { it.write(count.toString().toByteArray()); it.fd.sync() }
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
