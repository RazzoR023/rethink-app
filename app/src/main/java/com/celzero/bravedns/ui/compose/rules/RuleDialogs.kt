/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.celzero.bravedns.R
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.ui.compose.design.ChoiceOption
import com.celzero.bravedns.ui.compose.design.RethinkSpacing
import com.celzero.bravedns.ui.compose.design.SegmentOption
import com.celzero.bravedns.ui.compose.design.SegmentedChoice
import com.celzero.bravedns.ui.compose.design.SettingRow
import com.celzero.bravedns.ui.compose.design.SettingTrailing
import com.celzero.bravedns.ui.compose.design.SingleChoiceList

/*
 * The rules screen's dialogs: add / edit rule, delete confirmations and the debug-only import.
 * All are Material 3 AlertDialogs, so they always draw on a surface with a scrim (the ported
 * add-rule dialog had neither).
 */

@Composable
internal fun RulesDialogs(state: RulesScreenState, actions: CustomRuleActions) {
    when (val d = state.dialog) {
        null -> Unit
        is RulesDialog.Editor -> RuleEditorDialog(d.draft, state, actions)
        is RulesDialog.ConfirmDelete -> ConfirmDialog(
            title = stringResource(R.string.rules_delete_one_title),
            message = stringResource(R.string.rules_delete_one_message, d.item.target),
            confirm = stringResource(R.string.rules_delete),
            onConfirm = { actions.delete(d.item) },
            onDismiss = { state.dialog = null },
        )
        is RulesDialog.ConfirmDeleteSelected -> ConfirmDialog(
            title = stringResource(R.string.rules_delete_selected_title),
            message = stringResource(R.string.rules_delete_selected_message, d.count),
            confirm = stringResource(R.string.rules_delete),
            onConfirm = { actions.deleteSelected(d.kind) },
            onDismiss = { state.dialog = null },
        )
        is RulesDialog.ConfirmDeleteAll -> ConfirmDialog(
            title = stringResource(if (d.kind == RuleKind.DOMAIN) R.string.univ_delete_domain_dialog_title else R.string.univ_delete_firewall_dialog_title),
            message = stringResource(if (d.kind == RuleKind.DOMAIN) R.string.univ_delete_domain_dialog_message else R.string.univ_delete_firewall_dialog_message),
            confirm = stringResource(R.string.univ_ip_delete_dialog_positive),
            onConfirm = { actions.deleteAll(d.kind) },
            onDismiss = { state.dialog = null },
        )
        is RulesDialog.Import -> ImportDialog(d, state, actions)
        is RulesDialog.ImportSummary -> AlertDialog(
            onDismissRequest = { state.dialog = null },
            title = { Text(stringResource(R.string.import_rules_complete_title)) },
            text = { Text(stringResource(R.string.import_rules_summary, d.imported, d.duplicates, d.invalid)) },
            confirmButton = { TextButton({ state.dialog = null }) { Text(stringResource(R.string.fapps_info_dialog_positive_btn)) } },
        )
    }
}

@Composable
private fun ConfirmDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onConfirm, Modifier.testTag("rule_delete_confirm")) {
                Text(confirm, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onDismiss, Modifier.testTag("rule_delete_cancel")) { Text(stringResource(R.string.lbl_cancel)) } },
    )
}

@Composable
internal fun RuleEditorDialog(draft: RuleDraft, state: RulesScreenState, actions: CustomRuleActions) {
    RuleEditorDialogContent(
        draft = draft,
        appName = appLabel(state, actions, draft.uid),
        proxies = state.proxies,
        onChange = { f -> state.updateDraft(f) },
        onPickProxy = { state.picker = PickerTarget.Draft },
        onPickApp = if (state.scopePickable && !draft.isEdit) ({ state.scopePicker = true }) else null,
        onSave = { actions.saveDraft() },
        onDismiss = { if (!draft.saving) state.dialog = null },
    )
}

