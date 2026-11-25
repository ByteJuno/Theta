package io.github.bytejuno.thetacore.chat

import android.content.Context
import io.github.bytejuno.thetacore.agent.ledger.ApprovalMode
import io.github.bytejuno.thetacore.agent.ledger.ApprovalPolicy
import io.github.bytejuno.thetacore.agent.ledger.ApprovalRequest
import io.github.bytejuno.thetacore.agent.ledger.InvocationInput
import io.github.bytejuno.thetacore.agent.ledger.Risk
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.Properties
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ChatApprovalSettings(
    val mode: ApprovalMode = ApprovalMode.BALANCED,
    val allowRootLongTerm: Boolean = false,
)

internal fun canGrantMultiCallApproval(approval: ApprovalRequest, allowRootLongTerm: Boolean): Boolean =
    approval.unknownCalls.isEmpty() && (approval.risk != Risk.ROOT || allowRootLongTerm)

/**
 * Host-owned local persistence for attended chat approval mode and Root multi-call grant toggle.
 * Fresh installations default to BALANCED mode with allowRootLongTerm disabled.
 */
internal class ChatApprovalSettingsStore(noBackupDirectory: File) {
    private val root = noBackupDirectory.absoluteFile.toPath().normalize()
    private val directory = root.resolve(DIRECTORY)
    private val file = directory.resolve(FILE_NAME)
    private val lock = locks[(file.toString().hashCode() and Int.MAX_VALUE) % locks.size]
    @Volatile private var initialized = false
    private val mutableState = MutableStateFlow(ChatApprovalSettings())
    val state: StateFlow<ChatApprovalSettings> = mutableState.asStateFlow()

    fun ensureLoaded(): ChatApprovalSettings = synchronized(lock) {
        if (!initialized) {
            mutableState.value = readFromDisk()
            initialized = true
        }
        mutableState.value
    }

    fun current(): ChatApprovalSettings = if (initialized) mutableState.value else ensureLoaded()

    fun setMode(mode: ApprovalMode): ChatApprovalSettings = synchronized(lock) {
        val base = ensureLoaded()
        updateLocked(base.copy(mode = mode))
    }

    fun setAllowRootLongTerm(allowed: Boolean): ChatApprovalSettings = synchronized(lock) {
        val base = ensureLoaded()
        updateLocked(base.copy(allowRootLongTerm = allowed))
    }

    fun policy(denied: (InvocationInput) -> Boolean = { false }): ApprovalPolicy = ApprovalPolicy(
        denied = denied,
        allowRootLongTerm = { current().allowRootLongTerm },
        mode = { current().mode },
    )

    private fun updateLocked(next: ChatApprovalSettings): ChatApprovalSettings {
        persist(next)
        mutableState.value = next
        initialized = true
        return next
    }

    private fun readFromDisk(): ChatApprovalSettings {
        checkDirectories()
        if (Files.notExists(file, NOFOLLOW_LINKS)) {
            return ChatApprovalSettings(mode = ApprovalMode.BALANCED, allowRootLongTerm = false)
        }
        return try {
            check(Files.isRegularFile(file, NOFOLLOW_LINKS))
            check(Files.size(file) <= MAX_BYTES)
            val props = Properties()
            Files.newInputStream(file, READ, NOFOLLOW_LINKS).use { input ->
                props.load(input.reader(Charsets.UTF_8))
            }
            val rawMode = props.getProperty(KEY_MODE)?.trim()
            val mode = when (rawMode) {
                ApprovalMode.STRICT.name -> ApprovalMode.STRICT
                ApprovalMode.BALANCED.name -> ApprovalMode.BALANCED
                ApprovalMode.FULL_AUTO.name -> ApprovalMode.FULL_AUTO
                else -> return ChatApprovalSettings(mode = ApprovalMode.STRICT, allowRootLongTerm = false)
            }
            val allowRoot = when (props.getProperty(KEY_ALLOW_ROOT_LONG_TERM)?.trim()) {
                "true" -> true
                "false", null -> false
                else -> false
            }
            ChatApprovalSettings(mode = mode, allowRootLongTerm = allowRoot)
        } catch (_: Exception) {
            // Corrupt file fails closed conservatively rather than escalating privileges.
            ChatApprovalSettings(mode = ApprovalMode.STRICT, allowRootLongTerm = false)
        }
    }

    private fun checkDirectories() {
        for (parent in listOf(root, directory)) {
            check(Files.notExists(parent, NOFOLLOW_LINKS) || Files.isDirectory(parent, NOFOLLOW_LINKS))
        }
    }

    private fun persist(settings: ChatApprovalSettings) {
        checkDirectories()
        Files.createDirectories(directory)
        check(Files.notExists(file, NOFOLLOW_LINKS) || Files.isRegularFile(file, NOFOLLOW_LINKS))
        val text = buildString {
            append(KEY_MODE).append('=').append(settings.mode.name).append('\n')
            append(KEY_ALLOW_ROOT_LONG_TERM).append('=').append(settings.allowRootLongTerm).append('\n')
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_BYTES)
        val temp = directory.resolve("$FILE_NAME-${UUID.randomUUID()}.tmp")
        try {
            FileChannel.open(temp, WRITE, CREATE_NEW, NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temp, file, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    companion object {
        internal const val DIRECTORY = "chat-approval-v1"
        private const val FILE_NAME = "settings.properties"
        private const val KEY_MODE = "mode"
        private const val KEY_ALLOW_ROOT_LONG_TERM = "allowRootLongTerm"
        private const val MAX_BYTES = 16 * 1024L
        private val locks = Array(16) { Any() }

        fun android(context: Context): ChatApprovalSettingsStore =
            ChatApprovalSettingsStore(context.noBackupFilesDir)
    }
}
