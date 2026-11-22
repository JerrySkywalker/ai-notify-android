package io.github.jerryskywalker.ainotify.mobile

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.jerryskywalker.ainotify.core.Entry
import io.github.jerryskywalker.ainotify.core.MailState
import io.github.jerryskywalker.ainotify.core.Settings
import io.github.jerryskywalker.ainotify.core.validateSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    settings: Settings?,
    workspace: RelayWorkspace,
    entries: List<Entry>,
    status: ReceiverStatus,
    prefs: PhonePreferences,
    preferences: Preferences,
    update: ((RelayWorkspace) -> RelayWorkspace) -> Unit,
    save: (Settings) -> Unit,
    start: () -> Unit,
    stop: () -> Unit,
    back: () -> Unit,
) {
    val context = LocalContext.current
    val language = Preferences.language(context)
    var relayOpen by rememberSaveable { mutableStateOf(settings == null) }
    var groupId by rememberSaveable { mutableStateOf<String?>(null) }
    var creatingGroup by rememberSaveable { mutableStateOf(false) }
    var deletingGroup by rememberSaveable { mutableStateOf<String?>(null) }
    val devices = remember(entries, workspace.groups) { knownDevices(entries, workspace.groups) }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.settings)) }, navigationIcon = {
            TextButton(onClick = back) { Text(stringResource(R.string.back)) }
        })
    }) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SettingsHeading(R.string.appearance)
            ChoiceSetting(R.string.language, language, Language.entries, { it.label }) {
                Preferences.setLanguage(context, it)
            }
            ChoiceSetting(R.string.theme, prefs.theme, ThemeMode.entries, { it.label }) {
                preferences.update { current -> current.copy(theme = it) }
            }
            ChoiceSetting(R.string.accent, prefs.accent, Accent.entries, { it.label }) {
                preferences.update { current -> current.copy(accent = it) }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SettingsHeading(R.string.swipes)
            ChoiceSetting(R.string.swipe_left, prefs.left, SwipeAction.entries, { it.label }) {
                preferences.update { current -> current.copy(left = it) }
            }
            ChoiceSetting(R.string.swipe_right, prefs.right, SwipeAction.entries, { it.label }) {
                preferences.update { current -> current.copy(right = it) }
            }
            SwipePreview(prefs)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SettingsHeading(R.string.groups)
            if (workspace.groups.isEmpty()) {
                Text(stringResource(R.string.groups_empty), Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            workspace.groups.sortedBy { it.name.lowercase() }.forEach { group ->
                ListItem(
                    headlineContent = { Text(group.name) },
                    supportingContent = { Text(stringResource(R.string.group_member_count, group.devices.size)) },
                    trailingContent = {
                        TextButton(onClick = { groupId = group.id }) { Text(stringResource(R.string.group_edit)) }
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                )
            }
            TextButton(onClick = { creatingGroup = true }, enabled = settings != null && devices.isNotEmpty()) {
                Text(stringResource(R.string.group_add))
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SettingsHeading(R.string.relay_settings)
            TextButton(onClick = { relayOpen = true }) {
                Text(settings?.relay ?: stringResource(R.string.relay_settings))
            }
            if (settings != null) {
                val off = status == ReceiverStatus.OFF
                TextButton(onClick = if (off) start else stop) {
                    Text(stringResource(if (off) R.string.start_receiver else R.string.stop_receiver))
                }
                val mode = when (workspace.mode) {
                    SyncMode.SYNC -> R.string.sync_actions
                    SyncMode.LOCAL -> R.string.local_actions
                    SyncMode.UNKNOWN -> null
                }
                mode?.let { Text(stringResource(it), Modifier.padding(start = 12.dp, bottom = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }

    if (relayOpen) RelayDialog(settings, close = { relayOpen = false }, save)
    val group = workspace.groups.firstOrNull { it.id == groupId }
    if (creatingGroup || group != null) {
        GroupDialog(
            group, devices,
            close = { creatingGroup = false; groupId = null },
            delete = group?.let { { deletingGroup = it.id; groupId = null } },
        ) { edited ->
            update {
                it.copy(groups = it.groups.filterNot { saved -> saved.id == edited.id } + edited)
            }
            creatingGroup = false
            groupId = null
        }
    }
    workspace.groups.firstOrNull { it.id == deletingGroup }?.let { deleting ->
        AlertDialog(
            onDismissRequest = { deletingGroup = null },
            title = { Text(stringResource(R.string.group_delete_confirm, deleting.name)) },
            confirmButton = {
                TextButton(onClick = {
                    update {
                        it.copy(
                            groups = it.groups.filterNot { group -> group.id == deleting.id },
                            filters = it.filters.copy(groups = it.filters.groups - deleting.id),
                        )
                    }
                    deletingGroup = null
                }) { Text(stringResource(R.string.group_delete)) }
            },
            dismissButton = { TextButton(onClick = { deletingGroup = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SettingsHeading(@StringRes id: Int) {
    Text(
        stringResource(id), Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp),
        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun <T> ChoiceSetting(@StringRes title: Int, selected: T, choices: List<T>, label: (T) -> Int, change: (T) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val description = stringResource(title)
    ListItem(
        headlineContent = { Text(description) },
        supportingContent = { Text(stringResource(label(selected))) },
        modifier = Modifier.selectable(selected = false, onClick = { open = true }, role = Role.Button),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    )
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(description) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                choices.forEach { choice ->
                    val choose = {
                        open = false
                        change(choice)
                    }
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = selected == choice, role = Role.RadioButton, onClick = choose)
                            .padding(horizontal = 4.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = selected == choice, onClick = null)
                        Text(stringResource(label(choice)))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
internal fun CheckRow(title: String, checked: Boolean, supporting: String? = null, toggle: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = { Checkbox(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = { toggle() }),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    )
}

@Composable
private fun RelayDialog(current: Settings?, close: () -> Unit, save: (Settings) -> Unit) {
    var address by rememberSaveable { mutableStateOf(current?.relay.orEmpty()) }
    // A bearer token must not be written to Activity saved-state.
    var token by remember { mutableStateOf(current?.token.orEmpty()) }
    var error by rememberSaveable { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.relay_settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    address, { address = it }, label = { Text(stringResource(R.string.relay_address)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    token, { token = it }, label = { Text(stringResource(R.string.relay_token)) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                try {
                    save(validateSettings(Settings(address, token), BuildConfig.DEBUG))
                } catch (_: IllegalArgumentException) {
                    error = R.string.relay_invalid
                } catch (_: java.io.IOException) {
                    error = R.string.settings_saved_error
                }
            }) { Text(stringResource(R.string.save_receive)) }
        },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun GroupDialog(group: DeviceGroup?, devices: List<Pair<String, String>>, close: () -> Unit, delete: (() -> Unit)?, save: (DeviceGroup) -> Unit) {
    var name by rememberSaveable(group?.id) { mutableStateOf(group?.name.orEmpty()) }
    var ids by rememberSaveable(group?.id) { mutableStateOf(group?.devices.orEmpty().toTypedArray()) }
    var search by rememberSaveable(group?.id) { mutableStateOf("") }
    val query = search.trim()
    val matches = devices.filter { (id, label) -> label.contains(query, true) || id.contains(query, true) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(if (group == null) R.string.group_add else R.string.group_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    name, { if (it.length <= 60) name = it }, label = { Text(stringResource(R.string.group_name)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    search, { search = it }, label = { Text(stringResource(R.string.search)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                if (matches.isEmpty()) Text(stringResource(R.string.no_matches))
                matches.forEach { (id, label) ->
                    CheckRow(label, id in ids, supporting = id.takeUnless { it == label }) {
                        ids = ids.toSet().toggle(id).toTypedArray()
                    }
                }
                if (delete != null) TextButton(onClick = delete) { Text(stringResource(R.string.group_delete)) }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && ids.isNotEmpty(),
                onClick = {
                    val members = ids.toSet()
                    save(group?.copy(name = name.trim(), devices = members) ?: DeviceGroup(name = name.trim(), devices = members))
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SwipePreview(prefs: PhonePreferences) {
    var status by remember(prefs.left, prefs.right) { mutableStateOf<ReplyAction?>(null) }
    val preview = Entry(
        id = "preview", seq = 0, deviceId = "sample", deviceName = stringResource(R.string.swipe_preview),
        tool = "codex", kind = "turn.completed", occurredAt = remember { System.currentTimeMillis() },
        preview = stringResource(R.string.swipe_preview_body),
    )
    ReplyRow(preview, MailState(preview.id, pinned = status == ReplyAction.PIN), false, prefs, open = {}) {
        status = it
    }
    status?.let {
        Text(stringResource(it.feedback), Modifier.padding(start = 12.dp), color = MaterialTheme.colorScheme.primary)
    }
}
