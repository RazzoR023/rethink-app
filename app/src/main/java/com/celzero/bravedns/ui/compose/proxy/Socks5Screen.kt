/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.proxy

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.design.RethinkSpacing
import com.celzero.bravedns.ui.compose.design.SectionDivider
import com.celzero.bravedns.ui.compose.design.SegmentOption
import com.celzero.bravedns.ui.compose.design.SegmentedChoice
import com.celzero.bravedns.ui.compose.design.SettingRow
import com.celzero.bravedns.ui.compose.design.SettingTrailing
import com.celzero.bravedns.ui.compose.design.SettingsSection
import com.celzero.bravedns.ui.compose.design.SwitchRow

/** Observable state of the SOCKS5 page; Socks5ConfigActivity does the I/O. */
class Socks5PageState(name: String) {
    var header by mutableStateOf(ProxyHeaderUi(ProxyType.SOCKS5, name))
    /** the stored endpoint; null until loaded */
    var saved by mutableStateOf<Socks5Saved?>(null)
    var form by mutableStateOf(Socks5Form())
    var allApps by mutableStateOf(false)
    var appCount by mutableIntStateOf(0)
    /** forwarder app name, null for none */
    var forwarder by mutableStateOf<String?>(null)
    var loopback by mutableStateOf(false)
    /** Android's "Block connections without VPN": the forwarder is always looped back */
    var vpnLockdown by mutableStateOf(false)
    /** proxy lockdown: the forwarder app can't be set */
    var proxyLockdown by mutableStateOf(false)
    /** the forwarder chooser: app names (first = none) and the checked one */
    var forwarderChoices by mutableStateOf<Pair<List<String>, Int>?>(null)
    var discardDialog by mutableStateOf(false)

    val dirty: Boolean get() = saved?.let { Socks5FormLogic.isDirty(form, it) } ?: false
    val apps: ProxyAppsUi get() = ProxyDetailLogic.socks5Apps(appCount, header.enabled, allApps)
}

interface Socks5Actions {
    fun back()
    fun toggle(on: Boolean)
    fun edit(form: Socks5Form)
    fun save()
    fun revert()
    fun setAllApps(allApps: Boolean)
    fun openApps()
    fun openLogs()
    fun openForwarderPicker()
    fun forwarderPicked(index: Int)
    fun dismissForwarderPicker()
    fun setLoopback(on: Boolean)
    fun openVpnSettings()
    fun discard()
    fun dismissDiscard()
}

