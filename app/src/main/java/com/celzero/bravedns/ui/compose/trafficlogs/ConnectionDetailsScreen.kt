/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.celzero.bravedns.ui.compose.trafficlogs

import android.graphics.drawable.Drawable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.appdetails.DrawableImage
import com.celzero.bravedns.ui.compose.design.ChoiceOption
import com.celzero.bravedns.ui.compose.design.RethinkSpacing
import com.celzero.bravedns.ui.compose.design.SectionDivider
import com.celzero.bravedns.ui.compose.design.SettingRow
import com.celzero.bravedns.ui.compose.design.SettingTrailing
import com.celzero.bravedns.ui.compose.design.SettingsSection
import com.celzero.bravedns.ui.compose.design.SingleChoiceList
import com.celzero.bravedns.ui.compose.design.SwitchRow
import com.celzero.bravedns.ui.compose.design.statusColors
import com.celzero.bravedns.ui.compose.rules.RuleSheetActions
import com.celzero.bravedns.ui.compose.rules.RuleSheetEffects
import com.celzero.bravedns.ui.compose.rules.RuleSheetRows
import com.celzero.bravedns.ui.compose.rules.RuleSheetState
import com.celzero.bravedns.ui.compose.rules.RuleSheetSurface

/*
 * Network log -> a connection (ConnTrackerBottomSheet): who made it, why it was allowed or
 * blocked, where it went, and the rules for it: the app's firewall rule, and the app's rule for
 * the IP and the domain, each with "Route via". Built on the design system; the fragment fills
 * [ConnectionDetailsUi] and handles the clicks ([ConnectionDetailsCallbacks]).
 */

/** Everything the sheet shows except the IP / domain rules (those are in [RuleSheetState]). */
@Stable
class ConnectionDetailsUi {
    var appName by mutableStateOf("")
    var appIcon by mutableStateOf<Drawable?>(null)
    /** false for uids without a known app: no app rule row; the "block unknown" switch instead */
    var isApp by mutableStateOf(true)
    /** "TCP/443 · 2 min ago" (allowed / blocked wording) */
    var summary by mutableStateOf("")
    var verdict by mutableStateOf("")
    var verdictNegative by mutableStateOf(false)
    var verdictIcon by mutableStateOf<Int?>(null)
    var flag by mutableStateOf("")
    var ip by mutableStateOf("")
    /** the DNS query, or the destination's country when there was none */
    var domainLine by mutableStateOf("")
    /** duration, upload, download, metered; only the network type for flows with no data */
    var meta by mutableStateOf<List<String>>(emptyList())
    var appRuleTitle by mutableStateOf("")
    var appRuleState by mutableStateOf("")
    var blockUnknown by mutableStateOf(false)
    var message by mutableStateOf("")
    var infoDialog by mutableStateOf<LogInfoDialogState?>(null)
    var appRuleDialog by mutableStateOf<AppRuleDialog?>(null)
}

/** The app firewall rule picker: the historical spinner labels, one selected. */
@Immutable
data class AppRuleDialog(val title: String, val labels: List<String>, val selected: Int)

interface ConnectionDetailsCallbacks {
    fun openApp()
    fun showVerdictInfo()
    fun openAppRules()
    fun selectAppRule(index: Int)
    fun setBlockUnknown(block: Boolean)
}

