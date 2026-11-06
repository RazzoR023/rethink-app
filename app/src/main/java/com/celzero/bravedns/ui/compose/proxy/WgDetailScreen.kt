/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.proxy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.design.RethinkSpacing
import com.celzero.bravedns.ui.compose.design.SectionDivider
import com.celzero.bravedns.ui.compose.design.SettingRow
import com.celzero.bravedns.ui.compose.design.SettingTrailing
import com.celzero.bravedns.ui.compose.design.SettingsSection
import com.celzero.bravedns.ui.compose.design.SwitchRow

/** Observable state of a WireGuard config page; WgConfigDetailActivity does the I/O. */
class WgPageState(name: String, val oneWg: Boolean) {
    var header by mutableStateOf(ProxyHeaderUi(ProxyType.WIREGUARD, name, toggleable = WgPageLogic.toggleable(oneWg)))
    /** Advanced: the interface public key (tap to copy); Simple: its DNS servers */
    var publicKey by mutableStateOf<String?>(null)
    var dns by mutableStateOf<String?>(null)
    var exitIp by mutableStateOf<WgExitIpUi?>(null)
    var peers by mutableStateOf<List<WgPeerUi>>(emptyList())
    var appCount by mutableIntStateOf(0)
    var catchAll by mutableStateOf(false)
    var lockdown by mutableStateOf(false)
    /** name of the config this one is relayed through (hop), null when none */
    var hopName by mutableStateOf<String?>(null)
    var mobileOnly by mutableStateOf(false)
    /** device has Wi-Fi (the SSID option is hidden otherwise) */
    var ssidSupported by mutableStateOf(true)
    var ssidEnabled by mutableStateOf(false)
    /** saved SSIDs ("" = all Wi-Fi networks) */
    var ssidText by mutableStateOf("")
    var ssidPermissionError by mutableStateOf(false)
    var ssidLocationError by mutableStateOf(false)
    var ssidPermissionText by mutableStateOf("")
    /** automation owned by an external app (tasker): mobile / SSID rows are inert */
    var taskerOwned by mutableStateOf(false)
    var confirm by mutableStateOf<ProxyConfirm?>(null)

    val apps: ProxyAppsUi get() = ProxyDetailLogic.wireguardApps(appCount, header.enabled, oneWg, catchAll)
    val appsEnabled: Boolean get() = WgPageLogic.appsEnabled(oneWg, catchAll)
}

interface WgDetailActions {
    fun back()
    fun toggle(on: Boolean)
    fun copyPublicKey()
    fun editConfig()
    fun addPeer()
    fun editPeer(index: Int)
    fun deletePeer(index: Int)
    fun openApps()
    fun setLockdown(on: Boolean)
    fun setCatchAll(on: Boolean)
    fun openHop()
    fun setMobileOnly(on: Boolean)
    fun setSsidEnabled(on: Boolean)
    fun editSsids()
    fun fixSsidPermission()
    fun fixSsidLocation()
    fun openLogs()
    fun deleteConfig()
}

@Composable
fun WgDetailScreen(state: WgPageState, actions: WgDetailActions, rules: ProxyRulesController) {
    ProxyDetailScaffold(
        title = stringResource(R.string.proxy_page_title_wireguard),
        header = state.header,
        onBack = actions::back,
        onToggle = actions::toggle,
        headerNotes = {
            if (state.oneWg) ProxyNote(stringResource(R.string.one_wg_dialog_desc))
        },
    ) {
        WgConfigurationSection(state, actions)
        WgPeersSection(state, actions)
        ProxyAppsSection(state.apps, actions::openApps, enabled = state.appsEnabled)
        ProxyRulesSection(rules, state.header.name)
        if (WgPageLogic.showRouting(state.oneWg)) {
            WgRoutingSection(state, actions)
            WgAutomationSection(state, actions)
        }
        SettingsSection(title = stringResource(R.string.proxy_page_section_more)) {
            SettingRow(
                title = stringResource(R.string.lbl_logs),
                modifier = Modifier.testTag("proxy_logs"),
                supportingText = stringResource(R.string.proxy_page_logs_desc),
                icon = Icons.AutoMirrored.Filled.ListAlt,
                onClick = actions::openLogs,
            ) { SettingTrailing.Chevron() }
            SectionDivider()
            SettingRow(
                title = stringResource(R.string.proxy_page_wg_delete),
                modifier = Modifier.testTag("wg_delete"),
                icon = Icons.Filled.Delete,
                onClick = actions::deleteConfig,
            )
        }
    }
    ProxyRulesHosts(rules)
    state.confirm?.let { c -> ProxyConfirmDialog(c) { state.confirm = null } }
}