@Composable
fun Socks5Screen(state: Socks5PageState, actions: Socks5Actions, rules: ProxyRulesController) {
    ProxyDetailScaffold(
        title = stringResource(R.string.proxy_page_title_socks5),
        header = state.header,
        onBack = actions::back,
        onToggle = actions::toggle,
    ) {
        Socks5Config(state, actions)
        ProxyAppsSection(state.apps, actions::openApps, top = {
            SegmentedChoice(
                listOf(
                    SegmentOption(true, stringResource(R.string.proxy_page_mode_all)),
                    SegmentOption(false, stringResource(R.string.proxy_page_mode_selected)),
                ),
                selected = state.allApps,
                onSelect = actions::setAllApps,
                modifier = Modifier
                    .padding(horizontal = RethinkSpacing.lg, vertical = RethinkSpacing.sm)
                    .testTag("socks5_mode"),
            )
            if (!state.allApps) {
                Text(
                    stringResource(R.string.socks5_apps_desc),
                    Modifier.padding(horizontal = RethinkSpacing.screenGutter, vertical = RethinkSpacing.xs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        })
        ProxyRulesSection(rules, stringResource(R.string.proxy_page_name_socks5))
        Socks5Options(state, actions)
        SettingsSection(title = stringResource(R.string.proxy_page_section_more)) {
            SettingRow(
                title = stringResource(R.string.lbl_logs),
                modifier = Modifier.testTag("proxy_logs"),
                supportingText = stringResource(R.string.proxy_page_logs_desc),
                icon = Icons.AutoMirrored.Filled.ListAlt,
                onClick = actions::openLogs,
            ) { SettingTrailing.Chevron() }
        }
    }
    ProxyRulesHosts(rules)
    state.forwarderChoices?.let { (names, checked) ->
        ForwarderDialog(names, checked, actions::forwarderPicked, actions::dismissForwarderPicker)
    }
    if (state.discardDialog) {
        AlertDialog(
            onDismissRequest = actions::dismissDiscard,
            title = { Text(stringResource(R.string.socks5_discard_title)) },
            confirmButton = { TextButton(actions::discard, Modifier.testTag("proxy_discard")) { Text(stringResource(R.string.socks5_discard)) } },
            dismissButton = { TextButton(actions::dismissDiscard) { Text(stringResource(R.string.lbl_cancel)) } },
        )
    }
}

@Composable
private fun Socks5Config(state: Socks5PageState, actions: Socks5Actions) {
    val form = state.form
    SettingsSection(title = stringResource(R.string.proxy_page_section_config)) {
        Column(
            Modifier.padding(horizontal = RethinkSpacing.lg, vertical = RethinkSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(RethinkSpacing.xs),
        ) {
            ProxyTextField(
                form.host, { actions.edit(form.copy(host = it).clearErrors()) },
                stringResource(R.string.settings_socks5_dialog_hint_ip),
                Modifier.testTag("socks5_host"), error = form.hostError,
            )
            ProxyTextField(
                form.port, { actions.edit(form.copy(port = it).clearErrors()) },
                stringResource(R.string.settings_socks5_dialog_hint_port),
                Modifier.testTag("socks5_port"), error = form.portError, keyboardType = KeyboardType.Number,
            )
            ProxyTextField(
                form.userName, { actions.edit(form.copy(userName = it).clearErrors()) },
                stringResource(R.string.settings_socks5_dialog_hint_username),
                Modifier.testTag("socks5_user"), keyboardType = KeyboardType.Text,
            )
            ProxyTextField(
                form.password, { actions.edit(form.copy(password = it).clearErrors()) },
                stringResource(R.string.settings_socks5_dialog_hint_password),
                Modifier.testTag("socks5_password"), password = true,
            )
        }
        ProxyFormActions(state.dirty, actions::revert, actions::save)
    }
}

@Composable
private fun Socks5Options(state: Socks5PageState, actions: Socks5Actions) {
    SettingsSection(title = stringResource(R.string.socks5_options_title)) {
        ForwarderRow(state.forwarder, state.proxyLockdown, actions::openForwarderPicker)
        SectionDivider()
        LoopbackRow(state.loopback, state.vpnLockdown, actions::setLoopback, actions::openVpnSettings)
    }
}

/** The app running the proxy server on this device (never routed back into it). */
@Composable
internal fun ForwarderRow(
    name: String?,
    proxyLockdown: Boolean,
    onClick: () -> Unit,
    @StringRes description: Int = R.string.socks5_forwarder_desc,
) {
    SettingRow(
        title = stringResource(R.string.socks5_forwarder_title),
        modifier = Modifier.testTag("proxy_forwarder"),
        supportingText = (name ?: stringResource(R.string.proxy_page_forwarder_none)) + "\n" + stringResource(description),
        icon = Icons.Filled.Apps,
        // under proxy lockdown a tap explains why it can't change (as before)
        enabled = !proxyLockdown,
        onClick = onClick,
    ) { SettingTrailing.Chevron() }
}

/** "Loopback proxy forwarder apps"; forced on (and explained) in Android's VPN lockdown. */
@Composable
internal fun LoopbackRow(loopback: Boolean, vpnLockdown: Boolean, onChange: (Boolean) -> Unit, onVpnSettings: () -> Unit) {
    SwitchRow(
        title = stringResource(R.string.settings_exclude_proxy_apps_heading),
        checked = vpnLockdown || loopback,
        onCheckedChange = onChange,
        modifier = Modifier.testTag("proxy_loopback"),
        supportingText = stringResource(R.string.settings_exclude_proxy_apps_desc),
        enabled = !vpnLockdown,
    )
    if (vpnLockdown) {
        TextButton(onVpnSettings, Modifier.padding(horizontal = RethinkSpacing.sm)) {
            Text(stringResource(R.string.socks5_lockdown_loopback))
        }
    }
}

@Composable
internal fun ForwarderDialog(names: List<String>, checked: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("proxy_forwarder_dialog"),
        title = { Text(stringResource(R.string.socks5_forwarder_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(names.size) { i -> RadioLine(names[i], i == checked) { onPick(i) } }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.lbl_cancel)) } },
    )
}
