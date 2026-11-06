/*
 * Copyright 2023 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.activity

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.celzero.bravedns.R
import com.celzero.bravedns.customdownloader.IpInfoDownloader
import com.celzero.bravedns.data.SsidItem
import com.celzero.bravedns.database.CustomDomainDAO
import com.celzero.bravedns.database.CustomIpDao
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.database.WgConfigFilesImmutable
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager.ID_WG_BASE
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.service.WireguardManager.INVALID_CONF_ID
import com.celzero.bravedns.service.WireguardManager.WG_UPTIME_THRESHOLD
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.ui.activity.NetworkLogsActivity.Companion.RULES_SEARCH_ID_WIREGUARD
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.proxy.ProxyConfirm
import com.celzero.bravedns.ui.compose.proxy.ProxyDetailLogic
import com.celzero.bravedns.ui.compose.proxy.ProxyRulesController
import com.celzero.bravedns.ui.compose.proxy.WgDetailActions
import com.celzero.bravedns.ui.compose.proxy.WgDetailScreen
import com.celzero.bravedns.ui.compose.proxy.WgExitIpUi
import com.celzero.bravedns.ui.compose.proxy.WgPageLogic
import com.celzero.bravedns.ui.compose.proxy.WgPageState
import com.celzero.bravedns.ui.compose.proxy.WgPeerUi
import com.celzero.bravedns.ui.dialog.WgAddPeerDialog
import com.celzero.bravedns.ui.dialog.WgHopDialog
import com.celzero.bravedns.ui.dialog.WgSsidDialog
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_PROXY
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.bravedns.util.SnackbarHelper
import com.celzero.bravedns.util.SsidPermissionManager
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.openAndroidAppInfo
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.viewmodel.ProxyAppsMappingViewModel
import com.celzero.bravedns.wireguard.Peer
import com.celzero.bravedns.wireguard.WgHopManager
import com.celzero.firestack.backend.IPMetadata
import com.celzero.firestack.backend.RouterStats
import inet.ipaddr.HostName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * WireGuard config page (Advanced and Simple / one-wg configs). Layout: ui/compose/proxy
 * (WgDetailScreen on ProxyDetailScaffold); all reads / writes are here. The enable switch runs
 * the same checks as the config list (WgConfigAdapter). The peer, relay (hop) and SSID editors
 * are still the ported dialogs (WgAddPeerDialog, WgHopDialog, WgSsidDialog).
 */
class WgConfigDetailActivity : BaseActivity(), WgDetailActions {
    private val persistentState by inject<PersistentState>()
    private val eventLogger by inject<EventLogger>()
    private val ipDao by inject<CustomIpDao>()
    private val domainDao by inject<CustomDomainDAO>()

    private val mappingViewModel: ProxyAppsMappingViewModel by viewModel()

    private lateinit var state: WgPageState
    private lateinit var rules: ProxyRulesController

    private var configId: Int = INVALID_CONF_ID
    private val peers: MutableList<Peer> = mutableListOf()
    private var wgType: WgType = WgType.DEFAULT
    private var pendingSsidEditor = false
    private var waitingForLocationSettings = false
    private var appCountObserved = false

    /** Coroutine that polls VpnController every [STATS_POLL_MS] ms. */
    private var statsJob: Job? = null

    // SSID permission handling
    private val ssidPermissionCallback = object : SsidPermissionManager.PermissionCallback {
        override fun onPermissionsGranted() {
            Logger.vv(LOG_TAG_UI, "ssid-callback permissions granted")
            // Check if we need background location too
            if (isAtleastQ() && !SsidPermissionManager.hasBackgroundLocationPermission(this@WgConfigDetailActivity)) {
                showLocationDisclosureDialog {
                    SsidPermissionManager.requestBackgroundLocationPermission(this@WgConfigDetailActivity)
                }
            } else {
                ui { continuePendingSsidEditor() }
            }
        }

        override fun onPermissionsDenied() {
            Logger.vv(LOG_TAG_UI, "ssid-callback permissions denied")
            pendingSsidEditor = false
            ui { showPermissionDeniedDialog() }
        }

        override fun onPermissionsRationale() {
            Logger.vv(LOG_TAG_UI, "ssid-callback permissions rationale")
            ui {
                showLocationDisclosureDialog {
                    SsidPermissionManager.requestSsidPermissions(this@WgConfigDetailActivity)
                }
            }
        }
    }