@Composable
private fun WgConfigurationSection(state: WgPageState, actions: WgDetailActions) {
    SettingsSection(title = stringResource(R.string.proxy_page_section_config)) {
        if (state.oneWg) {
            SettingRow(
                title = stringResource(R.string.lbl_dns_servers),
                modifier = Modifier.testTag("wg_dns"),
                supportingText = state.dns.orEmpty(),
                icon = Icons.Outlined.Dns,
            )
        } else {
            SettingRow(
                title = stringResource(R.string.lbl_public_key),
                modifier = Modifier.testTag("wg_public_key"),
                supportingText = state.publicKey.orEmpty(),
                icon = Icons.Outlined.Key,
                onClick = actions::copyPublicKey,
            ) { Icon(Icons.Filled.ContentCopy, stringResource(R.string.proxy_page_wg_copy_key), Modifier.size(20.dp)) }
        }
        state.exitIp?.let { exit ->
            SectionDivider()
            SettingRow(
                title = stringResource(R.string.lbl_client_ipv4),
                modifier = Modifier.testTag("wg_exit_ip"),
                supportingText = if (exit.loading) null
                    else listOfNotNull(exit.ip4 ?: stringResource(R.string.lbl_not_available_short), exit.ip6).joinToString("\n\n"),
                icon = Icons.Filled.Public,
            ) { if (exit.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
        }
        SectionDivider()
        SettingRow(
            title = stringResource(R.string.proxy_page_wg_edit),
            modifier = Modifier.testTag("wg_edit"),
            supportingText = stringResource(R.string.proxy_page_wg_edit_desc),
            icon = Icons.Filled.Edit,
            onClick = actions::editConfig,
        ) { SettingTrailing.Chevron() }
    }
}

@Composable
private fun WgPeersSection(state: WgPageState, actions: WgDetailActions) {
    SettingsSection(
        Modifier.testTag("wg_peers"),
        title = stringResource(R.string.proxy_page_section_peers),
        actionLabel = stringResource(R.string.add_peer),
        onAction = actions::addPeer,
    ) {
        if (state.peers.isEmpty()) {
            Text(
                stringResource(R.string.proxy_page_wg_no_peers),
                Modifier.padding(horizontal = RethinkSpacing.screenGutter, vertical = RethinkSpacing.md),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.peers.forEachIndexed { i, peer ->
            if (i > 0) SectionDivider()
            WgPeerRow(peer, actions)
        }
    }
}

@Composable
private fun WgPeerRow(peer: WgPeerUi, actions: WgDetailActions) {
    val lines = buildList {
        peer.allowedIps?.let { add(stringResource(R.string.lbl_allowed_ips) + ": " + it) }
        peer.keepalive?.let { add(stringResource(R.string.lbl_persistent_keepalive) + ": " + it) }
        add(stringResource(R.string.lbl_public_key) + ": " + peer.publicKey)
    }
    SettingRow(
        title = peer.title,
        modifier = Modifier.testTag("wg_peer_row"),
        supportingText = lines.joinToString("\n"),
        icon = Icons.Filled.Language,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.xxs), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ actions.editPeer(peer.index) }, Modifier.testTag("wg_peer_edit")) {
                Icon(Icons.Filled.Edit, stringResource(R.string.proxy_page_wg_edit_peer))
            }
            IconButton({ actions.deletePeer(peer.index) }, Modifier.testTag("wg_peer_delete")) {
                Icon(Icons.Filled.Delete, stringResource(R.string.proxy_page_wg_delete_peer))
            }
        }
    }
}

