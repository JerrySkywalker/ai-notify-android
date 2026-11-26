package io.github.jerryskywalker.ainotify.mobile

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jerryskywalker.ainotify.core.Entry
import io.github.jerryskywalker.ainotify.core.Folder
import io.github.jerryskywalker.ainotify.core.MailAction
import io.github.jerryskywalker.ainotify.core.MailState
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.launch

internal enum class Facet { DEVICES, FRONTENDS }

@get:StringRes
internal val Folder.label: Int get() = when (this) {
    Folder.INBOX -> R.string.inbox
    Folder.ARCHIVE -> R.string.archive
    Folder.TRASH -> R.string.trash
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InboxScreen(
    entries: List<Entry>,
    workspace: RelayWorkspace,
    hasRelay: Boolean,
    status: ReceiverStatus,
    prefs: PhonePreferences,
    update: ((RelayWorkspace) -> RelayWorkspace) -> Unit,
    showSettings: () -> Unit,
    openReply: (String) -> Unit,
    start: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val shown = remember(workspace, entries) { workspace.entries(entries) }
    var facet by rememberSaveable { mutableStateOf<Facet?>(null) }
    val filters = workspace.filters
    val filterCount = filters.devices.size + filters.groups.size
    val filtering = filterCount + filters.frontends.size > 0
    val covered = workspace.devicesCovered().size
    val deviceCoverage = stringResource(
        R.string.device_coverage,
        pluralStringResource(R.plurals.selected_filters, filterCount, filterCount),
        pluralStringResource(R.plurals.covered_devices, covered, covered),
    )

    fun submit(id: String, action: ReplyAction) {
        var inverse: MailAction? = null
        var change: MailAction? = null
        try {
            update { latest ->
                val before = latest.state(id)
                if (!action.available(before)) return@update latest
                val next = action.change(id)
                inverse = MailAction(UUID.randomUUID().toString(), id, before.folder.takeIf { next.folder != null }, before.pinned.takeIf { next.pinned != null })
                change = next
                latest.queue(next)
            }
        } catch (_: java.io.IOException) {
            scope.launch { snackbar.showSnackbar(context.getString(R.string.settings_saved_error)) }
            return
        }
        val undo = inverse ?: return
        val applied = change ?: return
        android.util.Log.i("AINotifyUndo", "submit queued change: $applied, undo: $undo")
        snackbar.currentSnackbarData?.dismiss()
        scope.launch {
            val result = snackbar.showSnackbar(
                message = context.getString(action.feedback),
                actionLabel = context.getString(R.string.undo),
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            android.util.Log.i("AINotifyUndo", "showSnackbar result: $result")
            if (result == SnackbarResult.ActionPerformed) {
                try {
                    update { latest ->
                        val current = latest.state(id)
                        val stillApplied = (applied.folder == null || applied.folder == current.folder) &&
                            (applied.pinned == null || applied.pinned == current.pinned)
                        android.util.Log.i("AINotifyUndo", "stillApplied: $stillApplied, applied: $applied, current: $current")
                        if (stillApplied) {
                            android.util.Log.i("AINotifyUndo", "Queuing undo: $undo")
                            latest.queue(undo)
                        } else latest
                    }
                } catch (_: java.io.IOException) {
                    snackbar.showSnackbar(context.getString(R.string.settings_saved_error))
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("AINotify") }, actions = {
                TextButton(onClick = showSettings) { Text(stringResource(R.string.settings)) }
            })
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            PrimaryTabRow(selectedTabIndex = workspace.folder.ordinal) {
                Folder.entries.forEach { folder ->
                    Tab(
                        selected = workspace.folder == folder,
                        onClick = { update { it.copy(folder = folder) } },
                        enabled = hasRelay,
                        text = { Text(stringResource(folder.label)) },
                    )
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = filterCount > 0, onClick = { facet = Facet.DEVICES },
                    modifier = Modifier.semantics { if (filterCount > 0) stateDescription = deviceCoverage },
                    enabled = hasRelay,
                    label = { Text(filterLabel(R.string.devices_groups, filterCount)) },
                )
                FilterChip(
                    selected = filters.frontends.isNotEmpty(), onClick = { facet = Facet.FRONTENDS },
                    enabled = hasRelay,
                    label = { Text(filterLabel(R.string.frontends, filters.frontends.size)) },
                )
                if (filtering) TextButton(onClick = { update { it.copy(filters = Filters()) } }) {
                    Text(stringResource(R.string.clear_filters))
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val live = status == ReceiverStatus.LISTENING || status == ReceiverStatus.WEAR || status == ReceiverStatus.PUSH
                val scheme = MaterialTheme.colorScheme
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (live) scheme.primary else scheme.outline))
                Text(
                    stringResource(if (hasRelay) status.label else R.string.no_relay),
                    Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant,
                )
                if (hasRelay && (status == ReceiverStatus.OFF || status == ReceiverStatus.PUSH) && shown.isNotEmpty()) {
                    TextButton(onClick = start) { Text(stringResource(R.string.start_receiver)) }
                }
                if (workspace.pending.isNotEmpty()) Text(
                    stringResource(R.string.pending_count, workspace.pending.size),
                    color = LocalPendingColor.current, style = MaterialTheme.typography.labelMedium,
                )
            }

            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val empty = when {
                            !hasRelay -> R.string.relay_required
                            filtering -> R.string.empty_filtered
                            workspace.folder == Folder.ARCHIVE -> R.string.empty_archive
                            workspace.folder == Folder.TRASH -> R.string.empty_trash
                            else -> R.string.empty_inbox
                        }
                        Text(stringResource(empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!hasRelay || status == ReceiverStatus.OFF) {
                            Button(onClick = if (hasRelay) start else showSettings) {
                                Text(stringResource(if (hasRelay) R.string.start_receiver else R.string.relay_settings))
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.id }) { entry ->
                        ReplyRow(entry, workspace.state(entry.id), workspace.isPending(entry.id), prefs, { openReply(entry.id) }) {
                            submit(entry.id, it)
                        }
                    }
                }
            }
        }
    }
    facet?.let { selected ->
        FilterSheet(selected, entries, workspace, close = { facet = null }, update)
    }
}

