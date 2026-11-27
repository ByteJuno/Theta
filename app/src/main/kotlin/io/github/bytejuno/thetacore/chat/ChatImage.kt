package io.github.bytejuno.thetacore.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.uiStrings
import io.github.bytejuno.thetacore.agent.ledger.InvocationRecord
import io.github.bytejuno.thetacore.agent.protocol.BlobRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ChatImage(record: InvocationRecord, owner: ChatSessionOwner) {
    val strings = uiStrings()
    var bitmap by remember(record.callId) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var loading by remember(record.callId) { mutableStateOf(true) }
    var failed by remember(record.callId) { mutableStateOf(false) }
    LaunchedEffect(record.callId, record.revision) {
        loading = true
        try {
            val decoded = withContext(Dispatchers.IO) {
                owner.image(record.input.sessionId, record.callId)?.let(::decodeImage)
            }
            bitmap = decoded
            failed = decoded == null && record.result?.text == IMAGE_COMPLETE
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (failed) Text(strings.get(R.string.chat_image_unavailable), color = MaterialTheme.colorScheme.error)
    bitmap?.let { image ->
        Image(image.asImageBitmap(), contentDescription = strings.get(R.string.chat_image_description),
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).aspectRatio(image.width.toFloat() / image.height),
            contentScale = ContentScale.Fit)
    }
}

@Composable
internal fun UserChatImage(blob: BlobRef, sessionId: String?, owner: ChatSessionOwner) {
    val strings = uiStrings()
    var bitmap by remember(sessionId, blob.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var loading by remember(sessionId, blob.id) { mutableStateOf(true) }
    var failed by remember(sessionId, blob.id) { mutableStateOf(false) }
    LaunchedEffect(sessionId, blob.id) {
        loading = true
        failed = false
        try {
            val decoded = if (sessionId == null) null else withContext(Dispatchers.IO) {
                owner.userImage(sessionId, blob.id)?.let(::decodeImage)
            }
            bitmap = decoded
            failed = decoded == null
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (failed) Text(strings.get(R.string.chat_image_unavailable), color = MaterialTheme.colorScheme.error)
    bitmap?.let { image ->
        Image(
            image.asImageBitmap(),
            contentDescription = strings.get(R.string.chat_image_user_description),
            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).aspectRatio(image.width.toFloat() / image.height),
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
internal fun PendingImagePreview(item: PendingUserImage, enabled: Boolean, compact: Boolean = false, onRemove: () -> Unit) {
    val strings = uiStrings()
    var bitmap by remember(item.localId) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(item.localId) {
        bitmap = withContext(Dispatchers.IO) { runCatching { decodeImage(item.bytes) }.getOrNull() }
    }
    if (compact) {
        OutlinedCard {
            Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp)) {
                    bitmap?.let { image ->
                        Image(image.asImageBitmap(), strings.get(R.string.chat_image_user_description),
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
                IconButton(onClick = onRemove, enabled = enabled) {
                    Icon(painterResource(R.drawable.ic_chat_remove_image), strings.get(R.string.chat_image_remove))
                }
            }
        }
    } else OutlinedCard(Modifier.width(96.dp)) {
        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            bitmap?.let { image ->
                Image(
                    image.asImageBitmap(),
                    contentDescription = strings.get(R.string.chat_image_user_description),
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                    contentScale = ContentScale.Crop,
                )
            }
            TextButton(onClick = onRemove, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(strings.get(R.string.chat_image_remove))
            }
        }
    }
}

