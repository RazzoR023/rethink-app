/*
 * Copyright 2020 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.ui.bottomsheet

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.Spanned
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.FirewallRuleset
import com.celzero.bravedns.service.FirewallRuleset.Companion.getFirewallRule
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager.isNotLocalAndRpnProxy
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.activity.AppInfoActivity
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.rules.RuleSheetActions
import com.celzero.bravedns.ui.compose.rules.RuleSheetLogic
import com.celzero.bravedns.ui.compose.rules.RuleSheetState
import com.celzero.bravedns.ui.compose.trafficlogs.AppRuleDialog
import com.celzero.bravedns.ui.compose.trafficlogs.ConnectionDetailsCallbacks
import com.celzero.bravedns.ui.compose.trafficlogs.ConnectionDetailsScreen
import com.celzero.bravedns.ui.compose.trafficlogs.ConnectionDetailsUi
import com.celzero.bravedns.ui.compose.trafficlogs.LogInfoDialogState
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_FIREWALL
import com.celzero.bravedns.util.Protocol
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.htmlToSpannedText
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.getIcon
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.google.common.collect.HashMultimap
import com.google.common.collect.Multimap
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.core.component.KoinComponent
import java.util.Locale

/**
 * Network logs -> a connection: its app, verdict, destination and the rules for it (the app's
 * firewall rule; the app's IP and domain rules, each with "Route via"). UI in
 * [ConnectionDetailsScreen]; IP / domain rules through [RuleSheetActions].
 */
class ConnTrackerBottomSheet : BaseBottomSheetDialogFragment(), KoinComponent, ConnectionDetailsCallbacks {

    private val persistentState by inject<PersistentState>()
    private val eventLogger by inject<EventLogger>()

    private var info: ConnectionTracker? = null
    private val ui = ConnectionDetailsUi()
    private val rules = RuleSheetState()
    private lateinit var ruleActions: RuleSheetActions