@Composable
private fun filterLabel(label: Int, count: Int): String {
    val text = stringResource(label)
    return if (count == 0) text else stringResource(R.string.filter_count, text, count)
}

internal enum class ReplyAction(
    @get:StringRes val label: Int,
    @get:StringRes val feedback: Int,
    @get:DrawableRes val icon: Int,
    val folder: Folder? = null,
    val pinned: Boolean? = null,
) {
    PIN(R.string.pin, R.string.pinned, R.drawable.ic_pin, pinned = true),
    UNPIN(R.string.unpin, R.string.unpinned, R.drawable.ic_pin, pinned = false),
    ARCHIVE(R.string.move_archive, R.string.archived, R.drawable.ic_archive, folder = Folder.ARCHIVE),
    TRASH(R.string.move_trash, R.string.trashed, R.drawable.ic_trash, folder = Folder.TRASH),
    RESTORE(R.string.restore, R.string.restored, R.drawable.ic_restore, folder = Folder.INBOX);

    fun available(state: MailState): Boolean = when (this) {
        PIN, UNPIN -> state.folder != Folder.TRASH && state.pinned != pinned
        ARCHIVE -> state.folder == Folder.INBOX
        TRASH -> state.folder != Folder.TRASH
        RESTORE -> state.folder != Folder.INBOX
    }

    fun change(id: String) = MailAction(UUID.randomUUID().toString(), id, folder, pinned)
}

