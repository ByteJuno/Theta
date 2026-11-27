package io.github.bytejuno.thetacore.chat

/** UI identity only. History admission never retains draft text or image bytes. */
internal class ChatDraftReceipt(
    val sessionId: String,
    val textVersion: String,
    private val imageIds: Set<String>,
    private val savedVersion: String? = null,
    private val savedImages: List<String> = emptyList(),
) {
    fun consumption(hasText: Boolean = true) = io.github.bytejuno.thetacore.agent.ledger.ComposerConsumption(
        versions = setOfNotNull(savedVersion, textVersion),
        imageIds = imageIds + savedImages,
        submittedVersions = setOfNotNull(textVersion.takeIf { hasText }),
        submittedImageIds = imageIds,
    )
    fun clearsText(currentVersion: String) = currentVersion == textVersion
    fun clearsImage(localId: String) = localId in imageIds
    fun remaining(images: List<PendingUserImage>) = images.filterNot { it.localId in imageIds }
}
