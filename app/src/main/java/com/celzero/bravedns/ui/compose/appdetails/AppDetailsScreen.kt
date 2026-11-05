/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.appdetails

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.design.AppProxyPickerSheet
import com.celzero.bravedns.ui.compose.design.ChoiceOption
import com.celzero.bravedns.ui.compose.design.EmptyState
import com.celzero.bravedns.ui.compose.design.RethinkSpacing
import com.celzero.bravedns.ui.compose.design.SectionDivider
import com.celzero.bravedns.ui.compose.design.SectionHeader
import com.celzero.bravedns.ui.compose.design.SegmentOption
import com.celzero.bravedns.ui.compose.design.SegmentedChoice
import com.celzero.bravedns.ui.compose.design.SettingRow
import com.celzero.bravedns.ui.compose.design.SettingTrailing
import com.celzero.bravedns.ui.compose.design.SettingsSection
import com.celzero.bravedns.ui.compose.design.SingleChoiceList
import com.celzero.bravedns.ui.compose.design.StatusChip
import com.celzero.bravedns.ui.compose.design.StatusDot
import com.celzero.bravedns.ui.compose.design.SwitchRow
import com.celzero.bravedns.ui.compose.design.displayDescription
import com.celzero.bravedns.ui.compose.design.displayTitle
import com.celzero.bravedns.ui.stats.StatsViewMode
import com.celzero.bravedns.util.Utilities

/*
 * App details: header (who the app is and a one-line summary of what applies to it), network
 * access (temp allow, Wi-Fi / mobile data, mode), proxy, rules and activity. Built only from
 * the design system (ui/compose/design); state lives in AppDetailsState, I/O in AppInfoActivity.
 */

@Composable
fun AppDetailsScreen(state: AppDetailsState, actions: AppDetailsActions) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            state.showRethinkWarning -> RethinkWarning(actions)
            state.ui.loaded -> AppDetailsBody(state, actions)
            else -> Box(Modifier.fillMaxSize())
        }
    }
    AppDetailsDialogHost(state, actions)
    val ui = state.ui
    if (state.showProxyPicker && ui.loaded) {
        AppProxyPickerSheet(
            uid = ui.uid,
            packageName = ui.packageName,
            appName = ui.appName,
            onDismiss = actions::closeProxyPicker,
            onChanged = actions::onProxyChanged,
        )
    }
}

@Composable
private fun RethinkWarning(actions: AppDetailsActions) {
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        EmptyState(
            icon = Icons.Filled.WarningAmber,
            title = stringResource(R.string.app_info_rethink_warning_title),
            body = stringResource(R.string.app_info_rethink_warning_desc),
            actionLabel = stringResource(R.string.app_info_rethink_warning_proceed),
            onAction = actions::acknowledgeRethinkWarning,
        )
    }
}

@Composable
private fun AppDetailsBody(state: AppDetailsState, actions: AppDetailsActions) {
    val ui = state.ui
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = RethinkSpacing.xl)
            .testTag("app_details"),
        verticalArrangement = Arrangement.spacedBy(RethinkSpacing.lg),
    ) {
        Header(state, actions)
        NetworkSection(ui, actions)
        ModeSection(ui, actions)
        ProxySection(ui, actions)
        RulesSection(ui, actions)
        ActivitySection(state, actions)
    }
}

