/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
@file:OptIn(ExperimentalMaterial3Api::class)

package com.celzero.bravedns.ui.compose.rules

import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.service.ProxyChoices
import com.celzero.bravedns.ui.compose.appdetails.DrawableImage
import com.celzero.bravedns.ui.compose.design.PreviewSurface
import com.celzero.bravedns.ui.compose.design.RethinkSpacing
import com.celzero.bravedns.ui.compose.design.RuleProxyPickerSheet
import com.celzero.bravedns.ui.compose.design.SectionDivider
import com.celzero.bravedns.ui.compose.design.SegmentOption
import com.celzero.bravedns.ui.compose.design.SegmentedChoice
import com.celzero.bravedns.ui.compose.design.SettingRow
import com.celzero.bravedns.ui.compose.design.SettingTrailing
import com.celzero.bravedns.ui.compose.design.SettingsSection
import com.celzero.bravedns.ui.compose.design.StatusChip
import com.celzero.bravedns.ui.compose.design.ThemePreviews
import com.celzero.bravedns.ui.compose.design.displayTitle
import com.celzero.bravedns.ui.compose.design.ProxyPickerSamples
import com.celzero.bravedns.ui.compose.design.toStatus
import kotlinx.coroutines.delay

/*
 * The per-connection rule sheets: for each IP / domain of a connection, its action
 * (Trust / Block / None) and, independently, "Route via" a proxy. Used by
 * AppIpRulesBottomSheet, AppDomainRulesBottomSheet (whole sheet) and ConnTrackerBottomSheet
 * (its rules section).
 */

/** How often the proxies' status chips refresh while a sheet is shown (as the rules page). */
private const val PROXY_REFRESH_MS = 5_000L

