package io.github.bytejuno.thetacore.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.github.bytejuno.thetacore.R
import io.github.bytejuno.thetacore.uiStrings
import io.github.bytejuno.thetacore.agent.ledger.InvocationRecord
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

