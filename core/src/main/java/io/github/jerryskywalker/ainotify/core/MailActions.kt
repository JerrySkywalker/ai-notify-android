package io.github.jerryskywalker.ainotify.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class Folder {
    @SerialName("inbox") INBOX,
    @SerialName("archive") ARCHIVE,
    @SerialName("trash") TRASH,
}

@Serializable
data class MailState(
    @SerialName("event_id") val eventId: String,
    val seq: Long = 0,
    val folder: Folder = Folder.INBOX,
    val pinned: Boolean = false,
)

@Serializable
data class MailAction(
    val id: String,
    @SerialName("event_id") val eventId: String,
    val folder: Folder? = null,
    val pinned: Boolean? = null,
) {
    fun applyTo(state: MailState): MailState = state.copy(
        folder = folder ?: state.folder,
        pinned = pinned ?: state.pinned,
    )
}

@Serializable
data class MailChanges(
    val changes: List<MailState>,
    @SerialName("next_cursor") val nextCursor: Long,
    @SerialName("can_write") val canWrite: Boolean,
)
