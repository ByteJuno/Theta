package io.github.bytejuno.thetacore.hook

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.github.bytejuno.thetacore.plugin.PlatformPluginSignatureVerifier
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal data class HookImport(val bytes: ByteArray, val descriptor: HookDescriptor, val signers: String)
internal data class HookPublishGrant(val service: XposedService, val action: Long, val target: String)

/** Official bootstrap is used unchanged. Delivery identity is not authenticated here:
 * integration verification is outstanding. This is not a custom target -> host IPC endpoint.
 */
internal object HookHost {
    private val mutableStatus = MutableStateFlow("Framework unavailable; open LSPosed and enable the module")
    val status = mutableStatus.asStateFlow()
    @Volatile private var service: XposedService? = null
    private val registered = AtomicBoolean()
    private val connectionLock = Any()
    private var ambiguous = false
    private val order = HookHostOrder()
    private val scopeBudget = HookScopeBudget()
    // Official RemotePreferences updates its local map BEFORE IPC, even when commit fails.
    // Never use that cache to choose a file to delete after an ambiguous selection commit.
    private val selectionFence = HookSelectionFence()

    // Neither Main nor the connection-state lock waits for publication/remote storage IO.
    suspend fun connect() = withContext(Dispatchers.IO) {
        if (!registered.compareAndSet(false, true)) return@withContext
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(value: XposedService) {
                try {
                    require(value.apiVersion >= 102) { "Framework API 102 required" }
                    require(value.frameworkProperties and XposedService.PROP_CAP_REMOTE != 0L) { "Remote storage unavailable" }
                    synchronized(connectionLock) {
                        if (ambiguous || (service != null && service !== value)) {
                            ambiguous = true
                            service = null
                            order.next()
                            mutableStatus.value = "Multiple framework services; restart with one framework"
                        } else if (service !== value) {
                            service = value
                            order.next()
                            mutableStatus.value = "Framework connected (bootstrap identity unverified); no Hook published"
                        }
                    }
                } catch (error: Throwable) { fail(error) }
            }
            override fun onServiceDied(value: XposedService) {
                synchronized(connectionLock) {
                    if (service === value) {
                        service = null
                        order.next()
                        mutableStatus.value = "Framework disconnected; target restart required, switch propagation unknown"
                    }
                }
            }
        })
    }

    fun inspect(context: Context, uri: Uri): HookImport {
        val bytes = checkNotNull(context.contentResolver.openInputStream(uri)).use { it.bounded(HOOK_LIMIT) }
        val file = File.createTempFile("hook-review-", ".apk", context.cacheDir)
        try {
            file.writeBytes(bytes)
            val signers = PlatformPluginSignatureVerifier(context.packageManager).verify(file).sha256.joinToString(",")
            val descriptor = HookDescriptor.read(bytes)
            HookTargetPolicy.check(context, descriptor.target)
            val pinned = context.getSharedPreferences("hook-signers", Context.MODE_PRIVATE).getString(descriptor.id, null)
            require(pinned == null || pinned == signers) { "Hook signer changed" }
            return HookImport(bytes, descriptor, signers)
        } finally { file.delete() }
    }

    /** Record the explicit user's intent before dispatching work, not when IO happens to run. */
    fun beginAction(): Long = order.next()

    /** Callback receives only a detachable, bounded ticket; never the reviewed APK bytes. */
    private class ScopeListener(private val ticket: HookScopeTicket) : XposedService.OnScopeEventListener {
        override fun onScopeRequestApproved(approved: List<String>) { ticket.finish(ticket.target in approved) }
        override fun onScopeRequestFailed(message: String) { ticket.finish(false) }
    }

    suspend fun requestScope(target: String, action: Long): HookPublishGrant {
        order.checkCurrent(action)
        require(target == HOOK_TARGET)
        val current = service ?: error("Framework unavailable")
        val ticket = scopeBudget.reserve(target)
        val outcome = ticket.outcome()
        mutableStatus.value = "Scope requested; awaiting framework decision"
        try {
            current.requestScope(listOf(target), ScopeListener(ticket))
            check(withTimeout(60_000) { outcome.await() }) { "Target scope not approved" }
            check(service === current) { "Framework connection changed" }
            order.checkCurrent(action)
            return HookPublishGrant(current, action, target)
        } finally { ticket.detach() }
    }

    /** Call off Main. Immutable remote file is committed before enabled selection.
     * Disable and publication share ONLY this IO lock; connection registration does not.
     */
    fun publish(context: Context, value: HookImport, grant: HookPublishGrant) = order.exclusive {
        val current = grant.service
        order.checkCurrent(grant.action)
        check(service === current) { "Framework connection changed" }
        check(value.descriptor.target == grant.target) { "Scope grant target changed" }
        HookTargetPolicy.check(context, value.descriptor.target)
        check(value.descriptor.target in current.scope) { "Target absent from granted scope" }
        val hash = digest(value.bytes)
        val name = newHookPayloadName()
        val preferences = current.getRemotePreferences(HOOK_GROUP)
        val retention = HookPayloadRetention(object : HookPayloadDirectory {
            override fun list() = current.listRemoteFiles().toList()
            override fun selected(): String? {
                val text = preferences.getString("selection", null) ?: return null
                require(text.length <= 16384) { "Existing selection too large" }
                return JSONObject(text).getString("file")
            }
            override fun delete(name: String) { current.deleteRemoteFile(name) }
        }, selectionFence)
        // Reclaim only obsolete owned files, preserving selection even when disabled. Abort
        // before writing if cleanup failed, so repeated attempts cannot grow remote storage.
        retention.prepare(name)
        order.checkCurrent(grant.action)
        check(preferences.edit().putBoolean("enabled", false).commit()) { "Could not pause prior selection" }
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(current.openRemoteFile(name)).use {
                it.write(value.bytes)
                it.flush()
            }
            val actual = ParcelFileDescriptor.AutoCloseInputStream(current.openRemoteFile(name)).use { it.bounded(HOOK_LIMIT) }
            check(digest(actual) == hash) { "Remote payload readback mismatch" }
            check(service === current) { "Framework connection changed during publication" }
            order.checkCurrent(grant.action)
            val pins = context.getSharedPreferences("hook-signers", Context.MODE_PRIVATE)
            val oldPin = pins.getString(value.descriptor.id, null)
            check(oldPin == null || oldPin == value.signers) { "Hook signer changed" }
            check(pins.edit().putString(value.descriptor.id, value.signers).commit()) { "Signer pin write failed" }
            val selection = JSONObject().put("file", name).put("sha256", hash)
                .put("size", value.bytes.size).put("id", value.descriptor.id)
                .put("version", value.descriptor.version).put("target", value.descriptor.target)
                .put("signers", value.signers).toString()
            order.checkCurrent(grant.action)
            selectionFence.beforeCommit()
            check(preferences.edit().putString("selection", selection).putBoolean("enabled", true).commit()) {
                "Selection commit failed; restart Theta to reload framework state before retry"
            }
            selectionFence.committed()
            // A disable/new action arriving during commit must win, not be lost in the IO queue.
            order.checkCurrent(grant.action)
            check(service === current) { "Framework connection changed during selection commit" }
            order.published(grant.action)
        } catch (error: Throwable) {
            val paused = runCatching { preferences.edit().putBoolean("enabled", false).commit() }.getOrDefault(false)
            // A failed commit can already have applied remotely OR only in the SDK cache.
            // The fence keeps BOTH candidates and blocks allocation until a process restart
            // reloads authoritative preferences. Never unlink either possible selected file.
            val cleaned = runCatching { retention.clean() }.isSuccess
            throw IllegalStateException("Publication failed; pause=$paused cleanup=$cleaned; ${error.message.orEmpty().take(150)}", error)
        }
        // Cleanup failure does NOT roll back/delete the newly selected payload. The next
        // publication retries cleanup before allocating; report the retained staging bound.
        val cleaned = runCatching { retention.clean() }.isSuccess
        mutableStatus.value = "Published ${value.descriptor.id}; cold-restart target. Target execution not yet verified." +
            if (cleaned) "" else " Remote cleanup incomplete; next publication blocked until cleanup succeeds."
    }

    fun disable(action: Long) = order.exclusive {
        if (order.shouldDisable(action)) {
            val current = service ?: error("Framework unavailable; disable module/scope in framework manager")
            check(current.getRemotePreferences(HOOK_GROUP).edit().putBoolean("enabled", false).commit()) {
                "Global switch write failed"
            }
            order.disabled(action)
            mutableStatus.value = "Global switch saved; live propagation unverified. Force-stop target for guaranteed code exit."
        }
    }
    fun fail(error: Throwable) {
        mutableStatus.value = "Hook operation failed: ${error.javaClass.simpleName}; ${error.message.orEmpty().take(220)}"
    }
}
