/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.proxy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.design.RethinkSpacing
import com.celzero.bravedns.ui.compose.design.SectionDivider
import com.celzero.bravedns.ui.compose.design.SettingRow
import com.celzero.bravedns.ui.compose.design.SettingTrailing
import com.celzero.bravedns.ui.compose.design.SettingsSection

/** Observable state of the HTTP(S) CONNECT proxy page; HttpProxyActivity does the I/O. */
class HttpPageState(name: String) {
    var header by mutableStateOf(ProxyHeaderUi(ProxyType.HTTP, name))
    /** the stored url ("" when never set); null until loaded */
    var savedUrl by mutableStateOf<String?>(null)
    var url by mutableStateOf("")
    var urlError by mutableStateOf<Int?>(null)
    var forwarder by mutableStateOf<String?>(null)
    var loopback by mutableStateOf(false)
    var vpnLockdown by mutableStateOf(false)
    var proxyLockdown by mutableStateOf(false)
    var forwarderChoices by mutableStateOf<Pair<List<String>, Int>?>(null)
    var discardDialog by mutableStateOf(false)

    val loaded: Boolean get() = savedUrl != null
    val dirty: Boolean get() = savedUrl?.let { HttpFormLogic.isDirty(url, it) } ?: false
    val apps: ProxyAppsUi get() = ProxyDetailLogic.httpApps()
}

interface HttpProxyActions {
    fun back()
    fun toggle(on: Boolean)
    fun editUrl(url: String)
    fun save()
    fun revert()
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
fun HttpProxyScreen(state: HttpPageState, actions: HttpProxyActions, rules: ProxyRulesController) {
    ProxyDetailScaffold(
        title = stringResource(R.string.proxy_page_title_http),
        header = state.header,
        onBack = actions::back,
        onToggle = actions::toggle,
    ) {
        SettingsSection(title = stringResource(R.string.proxy_page_section_config)) {
            Column(
                Modifier.padding(horizontal = RethinkSpacing.lg, vertical = RethinkSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(RethinkSpacing.xs),
            ) {
                ProxyTextField(
                    state.url, actions::editUrl,
                    stringResource(R.string.proxy_page_http_url),
                    Modifier.testTag("http_url"),
                    error = state.urlError,
                    supporting = stringResource(R.string.http_proxy_dialog_desc),
                )
            }
            ProxyFormActions(state.dirty, actions::revert, actions::save)
        }
        ProxyAppsSection(state.apps, onOpenApps = {})
        ProxyRulesSection(rules, stringResource(R.string.proxy_page_name_http))
        SettingsSection(title = stringResource(R.string.proxy_page_section_options)) {
            ForwarderRow(
                state.forwarder, state.proxyLockdown, actions::openForwarderPicker,
                description = R.string.proxy_page_forwarder_desc_http,
            )
            SectionDivider()
            LoopbackRow(state.loopback, state.vpnLockdown, actions::setLoopback, actions::openVpnSettings)
        }
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