@Composable
private fun WgRoutingSection(state: WgPageState, actions: WgDetailActions) {
    SettingsSection(title = stringResource(R.string.proxy_page_section_routing)) {
        SwitchRow(
            title = stringResource(R.string.firewall_rule_global_lockdown),
            checked = state.lockdown,
            onCheckedChange = actions::setLockdown,
            modifier = Modifier.testTag("wg_lockdown"),
            supportingText = stringResource(R.string.lockdown_wg_dialog_desc),
            icon = Icons.Filled.Lock,
        )
        SectionDivider()
        SwitchRow(
            title = stringResource(R.string.catch_all_wg_dialog_title),
            checked = state.catchAll,
            onCheckedChange = actions::setCatchAll,
            modifier = Modifier.testTag("wg_catch_all"),
            supportingText = stringResource(R.string.catch_all_wg_dialog_desc),
            icon = Icons.Filled.Shield,
        )
        SectionDivider()
        SettingRow(
            title = stringResource(R.string.lbl_hop),
            modifier = Modifier.testTag("wg_hop"),
            supportingText = state.hopName?.let { stringResource(R.string.proxy_page_wg_hop_via, it) }
                ?: stringResource(R.string.proxy_page_wg_hop_none),
            icon = Icons.Filled.Route,
            onClick = actions::openHop,
        ) { SettingTrailing.Chevron() }
    }
}

@Composable
private fun WgAutomationSection(state: WgPageState, actions: WgDetailActions) {
    val experimental = stringResource(R.string.lbl_experimental)
    SettingsSection(title = stringResource(R.string.adv_taster_title)) {
        if (state.taskerOwned) {
            Text(
                stringResource(R.string.wg_automation_tasker_managed),
                Modifier.padding(horizontal = RethinkSpacing.screenGutter, vertical = RethinkSpacing.sm),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        SwitchRow(
            title = stringResource(R.string.wg_setting_use_on_mobile) + " " + experimental,
            checked = state.mobileOnly,
            onCheckedChange = actions::setMobileOnly,
            modifier = Modifier.testTag("wg_mobile_only"),
            supportingText = stringResource(R.string.wg_setting_use_on_mobile_desc),
            icon = Icons.Filled.PhoneAndroid,
            enabled = !state.taskerOwned,
        )
        if (!state.ssidSupported) return@SettingsSection
        SectionDivider()
        SwitchRow(
            title = stringResource(R.string.wg_setting_ssid_title) + " " + experimental,
            checked = state.ssidEnabled,
            onCheckedChange = actions::setSsidEnabled,
            modifier = Modifier.testTag("wg_ssid"),
            supportingText = stringResource(R.string.wg_setting_ssid_desc, stringResource(R.string.lbl_ssids)),
            icon = Icons.Filled.Wifi,
            enabled = !state.taskerOwned,
        )
        if (state.taskerOwned || !state.ssidEnabled) return@SettingsSection
        SettingRow(
            title = stringResource(R.string.proxy_page_wg_ssid_edit),
            modifier = Modifier.testTag("wg_ssid_edit"),
            supportingText = state.ssidText.ifEmpty { stringResource(R.string.proxy_page_wg_ssid_all) },
            icon = Icons.Filled.Edit,
            onClick = actions::editSsids,
        ) { SettingTrailing.Chevron() }
        if (state.ssidPermissionError) {
            SettingRow(
                title = stringResource(R.string.ssid_permission_error_action),
                modifier = Modifier.testTag("wg_ssid_permission"),
                supportingText = state.ssidPermissionText,
                icon = Icons.Filled.WarningAmber,
                onClick = actions::fixSsidPermission,
            ) { SettingTrailing.Chevron() }
        }
        if (state.ssidLocationError) {
            SettingRow(
                title = stringResource(R.string.ssid_location_error_action),
                modifier = Modifier.testTag("wg_ssid_location"),
                supportingText = stringResource(R.string.ssid_location_error),
                icon = Icons.Filled.LocationOff,
                onClick = actions::fixSsidLocation,
            ) { SettingTrailing.Chevron() }
        }
    }
}
