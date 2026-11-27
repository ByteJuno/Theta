package io.github.bytejuno.thetacore.chat

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import io.github.bytejuno.thetacore.MainActivity
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.ThetaApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TASK_CHANNEL = "chat_task"
private const val WAITING_CHANNEL = "chat_attention"
private const val TASK_NOTIFICATION = 8301
private const val WAITING_NOTIFICATION = 8302
private const val EXECUTION_ID = "execution_id"

internal class AndroidChatTaskPresence(private val context: Context,
    private val output: (io.github.bytejuno.thetacore.sdk.TaskProgress?) -> Unit) : ChatTaskPresence {
    private val gate = Mutex()
    private var executionId: String? = null
    private var progress: ChatTaskProgress? = null
    private var service: ChatTaskService? = null
    private var ready: CompletableDeferred<Unit>? = null
    private val notifications get() = context.getSystemService(NotificationManager::class.java)

    override suspend fun update(executionId: String, progress: ChatTaskProgress) = withContext(Dispatchers.Main.immediate) { gate.withLock {
        if (this@AndroidChatTaskPresence.executionId == executionId && this@AndroidChatTaskPresence.progress == progress) return@withLock
        this@AndroidChatTaskPresence.executionId = executionId
        this@AndroidChatTaskPresence.progress = progress
        channels()
        if (progress == ChatTaskProgress.Approval || progress == ChatTaskProgress.Recovery) {
            stopService()
            attention(progress)
        } else {
            notifications.cancel(WAITING_NOTIFICATION)
            try {
                val current = service
                if (current != null) current.show(notification(executionId, progress))
                else {
                    val acknowledgement = CompletableDeferred<Unit>()
                    ready = acknowledgement
                    context.startForegroundService(Intent(context, ChatTaskService::class.java).putExtra(EXECUTION_ID, executionId))
                    withTimeout(5_000) { acknowledgement.await() }
                }
            } catch (error: Exception) {
                this@AndroidChatTaskPresence.progress = null
                ready?.cancel()
                ready = null
                stopService()
                if (error is CancellationException) throw error
                throw ChatTaskPresenceException(error)
            }
        }
        output(progress.outputTask(executionId))
    } }

    override suspend fun finish(executionId: String, waiting: ChatTaskProgress?) = withContext(Dispatchers.Main.immediate) { gate.withLock {
        if (this@AndroidChatTaskPresence.executionId != executionId) return@withLock
        this@AndroidChatTaskPresence.executionId = null
        progress = null
        ready?.cancel()
        ready = null
        stopService()
        if (waiting != null) attention(waiting) else notifications.cancel(WAITING_NOTIFICATION)
        output(waiting?.outputTask(executionId))
    } }

    fun attach(candidate: ChatTaskService, token: String?): Boolean {
        val current = progress
        if (token == null || token != executionId || current == null ||
            current == ChatTaskProgress.Approval || current == ChatTaskProgress.Recovery) return false
        try {
            candidate.show(notification(token, current))
            service = candidate
            ready?.complete(Unit)
            ready = null
            return true
        } catch (error: Exception) {
            ready?.completeExceptionally(error)
            return false
        }
    }

    fun detached(candidate: ChatTaskService) {
        if (service !== candidate) return
        service = null
        progress = null
        executionId?.let { (context as ThetaApplication).chat.stop(it) }
    }

    private fun stopService() {
        val current = service
        service = null
        current?.finish()
    }

    private fun channels() {
        notifications.createNotificationChannel(NotificationChannel(TASK_CHANNEL,
            context.getString(R.string.chat_task_channel), NotificationManager.IMPORTANCE_LOW))
        notifications.createNotificationChannel(NotificationChannel(WAITING_CHANNEL,
            context.getString(R.string.chat_attention_channel), NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(context, 83,
        Intent(context, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun base(channel: String, text: String) = Notification.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_chat_task)
        .setContentTitle(context.getString(R.string.app_name))
        .setContentText(text)
        .setStyle(Notification.BigTextStyle().bigText(text))
        .setContentIntent(contentIntent())
        .setVisibility(Notification.VISIBILITY_PRIVATE)
        .setOnlyAlertOnce(true)

    private fun notification(token: String, progress: ChatTaskProgress): Notification {
        val text = when (progress) {
            is ChatTaskProgress.Tool -> context.getString(R.string.chat_task_tool, progress.name.take(80))
            else -> context.getString(R.string.chat_task_model)
        }
        val stop = PendingIntent.getBroadcast(context, 83,
            Intent(context, ChatTaskStopReceiver::class.java).setAction(token).putExtra(EXECUTION_ID, token),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return base(TASK_CHANNEL, text).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.chat_stop), stop).build()).build()
    }

    private fun attention(progress: ChatTaskProgress) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val text = context.getString(if (progress == ChatTaskProgress.Approval)
            R.string.chat_task_approval else R.string.chat_task_recovery)
        notifications.notify(WAITING_NOTIFICATION, base(WAITING_CHANNEL, text).setAutoCancel(true).build())
    }
}

class ChatTaskService : Service() {
    private val presence get() = (application as ThetaApplication).chatTaskPresence
    private var latestStartId = 0
    private var wakeScope: CoroutineScope? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (!presence.attach(this, intent?.getStringExtra(EXECUTION_ID))) {
            try {
                show(Notification.Builder(this, TASK_CHANNEL).setSmallIcon(R.drawable.ic_chat_task)
                    .setContentTitle(getString(R.string.app_name)).build())
            } catch (error: Exception) {
                android.util.Log.e("ThetaChat", "Foreground admission failed: ${error.javaClass.simpleName}")
            } finally { finish() }
        }
        return START_NOT_STICKY
    }

    internal fun show(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(TASK_NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(TASK_NOTIFICATION, notification)
        if (wakeScope == null) {
            val lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Theta:chat-task")
            lock.setReferenceCounted(false)
            wakeLock = lock
            wakeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope -> scope.launch {
                while (isActive) { lock.acquire(10 * 60_000L); delay(8 * 60_000L) }
            } }
        }
    }

    internal fun finish() {
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(latestStartId)
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        releaseWakeLock()
        presence.detached(this)
        super.onDestroy()
    }

    private fun releaseWakeLock() {
        wakeScope?.cancel()
        wakeScope = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}

class ChatTaskStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = intent.getStringExtra(EXECUTION_ID) ?: return
        (context.applicationContext as ThetaApplication).chat.stop(token)
    }
}