    companion object {
        const val INSTANCE_STATE_IPDETAILS = "IPDETAILS"
        const val INSTANCE_OBJ_RETHINKLOG = "RETHINKLOG"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        ruleActions = ruleSheetActions(rules, eventLogger, "IP / domain rule changed")
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { RethinkTheme { ConnectionDetailsScreen(ui, rules, ruleActions, this@ConnTrackerBottomSheet) } }
        }
    }

    override fun getTheme(): Int = Themes.getBottomSheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    override fun onStart() {
        super.onStart()
        transparentSheetFrame()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.window?.let { Themes.applyBottomSheetSystemBarAppearance(it, isDarkThemeOn(), persistentState.theme) }
        info = Gson().fromJson(arguments?.getString(INSTANCE_STATE_IPDETAILS), ConnectionTracker::class.java)
        initView()
    }

    private fun isDarkThemeOn(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun initView() {
        val ci = info
        if (ci == null) {
            Logger.w(LOG_TAG_FIREWALL, "ip-details missing: initView called before onViewCreated?")
            dismiss()
            return
        }
        ui.ip = ci.ipAddress
        ui.flag = ci.flag
        ui.appName = ci.appName
        ui.domainLine = ci.dnsQuery?.takeIf { it.isNotEmpty() } ?: UIUtils.getCountryNameFromFlag(ci.flag)
        updateAppDetails(ci)
        ui.summary = summaryLine(ci)
        updateVerdict(ci)
        displaySummaryDetails(ci)
        // the app's ip rule and, with a dns query, its domain rule; each with "Route via"
        if (rules.rows.isEmpty()) ruleActions.load(RuleSheetLogic.connectionRows(ci.uid, ci.ipAddress, ci.dnsQuery))
    }

    override fun onResume() {
        super.onResume()
        val uid = info?.uid
        if (uid == null) {
            Logger.w(LOG_TAG_FIREWALL, "ip-details missing: initView called before onViewCreated?")
            dismiss()
            return
        }
        // the app rule may have changed elsewhere (e.g. app details)
        io {
            val appStatus = FirewallManager.appStatus(uid)
            val connStatus = FirewallManager.connectionStatus(uid)
            uiCtx { updateFirewallRulesUi(appStatus, connStatus) }
        }
    }

    private fun summaryLine(ci: ConnectionTracker): String {
        val protocol = Protocol.getProtocolName(ci.protocol).name
        val time = DateUtils.getRelativeTimeSpanString(ci.timeStamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
        val protocolDetails = "$protocol/${ci.port}"
        return if (ci.isBlocked) getString(R.string.bsct_conn_desc_blocked, protocolDetails, time)
        else getString(R.string.bsct_conn_desc_allowed, protocolDetails, time)
    }

    private fun updateVerdict(ci: ConnectionTracker) {
        ui.verdict = verdictText(ci).orEmpty()
        ui.verdictNegative = ci.isBlocked || isInvalidProxyDetails()
        ui.verdictIcon = FirewallRuleset.getRulesIcon(ci.blockedByRule)
    }

    private fun verdictText(ci: ConnectionTracker): String? {
        val rule = ci.blockedByRule
        if (rule.isBlank()) return getString(R.string.firewall_rule_no_rule)
        val isIpnProxy = isNotLocalAndRpnProxy(ci.proxyDetails)
        // TODO: below code is not required, remove it in future (20/03/2023)
        return if (rule.contains(FirewallRuleset.RULE2G.id)) {
            getFirewallRule(FirewallRuleset.RULE2G.id)?.title?.let { getString(it) }
        } else if (ci.proxyDetails.isNotEmpty() && isIpnProxy) {
            // add the proxy id to the verdict if available
            getString(R.string.two_argument_colon, getString(FirewallRuleset.RULE12.title), ci.proxyDetails)
        } else if (isInvalidProxyDetails()) {
            getString(getFirewallRule(FirewallRuleset.RULE18.id)?.title ?: R.string.firewall_rule_no_rule)
        } else {
            val allowReason = if (!ci.isBlocked) FirewallRuleset.getAllowReason(rule) else null
            allowReason?.title?.let { getString(R.string.bsct_allow_reason, getString(it)) }
                ?: getFirewallRule(rule)?.title?.let { getString(it) }
        }
    }

    private fun isInvalidProxyDetails(): Boolean {
        val isIpnProxy = isNotLocalAndRpnProxy(info?.proxyDetails.orEmpty())
        val rule = info?.blockedByRule ?: return false
        val isRuleAddedAsProxy = getFirewallRule(rule)?.id == FirewallRuleset.RULE12.id
        // when the conn is marked as proxied with id from flow, but the returned summary
        // doesn't have the proxy details. change the rule from proxied to error (RULE1C)
        return isRuleAddedAsProxy && (info?.proxyDetails.isNullOrEmpty() || !isIpnProxy)
    }

    private fun updateAppDetails(ci: ConnectionTracker) {
        val context = requireContext().applicationContext
        io {
            val appNames = FirewallManager.getAppNamesByUid(ci.uid)
            val pkgName = appNames.firstOrNull()?.let { FirewallManager.getPackageNameByAppName(it) }
            val icon = pkgName?.let { getIcon(context, it, ci.appName) }
            uiCtx {
                if (appNames.isEmpty()) {
                    // apps which are not available in cache are treated as non app.
                    handleNonApp()
                    return@uiCtx
                }
                val appName = if (appNames.size >= 2) {
                    getString(R.string.ctbs_app_other_apps, appNames[0], (appNames.size - 1).toString())
                } else appNames[0]
                ui.isApp = true
                ui.appName = appName
                ui.appRuleTitle = appName
                if (icon != null) ui.appIcon = icon
            }
        }
    }

    private fun handleNonApp() {
        // unknown uids have no meaningful app rule; show the universal "block unknown" switch
        ui.isApp = false
        ui.blockUnknown = persistentState.getBlockUnknownConnections()
        ui.appName = info?.appName.orEmpty()
    }

    private fun displaySummaryDetails(ci: ConnectionTracker) {
        // show connId and message if the log level is less than DEBUG
        val debug = Logger.LoggerLevel.fromId(persistentState.goLoggerLevel.toInt())?.isLessThanOrEqualTo(Logger.LoggerLevel.DEBUG) == true
        ui.message = if (debug) "${ci.proxyDetails}; ${ci.rpid}; ${ci.connId}; ${ci.message}; ${ci.synack}" else ci.message
        val connType = if (ConnectionTracker.ConnType.get(ci.connType).isMetered()) {
            getString(R.string.two_argument_space, getString(R.string.ada_app_metered), getString(R.string.symbol_currency))
        } else {
            getString(R.string.two_argument_space, getString(R.string.ada_app_unmetered), getString(R.string.symbol_global))
        }
        if (ci.message.isEmpty() && ci.duration == 0 && ci.downloadBytes == 0L && ci.uploadBytes == 0L) {
            ui.meta = listOf(connType)
            return
        }
        val download = getString(R.string.symbol_download, Utilities.humanReadableByteCount(ci.downloadBytes, true))
        val upload = getString(R.string.symbol_upload, Utilities.humanReadableByteCount(ci.uploadBytes, true))
        ui.meta = listOf(upload, download, connType)
        io {
            val hasCid = VpnController.hasCid(ci.connId, ci.uid)
            uiCtx {
                val duration = if (hasCid) {
                    getString(R.string.two_argument_space, getString(R.string.lbl_active), getString(R.string.symbol_green_circle))
                } else {
                    val d = UIUtils.getDurationInHumanReadableFormat(requireContext(), ci.duration)
                    getString(R.string.two_argument_space, d.ifEmpty { getString(R.string.symbol_hyphen) }, getString(R.string.symbol_clock))
                }
                ui.meta = listOf(duration, upload, download, connType)
            }
        }
    }

    // ---- callbacks ----

    override fun openApp() {
        val uid = info?.uid ?: return
        io {
            val ai = FirewallManager.getAppInfoByUid(uid)
            uiCtx {
                // case: app is uninstalled but still available in RethinkDNS database
                if (ai == null || uid == Constants.INVALID_UID) {
                    showToastUiCentered(requireContext(), getString(R.string.ct_bs_app_info_error), Toast.LENGTH_SHORT)
                    return@uiCtx
                }
                val context = requireContext()
                dismiss()
                val intent = Intent(context, AppInfoActivity::class.java)
                intent.putExtra(AppInfoActivity.INTENT_UID, uid)
                context.startActivity(intent)
            }
        }
    }

    override fun showVerdictInfo() = showFirewallRulesDialog(info?.blockedByRule)

    override fun setBlockUnknown(block: Boolean) {
        Logger.d(LOG_TAG_FIREWALL, "Unknown app, universal firewall settings(block unknown app): $block")
        ui.blockUnknown = block
        persistentState.setBlockUnknownConnections(block)
        logEvent("Universal firewall setting changed", "Block unknown apps: $block")
    }

    /** Full app-rule option list (the historical spinner order) as a single-choice dialog. */
    override fun openAppRules() {
        val uid = info?.uid ?: return
        val labels = FirewallManager.getLabel(requireContext()).toList()
        val fallback = getString(R.string.lbl_app_rules)
        io {
            val a = FirewallManager.appStatus(uid)
            val c = FirewallManager.connectionStatus(uid)
            // title the dialog with the app the rules apply to, falling back to the generic label
            val title = FirewallManager.getAppNamesByUid(uid).firstOrNull()
                ?: info?.appName?.takeIf { it.isNotBlank() }
                ?: fallback
            uiCtx { ui.appRuleDialog = AppRuleDialog(title, labels, appRuleLabelIndex(a, c)) }
        }
    }

    override fun selectAppRule(index: Int) {
        onAppRuleSelected(
            FirewallManager.FirewallStatus.getStatusByLabel(index),
            FirewallManager.ConnectionStatus.getStatusByLabel(index),
        )
    }

    /**
     * Validates and applies an app-level firewall rule. Skips when the requested state already
     * matches the persisted state; refuses exclude in VPN lockdown and for unknown packages
     * (re-rendering the persisted state, with a toast).
     */
    private fun onAppRuleSelected(fStatus: FirewallManager.FirewallStatus, connStatus: FirewallManager.ConnectionStatus) {
        val uid = info?.uid ?: return
        io {
            val a = FirewallManager.appStatus(uid)
            val c = FirewallManager.connectionStatus(uid)
            // no change, prev state and current state are same
            if (a == fStatus && c == connStatus) return@io
            if (VpnController.isVpnLockdown() && fStatus.isExclude()) {
                uiCtx {
                    updateFirewallRulesUi(a, c)
                    showToastUiCentered(requireContext(), getString(R.string.hsf_exclude_error), Toast.LENGTH_LONG)
                }
                return@io
            }
            // exclude needs a known package to be meaningful
            if (FirewallManager.isUnknownPackage(uid) && fStatus.isExclude()) {
                uiCtx {
                    updateFirewallRulesUi(a, c)
                    showToastUiCentered(requireContext(), getString(R.string.exclude_no_package_err_toast), Toast.LENGTH_LONG)
                }
                return@io
            }
            Logger.i(LOG_TAG_FIREWALL, "Change in firewall rule for app uid: $uid, firewall status: $fStatus, conn status: $connStatus")
            applyFirewallRule(fStatus, connStatus)
        }
    }

    /**
     * Index into [FirewallManager.getLabel] for the given state; keeps the historical spinner
     * ordering (allow, block, block-unmetered, block-metered, isolate, bypass dns+firewall,
     * bypass universal, exclude).
     */
    private fun appRuleLabelIndex(fStatus: FirewallManager.FirewallStatus, connStatus: FirewallManager.ConnectionStatus): Int =
        when (fStatus) {
            FirewallManager.FirewallStatus.NONE -> when (connStatus) {
                FirewallManager.ConnectionStatus.ALLOW -> 0
                FirewallManager.ConnectionStatus.BOTH -> 1
                FirewallManager.ConnectionStatus.UNMETERED -> 2
                FirewallManager.ConnectionStatus.METERED -> 3
            }
            FirewallManager.FirewallStatus.ISOLATE -> 4
            FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL -> 5
            FirewallManager.FirewallStatus.BYPASS_UNIVERSAL -> 6
            FirewallManager.FirewallStatus.EXCLUDE -> 7
        }

    private fun updateFirewallRulesUi(firewallStatus: FirewallManager.FirewallStatus, connStatus: FirewallManager.ConnectionStatus) {
        ui.appRuleState = firewallStatusText(firewallStatus, connStatus)
    }

    /** Same status mapping as AppInfoActivity#getFirewallText. */
    private fun firewallStatusText(aStat: FirewallManager.FirewallStatus, cStat: FirewallManager.ConnectionStatus): String =
        when (aStat) {
            FirewallManager.FirewallStatus.NONE -> when {
                cStat.mobileData() -> getString(R.string.ada_app_status_block_md)
                cStat.wifi() -> getString(R.string.ada_app_status_block_wifi)
                cStat.allow() -> getString(R.string.ada_app_status_allow)
                cStat.blocked() -> getString(R.string.ada_app_status_block)
                else -> getString(R.string.ada_app_status_unknown)
            }
            FirewallManager.FirewallStatus.EXCLUDE -> getString(R.string.ada_app_status_exclude)
            FirewallManager.FirewallStatus.BYPASS_UNIVERSAL -> getString(R.string.ada_app_status_whitelist)
            FirewallManager.FirewallStatus.ISOLATE -> getString(R.string.ada_app_status_isolate)
            FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL -> getString(R.string.ada_app_status_bypass_dns_firewall)
        }

    private fun showFirewallRulesDialog(blockedRule: String?) {
        if (blockedRule == null) return
        val headingText: String
        var descText: Spanned
        if (blockedRule.contains(FirewallRuleset.RULE2G.id)) {
            val group: Multimap<String, String> = HashMultimap.create()
            val blocklists = if (info?.blocklists?.isEmpty() == true) {
                val startIndex = blockedRule.indexOfFirst { it == '|' }
                blockedRule.substring(startIndex + 1).split(",")
            } else {
                info?.blocklists?.split(",") ?: listOf()
            }
            blocklists.forEach {
                val items = it.split(":")
                if (items.count() <= 1) return@forEach
                group.put(items[0], items[1])
            }
            descText = formatText(group)
            val groupCount = group.keys().distinct().count()
            headingText = if (groupCount > 1) {
                "${group.keys().firstOrNull()} +${groupCount - 1}"
            } else if (groupCount == 1) {
                group.keys().firstOrNull() ?: getString(R.string.firewall_rule_no_rule)
            } else {
                val tempDesc = getFirewallRule(FirewallRuleset.RULE2G.id)?.let { getString(it.desc) }
                    ?: getString(R.string.firewall_rule_no_rule_desc)
                descText = htmlToSpannedText(tempDesc)
                getFirewallRule(FirewallRuleset.RULE2G.id)?.let { getString(it.title) } ?: getString(R.string.firewall_rule_no_rule)
            }
        } else {
            headingText = getFirewallRule(blockedRule)?.let { getString(it.title) } ?: getString(R.string.firewall_rule_no_rule)
            val tempDesc = getFirewallRule(blockedRule)?.let { getString(it.desc) } ?: getString(R.string.firewall_rule_no_rule_desc)
            descText = htmlToSpannedText(tempDesc)
        }
        ui.infoDialog = LogInfoDialogState(headingText, descText, FirewallRuleset.getRulesIcon(blockedRule))
    }

    private fun formatText(groupNames: Multimap<String, String>): Spanned {
        var text = ""
        groupNames.keys().distinct().forEach {
            val heading = it.replaceFirstChar { a -> if (a.isLowerCase()) a.titlecase(Locale.getDefault()) else a.toString() }
            text += getString(R.string.dns_btm_sheet_dialog_message, heading, groupNames.get(it).count().toString(), TextUtils.join(", ", groupNames.get(it)))
        }
        text = text.replace(",", ", ")
        return htmlToSpannedText(text)
    }

    private suspend fun applyFirewallRule(firewallStatus: FirewallManager.FirewallStatus, connStatus: FirewallManager.ConnectionStatus) {
        val uid = info?.uid ?: return
        // persistence + audit log run on IO unconditionally; only the UI update is gated on the
        // view lifecycle (gating the persistence dropped the change when the view was destroyed
        // while the fragment remained attached)
        applyFirewallRuleWithLifecycle(
            isViewAlive = { isAdded && view != null },
            persistAndLog = {
                io {
                    FirewallManager.updateFirewallStatus(uid, firewallStatus, connStatus)
                    logEvent("Firewall rule changed", "UID: $uid, FirewallStatus: ${firewallStatus.name}, ConnectionStatus: ${connStatus.name}")
                }
            },
            renderUi = { updateFirewallRulesUi(firewallStatus, connStatus) }
        )
    }

    private fun logEvent(msg: String, details: String) {
        eventLogger.log(EventType.FW_RULE_MODIFIED, Severity.LOW, msg, EventSource.UI, false, details)
    }

    private fun io(f: suspend () -> Unit) = lifecycleScope.launch(Dispatchers.IO) { f() }

    private suspend fun uiCtx(f: suspend () -> Unit) =
        withContext(Dispatchers.Main) {
            if (isAdded && view != null) f()
        }
}

/**
 * Applies a firewall rule with UI-lifecycle awareness.
 *
 * Regression guard for the "view destroyed while the fragment remains
 * attached" sequence: [persistAndLog] (persistence + audit log) must always
 * run, on an IO dispatcher — it must never be gated behind the view check,
 * which silently dropped the user's rule change. Only [renderUi] is skipped
 * when [isViewAlive] reports the view is gone.
 */
internal suspend fun applyFirewallRuleWithLifecycle(
    isViewAlive: () -> Boolean,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    persistAndLog: suspend () -> Unit,
    renderUi: () -> Unit
) {
    persistAndLog()
    withContext(mainDispatcher) {
        if (!isViewAlive()) return@withContext
        renderUi()
    }
}
