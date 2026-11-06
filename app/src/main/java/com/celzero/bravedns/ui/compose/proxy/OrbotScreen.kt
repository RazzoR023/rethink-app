/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.proxy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.design.ChoiceOption
import com.celzero.bravedns.ui.compose.design.RethinkSpacing
import com.celzero.bravedns.ui.compose.design.SectionDivider
import com.celzero.bravedns.ui.compose.design.SettingRow
import com.celzero.bravedns.ui.compose.design.SettingTrailing
import com.celzero.bravedns.ui.compose.design.SettingsSection
import com.celzero.bravedns.ui.compose.design.SingleChoiceList

/** Observable state of the Orbot page; OrbotProxyActivity does the I/O. */
class OrbotPageState(name: String) {
    var header by mutableStateOf(ProxyHeaderUi(ProxyType.ORBOT, name))
    var installed by mutableStateOf(true)
    /** the running mode (NONE when off) */
    var mode by mutableStateOf(OrbotMode.NONE)
    /** the mode the switch starts; the last one used, TCP by default */
    var lastMode by mutableStateOf(OrbotMode.SOCKS5)
    var modes by mutableStateOf(OrbotLogic.modes(atLeastQ = true))
    var appCount by mutableIntStateOf(0)
    /** proxy lockdown: Orbot can't be used */
    var lockdown by mutableStateOf(false)
    /** Orbot is starting (waiting for its broadcast) */
    var connecting by mutableStateOf(false)
    var infoDialog by mutableStateOf(false)
    /** after stopping: "Orbot may still run"; true when DNS also goes to Orbot */
    var stopDialog by mutableStateOf<Boolean?>(null)

    val apps: ProxyAppsUi get() = ProxyDetailLogic.orbotApps(appCount)
}

interface OrbotActions {
    fun back()
    fun toggle(on: Boolean)
    fun pickMode(mode: OrbotMode)
    fun openOrbotApp()
    fun install()
    fun openWebsite()
    fun openApps()
    fun openLogs()
    fun showInfo(show: Boolean)
    fun dismissStopDialog()
    fun stopDialogOpenOrbot()
    fun stopDialogChangeDns()
}

@Composable
fun OrbotScreen(state: OrbotPageState, actions: OrbotActions, rules: ProxyRulesController) {
    ProxyDetailScaffold(
        title = stringResource(R.string.proxy_page_title_orbot),
        header = state.header,
        onBack = actions::back,
        onToggle = actions::toggle,
        headerNotes = {
            if (state.lockdown) ProxyNote(stringResource(R.string.proxy_page_orbot_lockdown), warning = true)
        },
    ) {
        SettingsSection(title = stringResource(R.string.proxy_page_section_config)) {
            if (!state.installed) {
                Text(
                    stringResource(R.string.proxy_page_orbot_not_installed) + ". " + stringResource(R.string.orbot_install_dialog_message),
                    Modifier.padding(horizontal = RethinkSpacing.screenGutter, vertical = RethinkSpacing.sm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = RethinkSpacing.lg, vertical = RethinkSpacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.sm),
                ) {
                    FilledTonalButton(actions::install, Modifier.weight(1f).testTag("orbot_install")) {
                        Text(stringResource(R.string.proxy_page_orbot_install))
                    }
                    OutlinedButton(actions::openWebsite, Modifier.weight(1f)) {
                        Text(stringResource(R.string.proxy_page_orbot_website))
                    }
                }
                SectionDivider()
            }
            Text(
                stringResource(R.string.proxy_page_orbot_mode),
                Modifier.padding(horizontal = RethinkSpacing.screenGutter, vertical = RethinkSpacing.sm),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            val selected = if (state.mode == OrbotMode.NONE) state.lastMode else state.mode
            val usable = state.installed && !state.lockdown && !state.connecting
            SingleChoiceList(
                state.modes.filter { it != OrbotMode.NONE }.map { m ->
                    ChoiceOption(m, stringResource(modeTitle(m)), stringResource(modeDescription(m)), enabled = usable)
                },
                selected = selected,
                onSelect = actions::pickMode,
                modifier = Modifier.testTag("orbot_mode"),
            )
            SectionDivider()
            SettingRow(
                title = stringResource(R.string.proxy_page_orbot_open),
                supportingText = stringResource(R.string.proxy_page_orbot_open_desc),
                icon = Icons.AutoMirrored.Filled.OpenInNew,
                enabled = state.installed,
                onClick = actions::openOrbotApp,
            )
            SectionDivider()
            SettingRow(
                title = stringResource(R.string.proxy_page_orbot_about),
                icon = Icons.Outlined.Info,
                onClick = { actions.showInfo(true) },
            )
        }
        ProxyAppsSection(state.apps, actions::openApps)
        ProxyRulesSection(rules, stringResource(R.string.orbot))
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
    if (state.infoDialog) {
        AlertDialog(
            onDismissRequest = { actions.showInfo(false) },
            title = { Text(stringResource(R.string.orbot_title)) },
            text = { Text(orbotExplanation(), Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton({ actions.showInfo(false) }) { Text(stringResource(R.string.lbl_dismiss)) } },
        )
    }
    state.stopDialog?.let { dns ->
        AlertDialog(
            onDismissRequest = actions::dismissStopDialog,
            title = { Text(stringResource(R.string.orbot_stop_dialog_title)) },
            text = {
                val msg = stringResource(R.string.orbot_stop_dialog_message)
                Text(if (dns) msg + "\n" + stringResource(R.string.orbot_stop_dialog_dns_message) else msg)
            },
            confirmButton = { TextButton(actions::dismissStopDialog) { Text(stringResource(R.string.lbl_dismiss)) } },
            dismissButton = {
                Row {
                    if (dns) TextButton(actions::stopDialogChangeDns) { Text(stringResource(R.string.orbot_stop_dialog_neutral)) }
                    TextButton(actions::stopDialogOpenOrbot) { Text(stringResource(R.string.orbot_stop_dialog_negative)) }
                }
            },
        )
    }
}

@Composable
private fun orbotExplanation(): String {
    val raw = stringResource(R.string.orbot_explanation)
    // the string carries html tags (<u>, <i>) for the old dialog; show it as plain text
    return androidx.core.text.HtmlCompat.fromHtml(raw.replace("\n", "<br /><br />"), androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY)
        .toString().trim()
}

internal fun modeTitle(m: OrbotMode): Int = when (m) {
    OrbotMode.SOCKS5 -> R.string.orbot_socks5
    OrbotMode.HTTP -> R.string.orbot_http
    OrbotMode.BOTH -> R.string.orbot_both
    OrbotMode.NONE -> R.string.orbot_none
}

internal fun modeDescription(m: OrbotMode): Int = when (m) {
    OrbotMode.SOCKS5 -> R.string.orbot_socks5_desc
    OrbotMode.HTTP -> R.string.orbot_http_desc
    OrbotMode.BOTH -> R.string.orbot_both_desc
    OrbotMode.NONE -> R.string.orbot_none_desc
}