private fun SwipeAction.resolve(state: MailState): ReplyAction? =
    when (this) {
        SwipeAction.PIN -> if (state.pinned) ReplyAction.UNPIN else ReplyAction.PIN
        SwipeAction.ARCHIVE -> ReplyAction.ARCHIVE
        SwipeAction.TRASH -> ReplyAction.TRASH
        SwipeAction.NONE -> null
    }?.takeIf { it.available(state) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReplyRow(entry: Entry, state: MailState, pending: Boolean, prefs: PhonePreferences, open: () -> Unit, act: (ReplyAction) -> Unit) {
    val swipe = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled) { width -> width * 0.45f } }
    val left = prefs.left.resolve(state)
    val right = prefs.right.resolve(state)
    val dispatch by rememberUpdatedState<(SwipeToDismissBoxValue) -> Unit> { direction ->
        val action = when (direction) {
            SwipeToDismissBoxValue.EndToStart -> left
            SwipeToDismissBoxValue.StartToEnd -> right
            SwipeToDismissBoxValue.Settled -> null
        }
        action?.let(act)
    }
    LaunchedEffect(swipe) {
        snapshotFlow { swipe.settledValue }.collect { direction ->
            if (direction != SwipeToDismissBoxValue.Settled) {
                // Clear the anchor before changing a row that can reorder or leave this list.
                swipe.snapTo(SwipeToDismissBoxValue.Settled)
                dispatch(direction)
            }
        }
    }
    val actions = ReplyAction.entries.filter { it.available(state) }
    val accessibility = actions.map { action ->
        CustomAccessibilityAction(stringResource(action.label)) { act(action); true }
    }
    val gesturing = left != null || right != null
    // With both anchors disabled, consume horizontal drags so they do not become row taps.
    val dragGuard = if (gesturing) Modifier else Modifier.pointerInput(Unit) {
        detectHorizontalDragGestures { change, _ -> change.consume() }
    }
    SwipeToDismissBox(
        state = swipe,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).then(dragGuard),
        enableDismissFromEndToStart = left != null,
        enableDismissFromStartToEnd = right != null,
        gesturesEnabled = gesturing,
        backgroundContent = {
            val leftward = swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart
            val action = if (leftward) left else right
            val colors = MaterialTheme.colorScheme
            val color by animateColorAsState(when (action) {
                ReplyAction.TRASH -> colors.errorContainer
                null -> Color.Transparent
                else -> colors.primaryContainer
            }, label = "swipe")
            Box(
                Modifier.fillMaxSize().background(color).padding(horizontal = 20.dp),
                contentAlignment = if (leftward) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                action?.let {
                    val tint = if (it == ReplyAction.TRASH) colors.onErrorContainer else colors.onPrimaryContainer
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(painterResource(it.icon), contentDescription = null, tint = tint)
                        Text(stringResource(it.label), color = tint)
                    }
                }
            }
        },
    ) {
        Card(
            onClick = open,
            modifier = Modifier.fillMaxWidth().semantics { customActions = accessibility },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, top = 10.dp, bottom = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.heading, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(
                        entry.preview ?: stringResource(if (entry.kind == "test") R.string.test_message else R.string.reply_complete),
                        maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val locale = LocalConfiguration.current.locales[0]
                        val date = remember(entry.occurredAt, locale) {
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(Date(entry.occurredAt))
                        }
                        Text(date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.pinned) Text(stringResource(R.string.pinned), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        if (pending) Text(stringResource(R.string.pending_sync), style = MaterialTheme.typography.labelSmall, color = LocalPendingColor.current)
                    }
                }
                ReplyMenu(actions, act)
            }
        }
    }
}