/** Add / edit dialog; stateless. */
@Composable
internal fun RuleEditorDialogContent(
    draft: RuleDraft,
    appName: String,
    proxies: List<com.celzero.bravedns.service.ProxyChoices.Choice>?,
    onChange: ((RuleDraft) -> RuleDraft) -> Unit,
    onPickProxy: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    /** when set, "Applies to" is a row that opens an app / All apps chooser */
    onPickApp: (() -> Unit)? = null,
) {
    val domain = draft.kind == RuleKind.DOMAIN
    val title = when {
        domain && draft.isEdit -> R.string.cd_dialog_edit_title
        domain -> R.string.cd_dialog_title
        draft.isEdit -> R.string.rules_dialog_edit_ip
        else -> R.string.ci_dialog_title
    }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        modifier = Modifier.testTag("rule_editor"),
        title = { Text(stringResource(title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(RethinkSpacing.md)) {
                if (onPickApp == null) {
                    Text(
                        stringResource(R.string.rules_dialog_applies_to, appName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        SettingRow(
                            title = stringResource(R.string.rules_dialog_applies_to_title),
                            modifier = Modifier.testTag("rule_editor_scope"),
                            supportingText = appName,
                            icon = Icons.Filled.Apps,
                            onClick = onPickApp,
                        ) { SettingTrailing.Chevron() }
                    }
                }
                Text(
                    stringResource(if (domain) R.string.cd_dialog_desc else R.string.ci_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (domain) {
                    SegmentedChoice(
                        listOf(
                            SegmentOption(DomainRulesManager.DomainType.DOMAIN, stringResource(R.string.lbl_domain)),
                            SegmentOption(DomainRulesManager.DomainType.WILDCARD, stringResource(R.string.lbl_wildcard)),
                        ),
                        selected = draft.domainType,
                        onSelect = { t -> onChange { it.copy(domainType = t, error = null) } },
                        modifier = Modifier.testTag("rule_editor_type"),
                    )
                }
                val error = draft.error?.let { stringResource(it) }
                OutlinedTextField(
                    value = draft.input,
                    onValueChange = { v -> onChange { it.withInput(v) } },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("rule_input"),
                    label = { Text(stringResource(if (domain) R.string.rules_dialog_domain_label else R.string.ci_dialog_edittext_hint)) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("rule_error")) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSave() }),
                )
                Text(stringResource(R.string.rules_section_action), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                // a radio list, not segments: "Bypass universal" doesn't fit a segment
                SingleChoiceList(
                    RulesLogic.actionOptions(draft.kind, draft.uid, draft.action).map {
                        ChoiceOption(it, stringResource(actionLabel(it)), stringResource(actionDescription(it)))
                    },
                    selected = draft.action,
                    onSelect = { a -> onChange { it.copy(action = a) } },
                    modifier = Modifier.testTag("rule_editor_action"),
                    horizontalPadding = 0.dp,
                )
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    RouteViaRow(draft.proxyId, proxies, onPickProxy, Modifier.testTag("rule_editor_route"))
                }
            }
        },
        confirmButton = {
            TextButton(onSave, enabled = draft.canSave, modifier = Modifier.testTag("rule_save")) {
                Text(stringResource(if (draft.isEdit) R.string.lbl_save else R.string.lbl_add))
            }
        },
        dismissButton = {
            TextButton(onDismiss, enabled = !draft.saving, modifier = Modifier.testTag("rule_cancel")) { Text(stringResource(R.string.lbl_cancel)) }
        },
    )
    LaunchedEffect(Unit) { if (!draft.isEdit) runCatching { focus.requestFocus() } }
}

@Composable
private fun ImportDialog(d: RulesDialog.Import, state: RulesScreenState, actions: CustomRuleActions) {
    AlertDialog(
        onDismissRequest = { state.dialog = null },
        title = { Text(stringResource(R.string.import_rules_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(RethinkSpacing.xs)) {
                Text("${stringResource(R.string.import_rules_file_label)}: ${d.parsed.fileName}", maxLines = 2)
                Text("${stringResource(R.string.import_rules_valid_label)}: ${d.parsed.valid.size}")
                Text("${stringResource(R.string.import_rules_ignored_label)}: ${d.parsed.invalidCount}")
                Text(stringResource(R.string.import_rules_action_label), style = MaterialTheme.typography.titleSmall)
                val trust = RulesLogic.trustAction(d.kind, state.scope.uid)
                SingleChoiceList(
                    listOf(RuleAction.BLOCK, trust).map { ChoiceOption(it, stringResource(actionLabel(it))) },
                    selected = d.action,
                    onSelect = { a -> state.dialog = d.copy(action = a) },
                    horizontalPadding = 0.dp,
                )
            }
        },
        confirmButton = {
            TextButton({ actions.importConfirmed() }, Modifier.testTag("rules_import_confirm")) { Text(stringResource(R.string.import_rules_btn)) }
        },
        dismissButton = { TextButton({ state.dialog = null }) { Text(stringResource(R.string.lbl_cancel)) } },
    )
}
