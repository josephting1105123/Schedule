package com.EntropicSoftwareLab.Schedule.ai

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A file the user attached, read into memory and ready to be sent.
 *
 * Not a `data class`: the payload is a [ByteArray], and generated equality on one compares
 * references, which is the kind of quietly-wrong that survives a code review. Nothing here needs
 * equality anyway — an attachment is read, sent, and dropped.
 */
class Attachment(
    val name: String,
    val mimeType: String,
    val bytes: ByteArray,
)

/**
 * An attachment the user has picked but not yet sent.
 *
 * Held as a URI rather than as bytes so a photo sitting in the composer costs a row in a list, not
 * several megabytes of heap. The bytes are read once, at send.
 */
data class PendingAttachment(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
) {
    val sizeLabel: String
        get() = when {
            sizeBytes >= 1_000_000 -> "%.1f MB".format(sizeBytes / 1_000_000.0)
            sizeBytes > 0 -> "%.0f KB".format(sizeBytes / 1000.0)
            else -> ""
        }
}

/**
 * Turns a picked document into something sendable, and refuses the ones that aren't.
 *
 * Two limits are enforced here rather than discovered at the API: the type, because a `.docx` comes
 * back from Gemini as an unhelpful 400 rather than as "I can't read that"; and the total size,
 * because inline data travels inside the request body and a 40 MB photo fails after the user has
 * waited for it to upload.
 */
object AttachmentReader {

    /**
     * Ceiling for everything attached to one message.
     *
     * Well under the API's own request limit on purpose. The prompt, the learned habits and the
     * base64 expansion (which costs a third on top of the raw bytes) all share that budget, and a
     * request rejected for size after a slow upload is the worst way to find out.
     */
    const val MAX_TOTAL_BYTES = 10L * 1024 * 1024

    /** What the cloud model can actually read. Anything else is refused at the picker. */
    val ACCEPTED_MIME_TYPES = arrayOf("image/*", "application/pdf", "text/plain")

    fun isSupported(mimeType: String?): Boolean {
        val type = mimeType?.lowercase().orEmpty()
        return type.startsWith("image/") || type == "application/pdf" || type.startsWith("text/")
    }

    /**
     * Reads the name, size and type out of the provider without touching the contents.
     *
     * Returns null when the document has vanished between the picker and here, which is a real case
     * on a provider backed by a cloud drive.
     */
    fun describe(resolver: ContentResolver, uri: Uri): PendingAttachment? = runCatching {
        var name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
        var size = 0L
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { name = cursor.getString(it) }
                cursor.getColumnIndex(OpenableColumns.SIZE)
                    .takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { size = cursor.getLong(it) }
            }
        }
        PendingAttachment(
            uri = uri,
            name = name.ifBlank { "attachment" },
            mimeType = resolver.getType(uri) ?: "application/octet-stream",
            sizeBytes = size,
        )
    }.getOrNull()

    /** Reads the bytes. Null when the document can no longer be opened. */
    suspend fun read(resolver: ContentResolver, pending: PendingAttachment): Attachment? =
        withContext(Dispatchers.IO) {
            runCatching {
                resolver.openInputStream(pending.uri)?.use { stream ->
                    Attachment(
                        name = pending.name,
                        mimeType = pending.mimeType,
                        bytes = stream.readBytes(),
                    )
                }
            }.getOrNull()
        }

    /**
     * Why this set can't be sent, or null when it can.
     *
     * A size of 0 from the provider means "unknown", not "empty" — some providers don't report one —
     * so it is not treated as over the limit here. The real bytes are checked after reading.
     */
    fun rejectionReason(pending: List<PendingAttachment>): String? {
        pending.firstOrNull { !isSupported(it.mimeType) }?.let {
            return "I can't read ${it.name}. Attach an image, a PDF, or a text file."
        }
        val total = pending.sumOf { it.sizeBytes }
        if (total > MAX_TOTAL_BYTES) {
            return "That's ${"%.1f".format(total / 1_000_000.0)} MB of attachments. The limit is " +
                "${MAX_TOTAL_BYTES / 1_000_000} MB per message."
        }
        return null
    }

    /** The same ceiling, applied to what was actually read. */
    fun tooLarge(attachments: List<Attachment>): Boolean =
        attachments.sumOf { it.bytes.size.toLong() } > MAX_TOTAL_BYTES
}