// ---- header ----

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(state: AppDetailsState, actions: AppDetailsActions) {
    val ui = state.ui
    Column(
        Modifier.fillMaxWidth().padding(start = RethinkSpacing.screenGutter, end = RethinkSpacing.xs, top = RethinkSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(RethinkSpacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.lg)) {
            DrawableImage(state.icon, Modifier.size(56.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(RethinkSpacing.xxs)) {
                val name = if (ui.otherAppNames.isEmpty()) ui.appName
                    else stringResource(R.string.ctbs_app_other_apps, ui.appName, ui.otherAppNames.size.toString())
                Text(
                    name,
                    Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                SelectionContainer {
                    Text(
                        stringResource(R.string.app_details_package_uid, ui.packageName, ui.uid),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(actions::openAndroidAppInfo, Modifier.testTag("app_details_app_info")) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.about_settings_app_info),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(Modifier.padding(end = RethinkSpacing.md), verticalArrangement = Arrangement.spacedBy(RethinkSpacing.xs)) {
            SummaryLine(ui)
            UsageLine(ui)
            if (ui.otherAppNames.isNotEmpty()) {
                Text(
                    stringResource(R.string.app_details_shared_uid, ui.otherAppNames.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (ui.notes.isNotBlank()) {
                Text(
                    stringResource(R.string.app_details_notes_label, ui.notes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        FlowRow(
            Modifier.padding(end = RethinkSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.sm),
        ) {
            OutlinedButton(
                actions::openNotes,
                Modifier.testTag("app_details_notes"),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(Icons.AutoMirrored.Filled.Notes, null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.lbl_notes))
            }
            OutlinedButton(
                actions::requestCloseConnections,
                Modifier.testTag("app_details_close_conns"),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(Icons.Filled.LinkOff, null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.app_details_close_connections))
            }
        }
    }
}

@Composable
private fun SummaryLine(ui: AppDetailsUi) {
    val summary = ui.summary
    val parts = listOfNotNull(accessLabel(summary.access), summary.proxy?.let { proxySummaryLabel(it) })
    val text = parts.joinToString(stringResource(R.string.app_details_summary_separator))
    Row(
        Modifier.semantics(mergeDescendants = true) {}.testTag("app_details_summary"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.sm),
    ) {
        StatusDot(summary.status)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun UsageLine(ui: AppDetailsUi) {
    val up = Utilities.humanReadableByteCount(ui.uploadBytes, true)
    val down = Utilities.humanReadableByteCount(ui.downloadBytes, true)
    val usage = stringResource(R.string.app_details_usage, up, down)
    val usageCd = stringResource(R.string.app_details_usage_cd, up, down)
    val changed = if (ui.modifiedTs > 0) {
        val now = System.currentTimeMillis()
        val rel = DateUtils.getRelativeTimeSpanString(ui.modifiedTs, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
        stringResource(R.string.app_details_rules_changed, rel)
    } else null
    val sep = stringResource(R.string.app_details_summary_separator)
    Text(
        listOfNotNull(usage, changed).joinToString(sep),
        Modifier.semantics { contentDescription = listOfNotNull(usageCd, changed).joinToString(", ") },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun accessLabel(access: AppAccess): String = stringResource(
    when (access) {
        AppAccess.ALLOWED -> R.string.app_details_access_allowed
        AppAccess.BLOCKED -> R.string.app_details_access_blocked
        AppAccess.BLOCKED_WIFI -> R.string.app_details_access_blocked_wifi
        AppAccess.BLOCKED_MOBILE -> R.string.app_details_access_blocked_mobile
        AppAccess.ISOLATED -> R.string.app_details_access_isolated
        AppAccess.BYPASS_UNIVERSAL -> R.string.app_details_access_bypass_universal
        AppAccess.BYPASS_DNS_FIREWALL -> R.string.app_details_access_bypass_dns_firewall
        AppAccess.EXCLUDED -> R.string.app_details_access_excluded
        AppAccess.TEMP_ALLOWED -> R.string.app_details_access_temp_allowed
    }
)

@Composable
private fun proxySummaryLabel(proxy: SummaryProxy): String = when (proxy) {
    SummaryProxy.Direct -> stringResource(R.string.app_details_summary_direct)
    is SummaryProxy.Via -> stringResource(R.string.app_details_summary_via, proxy.choice.displayTitle())
    is SummaryProxy.Multiple -> stringResource(R.string.app_details_summary_multiple, proxy.count)
}

// ---- network access ----

@Composable
private fun NetworkSection(ui: AppDetailsUi, actions: AppDetailsActions) {
    SettingsSection(title = stringResource(R.string.app_details_section_network)) {
        val tempDesc = if (ui.tempAllowed && ui.tempAllowExpiry > System.currentTimeMillis()) {
            val time = DateFormat.getTimeFormat(LocalContext.current).format(java.util.Date(ui.tempAllowExpiry))
            stringResource(R.string.app_details_temp_allow_until, time)
        } else stringResource(R.string.temp_allow_desc)
        SwitchRow(
            title = stringResource(R.string.temp_allow_label),
            checked = ui.tempAllowed,
            onCheckedChange = actions::setTempAllow,
            modifier = Modifier.testTag("app_details_temp_allow"),
            supportingText = tempDesc,
            icon = Icons.Filled.Timer,
        )
        SectionDivider()
        SwitchRow(
            title = stringResource(R.string.app_details_wifi),
            checked = !ui.wifiBlocked,
            onCheckedChange = { actions.toggleWifi() },
            modifier = Modifier.testTag("app_details_wifi"),
            supportingText = stringResource(if (ui.wifiBlocked) R.string.app_details_blocked else R.string.app_details_allowed),
            icon = Icons.Filled.Wifi,
        )
        SwitchRow(
            title = stringResource(R.string.app_details_mobile_data),
            checked = !ui.mobileBlocked,
            onCheckedChange = { actions.toggleMobileData() },
            modifier = Modifier.testTag("app_details_mobile"),
            supportingText = stringResource(if (ui.mobileBlocked) R.string.app_details_blocked else R.string.app_details_allowed),
            icon = Icons.Filled.SignalCellularAlt,
        )
        if (ui.mode != AppNetworkMode.NORMAL) {
            Note(stringResource(R.string.app_details_network_mode_note))
        }
    }
}

@Composable
private fun ModeSection(ui: AppDetailsUi, actions: AppDetailsActions) {
    SettingsSection(title = stringResource(R.string.app_details_section_mode)) {
        val excludeReason = when (ui.excludeBlocker) {
            ExcludeBlocker.NONE -> null
            ExcludeBlocker.UNKNOWN_PACKAGE -> stringResource(R.string.exclude_no_package_err_toast)
            ExcludeBlocker.VPN_LOCKDOWN -> stringResource(R.string.hsf_exclude_error)
        }
        val options = AppNetworkMode.entries.map { mode ->
            val (title, desc) = when (mode) {
                AppNetworkMode.NORMAL -> R.string.app_details_mode_normal to R.string.app_details_mode_normal_desc
                AppNetworkMode.ISOLATE -> R.string.app_details_mode_isolate to R.string.app_details_mode_isolate_desc
                AppNetworkMode.BYPASS_DNS_FIREWALL -> R.string.app_details_mode_bypass_dns_firewall to R.string.app_details_mode_bypass_dns_firewall_desc
                AppNetworkMode.BYPASS_UNIVERSAL -> R.string.app_details_mode_bypass_universal to R.string.app_details_mode_bypass_universal_desc
                AppNetworkMode.EXCLUDE -> R.string.app_details_mode_exclude to R.string.app_details_mode_exclude_desc
            }
            ChoiceOption(
                value = mode,
                title = stringResource(title),
                description = stringResource(desc),
                enabled = mode != AppNetworkMode.EXCLUDE || excludeReason == null,
                disabledReason = if (mode == AppNetworkMode.EXCLUDE) excludeReason else null,
            )
        }
        SingleChoiceList(options, ui.mode, actions::selectMode, Modifier.testTag("app_details_mode"))
    }
}

// ---- proxy ----

@Composable
private fun ProxySection(ui: AppDetailsUi, actions: AppDetailsActions) {
    SettingsSection(title = stringResource(R.string.app_details_section_proxy)) {
        val proxy = ui.proxy
        val (title, desc) = when (proxy) {
            ProxyRowState.Loading -> stringResource(R.string.proxy_picker_loading) to null
            ProxyRowState.Default ->
                stringResource(R.string.proxy_picker_follow_global) to stringResource(R.string.proxy_picker_follow_global_desc)
            ProxyRowState.NoProxy ->
                stringResource(R.string.proxy_picker_bypass) to stringResource(R.string.proxy_picker_bypass_desc)
            is ProxyRowState.Via -> proxy.choice.displayTitle() to proxy.choice.displayDescription()
            is ProxyRowState.Multiple -> {
                val names = proxy.choices.map { it.displayTitle() }
                stringResource(R.string.proxy_picker_multiple, names.size) to
                    stringResource(R.string.proxy_picker_multiple_desc, names.joinToString(", "))
            }
        }
        val rpn = if (ui.rpnNames.isEmpty()) null
            else stringResource(R.string.app_details_proxy_rpn, ui.rpnNames.joinToString(", "))
        val status = AppDetailsLogic.proxyStatus(proxy)
        SettingRow(
            title = title,
            modifier = Modifier.testTag("app_details_proxy"),
            supportingText = listOfNotNull(desc, rpn).joinToString("\n").ifEmpty { null },
            icon = Icons.Filled.VpnKey,
            onClick = actions::openProxyPicker,
        ) {
            if (status != null) StatusChip(status)
            SettingTrailing.Chevron()
        }
        if (ui.mode == AppNetworkMode.EXCLUDE) {
            Note(stringResource(R.string.app_details_proxy_excluded_note))
        }
    }
}

// ---- rules ----

@Composable
private fun RulesSection(ui: AppDetailsUi, actions: AppDetailsActions) {
    SettingsSection(title = stringResource(R.string.app_details_section_rules)) {
        SettingRow(
            title = stringResource(R.string.app_details_ip_rules),
            modifier = Modifier.testTag("app_details_ip_rules"),
            supportingText = stringResource(R.string.app_details_ip_rules_desc),
            icon = Icons.Filled.Lan,
            onClick = actions::openIpRules,
        ) {
            SettingTrailing.Value(ui.ipRuleCount?.toString() ?: "")
            SettingTrailing.Chevron()
        }
        SectionDivider()
        SettingRow(
            title = stringResource(R.string.app_details_domain_rules),
            modifier = Modifier.testTag("app_details_domain_rules"),
            supportingText = stringResource(R.string.app_details_domain_rules_desc),
            icon = Icons.Filled.Dns,
            onClick = actions::openDomainRules,
        ) {
            SettingTrailing.Value(ui.domainRuleCount?.toString() ?: "")
            SettingTrailing.Chevron()
        }
    }
}

// ---- activity ----

@Composable
private fun ActivitySection(state: AppDetailsState, actions: AppDetailsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(RethinkSpacing.sm)) {
        SettingsSection(title = stringResource(R.string.app_details_section_activity)) {
            SettingRow(
                title = stringResource(R.string.app_details_domain_logs),
                modifier = Modifier.testTag("app_details_domain_logs"),
                supportingText = stringResource(R.string.app_details_domain_logs_desc),
                icon = Icons.Filled.Language,
                onClick = actions::openDomainLogs,
            ) { SettingTrailing.Chevron() }
            SectionDivider()
            SettingRow(
                title = stringResource(R.string.app_details_ip_logs),
                modifier = Modifier.testTag("app_details_ip_logs"),
                supportingText = stringResource(R.string.app_details_ip_logs_desc),
                icon = Icons.Filled.Router,
                onClick = actions::openIpLogs,
            ) { SettingTrailing.Chevron() }
        }
        if (!state.anyActivity) {
            EmptyState(
                icon = Icons.Outlined.Insights,
                title = stringResource(R.string.app_details_activity_empty_title),
                body = stringResource(R.string.app_details_activity_empty_body),
            )
            return@Column
        }
        SegmentedChoice(
            listOf(
                SegmentOption(StatsViewMode.LIST, stringResource(R.string.app_details_view_list)),
                SegmentOption(StatsViewMode.INSIGHTS, stringResource(R.string.app_details_view_insights)),
            ),
            selected = state.viewMode,
            onSelect = actions::setViewMode,
            modifier = Modifier.padding(horizontal = RethinkSpacing.md).testTag("app_details_view_mode"),
        )
        val insights = state.viewMode == StatsViewMode.INSIGHTS
        AppInsightsSection.entries.forEach { section ->
            if (state.sectionHasData[section] != true) return@forEach
            val items = state.insightsSnapshots[section].orEmpty()
            if (insights && items.isEmpty()) return@forEach
            Column {
                SectionHeader(
                    stringResource(section.title),
                    actionLabel = stringResource(R.string.ssv_see_more),
                    onAction = { actions.openSection(section) },
                )
                if (insights) {
                    AppDetailsInsights(section, items)
                } else {
                    state.presenters[section]?.Content(Modifier.padding(horizontal = RethinkSpacing.sm))
                }
            }
        }
    }
}

/** A short explanation at the bottom of a section. */
@Composable
private fun Note(text: String) {
    Text(
        text,
        Modifier.padding(horizontal = RethinkSpacing.screenGutter, vertical = RethinkSpacing.sm),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
