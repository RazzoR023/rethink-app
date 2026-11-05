/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
@file:OptIn(ExperimentalMaterial3Api::class)

package com.celzero.bravedns.ui.compose.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.service.ProxyChoices
import com.celzero.bravedns.ui.compose.design.ChoiceOption
import com.celzero.bravedns.ui.compose.design.RethinkSpacing
import com.celzero.bravedns.ui.compose.design.RuleProxyPickerSheet
import com.celzero.bravedns.ui.compose.design.SettingRow
import com.celzero.bravedns.ui.compose.design.SettingTrailing
import com.celzero.bravedns.ui.compose.design.SettingsSection
import com.celzero.bravedns.ui.compose.design.SingleChoiceList
import com.celzero.bravedns.ui.compose.design.StatusChip
import com.celzero.bravedns.ui.compose.design.displayDescription
import com.celzero.bravedns.ui.compose.design.displayTitle
import com.celzero.bravedns.ui.compose.design.toStatus

/*
 * A rule's details, as a bottom sheet over the rules list: what it matches and for which app,
 * its action (Trust / Block / None, or Bypass universal for universal IP rules) and its proxy
 * ("Route via", independent of the action), plus Edit and Delete.
 */

@Composable
internal fun RuleDetailsSheet(item: RuleItem, state: RulesScreenState, actions: CustomRuleActions) {
    ModalBottomSheet(
        onDismissRequest = { state.details = null },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        RuleDetailsContent(
            item = item,
            appName = appLabel(state, actions, item.uid),
            proxies = state.proxies,
            onAction = { actions.setAction(item, it) },
            onRoute = { state.picker = PickerTarget.Rule(item) },
            onEdit = { state.openEditor(item.kind, item) },
            onDelete = { state.dialog = RulesDialog.ConfirmDelete(item) },
            modifier = Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding(),
        )
    }
}

/** The sheet's content; stateless, for previews and tests. */
@Composable
internal fun RuleDetailsContent(
    item: RuleItem,
    appName: String,
    proxies: List<ProxyChoices.Choice>?,
    onAction: (RuleAction) -> Unit,
    onRoute: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(bottom = RethinkSpacing.lg).testTag("rule_details"),
        verticalArrangement = Arrangement.spacedBy(RethinkSpacing.lg),
    ) {
        Row(
            Modifier.padding(horizontal = RethinkSpacing.screenGutter),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.lg),
        ) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { RuleLeading(item) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(RethinkSpacing.xxs)) {
                SelectionContainer {
                    Text(
                        item.target,
                        Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(appName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(changedLabel(item.modifiedTs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        SettingsSection(title = stringResource(R.string.rules_section_action)) {
            val options = RulesLogic.actionOptions(item.kind, item.uid, item.action).map { a ->
                ChoiceOption(a, stringResource(actionLabel(a)), stringResource(actionDescription(a)))
            }
            SingleChoiceList(options, item.action, onAction, Modifier.testTag("rule_details_action"))
        }

        SettingsSection(title = stringResource(R.string.rules_section_proxy)) {
            RouteViaRow(item.proxyId, proxies, onRoute, Modifier.testTag("rule_details_route"))
            if (RulesLogic.proxyIgnored(item.action, item.proxyId)) {
                Text(
                    stringResource(R.string.rules_proxy_ignored_note),
                    Modifier.padding(horizontal = RethinkSpacing.screenGutter, vertical = RethinkSpacing.sm),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = RethinkSpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.sm),
        ) {
            OutlinedButton(onEdit, Modifier.weight(1f).testTag("rule_details_edit"), contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                Icon(Icons.Filled.Edit, null, Modifier.size(ButtonDefaults.IconSize))
                Text(stringResource(R.string.rules_edit), Modifier.padding(start = ButtonDefaults.IconSpacing))
            }
            OutlinedButton(
                onDelete,
                Modifier.weight(1f).testTag("rule_details_delete"),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Filled.Delete, null, Modifier.size(ButtonDefaults.IconSize))
                Text(stringResource(R.string.rules_delete), Modifier.padding(start = ButtonDefaults.IconSpacing))
            }
        }
    }
}

/** "Route via": the rule's proxy (or no override) with live status; opens the picker. */
@Composable
internal fun RouteViaRow(proxyId: String, proxies: List<ProxyChoices.Choice>?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val choice = RulesLogic.proxyChoice(proxyId, proxies)
    SettingRow(
        title = stringResource(R.string.rules_route_via_title),
        modifier = modifier,
        supportingText = if (choice == null) stringResource(R.string.proxy_picker_no_override)
            else listOfNotNull(choice.displayTitle(), choice.displayDescription()).joinToString(" · "),
        icon = Icons.Filled.Route,
        onClick = onClick,
    ) {
        if (choice != null && proxies != null) StatusChip(choice.health.toStatus())
        SettingTrailing.Chevron()
    }
}

/** The rule proxy picker, for a stored rule (saved at once) or the editor's draft. */
@Composable
internal fun RulePickerHost(state: RulesScreenState, actions: CustomRuleActions) {
    when (val target = state.picker) {
        null -> Unit
        is PickerTarget.Rule -> RuleProxyPickerSheet(
            currentProxyId = target.item.proxyId,
            subject = target.item.target,
            onSelect = { actions.setProxy(target.item, it) },
            onDismiss = { state.picker = null },
        )
        PickerTarget.Draft -> {
            val draft = state.draft
            if (draft == null) {
                state.picker = null
            } else {
                RuleProxyPickerSheet(
                    currentProxyId = draft.proxyId,
                    subject = draft.input.ifBlank { null },
                    onSelect = { id -> state.updateDraft { it.copy(proxyId = id) } },
                    onDismiss = { state.picker = null },
                )
            }
        }
    }
}