@Composable
private fun ReplyMenu(actions: List<ReplyAction>, act: (ReplyAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.reply_actions)
    Box {
        androidx.compose.material3.IconButton(
            onClick = { open = true },
            modifier = Modifier.semantics { this.contentDescription = label },
        ) { Icon(painterResource(R.drawable.ic_more), contentDescription = null) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(stringResource(action.label)) },
                    leadingIcon = { Icon(painterResource(action.icon), contentDescription = null) },
                    onClick = { open = false; act(action) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(facet: Facet, entries: List<Entry>, workspace: RelayWorkspace, close: () -> Unit, update: ((RelayWorkspace) -> RelayWorkspace) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var search by rememberSaveable(facet) { mutableStateOf("") }
    val query = search.trim()
    val devices = remember(entries, workspace.groups) { knownDevices(entries, workspace.groups) }
    val frontends = remember(entries) { entries.distinctBy { it.frontendKey } }
    val allFrontends = frontends.map { entry ->
        val label = entry.frontend?.label ?: if (entry.tool == "manual") {
            stringResource(R.string.manual_frontend)
        } else {
            stringResource(R.string.unknown_frontend, entry.toolLabel)
        }
        entry.frontendKey to label
    }.sortedBy { it.second.lowercase() }
    val visibleDevices = devices.filter { (id, label) -> label.contains(query, true) || id.contains(query, true) }
    val visibleGroups = workspace.groups.filter { it.name.contains(query, true) }
    val visibleFrontends = allFrontends.filter { (id, label) -> label.contains(query, true) || id.contains(query, true) }
    val f = workspace.filters
    val names = remember(devices) { devices.toMap() }

    ModalBottomSheet(onDismissRequest = close, sheetState = state) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(
                stringResource(if (facet == Facet.DEVICES) R.string.devices_groups else R.string.frontends),
                style = MaterialTheme.typography.headlineSmall,
            )
            OutlinedTextField(
                search, { search = it }, label = { Text(stringResource(R.string.search)) },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            LazyColumn(Modifier.weight(1f, fill = false)) {
                if (facet == Facet.DEVICES) {
                    if (visibleGroups.isNotEmpty()) item { FilterHeading(R.string.groups) }
                    items(visibleGroups, key = { "group:" + it.id }) { group ->
                        var expanded by rememberSaveable { mutableStateOf(false) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                CheckRow(group.name, group.id in f.groups, supporting = stringResource(R.string.group_member_count, group.devices.size)) {
                                    update { it.copy(filters = it.filters.copy(groups = it.filters.groups.toggle(group.id))) }
                                }
                            }
                            TextButton(onClick = { expanded = !expanded }) {
                                Text(stringResource(if (expanded) R.string.hide_members else R.string.show_members))
                            }
                        }
                        if (expanded) {
                            Text(
                                group.devices.map { names[it] ?: it }.sorted().joinToString(" · "),
                                Modifier.padding(start = 16.dp, bottom = 6.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (visibleDevices.isNotEmpty()) item { FilterHeading(R.string.devices) }
                    items(visibleDevices, key = { "device:" + it.first }) { (id, label) ->
                        CheckRow(label, id in f.devices, supporting = id.takeUnless { it == label }) {
                            update { it.copy(filters = it.filters.copy(devices = it.filters.devices.toggle(id))) }
                        }
                    }
                    if (visibleGroups.isEmpty() && visibleDevices.isEmpty()) item {
                        Text(stringResource(if (query.isEmpty()) R.string.devices_empty else R.string.no_matches), Modifier.padding(vertical = 12.dp))
                    }
                } else {
                    items(visibleFrontends, key = { it.first }) { (id, label) ->
                        CheckRow(label, id in f.frontends, supporting = id.substringAfter(':').ifBlank { null }) {
                            update { it.copy(filters = it.filters.copy(frontends = it.filters.frontends.toggle(id))) }
                        }
                    }
                    if (visibleFrontends.isEmpty()) item { Text(stringResource(R.string.no_matches), Modifier.padding(vertical = 12.dp)) }
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = {
                    update {
                        val next = if (facet == Facet.DEVICES) it.filters.copy(devices = emptySet(), groups = emptySet()) else it.filters.copy(frontends = emptySet())
                        it.copy(filters = next)
                    }
                }) { Text(stringResource(R.string.clear_filters)) }
                Button(onClick = close) { Text(stringResource(R.string.done)) }
            }
        }
    }
}

@Composable
private fun FilterHeading(@StringRes id: Int) {
    Text(stringResource(id), Modifier.padding(top = 10.dp, bottom = 2.dp), style = MaterialTheme.typography.titleSmall)
}

internal fun knownDevices(entries: List<Entry>, groups: List<DeviceGroup>): List<Pair<String, String>> {
    val devices = entries.distinctBy { it.deviceId }.associate { it.deviceId to it.deviceName }
    return (devices.keys + groups.flatMap { it.devices })
        .map { it to (devices[it] ?: it) }
        .sortedWith(compareBy({ it.second.lowercase() }, { it.first }))
}

internal fun Set<String>.toggle(id: String): Set<String> = if (id in this) this - id else this + id