    companion object {
        private const val CLIPBOARD_PUBLIC_KEY_LBL = "Public Key"
        const val INTENT_EXTRA_WG_TYPE = "WIREGUARD_TUNNEL_TYPE"

        /** Polling interval for live stats. */
        private const val STATS_POLL_MS = 2_000L
        private const val PROXY_REFRESH_MS = 5_000L
    }

    enum class WgType(val value: Int) {
        DEFAULT(0),
        ONE_WG(1);

        fun isOneWg() = this == ONE_WG

        fun isDefault() = this == DEFAULT

        companion object {
            fun fromInt(value: Int): WgType {
                return entries.firstOrNull { it.value == value }
                    ?: throw IllegalArgumentException("Invalid WgType value: $value")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = Themes.isActivityLightTheme(isDarkThemeOn(), persistentState.theme)
            window.isNavigationBarContrastEnforced = false
        }
        configId = intent.getIntExtra(WgConfigEditorActivity.INTENT_EXTRA_WG_ID, INVALID_CONF_ID)
        wgType = WgType.fromInt(intent.getIntExtra(INTENT_EXTRA_WG_TYPE, WgType.DEFAULT.value))

        state = WgPageState(WireguardManager.getConfigName(configId), wgType.isOneWg())
        rules = ProxyRulesController.create(this, ID_WG_BASE + configId, lifecycleScope, ipDao, domainDao,
            notify = { id, args -> Utilities.showToastUiCentered(this, getString(id, *args), Toast.LENGTH_SHORT) },
            log = { kind, details -> logRule(eventLogger, kind, details) },
        )
        rules.start()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    rules.refreshProxies()
                    delay(PROXY_REFRESH_MS)
                }
            }
        }
        setContent { RethinkTheme { WgDetailScreen(state, this, rules) } }
    }

    override fun onResume() {
        super.onResume()
        init()
        startStatsPolling(configId)
        if (waitingForLocationSettings) {
            waitingForLocationSettings = false
            if (pendingSsidEditor && SsidPermissionManager.isLocationEnabled(this)) {
                continuePendingSsidEditor()
            } else {
                pendingSsidEditor = false
                setupSsidSection(WireguardManager.getConfigFilesById(configId))
            }
        }
    }

    override fun onPause() {
        super.onPause()
        cancelStatsJob()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Logger.vv(LOG_TAG_UI, "onRequestPermissionsResult: requestCode=$requestCode")
        SsidPermissionManager.handlePermissionResult(requestCode, permissions, grantResults, ssidPermissionCallback)
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    private fun init() {
        if (!VpnController.hasTunnel()) {
            Logger.i(LOG_TAG_PROXY, "VPN not active, config may not be available")
            Utilities.showToastUiCentered(this, getString(R.string.settings_socks5_vpn_disabled_error), Toast.LENGTH_LONG)
            finish()
            return
        }

        val config = WireguardManager.getConfigById(configId)
        val mapping = WireguardManager.getConfigFilesById(configId)
        if (config == null) {
            showInvalidConfigDialog()
            return
        }

        val mode = getString(if (wgType.isOneWg()) R.string.rt_list_simple_btn_txt else R.string.lbl_advanced)
        state.header = state.header.copy(
            name = config.getName(),
            detail = mode + getString(R.string.app_details_summary_separator) + getString(R.string.proxy_page_wg_id, config.getId()),
            enabled = mapping?.isActive == true,
        )
        if (mapping != null) {
            state.catchAll = mapping.isCatchAll
            state.lockdown = mapping.isLockdown
            state.mobileOnly = mapping.useOnlyOnMetered
        }
        observeAppsCount()
        setupSsidSection(mapping)
        io { updateStatusUi(config.getId()) }
        io { updateFlag(configId) }
        prefillConfig()
        refreshHopStatus()
        if (persistentState.downloadIpInfo && mapping?.isActive == true) {
            resolveClientIps(configId)
        } else {
            state.exitIp = null
        }
        state.taskerOwned = persistentState.wgTaskerAutomationEnabled
    }

    private fun observeAppsCount() {
        if (appCountObserved) return
        appCountObserved = true
        mappingViewModel.getAppCountById(ID_WG_BASE + configId).observe(this) { state.appCount = it ?: 0 }
    }

    private suspend fun updateStatusUi(id: Int) {
        val config = WireguardManager.getConfigFilesById(id)
        val cid = ID_WG_BASE + id
        val vpnOn = VpnController.isOn()
        if (config?.isActive != true) {
            uiCtx {
                state.header = state.header.copy(enabled = false, status = ProxyDetailLogic.status(false, vpnOn, null), statusText = null)
            }
            return
        }
        val statusPair = VpnController.getProxyStatusById(cid)
        val stats = VpnController.getProxyStats(cid)
        val ps = UIUtils.ProxyStatus.entries.find { it.id == statusPair.first }
        val dnsStatusId = if (persistentState.splitDns) VpnController.getDnsStatus(cid) else null
        val dnsFailing = dnsStatusId != null && isDnsError(dnsStatusId)
        val failing = ProxyDetailLogic.isFailing(System.currentTimeMillis(), stats?.since ?: 0L, stats?.lastOK ?: 0L, WG_UPTIME_THRESHOLD)
        uiCtx {
            val text = when {
                // check for dns failure cases
                dnsFailing && ps != UIUtils.ProxyStatus.TPU -> getString(R.string.status_failing).replaceFirstChar(Char::titlecase)
                statusPair.first != null -> getStatusText(ps, getHandshakeTime(stats).toString(), stats, statusPair.second)
                statusPair.second.isEmpty() -> getString(R.string.status_waiting).replaceFirstChar(Char::titlecase)
                else -> getString(R.string.status_waiting).replaceFirstChar(Char::titlecase) + " (${statusPair.second})"
            }
            state.header = state.header.copy(
                enabled = true,
                status = ProxyDetailLogic.status(true, vpnOn, statusPair.first, failing, dnsFailing),
                statusText = text,
            )
        }
    }

    private fun cancelStatsJob() {
        if (statsJob?.isActive == true) statsJob?.cancel()
        statsJob = null
    }

    private fun startStatsPolling(id: Int) {
        cancelStatsJob()
        statsJob = io {
            while (true) {
                try {
                    updateStatusUi(id)
                } catch (e: Exception) {
                    Logger.w(LOG_TAG_UI, "stats poll error: ${e.message}")
                }
                delay(STATS_POLL_MS)
            }
        }
    }

    /** Resolves the exit IPs (and their [IPMetadata]) on IO; a spinner shows meanwhile. */
    private fun resolveClientIps(id: Int) {
        state.exitIp = WgExitIpUi(loading = true)
        io {
            var ip4Meta: IPMetadata? = null
            var ip6Meta: IPMetadata? = null
            try {
                val client = VpnController.getWgClientInfoById(ID_WG_BASE + id)
                ip4Meta = client?.iP4()
                ip6Meta = client?.iP6()
                Logger.v(LOG_TAG_UI, "client ips resolved for $id: ip4: $ip4Meta, ip6: $ip6Meta")
            } catch (e: Exception) {
                Logger.w(LOG_TAG_UI, "failed to resolve client ips: ${e.message}")
            }
            uiCtx { state.exitIp = WgExitIpUi(false, detail(ip4Meta), detail(ip6Meta)) }
        }
    }

    private fun detail(m: IPMetadata?): String? =
        m?.let { WgPageLogic.ipDetail(it.ip, it.asn, it.asnOrg, it.asnDom, it.city, it.cc, it.lat, it.lon) }

    private fun stripPort(addr: String): String = IpRulesManager.splitHostPort(addr).first

    private suspend fun updateFlag(id: Int) {
        val addr = VpnController.getProxyAddrById(ID_WG_BASE + id)
        var ip: String? = addr?.split(",")?.firstOrNull()?.trim()?.let { stripPort(it) }
        if (ip.isNullOrBlank()) {
            val host = WireguardManager.getConfigById(configId)?.getPeers()?.getOrNull(0)
                ?.getEndpoint()?.orElse(null)?.let { stripPort(it) }
            if (!host.isNullOrBlank() && HostName(host).asAddress() != null) ip = host
        }
        val flag = if (ip.isNullOrBlank()) null else {
            IpInfoDownloader.getIpInfo(ip)?.countryCode?.takeIf { it.isNotEmpty() }?.let { Utilities.getFlag(it) }
        }
        uiCtx { state.header = state.header.copy(flag = flag) }
    }

    private fun isDnsError(statusId: Int?): Boolean {
        if (statusId == null) return true
        val s = Transaction.Status.fromId(statusId)
        return s == Transaction.Status.BAD_QUERY || s == Transaction.Status.BAD_RESPONSE || s == Transaction.Status.NO_RESPONSE || s == Transaction.Status.SEND_FAIL || s == Transaction.Status.CLIENT_ERROR || s == Transaction.Status.INTERNAL_ERROR || s == Transaction.Status.TRANSPORT_ERROR
    }

    private fun getStatusText(status: UIUtils.ProxyStatus?, handshakeTime: String?, stats: RouterStats?, errMsg: String?): String {
        if (status == null) {
            val txt = if (!errMsg.isNullOrEmpty()) getString(R.string.status_waiting) + " ($errMsg)" else getString(R.string.status_waiting)
            return txt.replaceFirstChar(Char::titlecase)
        }
        // no need to check for lastOk/since for paused wg
        if (status == UIUtils.ProxyStatus.TPU) {
            return getString(UIUtils.getProxyStatusStringRes(status.id)).replaceFirstChar(Char::titlecase)
        }
        val lastOk = stats?.lastOK ?: 0L
        val since = stats?.since ?: 0L
        if (ProxyDetailLogic.isFailing(System.currentTimeMillis(), since, lastOk, WG_UPTIME_THRESHOLD)) {
            return getString(R.string.status_failing).replaceFirstChar(Char::titlecase)
        }
        val baseText = getString(UIUtils.getProxyStatusStringRes(status.id)).replaceFirstChar(Char::titlecase)
        return if (stats?.lastOK != 0L && !handshakeTime.isNullOrEmpty()) {
            getString(R.string.about_version_install_source, baseText, handshakeTime)
        } else {
            baseText
        }
    }

    private fun getHandshakeTime(stats: RouterStats?): CharSequence {
        if (stats == null || stats.lastOK == 0L) return ""
        return DateUtils.getRelativeTimeSpanString(stats.lastOK, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
    }

    private fun showInvalidConfigDialog() {
        state.confirm = ProxyConfirm(
            title = getString(R.string.lbl_wireguard),
            message = getString(R.string.config_invalid_desc),
            confirmLabel = getString(R.string.fapps_info_dialog_positive_btn),
            onConfirm = { finish() },
            neutralLabel = getString(R.string.lbl_delete),
            onNeutral = {
                io {
                    WireguardManager.deleteConfig(configId)
                    uiCtx { finish() }
                }
            },
            cancelable = false,
        )
    }

    private fun prefillConfig() {
        val config = WireguardManager.getConfigById(configId) ?: return
        val wgInterface = config.getInterface() ?: return
        if (wgType.isOneWg()) {
            // show dns servers if in one-wg mode
            state.dns = WgPageLogic.dnsLine(
                wgInterface.dnsServers.map { it.hostAddress ?: "" },
                wgInterface.dnsSearchDomains.toList(),
            )
        } else {
            state.publicKey = wgInterface.getKeyPair().getPublicKey().base64()
        }
        setPeers(config.getPeers() ?: emptyList())
    }

    private fun setPeers(list: List<Peer>) {
        peers.clear()
        peers.addAll(list)
        state.peers = list.mapIndexed { i, p ->
            WgPeerUi(
                index = i,
                publicKey = p.getPublicKey().base64(),
                endpoint = p.getEndpoint().orElse(null),
                allowedIps = p.getAllowedIps().takeIf { it.isNotEmpty() }?.joinToString { it.toString() },
                keepalive = p.persistentKeepalive.orElse(null)?.let { UIUtils.getDurationInHumanReadableFormat(this, it) },
            )
        }
    }

    private fun reloadPeers() {
        io {
            val p = WireguardManager.getPeers(configId)
            uiCtx { setPeers(p) }
        }
    }

    // --- actions ---

    override fun back() {
        onBackPressedDispatcher.onBackPressed()
    }

    override fun toggle(on: Boolean) {
        if (!wgType.isDefault()) return
        val cfg = WireguardManager.getConfigFilesById(configId)
        if (cfg == null) {
            showInvalidConfigInfoDialog()
            return
        }
        state.header = state.header.copy(busy = true)
        io {
            if (on) enableWgIfPossible(cfg) else disableWgIfPossible(cfg)
            uiCtx { state.header = state.header.copy(busy = false) }
            updateStatusUi(configId)
        }
    }

    // same checks as WgConfigAdapter.disableWgIfPossible
    private suspend fun disableWgIfPossible(cfg: WgConfigFilesImmutable) {
        if (!VpnController.hasTunnel()) {
            uiCtx { toast(R.string.settings_socks5_vpn_disabled_error, Toast.LENGTH_LONG) }
            return
        }
        if (WireguardManager.canDisableConfig(cfg)) {
            WireguardManager.disableConfig(cfg)
            logEvent("Wireguard disable", "Disabled WireGuard config: ${cfg.name} (id: ${cfg.id})")
            uiCtx { state.exitIp = null }
        } else {
            val msg = if (cfg.isCatchAll) R.string.wireguard_disable_failure else R.string.wireguard_disable_failure_relay
            uiCtx { toast(msg, Toast.LENGTH_LONG) }
        }
    }

    // same checks as WgConfigAdapter.enableWgIfPossible
    private suspend fun enableWgIfPossible(cfg: WgConfigFilesImmutable) {
        if (!VpnController.hasTunnel()) {
            uiCtx { toast(R.string.settings_socks5_vpn_disabled_error, Toast.LENGTH_LONG) }
            return
        }
        // checks only for app mode, so show appropriate error message if not enabled
        if (!WireguardManager.canEnableProxy()) {
            uiCtx { toast(R.string.wireguard_dns_mode_conflict, Toast.LENGTH_LONG) }
            return
        }
        if (WireguardManager.oneWireGuardEnabled()) {
            uiCtx { toast(R.string.wireguard_one_wg_active_conflict, Toast.LENGTH_LONG) }
            return
        }
        if (!WireguardManager.isValidConfig(cfg.id)) {
            uiCtx { showInvalidConfigInfoDialog() }
            return
        }
        // key overlap is a warning: the user can proceed or keep the config disabled
        if (!WireguardManager.canEnableProxy(cfg.id)) {
            Logger.i(LOG_TAG_PROXY, "wg keys overlap with an active config: ${cfg.id}")
            uiCtx { showDuplicateKeysWarningDialog { io { enableWg(cfg) } } }
            return
        }
        enableWg(cfg)
    }

    private suspend fun enableWg(cfg: WgConfigFilesImmutable) {
        WireguardManager.enableConfig(cfg)
        logEvent("Wireguard enable", "Enabled WireGuard config: ${cfg.name} (id: ${cfg.id})")
        uiCtx { init() }
        updateStatusUi(configId)
    }

    override fun copyPublicKey() {
        val key = state.publicKey ?: return
        UIUtils.clipboardCopy(this, key, CLIPBOARD_PUBLIC_KEY_LBL)
        toast(R.string.public_key_copy_toast_msg, Toast.LENGTH_SHORT)
    }

    override fun editConfig() {
        val intent = Intent(this, WgConfigEditorActivity::class.java)
        intent.putExtra(WgConfigEditorActivity.INTENT_EXTRA_WG_ID, configId)
        intent.putExtra(INTENT_EXTRA_WG_TYPE, wgType.value)
        startActivity(intent)
    }

    override fun addPeer() {
        openPeerDialog(null)
    }

    override fun editPeer(index: Int) {
        peers.getOrNull(index)?.let { openPeerDialog(it) }
    }

    private fun openPeerDialog(peer: Peer?) {
        val themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
        val dialog = WgAddPeerDialog(this, themeId, configId, peer)
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        dialog.setOnDismissListener { reloadPeers() }
    }

    override fun deletePeer(index: Int) {
        val peer = peers.getOrNull(index) ?: return
        val delText = getString(R.string.two_argument_space, getString(R.string.config_delete_dialog_title), getString(R.string.lbl_peer))
        state.confirm = ProxyConfirm(
            title = delText,
            message = getString(R.string.config_delete_dialog_desc),
            confirmLabel = delText,
            onConfirm = {
                io {
                    WireguardManager.deletePeer(configId, peer)
                    val p = WireguardManager.getPeers(configId)
                    uiCtx { setPeers(p) }
                }
            },
            dismissLabel = getString(R.string.lbl_cancel),
            destructive = true,
        )
    }

    override fun openApps() {
        if (!state.appsEnabled) return
        val proxyName = WireguardManager.getConfigName(configId)
        startActivity(WgIncludeAppsActivity.newIntent(this, ID_WG_BASE + configId, proxyName))
    }

    override fun setLockdown(on: Boolean) {
        state.lockdown = on
        io { WireguardManager.updateLockdownConfig(configId, on) }
    }

    override fun setCatchAll(on: Boolean) {
        io {
            if (!VpnController.hasTunnel()) {
                uiCtx { toast(R.string.settings_socks5_vpn_disabled_error, Toast.LENGTH_LONG) }
                return@io
            }
            // checks only for app mode, so show appropriate error message if not enabled
            if (!WireguardManager.canEnableProxy()) {
                uiCtx { toast(R.string.wireguard_dns_mode_conflict, Toast.LENGTH_LONG) }
                return@io
            }
            if (WireguardManager.oneWireGuardEnabled()) {
                Logger.w(LOG_TAG_PROXY, "one wireGuard is already enabled")
                uiCtx { toast(R.string.wireguard_one_wg_active_conflict, Toast.LENGTH_LONG) }
                return@io
            }
            if (on && WireguardManager.isSocks5AllAppsOn()) {
                uiCtx { toast(R.string.wireguard_socks5_all_apps_conflict, Toast.LENGTH_LONG) }
                return@io
            }
            if (WireguardManager.getConfigFilesById(configId) == null) {
                Logger.e(LOG_TAG_PROXY, "updateCatchAll: config not found for $configId")
                uiCtx { showInvalidConfigInfoDialog() }
                return@io
            }
            // key overlap is a warning: the user can proceed or keep catch-all disabled
            if (on && !WireguardManager.canEnableProxy(configId)) {
                uiCtx { showDuplicateKeysWarningDialog { applyCatchAll(true) } }
                return@io
            }
            applyCatchAll(on)
        }
    }

    private fun applyCatchAll(enabled: Boolean) {
        state.catchAll = enabled
        io {
            WireguardManager.updateCatchAllConfig(configId, enabled)
            logEvent(
                "WireGuard Catch All apps",
                "User ${if (enabled) "enabled" else "disabled"} catch all apps for WireGuard config with id $configId"
            )
            updateStatusUi(configId)
        }
    }

    // Warns that the config's keys are already in use by another active config.
    // Proceed goes ahead anyway; OK (or cancel) keeps it off.
    private fun showDuplicateKeysWarningDialog(onProceed: () -> Unit) {
        if (isFinishing) return
        state.confirm = ProxyConfirm(
            title = getString(R.string.wireguard_duplicate_keys_warning_title),
            message = getString(R.string.wireguard_duplicate_keys_warning),
            confirmLabel = getString(R.string.dns_info_positive),
            onConfirm = {},
            neutralLabel = getString(R.string.lbl_proceed),
            onNeutral = onProceed,
        )
    }

    private fun showInvalidConfigInfoDialog() {
        if (isFinishing) return
        state.confirm = ProxyConfirm(
            title = getString(R.string.wireguard_invalid_config_title),
            message = getString(R.string.wireguard_invalid_config_message),
            confirmLabel = getString(R.string.lbl_dismiss),
            onConfirm = {},
        )
    }

    override fun openHop() {
        val mapping = WireguardManager.getConfigFilesById(configId)
        if (mapping == null) {
            showInvalidConfigDialog()
            return
        }
        if (!(mapping.isActive || mapping.isLockdown || mapping.isCatchAll)) {
            toast(R.string.wireguard_no_config_msg, Toast.LENGTH_SHORT)
            return
        }
        io {
            val sid = ID_WG_BASE + configId
            if (WgHopManager.isAlreadyHop(sid)) {
                uiCtx {
                    Utilities.showToastUiCentered(this, getString(R.string.hop_error_toast_msg_1, getString(R.string.lbl_hop)), Toast.LENGTH_LONG)
                }
                return@io
            }
            val hopId = WgHopManager.getHop(configId)
            Logger.d(LOG_TAG_PROXY, "hop result: $hopId")
            val iid = WgPageLogic.configIdOf(hopId, ID_WG_BASE, INVALID_CONF_ID)
            val hopables = WgHopManager.getHopableWgs(configId)
            uiCtx {
                if (hopables.isEmpty()) {
                    toast(R.string.hop_error_toast_msg_2, Toast.LENGTH_LONG)
                    return@uiCtx
                }
                if (WireguardManager.getConfigById(configId) == null) {
                    toast(R.string.config_invalid_desc, Toast.LENGTH_SHORT)
                    return@uiCtx
                }
                val themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
                val hopDialog = WgHopDialog(this, themeId, configId, hopables, iid)
                hopDialog.setCanceledOnTouchOutside(false)
                hopDialog.setOnDismissListener { refreshHopStatus() }
                hopDialog.show()
            }
        }
    }

    private fun refreshHopStatus() {
        io {
            val hopIdStr = WgHopManager.getHop(configId)
            val hopName = if (hopIdStr.isNotEmpty()) {
                WireguardManager.getConfigById(WgPageLogic.configIdOf(hopIdStr, ID_WG_BASE, INVALID_CONF_ID))?.getName()
            } else {
                null
            }
            uiCtx { state.hopName = hopName }
        }
    }

    override fun setMobileOnly(on: Boolean) {
        // wg automation is owned by an external app (tasker et al)
        if (persistentState.wgTaskerAutomationEnabled) return
        if (WgHopManager.isAlreadyHop(ID_WG_BASE + configId)) {
            toast(R.string.wg_mobile_only_hop_err, Toast.LENGTH_LONG)
            return
        }
        state.mobileOnly = on
        io { WireguardManager.updateUseOnMobileNetworkConfig(configId, on) }
        logEvent(
            "WireGuard Use on Mobile Networks",
            "User ${if (on) "enabled" else "disabled"} use on mobile networks for WireGuard config with id $configId"
        )
        if (on && persistentState.enableStabilityDependentSettings()) {
            SnackbarHelper.showStabilityProgram(window.decorView, persistentState)
        }
    }

    override fun openLogs() {
        val intent = Intent(this, NetworkLogsActivity::class.java)
        intent.putExtra(Constants.SEARCH_QUERY, RULES_SEARCH_ID_WIREGUARD + ID_WG_BASE + configId)
        startActivity(intent)
    }

    override fun deleteConfig() {
        val delText = getString(R.string.two_argument_space, getString(R.string.config_delete_dialog_title), getString(R.string.lbl_wireguard))
        state.confirm = ProxyConfirm(
            title = delText,
            message = getString(R.string.config_delete_dialog_desc),
            confirmLabel = delText,
            onConfirm = {
                io {
                    WireguardManager.deleteConfig(configId)
                    uiCtx {
                        toast(R.string.config_add_success_toast, Toast.LENGTH_SHORT)
                        finish()
                    }
                    logEvent("Delete WireGuard config", "User deleted WireGuard config with id $configId")
                }
            },
            dismissLabel = getString(R.string.lbl_cancel),
            destructive = true,
        )
    }

    // --- SSID ---

    private fun setupSsidSection(cfg: WgConfigFilesImmutable?) {
        if (persistentState.wgTaskerAutomationEnabled) {
            // tasker owns wg automation; the rows are shown inert
            state.taskerOwned = true
            state.ssidPermissionError = false
            state.ssidLocationError = false
            return
        }
        state.taskerOwned = false
        if (cfg == null) {
            state.ssidEnabled = false
            state.ssidPermissionError = false
            state.ssidLocationError = false
            Logger.w(LOG_TAG_UI, "setupSsidSection: cfg is null for $configId")
            return
        }
        if (!SsidPermissionManager.isDeviceSupported(this)) {
            state.ssidSupported = false
            Logger.w(LOG_TAG_UI, "setupSsidSection: device not supported for SSID feature")
            return
        }
        state.ssidSupported = true
        state.ssidEnabled = cfg.ssidEnabled
        applySsids(SsidItem.parseStorageList(cfg.ssids))
    }

    private fun applySsids(items: List<SsidItem>) {
        state.ssidText = items.joinToString(", ") { "${it.name} (${it.type.getDisplayName(this)})" }
        val (perm, loc) = WgPageLogic.ssidErrors(
            state.ssidEnabled,
            items.isNotEmpty(),
            SsidPermissionManager.hasRequiredPermissions(this),
            SsidPermissionManager.isLocationEnabled(this),
        )
        state.ssidPermissionError = perm
        state.ssidLocationError = loc
        if (perm) state.ssidPermissionText = SsidPermissionManager.getPermissionExplanation(this)
    }

    override fun setSsidEnabled(on: Boolean) {
        if (persistentState.wgTaskerAutomationEnabled) return
        state.ssidEnabled = on
        logEvent("SSID feature toggled", "ConfigId: $configId, Enabled: $on")
        if (on && persistentState.enableStabilityDependentSettings()) {
            SnackbarHelper.showStabilityProgram(window.decorView, persistentState)
        }
        io {
            WireguardManager.updateSsidEnabled(configId, on)
            // an empty saved list means all SSIDs and needs no location access
            val list = SsidItem.parseStorageList(WireguardManager.getConfigFilesById(configId)?.ssids.orEmpty())
            uiCtx { applySsids(list) }
        }
    }

    override fun editSsids() {
        requestSsidEditor()
    }

    override fun fixSsidPermission() {
        openAndroidAppInfo(this, packageName)
    }

    override fun fixSsidLocation() {
        SsidPermissionManager.requestLocationEnable(this)
    }

    private fun openSsidDialog() {
        val currentSsids = WireguardManager.getConfigFilesById(configId)?.ssids.orEmpty()
        val themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
        val ssidDialog = WgSsidDialog(this, themeId, currentSsids) { newSsids ->
            io {
                WireguardManager.updateSsids(configId, newSsids)
                uiCtx { setupSsidSection(WireguardManager.getConfigFilesById(configId)) }
            }
        }
        ssidDialog.setCanceledOnTouchOutside(false)
        ssidDialog.show()
        ssidDialog.setOnDismissListener { setupSsidSection(WireguardManager.getConfigFilesById(configId)) }
    }

    private fun showLocationDisclosureDialog(onContinue: () -> Unit) {
        state.confirm = ProxyConfirm(
            title = getString(R.string.location_disclosure_title),
            message = getString(R.string.location_disclosure_message),
            confirmLabel = getString(R.string.location_disclosure_positive),
            onConfirm = onContinue,
            dismissLabel = getString(R.string.location_disclosure_negative),
            onDismiss = { pendingSsidEditor = false },
        )
    }

    private fun showLocationEnableDialog() {
        state.confirm = ProxyConfirm(
            title = getString(R.string.location_disclosure_title),
            message = getString(R.string.location_disclosure_message),
            confirmLabel = getString(R.string.location_disclosure_positive),
            onConfirm = {
                waitingForLocationSettings = true
                SsidPermissionManager.requestLocationEnable(this)
                Logger.vv(LOG_TAG_UI, "Prompted user to enable location services, opening settings...")
            },
            dismissLabel = getString(R.string.location_disclosure_negative),
            onDismiss = { pendingSsidEditor = false },
        )
    }

    private fun requestSsidEditor() {
        pendingSsidEditor = true
        when {
            !SsidPermissionManager.hasForegroundPermissions(this) ->
                showLocationDisclosureDialog { SsidPermissionManager.requestSsidPermissions(this) }
            isAtleastQ() && !SsidPermissionManager.hasBackgroundLocationPermission(this) ->
                showLocationDisclosureDialog { SsidPermissionManager.requestBackgroundLocationPermission(this) }
            else -> continuePendingSsidEditor()
        }
    }

    private fun continuePendingSsidEditor() {
        if (!pendingSsidEditor || isFinishing || isDestroyed) return
        if (!SsidPermissionManager.hasRequiredPermissions(this)) {
            pendingSsidEditor = false
            return
        }
        if (!SsidPermissionManager.isLocationEnabled(this)) {
            showLocationEnableDialog()
            return
        }
        pendingSsidEditor = false
        openSsidDialog()
    }

    private fun showPermissionDeniedDialog() {
        state.confirm = ProxyConfirm(
            title = getString(R.string.ssid_permission_error_action),
            message = SsidPermissionManager.getPermissionExplanation(this),
            confirmLabel = getString(R.string.ssid_permission_error_action),
            onConfirm = { SsidPermissionManager.openAppSettings(this) },
            dismissLabel = getString(R.string.lbl_cancel),
        )
    }

    // --- helpers ---

    private fun toast(id: Int, length: Int) {
        Utilities.showToastUiCentered(this, getString(id), length)
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            if (!isFinishing && !isDestroyed) f()
        }
    }

    private fun io(f: suspend () -> Unit): Job {
        return lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private fun ui(f: suspend () -> Unit) {
        if (isFinishing || isDestroyed) return
        lifecycleScope.launch(Dispatchers.Main) { f() }
    }

    private fun logEvent(msg: String, details: String) {
        eventLogger.log(EventType.PROXY_SWITCH, Severity.LOW, msg, EventSource.UI, false, details)
    }
}
