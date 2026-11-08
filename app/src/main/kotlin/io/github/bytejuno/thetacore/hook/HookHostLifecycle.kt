package io.github.bytejuno.thetacore.hook

import kotlinx.coroutines.CompletableDeferred
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal fun validHookId(id: String) = id.matches(Regex("[A-Za-z][A-Za-z0-9_.]{0,159}"))

/** Remote names are opaque: accepted manifest IDs (including consecutive dots) are not paths. */
internal fun newHookPayloadName(): String = "theta-hook-${UUID.randomUUID()}.apk"

internal fun validHookRemoteName(name: String) =
    name.matches(Regex("[A-Za-z0-9_.-]{1,240}\\.apk")) && !name.contains("..")

/** Only our exact new/legacy generated namespaces may be reclaimed; never arbitrary module files. */
internal fun ownedHookPayload(name: String): Boolean {
    val uuid = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    return validHookRemoteName(name) &&
        (name.matches(Regex("theta-hook-$uuid\\.apk")) ||
            name.matches(Regex("[A-Za-z][A-Za-z0-9_.]{0,159}-[1-9][0-9]{0,18}-$uuid\\.apk")))
}

internal interface HookPayloadDirectory {
    fun list(): List<String>
    /** Must throw if selection cannot be read/parsed. Null means no selection, not read failure. */
    fun selected(): String?
    fun delete(name: String)
}

/** Run under the publication lock. At most selected + one staging payload are admitted.
 * Failed deletion/listing aborts BEFORE allocating another payload. The currently selected
 * payload is protected even while disabled, including a commit that applied but reported failure.
 */
internal class HookSelectionFence {
    private var uncertain = false
    fun beforeCommit() { uncertain = true }
    fun committed() { uncertain = false }
    fun requireCertain() = check(!uncertain) {
        "Selection commit uncertain; restart Theta to reload framework state before publication/cleanup"
    }
}

internal class HookPayloadRetention(
    private val directory: HookPayloadDirectory,
    private val fence: HookSelectionFence = HookSelectionFence(),
) {
    fun clean() {
        fence.requireCertain()
        val selected = directory.selected()
        check(selected == null || validHookRemoteName(selected)) { "Invalid existing selection; inspect remote storage" }
        directory.list().filter { ownedHookPayload(it) && it != selected }.forEach { directory.delete(it) }
        check(directory.list().none { ownedHookPayload(it) && it != selected }) {
            "Remote payload cleanup incomplete; no new payload allocated"
        }
    }

    fun prepare(name: String) {
        require(ownedHookPayload(name))
        clean()
        check(name !in directory.list()) { "Remote filename already exists" }
    }
}

/** Intent sequencing is nonblocking. Only IO workers take the operation lock.
 * A disable invalidates earlier scope approvals/publications immediately. If a newer publication
 * already committed before an older disable acquired the lock, that disable must not undo it.
 */
internal class HookHostOrder {
    private val serial = AtomicLong()
    private val lock = ReentrantLock()
    private var applied = 0L

    fun next(): Long = serial.incrementAndGet()
    fun checkCurrent(ticket: Long) = check(ticket == serial.get()) { "Hook action superseded; confirm again" }
    fun <T> exclusive(block: () -> T): T = lock.withLock(block)
    fun published(ticket: Long) { applied = ticket }
    fun shouldDisable(ticket: Long): Boolean = ticket > applied
    fun disabled(ticket: Long) { applied = ticket }
}

/** The official SDK has no scope-listener cancellation API and removes a listener only AFTER
 * invoking its terminal callback. A process-lifetime submission budget is deliberately not
 * replenished: even synchronous IPC errors, missing callbacks, or removal races cannot grow
 * the SDK's static callback map past this bound. Restart the host to replenish the budget.
 */
internal class HookScopeBudget(private val limit: Int = 16) {
    private var submitted = 0
    @Synchronized fun reserve(target: String): HookScopeTicket {
        check(submitted < limit) { "Scope request budget exhausted; restart Theta before requesting again" }
        submitted++
        return HookScopeTicket(target)
    }
}

/** An SDK-retained ticket carries only the target string after detach, never HookImport,
 * Context, service, or a suspended UI continuation. Completion and detach are race-safe.
 */
internal class HookScopeTicket(val target: String) {
    private var waiter: CompletableDeferred<Boolean>? = CompletableDeferred()
    @Synchronized fun outcome(): CompletableDeferred<Boolean> = checkNotNull(waiter)
    @Synchronized fun finish(approved: Boolean) { waiter?.complete(approved) }
    @Synchronized fun detach() {
        waiter?.cancel()
        waiter = null
    }
    @Synchronized internal fun hasWaiter(): Boolean = waiter != null
}