/** The app IP / app domain sheet: header, the IP (if any), its domains, footer. */
@Composable
internal fun AppRuleSheetScreen(
    title: String,
    appName: String?,
    appIcon: Drawable?,
    state: RuleSheetState,
    actions: RuleSheetActions,
) {
    RuleSheetSurface {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(bottom = RethinkSpacing.lg).testTag("rule_sheet"),
            verticalArrangement = Arrangement.spacedBy(RethinkSpacing.sm),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { BottomSheetDefaults.DragHandle() }
            RuleSheetHeader(title, appName, appIcon)
            val ips = state.rows.filter { it.kind == RuleKind.IP }
            val domains = state.rows.filter { it.kind == RuleKind.DOMAIN }
            if (ips.isNotEmpty()) {
                SettingsSection(title = stringResource(R.string.rules_sheet_section_ip)) {
                    RuleSheetRows(ips, state.proxies, actions, state)
                }
            }
            if (domains.isNotEmpty()) {
                val section = if (ips.isEmpty() && domains.size == 1) R.string.lbl_domain else R.string.rules_sheet_section_domains
                SettingsSection(title = stringResource(section)) {
                    RuleSheetRows(domains, state.proxies, actions, state)
                }
            }
            Text(
                stringResource(R.string.bsac_title_desc),
                Modifier.padding(horizontal = RethinkSpacing.screenGutter, vertical = RethinkSpacing.sm),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    RuleSheetEffects(state, actions)
}

/** The sheet background: the window is transparent, so the sheet draws its own surface. */
@Composable
internal fun RuleSheetSurface(content: @Composable () -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        content = content,
    )
}

/** Live proxy status (polled) and the "Route via" picker; host once per sheet. */
@Composable
internal fun RuleSheetEffects(state: RuleSheetState, actions: RuleSheetActions) {
    LaunchedEffect(Unit) {
        while (true) {
            actions.refreshProxies()
            delay(PROXY_REFRESH_MS)
        }
    }
    state.picker?.let { open ->
        RuleProxyPickerSheet(
            currentProxyId = open.proxyId,
            subject = open.value,
            onSelect = { id -> actions.setProxy(state.row(open.key) ?: open, id) },
            onDismiss = { state.picker = null },
        )
    }
}

@Composable
private fun RuleSheetHeader(title: String, appName: String?, appIcon: Drawable?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = RethinkSpacing.screenGutter, vertical = RethinkSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.lg),
    ) {
        if (appName != null) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                if (appIcon != null) DrawableImage(appIcon, Modifier.size(40.dp))
                else Icon(Icons.Filled.Apps, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(RethinkSpacing.xxs)) {
            Text(
                title,
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (appName != null) {
                Text(appName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Rows inside a section, separated by dividers. */
@Composable
internal fun ColumnScope.RuleSheetRows(
    rows: List<RuleSheetRow>,
    proxies: List<ProxyChoices.Choice>?,
    actions: RuleSheetActions,
    state: RuleSheetState,
) {
    rows.forEachIndexed { i, row ->
        if (i > 0) SectionDivider(Modifier.padding(vertical = RethinkSpacing.xs))
        RuleSheetRowContent(
            row = row,
            proxies = proxies,
            onAction = { actions.setAction(row, it) },
            onRoute = { state.picker = row },
        )
    }
}

/**
 * One IP / domain: the value, its action (segmented; read-only when locked) and "Route via".
 * Stateless, for previews and tests.
 */
@Composable
internal fun RuleSheetRowContent(
    row: RuleSheetRow,
    proxies: List<ProxyChoices.Choice>?,
    onAction: (RuleAction) -> Unit,
    onRoute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().testTag("rule_sheet_row_${row.kind.name.lowercase()}")) {
        SelectionContainer(Modifier.padding(horizontal = RethinkSpacing.screenGutter, vertical = RethinkSpacing.sm)) {
            Text(
                row.value,
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (RuleSheetLogic.locked(row)) {
            Column(
                Modifier.padding(horizontal = RethinkSpacing.screenGutter, vertical = RethinkSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(RethinkSpacing.xxs),
            ) {
                Text(stringResource(actionLabel(row.action)), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.rules_sheet_action_locked), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val actionCd = stringResource(R.string.rules_sheet_action_cd, row.value)
            SegmentedChoice(
                options = RuleSheetLogic.options(row).map { SegmentOption(it, stringResource(segmentLabel(it)), enabled = row.loaded) },
                selected = row.action,
                onSelect = onAction,
                modifier = Modifier
                    .padding(horizontal = RethinkSpacing.lg, vertical = RethinkSpacing.xs)
                    .semantics { contentDescription = actionCd }
                    .testTag("rule_sheet_action"),
            )
        }
        RuleSheetRouteRow(row, proxies, onRoute)
        if (RuleSheetLogic.proxyIgnored(row)) {
            Text(
                stringResource(R.string.rules_proxy_ignored_note),
                Modifier.padding(start = RethinkSpacing.screenGutter, end = RethinkSpacing.screenGutter, bottom = RethinkSpacing.sm),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "No proxy override" or "Route via <name>" with live status; opens the picker. */
@Composable
private fun RuleSheetRouteRow(row: RuleSheetRow, proxies: List<ProxyChoices.Choice>?, onRoute: () -> Unit) {
    val choice = RulesLogic.proxyChoice(row.proxyId, proxies)
    val title = if (choice == null) stringResource(R.string.proxy_picker_no_override)
    else stringResource(R.string.rules_route_via, choice.displayTitle())
    val cd = stringResource(R.string.rules_sheet_route_cd, row.value, title)
    SettingRow(
        title = title,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = cd }.testTag("rule_sheet_route"),
        icon = Icons.Filled.Route,
        enabled = row.loaded,
        onClick = onRoute,
    ) {
        if (choice != null && proxies != null) StatusChip(choice.health.toStatus())
        SettingTrailing.Chevron()
    }
}

/** Short labels for the segmented action control. */
internal fun segmentLabel(action: RuleAction): Int = when (action) {
    RuleAction.BYPASS_UNIVERSAL -> R.string.rules_action_bypass_short
    else -> actionLabel(action)
}

@ThemePreviews
@Composable
private fun RuleSheetRowPreview() = PreviewSurface {
    val proxies = ProxyPickerSamples.proxies
    SettingsSection(title = "IP address") {
        RuleSheetRowContent(RuleSheetRow(RuleKind.IP, 10100, "142.250.183.14", RuleAction.BLOCK, proxies.first().proxyId, loaded = true), proxies, {}, {})
        SectionDivider()
        RuleSheetRowContent(RuleSheetRow(RuleKind.DOMAIN, 10100, "calendar.google.com", RuleAction.NONE, loaded = true), proxies, {}, {})
    }
}
