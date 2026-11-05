/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.celzero.bravedns.R
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.ProxyChoices
import com.celzero.bravedns.service.ProxyChoices.Pick
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Shared proxy picker: one bottom sheet for choosing how an app (app mode) or an IP / domain
 * rule (rule mode) is routed. Replaces the WireGuard-only WireguardListBtmSheet.
 *
 * How a Compose screen opens it:
 *
 *   var showPicker by remember { mutableStateOf(false) }
 *   if (showPicker) AppProxyPickerSheet(uid, packageName, appName, onDismiss = { showPicker = false })
 *
 *   if (showPicker) RuleProxyPickerSheet(
 *       currentProxyId = rule.proxyId, subject = rule.ipAddress,
 *       onSelect = { proxyId -> /* "" = no override; save it on the rule */ },
 *       onDismiss = { showPicker = false })
 */

/** Which kind of picker: what the first rows mean and what selecting does. */
enum class ProxyPickerMode { APP, RULE }

/**
 * App mode, live data: lists the proxies, shows the app's current choice
 * (ProxyManager.getProxyForApp + the exclude-from-proxy flag) and writes the new one
 * (ProxyManager.setProxyForApp / FirewallManager.updateIsProxyExcluded), then closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppProxyPickerSheet(
    uid: Int,
    packageName: String,
    appName: String,
    onDismiss: () -> Unit,
    onChanged: (Pick) -> Unit = {},
    choices: ProxyChoices = remember { ProxyChoices() },
    writer: ProxyChoices.AppWriter = ProxyChoices.LiveAppWriter,
) {
    var proxies by remember { mutableStateOf<List<ProxyChoices.Choice>?>(null) }
    var selection by remember { mutableStateOf(ProxyChoices.AppSelection(null)) }
    var excluded by remember { mutableStateOf(false) }
    LaunchedEffect(uid) {
        withContext(Dispatchers.IO) {
            val list = choices.list()
            val ex = FirewallManager.isAppExcludedFromProxy(uid)
            val sel = ProxyChoices.currentForApp(ProxyManager.getProxyForApp(uid), ex)
            val shown = ProxyChoices.withUnknown(list, listOfNotNull((sel.pick as? Pick.Via)?.proxyId) + sel.multipleIds)
            withContext(Dispatchers.Main) { proxies = shown; excluded = ex; selection = sel }
        }
    }
    val scope = rememberCoroutineScope()
    ProxyPickerSheet(
        mode = ProxyPickerMode.APP,
        title = stringResource(R.string.proxy_picker_title_app, appName),
        subtitle = null,
        proxies = proxies,
        selected = selection.pick,
        multiple = selection.multipleIds.map { id -> proxies.displayName(id) },
        onSelect = { pick ->
            val current = selection
            selection = ProxyChoices.AppSelection(pick)
            scope.launch {
                val changed = withContext(Dispatchers.IO) {
                    ProxyChoices.applyForApp(pick, current, excluded, uid, packageName, ProxyManager::proxyDisplayName, writer)
                }
                Logger.i(LOG_TAG_UI, "proxy picker: $packageName($uid) -> $pick, changed: $changed")
                if (changed) onChanged(pick)
                onDismiss()
            }
        },
        onDismiss = onDismiss,
    )
}

/**
 * Rule mode, live data: lists the proxies with [currentProxyId] selected and returns the
 * chosen proxyId through [onSelect] ("" for no override); it writes nothing itself.
 * [subject] is the rule's IP / domain, shown under the title.
 */
