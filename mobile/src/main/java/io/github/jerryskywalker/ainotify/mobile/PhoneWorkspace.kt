package io.github.jerryskywalker.ainotify.mobile

import android.content.Context
import android.util.AtomicFile
import io.github.jerryskywalker.ainotify.core.Entry
import io.github.jerryskywalker.ainotify.core.Folder
import io.github.jerryskywalker.ainotify.core.MailAction
import io.github.jerryskywalker.ainotify.core.MailChanges
import io.github.jerryskywalker.ainotify.core.MailState
import io.github.jerryskywalker.ainotify.core.Relay
import io.github.jerryskywalker.ainotify.core.wireJson
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable
data class DeviceGroup(val id: String = UUID.randomUUID().toString(), val name: String, val devices: Set<String>)

@Serializable
data class Filters(
    val devices: Set<String> = emptySet(),
    val groups: Set<String> = emptySet(),
    val frontends: Set<String> = emptySet(),
)

@Serializable
enum class SyncMode { UNKNOWN, LOCAL, SYNC }

@Serializable
data class RelayWorkspace(
    val groups: List<DeviceGroup> = emptyList(),
    val filters: Filters = Filters(),
    val folder: Folder = Folder.INBOX,
    val mode: SyncMode = SyncMode.UNKNOWN,
    val cursor: Long = 0,
    val confirmed: Map<String, MailState> = emptyMap(),
    val pending: List<MailAction> = emptyList(),
    val local: Map<String, MailAction> = emptyMap(),
) {
    private val optimistic by lazy { pending.groupBy { it.eventId } }

    fun state(id: String): MailState {
        val baseline = confirmed[id] ?: MailState(id)
        val visible = if (mode == SyncMode.LOCAL) local[id]?.applyTo(baseline) ?: baseline else baseline
        return optimistic[id].orEmpty().fold(visible) { current, action -> action.applyTo(current) }
    }

    fun isPending(id: String): Boolean = id in optimistic

    fun devicesCovered(): Set<String> = filters.devices + groups.filter { it.id in filters.groups }.flatMap { it.devices }

    fun entries(events: List<Entry>): List<Entry> {
        val includedDevices = devicesCovered()
        val restrictDevices = filters.devices.isNotEmpty() || filters.groups.isNotEmpty()
        return events.asSequence()
            .filter {
                (!restrictDevices || it.deviceId in includedDevices) &&
                    (filters.frontends.isEmpty() || it.frontendKey in filters.frontends)
            }
            .map { it to state(it.id) }
            .filter { (_, state) -> state.folder == folder }
            .sortedWith(compareByDescending<Pair<Entry, MailState>> { it.second.pinned }.thenByDescending { it.first.seq })
            .map { it.first }
            .toList()
    }

    fun queue(action: MailAction): RelayWorkspace = if (mode == SyncMode.LOCAL) {
        copy(local = local + (action.eventId to action.mergePatch(local[action.eventId])))
    } else {
        copy(pending = pending + action)
    }

    fun readOnly(): RelayWorkspace {
        val updated = pending.fold(local) { patches, action ->
            patches + (action.eventId to action.mergePatch(patches[action.eventId]))
        }
        return copy(mode = SyncMode.LOCAL, local = updated, pending = emptyList())
    }

    private fun MailAction.mergePatch(previous: MailAction?): MailAction =
        copy(folder = folder ?: previous?.folder, pinned = pinned ?: previous?.pinned)

    fun receive(page: MailChanges): RelayWorkspace {
        val updated = confirmed.toMutableMap()
        page.changes.forEach { if ((updated[it.eventId]?.seq ?: 0) < it.seq) updated[it.eventId] = it }
        val current = if (page.canWrite) copy(mode = SyncMode.SYNC) else readOnly()
        return current.copy(cursor = maxOf(cursor, page.nextCursor), confirmed = updated)
    }

    fun acknowledge(action: MailAction, state: MailState): RelayWorkspace {
        val newer = (confirmed[state.eventId]?.seq ?: 0) < state.seq
        return copy(
            pending = pending.filterNot { it.id == action.id },
            confirmed = if (newer) confirmed + (state.eventId to state) else confirmed,
        )
    }
}

internal class PhoneWorkspace(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "phone-workspace.json"))
    private val state = MutableStateFlow(load())
    val workspaces = state.asStateFlow()

    private fun load(): Map<String, RelayWorkspace> = try {
        wireJson.decodeFromString(file.readFully().toString(Charsets.UTF_8))
    } catch (_: FileNotFoundException) {
        emptyMap()
    }

    @Synchronized
    fun update(relay: String, change: (RelayWorkspace) -> RelayWorkspace) {
        val before = state.value[relay] ?: RelayWorkspace()
        val after = change(before)
        if (after == before) return
        val next = state.value + (relay to after)
        val stream = file.startWrite()
        try {
            stream.write(wireJson.encodeToString(next).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
            state.value = next
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    fun sync(relay: String, client: Relay, stillCurrent: () -> Boolean) {
        val current = workspaces.value[relay] ?: RelayWorkspace()
        val page = try {
            client.inboxChanges(current.cursor)
        } catch (error: Relay.HttpError) {
            if (error.code != 404) throw error
            if (stillCurrent()) update(relay, RelayWorkspace::readOnly)
            return
        }
        if (!stillCurrent()) return
        update(relay) { it.receive(page) }
        if (!page.canWrite) return
        // Read changes before writing so a previous credential or old relay can be downgraded safely.
        for (action in workspaces.value[relay]?.pending.orEmpty().take(50)) {
            if (!stillCurrent()) return
            val acknowledged = client.apply(action)
            update(relay) { it.acknowledge(action, acknowledged) }
        }
    }
}