@Composable
internal fun ConnectionDetailsScreen(
    ui: ConnectionDetailsUi,
    rules: RuleSheetState,
    ruleActions: RuleSheetActions,
    callbacks: ConnectionDetailsCallbacks,
) {
    RuleSheetSurface {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(bottom = RethinkSpacing.lg).testTag("conn_details"),
            verticalArrangement = Arrangement.spacedBy(RethinkSpacing.sm),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { BottomSheetDefaults.DragHandle() }
            ConnectionHero(ui, callbacks)
            ConnectionFacts(ui)
            SettingsSection(title = stringResource(R.string.lbl_rules).replaceFirstChar { it.titlecase() }) {
                if (ui.isApp) {
                    SettingRow(
                        title = ui.appRuleTitle.ifEmpty { stringResource(R.string.lbl_app_rules) },
                        supportingText = ui.appRuleState,
                        modifier = Modifier.testTag("conn_app_rule"),
                        onClick = callbacks::openAppRules,
                    ) { SettingTrailing.Chevron() }
                    SectionDivider(Modifier.padding(vertical = RethinkSpacing.xs))
                }
                RuleSheetRows(rules.rows, rules.proxies, ruleActions, rules)
            }
            if (!ui.isApp) {
                SettingsSection {
                    SwitchRow(stringResource(R.string.univ_firewall_rule_3), ui.blockUnknown, callbacks::setBlockUnknown)
                }
            }
            if (ui.message.isNotBlank()) {
                SelectionContainer {
                    Text(
                        ui.message,
                        Modifier.fillMaxWidth().padding(horizontal = RethinkSpacing.screenGutter, vertical = RethinkSpacing.sm),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
    RuleSheetEffects(rules, ruleActions)
    ui.infoDialog?.let { VerdictInfoDialog(it) { ui.infoDialog = null } }
    ui.appRuleDialog?.let { d ->
        AppRuleChoiceDialog(d, onSelect = { ui.appRuleDialog = null; callbacks.selectAppRule(it) }, onDismiss = { ui.appRuleDialog = null })
    }
}

/** The app (opens its details) and the verdict (opens the rule's explanation). */
@Composable
private fun ConnectionHero(ui: ConnectionDetailsUi, callbacks: ConnectionDetailsCallbacks) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = RethinkSpacing.md),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(vertical = RethinkSpacing.xs)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.bsct_app_info), onClick = callbacks::openApp)
                    .padding(horizontal = RethinkSpacing.screenGutter, vertical = RethinkSpacing.md)
                    .testTag("conn_app"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.lg),
            ) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    if (ui.appIcon != null) DrawableImage(ui.appIcon, Modifier.size(40.dp))
                    else Icon(Icons.Filled.Apps, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(RethinkSpacing.xxs)) {
                    Text(
                        ui.appName, Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(ui.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SettingTrailing.Chevron()
            }
            SectionDivider()
            val tint = if (ui.verdictNegative) MaterialTheme.statusColors.error else MaterialTheme.statusColors.active
            Row(
                Modifier.fillMaxWidth().heightIn(min = RethinkSpacing.minTouchTarget)
                    .clickable(role = Role.Button, onClick = callbacks::showVerdictInfo)
                    .padding(horizontal = RethinkSpacing.screenGutter, vertical = RethinkSpacing.sm)
                    .testTag("conn_verdict"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.md),
            ) {
                ui.verdictIcon?.let { Icon(painterResource(it), null, Modifier.size(20.dp), tint = tint) }
                Text(ui.verdict, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = tint, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Destination, domain and connection info. */
@Composable
private fun ConnectionFacts(ui: ConnectionDetailsUi) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = RethinkSpacing.md),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier.padding(horizontal = RethinkSpacing.screenGutter, vertical = RethinkSpacing.md),
            verticalArrangement = Arrangement.spacedBy(RethinkSpacing.md),
        ) {
            Fact(stringResource(R.string.bsct_meta_destination)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.sm)) {
                    if (ui.flag.isNotBlank()) Text(ui.flag, Modifier.widthIn(min = 22.dp), style = MaterialTheme.typography.titleMedium)
                    SelectionContainer {
                        Text(ui.ip, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            if (ui.domainLine.isNotBlank()) {
                Fact(stringResource(R.string.lbl_domain)) {
                    SelectionContainer { Text(ui.domainLine, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface) }
                }
            }
            if (ui.meta.isNotEmpty()) {
                Fact(stringResource(R.string.bsct_meta_info)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.md), verticalArrangement = Arrangement.spacedBy(RethinkSpacing.xs)) {
                        ui.meta.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Fact(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(RethinkSpacing.xxs)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
private fun VerdictInfoDialog(state: LogInfoDialogState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = state.icon?.let { { Icon(painterResource(it), null) } },
        title = state.title?.let { { Text(it) } },
        text = {
            SelectionContainer(Modifier.verticalScroll(rememberScrollState())) {
                Text(logAnnotated(state.description), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.lbl_dismiss)) } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

@Composable
private fun AppRuleChoiceDialog(d: AppRuleDialog, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(d.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SingleChoiceList(
                    d.labels.mapIndexed { i, label -> ChoiceOption(i, label) },
                    selected = d.selected,
                    onSelect = onSelect,
                    horizontalPadding = 0.dp,
                )
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.lbl_dismiss)) } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}