@Composable
fun RuleProxyPickerSheet(
    currentProxyId: String?,
    subject: String?,
    onSelect: (proxyId: String) -> Unit,
    onDismiss: () -> Unit,
    choices: ProxyChoices = remember { ProxyChoices() },
) {
    var proxies by remember { mutableStateOf<List<ProxyChoices.Choice>?>(null) }
    LaunchedEffect(currentProxyId) {
        val list = withContext(Dispatchers.IO) { choices.list() }
        proxies = ProxyChoices.withUnknown(list, listOfNotNull(currentProxyId))
    }
    ProxyPickerSheet(
        mode = ProxyPickerMode.RULE,
        title = stringResource(R.string.proxy_picker_title_rule),
        subtitle = subject,
        proxies = proxies,
        selected = ProxyChoices.currentForRule(currentProxyId),
        onSelect = { pick ->
            onSelect(ProxyChoices.ruleProxyId(pick))
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

/** The picker as a modal bottom sheet; stateless (see [ProxyPickerContent]). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyPickerSheet(
    mode: ProxyPickerMode,
    title: String,
    subtitle: String?,
    proxies: List<ProxyChoices.Choice>?,
    selected: Pick?,
    onSelect: (Pick) -> Unit,
    onDismiss: () -> Unit,
    multiple: List<String> = emptyList(),
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        ProxyPickerContent(
            mode, title, subtitle, proxies, selected, onSelect,
            modifier = Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding(),
            multiple = multiple,
        )
    }
}

/**
 * The picker's content: title, optional subtitle, a "Multiple (n)" note for legacy
 * multi-mapped apps, then one radio list. [proxies] null means still loading.
 * Off proxies are listed with an "Off" chip and stay selectable.
 */
@Composable
fun ProxyPickerContent(
    mode: ProxyPickerMode,
    title: String,
    subtitle: String?,
    proxies: List<ProxyChoices.Choice>?,
    selected: Pick?,
    onSelect: (Pick) -> Unit,
    modifier: Modifier = Modifier,
    multiple: List<String> = emptyList(),
) {
    Column(modifier.fillMaxWidth().padding(bottom = RethinkSpacing.lg)) {
        Column(
            Modifier.padding(horizontal = RethinkSpacing.screenGutter, vertical = RethinkSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(RethinkSpacing.xxs),
        ) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (multiple.isNotEmpty()) {
            Surface(
                Modifier.fillMaxWidth().padding(horizontal = RethinkSpacing.lg, vertical = RethinkSpacing.xs),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Column(Modifier.padding(RethinkSpacing.md), verticalArrangement = Arrangement.spacedBy(RethinkSpacing.xxs)) {
                    Text(
                        stringResource(R.string.proxy_picker_multiple, multiple.size),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        stringResource(R.string.proxy_picker_multiple_desc, multiple.joinToString(", ")),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
        val options = baseOptions(mode) + (proxies?.let { proxyOptions(it, selected) } ?: emptyList())
        SingleChoiceList(options, selected, onSelect)
        if (proxies == null) {
            Text(
                stringResource(R.string.proxy_picker_loading),
                Modifier.padding(horizontal = RethinkSpacing.screenGutter, vertical = RethinkSpacing.md),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun baseOptions(mode: ProxyPickerMode): List<ChoiceOption<Pick>> = when (mode) {
    ProxyPickerMode.APP -> listOf(
        ChoiceOption(Pick.FollowGlobal, stringResource(R.string.proxy_picker_follow_global), stringResource(R.string.proxy_picker_follow_global_desc)),
        ChoiceOption(Pick.Bypass, stringResource(R.string.proxy_picker_bypass), stringResource(R.string.proxy_picker_bypass_desc)),
    )
    ProxyPickerMode.RULE -> listOf(
        ChoiceOption(Pick.FollowGlobal, stringResource(R.string.proxy_picker_no_override), stringResource(R.string.proxy_picker_no_override_desc)),
    )
}

@Composable
private fun proxyOptions(proxies: List<ProxyChoices.Choice>, selected: Pick?): List<ChoiceOption<Pick>> {
    return proxies.map { p ->
        val pick = Pick.Via(p.proxyId)
        var description = p.displayDescription()
        // a chosen proxy that is off does nothing until it is turned on; say so
        if (!p.enabled && p.kind != ProxyChoices.Kind.OTHER && selected == pick) {
            description = listOfNotNull(description, stringResource(R.string.proxy_picker_off_note)).joinToString("\n")
        }
        val status = p.health.toStatus()
        ChoiceOption(
            value = pick,
            title = p.displayTitle(),
            description = description,
            trailing = { StatusChip(status) },
        )
    }
}

@Composable
internal fun ProxyChoices.Choice.displayTitle(): String = when (kind) {
    ProxyChoices.Kind.WIREGUARD -> name.ifBlank { proxyId }
    ProxyChoices.Kind.SOCKS5 -> stringResource(R.string.proxy_picker_socks5)
    ProxyChoices.Kind.ORBOT -> stringResource(R.string.proxy_picker_orbot)
    ProxyChoices.Kind.OTHER -> proxyId
}

@Composable
internal fun ProxyChoices.Choice.displayDescription(): String? = when (kind) {
    ProxyChoices.Kind.WIREGUARD -> {
        val flags = listOfNotNull(
            stringResource(R.string.proxy_picker_wireguard),
            if (wgOneWireGuard) stringResource(R.string.proxy_picker_wg_one) else null,
            if (wgCatchAll) stringResource(R.string.proxy_picker_wg_catch_all) else null,
            if (wgLockdown) stringResource(R.string.proxy_picker_wg_lockdown) else null,
            if (wgMobileOnly) stringResource(R.string.proxy_picker_wg_mobile_only) else null,
            if (wgSsidOnly) stringResource(R.string.proxy_picker_wg_ssid) else null,
        )
        flags.joinToString(" · ")
    }
    ProxyChoices.Kind.SOCKS5 -> endpoint ?: stringResource(R.string.proxy_picker_socks5_not_set)
    ProxyChoices.Kind.ORBOT -> null
    ProxyChoices.Kind.OTHER -> stringResource(R.string.proxy_picker_unknown)
}

/** The status-chip state of a proxy's health. */
fun ProxyChoices.Health.toStatus(): RethinkStatus = when (this) {
    ProxyChoices.Health.UP -> RethinkStatus.Active
    ProxyChoices.Health.WAITING -> RethinkStatus.Waiting
    ProxyChoices.Health.OFF -> RethinkStatus.Off
    ProxyChoices.Health.ERROR -> RethinkStatus.Error
}

private fun List<ProxyChoices.Choice>?.displayName(id: String): String {
    val c = this?.firstOrNull { it.proxyId == id }
    return if (c?.kind == ProxyChoices.Kind.WIREGUARD && c.name.isNotBlank()) c.name else ProxyManager.proxyDisplayName(id)
}

/** Sample data for previews and the debug design gallery. */
object ProxyPickerSamples {
    val proxies = listOf(
        ProxyChoices.Choice("wg1", ProxyChoices.Kind.WIREGUARD, "wg-one", enabled = true, health = ProxyChoices.Health.UP),
        ProxyChoices.Choice("wg2", ProxyChoices.Kind.WIREGUARD, "wg-two", enabled = true, health = ProxyChoices.Health.WAITING, wgCatchAll = true),
        ProxyChoices.Choice("wg3", ProxyChoices.Kind.WIREGUARD, "home", enabled = false, health = ProxyChoices.Health.OFF, wgMobileOnly = true),
        ProxyChoices.Choice("S5", ProxyChoices.Kind.SOCKS5, "", "10.0.2.2:1080", enabled = true, health = ProxyChoices.Health.ERROR),
        ProxyChoices.Choice("ORBOT", ProxyChoices.Kind.ORBOT, "", enabled = false, health = ProxyChoices.Health.OFF),
    )
}

@ThemePreviews
@Composable
private fun AppPickerPreview() = PreviewSurface {
    ProxyPickerContent(ProxyPickerMode.APP, "Proxy for Chrome", null, ProxyPickerSamples.proxies, Pick.Via("wg2"), {})
}

@ThemePreviews
@Composable
private fun RulePickerPreview() = PreviewSurface {
    ProxyPickerContent(ProxyPickerMode.RULE, "Route via proxy", "1.1.1.1:443", ProxyPickerSamples.proxies, Pick.Via("wg3"), {}, multiple = emptyList())
}
