/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.appdetails

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.window.DialogProperties
import com.celzero.bravedns.R
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.ui.compose.design.RethinkSpacing

/** Max length of an app's notes. */
const val APP_NOTES_MAX_LENGTH = 500

/** The notes being edited; survives recreation through AppInfoActivity's saved state. */
class AppNotesDraft(initial: String) {
    var value by mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    val text: String get() = value.text
}

/**
 * Caps [next] at [APP_NOTES_MAX_LENGTH] characters by trimming only the inserted text (so a
 * paste in the middle keeps what was before and after it) without splitting a surrogate pair.
 */
fun filterAppNotes(old: TextFieldValue, next: TextFieldValue): TextFieldValue {
    if (next.text.length <= APP_NOTES_MAX_LENGTH) return next
    var start = 0
    while (start < minOf(old.text.length, next.text.length) && old.text[start] == next.text[start]) start++
    var suffix = 0
    while (suffix < old.text.length - start && suffix < next.text.length - start &&
        old.text[old.text.lastIndex - suffix] == next.text[next.text.lastIndex - suffix]) suffix++
    var keep = (APP_NOTES_MAX_LENGTH - start - suffix).coerceAtLeast(0).coerceAtMost(next.text.length - start - suffix)
    if (keep > 0 && Character.isHighSurrogate(next.text[start + keep - 1])) keep--
    val value = old.text.take(start) + next.text.substring(start, start + keep) + old.text.takeLast(suffix)
    return next.copy(
        text = value,
        selection = TextRange(minOf(next.selection.start, start + keep), minOf(next.selection.end, start + keep)),
        composition = null,
    )
}

@Composable
internal fun AppDetailsDialogHost(state: AppDetailsState, actions: AppDetailsActions) {
    when (val d = state.dialog) {
        null -> Unit
        AppDetailsDialog.NoApp -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.ada_noapp_dialog_title)) },
            text = { Text(stringResource(R.string.ada_noapp_dialog_message)) },
            confirmButton = {
                TextButton(actions::finishNoApp) { Text(stringResource(R.string.ada_noapp_dialog_positive)) }
            },
        )
        is AppDetailsDialog.Notes -> NotesDialog(d.draft, actions)
        AppDetailsDialog.CloseConnections -> AlertDialog(
            onDismissRequest = actions::dismissDialog,
            title = { Text(stringResource(R.string.close_conns_dialog_title)) },
            text = { Text(stringResource(R.string.close_conns_dialog_desc, state.ui.appName)) },
            confirmButton = { TextButton(actions::closeConnections) { Text(stringResource(R.string.lbl_proceed)) } },
            dismissButton = { TextButton(actions::dismissDialog) { Text(stringResource(R.string.lbl_cancel)) } },
        )
        is AppDetailsDialog.PickAppInfo -> AlertDialog(
            onDismissRequest = actions::dismissDialog,
            title = { Text(stringResource(R.string.about_settings_app_info)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    d.appNames.forEach { name ->
                        Text(
                            name,
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = RethinkSpacing.minTouchTarget)
                                .clickable(role = Role.Button) { actions.openAndroidAppInfoFor(name) }
                                .padding(vertical = RethinkSpacing.md),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(actions::dismissDialog) { Text(stringResource(R.string.ada_noapp_dialog_positive)) }
            },
        )
        is AppDetailsDialog.ConfirmSharedUid -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = {
                Text(stringResource(R.string.ctbs_block_other_apps, state.ui.appName, d.appNames.size.toString()))
            },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(RethinkSpacing.sm),
                ) {
                    d.appNames.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
                }
            },
            confirmButton = {
                val label = FirewallManager.getLabelForStatus(d.change.firewall, d.change.connection, d.change.previous)
                TextButton({ actions.confirmFirewallChange(d.change) }) { Text(stringResource(label)) }
            },
            dismissButton = {
                TextButton(actions::dismissDialog) { Text(stringResource(R.string.ctbs_dialog_negative_btn)) }
            },
        )
    }
}

@Composable
private fun NotesDialog(draft: AppNotesDraft, actions: AppDetailsActions) {
    AlertDialog(
        onDismissRequest = actions::dismissDialog,
        title = { Text(stringResource(R.string.lbl_notes)) },
        text = {
            OutlinedTextField(
                value = draft.value,
                onValueChange = { draft.value = filterAppNotes(draft.value, it) },
                modifier = Modifier.fillMaxWidth().testTag("app_notes_input"),
                placeholder = { Text(stringResource(R.string.hint_notes)) },
                minLines = 4,
                maxLines = 10,
                supportingText = {
                    Text(stringResource(R.string.ctbs_note_char_counter, draft.text.length, APP_NOTES_MAX_LENGTH))
                },
            )
        },
        confirmButton = {
            TextButton({ actions.saveNotes(draft.text.trim()) }, Modifier.testTag("app_notes_save")) {
                Text(stringResource(R.string.lbl_save))
            }
        },
        dismissButton = {
            TextButton(actions::dismissDialog, Modifier.testTag("app_notes_cancel")) {
                Text(stringResource(R.string.lbl_cancel))
            }
        },
    )
}
