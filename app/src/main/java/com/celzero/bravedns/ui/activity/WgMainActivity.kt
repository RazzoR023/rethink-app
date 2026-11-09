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

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.celzero.bravedns.R
import com.celzero.bravedns.customdownloader.IpInfoDownloader
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.database.WgConfigFilesImmutable
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.ProxyManager.ID_WG_BASE
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.service.WireguardManager.WG_UPTIME_THRESHOLD
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.ui.activity.WgConfigDetailActivity.Companion.INTENT_EXTRA_WG_TYPE
import com.celzero.bravedns.ui.activity.WgConfigEditorActivity.Companion.INTENT_EXTRA_WG_ID
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.proxy.ProxyConfirm
import com.celzero.bravedns.ui.compose.proxy.ProxyDetailLogic
import com.celzero.bravedns.ui.compose.wireguard.WgListActions
import com.celzero.bravedns.ui.compose.wireguard.WgListItem
import com.celzero.bravedns.ui.compose.wireguard.WgListLogic
import com.celzero.bravedns.ui.compose.wireguard.WgListMode
import com.celzero.bravedns.ui.compose.wireguard.WgListScreen
import com.celzero.bravedns.ui.compose.wireguard.WgListState
import com.celzero.bravedns.ui.compose.wireguard.WgListTag
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_PROXY
import com.celzero.bravedns.util.QrCodeFromFileScanner
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.TunnelImporter
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.viewmodel.WgConfigViewModel
import com.celzero.bravedns.wireguard.WgHopManager
import com.celzero.bravedns.wireguard.WgInterface
import com.celzero.firestack.backend.RouterStats
import com.google.zxing.qrcode.QRCodeReader
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import inet.ipaddr.HostName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.util.concurrent.ConcurrentHashMap

/**
 * WireGuard config list (Simple / Advanced). Layout: ui/compose/wireguard/WgListScreen; all
 * reads and writes are here. Rows are rebuilt from WireguardManager's in-memory mappings and
 * VpnController's live stats every [POLL_MS] while the screen is started, and right away when
 * the config table changes (import, delete) or a switch is flipped.
 */
class WgMainActivity : BaseActivity(), WgListActions {
    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()
    private val eventLogger by inject<EventLogger>()

    private val wgConfigViewModel: WgConfigViewModel by viewModel()

    private lateinit var state: WgListState

    /** row order while the screen is open (see WgListLogic.stableOrder) */
    private var order: List<Int> = emptyList()
    /** requested switch state of rows whose toggle is in flight */
    private val pending = ConcurrentHashMap<Int, Boolean>()
    /** flag emoji per config id, and the ip it was looked up for */
    private val flags = ConcurrentHashMap<Int, String>()
    private val flagLookups = ConcurrentHashMap<Int, String>()
    private val refreshLock = Mutex()
    private var connectedDns: String = ""

    companion object {
        private val IMPORT_LAUNCH_INPUT = arrayOf("*/*")
        private const val POLL_MS = 1500L
        private const val TAG = "WgList"
    }

    private val tunnelFileImportResultLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { data ->
            if (data == null) return@registerForActivityResult
            val contentResolver = contentResolver ?: return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    data,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                Logger.w(LOG_TAG_PROXY, "Could not persist URI permission for $data", e)
            }
            lifecycleScope.launch {
                if (QrCodeFromFileScanner.validContentType(contentResolver, data)) {
                    try {
                        val qrCodeFromFileScanner =
                            QrCodeFromFileScanner(contentResolver, QRCodeReader())
                        val result = qrCodeFromFileScanner.scan(data)
                        Logger.i(LOG_TAG_PROXY, "result: $result, data: $data")
                        if (result != null) {
                            withContext(Dispatchers.Main) {
                                if (isFinishing || isDestroyed) return@withContext
                                Logger.i(LOG_TAG_PROXY, "result: ${result.text}")
                                TunnelImporter.importTunnel(result.text) {
                                    Utilities.showToastUiCentered(
                                        this@WgMainActivity,
                                        it.toString(),
                                        Toast.LENGTH_LONG
                                    )
                                    Logger.e(LOG_TAG_PROXY, it.toString())
                                }
                                logEvent("Wireguard import", "imported from file")
                            }
                        } else {
                            val message =
                                resources.getString(
                                    R.string.generic_error,
                                    getString(R.string.invalid_file_error)
                                )
                            Utilities.showToastUiCentered(
                                this@WgMainActivity,
                                message,
                                Toast.LENGTH_LONG
                            )
                            Logger.e(LOG_TAG_PROXY, message)
                        }
                    } catch (e: Exception) {
                        val message =
                            resources.getString(
                                R.string.generic_error,
                                getString(R.string.invalid_file_error)
                            )
                        Utilities.showToastUiCentered(
                            this@WgMainActivity,
                            message,
                            Toast.LENGTH_LONG
                        )
                        Logger.e(LOG_TAG_PROXY, e.message ?: "err tun import", e)
                    }
                } else {
                    TunnelImporter.importTunnel(contentResolver, data) {
                        Logger.e(LOG_TAG_PROXY, it.toString())
                        Utilities.showToastUiCentered(
                            this@WgMainActivity,
                            it.toString(),
                            Toast.LENGTH_LONG
                        )
                    }
                }
                refresh()
            }
        }

    private val qrImportResultLauncher =
        registerForActivityResult(ScanContract()) { result ->
            val qrCode = result.contents
            if (qrCode != null) {
                lifecycleScope.launch {
                    TunnelImporter.importTunnel(qrCode) {
                        Utilities.showToastUiCentered(
                            this@WgMainActivity,
                            it.toString(),
                            Toast.LENGTH_LONG
                        )
                        Logger.e(LOG_TAG_PROXY, it.toString())
                        logEvent("Wireguard import", "imported via QR scanner")
                    }
                    refresh()
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

        state = WgListState(
            WgListLogic.initialMode(WireguardManager.isAnyWgActive(), WireguardManager.oneWireGuardEnabled())
        )
        setContent { RethinkTheme { WgListScreen(state, this) } }

        // the table changed (import, delete, edit): rebuild now rather than at the next tick
        wgConfigViewModel.configCount().observe(this) { refresh() }
        appConfig.getConnectedDnsObservable().observe(this) { dns ->
            connectedDns = dns.orEmpty()
            refreshDnsLine()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    rebuild()
                    delay(POLL_MS)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshDnsLine()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    // --- reading ---

    private fun refresh(): Job = io { rebuild() }

    /** Rebuilds every row from the in-memory mappings and live stats; IO, posts to the UI. */
    private suspend fun rebuild() = refreshLock.withLock {
        val mode = withContext(Dispatchers.Main) { state.mode }
        val mappings = WireguardManager.getAllMappings()
        val sorted = WgListLogic.storageOrder(mappings, { it.isActive }, { it.name })
        val byId = mappings.associateBy { it.id }
        val ids = WgListLogic.stableOrder(order, sorted.map { it.id })
        order = ids
        val vpnOn = VpnController.hasTunnel()
        try {
            ProxyManager.ensureLoaded()
        } catch (e: Exception) {
            Logger.w(LOG_TAG_PROXY, "$TAG app mappings not loaded: ${e.message}")
        }
        val items = ids.mapNotNull { id -> byId[id]?.let { buildItem(it, mode, vpnOn) } }
        uiCtx {
            state.vpnOn = vpnOn
            state.hasConfigs = items.isNotEmpty()
            state.items = items
            refreshDnsLine()
        }
    }

    private suspend fun buildItem(m: WgConfigFilesImmutable, mode: WgListMode, vpnOn: Boolean): WgListItem {
        val pid = ID_WG_BASE + m.id
        val on = m.isActive && vpnOn
        val tags = mutableListOf<WgListTag>()
        var status = ProxyDetailLogic.status(false, vpnOn, null)
        var statusText: String? = null
        var uptime: String? = null
        var traffic: String? = null
        var addr: String? = null

        if (on) {
            val statusPair = VpnController.getProxyStatusById(pid)
            val stats = VpnController.getProxyStats(pid)
            val ipv = VpnController.getSupportedIpVersion(pid)
            addr = VpnController.getProxyAddrById(pid)
            // Simple always reports DNS through the config; Advanced only with split DNS
            val dnsStatusId = if (mode == WgListMode.SIMPLE || persistentState.splitDns) VpnController.getDnsStatus(pid) else null
            val dnsFailing = dnsStatusId != null && isDnsError(dnsStatusId)
            val failing = ProxyDetailLogic.isFailing(System.currentTimeMillis(), stats?.since ?: 0L, stats?.lastOK ?: 0L, WG_UPTIME_THRESHOLD)
            val ps = UIUtils.ProxyStatus.entries.find { it.id == statusPair.first }
            status = ProxyDetailLogic.status(true, vpnOn, statusPair.first, failing, dnsFailing)
            statusText = if (dnsFailing && ps != UIUtils.ProxyStatus.TPU) {
                val lastOk = relative(stats?.lastOK ?: 0L, DateUtils.SECOND_IN_MILLIS)
                val failingText = getString(R.string.status_failing).replaceFirstChar(Char::titlecase)
                if (lastOk.isEmpty()) failingText else getString(R.string.about_version_install_source, failingText, lastOk)
            } else {
                statusText(ps, stats, statusPair.second)
            }
            val since = relative(stats?.since ?: 0L, DateUtils.MINUTE_IN_MILLIS)
            uptime = if (since.isNotEmpty()) {
                getString(R.string.two_argument_space, getString(R.string.lbl_active), getString(R.string.logs_card_duration, since))
            } else {
                getString(R.string.lbl_active)
            }
            traffic = stats?.let { rxTx(it) }

            if (ipv.first) tags += WgListTag(getString(R.string.settings_ip_text_ipv4))
            if (ipv.second) tags += WgListTag(getString(R.string.settings_ip_text_ipv6))
            val hasPeers = WireguardManager.getConfigById(m.id)?.getPeers()?.isNotEmpty() == true
            if (hasPeers && VpnController.isSplitTunnelProxy(pid, ipv)) tags += WgListTag(getString(R.string.lbl_split))
        }

        if (mode == WgListMode.ADVANCED) {
            WireguardManager.getConfigById(m.id)?.getInterface()?.let {
                if (isAmneziaConfig(it)) tags += WgListTag(getString(R.string.lbl_amnezia))
            }
            val via = WgHopManager.getMapBySrc(pid)
            if (via.isNotEmpty()) {
                tags += WgListTag(getString(R.string.wg_list_tag_relayed_via, via.joinToString { hopName(it.hop) }))
            }
            val relays = WgHopManager.getMapByHop(pid)
            if (relays.isNotEmpty()) {
                tags += WgListTag(getString(R.string.wg_list_tag_relays_for, relays.joinToString { hopName(it.src) }))
            }
            if (m.isCatchAll) tags += WgListTag(getString(R.string.wg_list_tag_always_on))
            // lockdown keeps its apps off the internet while the config is off: warn then
            if (m.isLockdown) tags += WgListTag(getString(R.string.wg_list_tag_lockdown), warning = !m.isActive)
            if (m.useOnlyOnMetered) tags += WgListTag(getString(R.string.wg_list_tag_mobile_only))
            if (m.ssidEnabled) tags += WgListTag(getString(R.string.wg_list_tag_ssid))
        }

        lookupFlag(m.id, addr)
        val requested = pending[m.id]
        return WgListItem(
            id = m.id,
            name = m.name,
            flag = flags[m.id],
            checked = requested ?: on,
            busy = requested != null,
            status = status,
            statusText = statusText,
            apps = WgListLogic.apps(mode, on, m.isCatchAll, ProxyManager.getAppCountForProxy(pid)),
            uptime = uptime,
            traffic = traffic,
            tags = tags,
        )
    }

    private fun hopName(proxyId: String): String {
        val id = proxyId.removePrefix(ID_WG_BASE).toIntOrNull() ?: return proxyId
        return WireguardManager.getConfigName(id).ifEmpty { proxyId }
    }

    private fun statusText(status: UIUtils.ProxyStatus?, stats: RouterStats?, errMsg: String?): String {
        if (status == null) {
            val txt = if (!errMsg.isNullOrEmpty()) getString(R.string.status_waiting) + " ($errMsg)" else getString(R.string.status_waiting)
            return txt.replaceFirstChar(Char::titlecase)
        }
        // no need to check for lastOk/since for paused wg
        if (status == UIUtils.ProxyStatus.TPU) {
            return getString(UIUtils.getProxyStatusStringRes(status.id)).replaceFirstChar(Char::titlecase)
        }
        val lastOk = stats?.lastOK ?: 0L
        if (ProxyDetailLogic.isFailing(System.currentTimeMillis(), stats?.since ?: 0L, lastOk, WG_UPTIME_THRESHOLD)) {
            return getString(R.string.status_failing).replaceFirstChar(Char::titlecase)
        }
        val base = getString(UIUtils.getProxyStatusStringRes(status.id)).replaceFirstChar(Char::titlecase)
        val handshake = relative(lastOk, DateUtils.SECOND_IN_MILLIS)
        return if (handshake.isNotEmpty()) getString(R.string.about_version_install_source, base, handshake) else base
    }

    /** "2 min. ago" for [ts]; "" when unknown. */
    private fun relative(ts: Long, minResolution: Long): String {
        if (ts <= 0L) return ""
        return DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), minResolution, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
    }

    private fun rxTx(stats: RouterStats): String {
        val rx = getString(R.string.symbol_download, Utilities.humanReadableByteCount(stats.rx, true))
        val tx = getString(R.string.symbol_upload, Utilities.humanReadableByteCount(stats.tx, true))
        return getString(R.string.two_argument_space, tx, rx)
    }

    private fun isDnsError(statusId: Int?): Boolean {
        if (statusId == null) return true
        val s = Transaction.Status.fromId(statusId)
        return s == Transaction.Status.BAD_QUERY || s == Transaction.Status.BAD_RESPONSE || s == Transaction.Status.NO_RESPONSE || s == Transaction.Status.SEND_FAIL || s == Transaction.Status.CLIENT_ERROR || s == Transaction.Status.INTERNAL_ERROR || s == Transaction.Status.TRANSPORT_ERROR
    }

    private fun isAmneziaConfig(c: WgInterface): Boolean {
        return c.getJc().isPresent || c.getJmin().isPresent || c.getJmax().isPresent ||
            c.getH1().isPresent || c.getH2().isPresent || c.getH3().isPresent ||
            c.getH4().isPresent || c.getS1().isPresent || c.getS2().isPresent
    }

    private fun stripPort(addr: String): String = IpRulesManager.splitHostPort(addr).first

    /**
     * Looks the endpoint's country up once per ip (the live address when connected, else the
     * first peer's endpoint if it is an IP), off the poll; the next rebuild shows the flag.
     */
    private fun lookupFlag(id: Int, addr: String?) {
        var ip: String? = addr?.split(",")?.firstOrNull()?.trim()?.let { stripPort(it) }
        if (ip.isNullOrBlank()) {
            if (flags.containsKey(id)) return
            val host = WireguardManager.getConfigById(id)?.getPeers()?.getOrNull(0)
                ?.getEndpoint()?.orElse(null)?.let { stripPort(it) }
            if (!host.isNullOrBlank() && HostName(host).asAddress() != null) ip = host
        }
        if (ip.isNullOrBlank()) return
        if (flags.containsKey(id) || flagLookups[id] == ip) return
        flagLookups[id] = ip
        io {
            val cc = IpInfoDownloader.getIpInfo(ip)?.countryCode
            if (!cc.isNullOrEmpty()) {
                flags[id] = Utilities.getFlag(cc)
                rebuild()
            }
        }
    }

    /** "DNS forwarded to …": the Simple config, else the connected DNS (+ split-DNS configs). */
    private fun refreshDnsLine() {
        if (!::state.isInitialized) return
        // a config without a DNS line answers no lookups; the connected DNS does (via the tunnel)
        val active = WireguardManager.getActiveConfigs()
            .filter { WireguardManager.hasDns(it.getId()) }
            .map { it.getName() }
        val oneWgWithDns = WireguardManager.oneWireGuardEnabled() &&
            WireguardManager.getOneWireGuardProxyId()?.let { WireguardManager.hasDns(it) } == true
        val target = WgListLogic.dnsTarget(oneWgWithDns, active, connectedDns, persistentState.splitDns)
        if (target != null) state.dnsTarget = target
    }

    // --- actions ---

    override fun back() {
        onBackPressedDispatcher.onBackPressed()
    }

    override fun selectMode(mode: WgListMode) {
        val needsStop = WgListLogic.needsStopAll(mode, WireguardManager.isAnyWgActive(), WireguardManager.oneWireGuardEnabled())
        if (!needsStop) {
            state.mode = mode
            refresh()
            return
        }
        state.confirm = ProxyConfirm(
            title = getString(R.string.wireguard_disable_title),
            message = getString(R.string.wireguard_disable_message),
            confirmLabel = getString(R.string.always_on_dialog_positive),
            onConfirm = { stopAllThenSwitch(mode) },
            dismissLabel = getString(R.string.lbl_cancel),
        )
    }

    private fun stopAllThenSwitch(mode: WgListMode) {
        io {
            if (WireguardManager.canDisableAllActiveConfigs()) {
                WireguardManager.disableAllActiveConfigs()
                logEvent("Wireguard disable", "all configs from toggle switch; isOneWgToggle: ${mode == WgListMode.SIMPLE}")
                uiCtx {
                    state.mode = mode
                    refreshDnsLine()
                }
                rebuild()
            } else {
                val msg = if (WireguardManager.getActiveCatchAllConfig().isNotEmpty()) {
                    R.string.wireguard_disable_failure
                } else {
                    R.string.wireguard_disable_failure_relay
                }
                uiCtx { toast(msg, Toast.LENGTH_LONG) }
            }
        }
    }

    override fun open(id: Int) {
        if (!VpnController.hasTunnel()) {
            toast(R.string.ssv_toast_start_rethink, Toast.LENGTH_SHORT)
            return
        }
        val type = if (state.mode == WgListMode.SIMPLE) WgConfigDetailActivity.WgType.ONE_WG else WgConfigDetailActivity.WgType.DEFAULT
        val intent = Intent(this, WgConfigDetailActivity::class.java)
        intent.putExtra(INTENT_EXTRA_WG_ID, id)
        intent.putExtra(INTENT_EXTRA_WG_TYPE, type.value)
        startActivity(intent)
    }

    override fun toggle(id: Int, on: Boolean) {
        val mode = state.mode
        pending[id] = on
        state.items = state.items.map { if (it.id == id) it.copy(checked = on, busy = true) else it }
        io {
            try {
                val cfg = WireguardManager.getConfigFilesById(id)
                when {
                    cfg == null -> uiCtx { showInvalidConfigDialog() }
                    mode == WgListMode.SIMPLE && on -> enableOneWgIfPossible(cfg)
                    mode == WgListMode.SIMPLE -> disableOneWgIfPossible(cfg)
                    on -> enableWgIfPossible(cfg)
                    else -> disableWgIfPossible(cfg)
                }
            } finally {
                pending.remove(id)
                uiCtx { refreshDnsLine() }
                rebuild()
            }
        }
    }

    // Simple (one-wg): same checks as the old OneWgConfigAdapter
    private suspend fun enableOneWgIfPossible(config: WgConfigFilesImmutable) {
        if (!VpnController.hasTunnel()) {
            Logger.i(LOG_TAG_PROXY, "$TAG VPN not active, cannot enable WireGuard")
            uiCtx { toast(R.string.settings_socks5_vpn_disabled_error, Toast.LENGTH_LONG) }
            return
        }
        // checks only for app mode, so show appropriate error message if not enabled
        if (!WireguardManager.canEnableProxy()) {
            Logger.i(LOG_TAG_PROXY, "$TAG not in DNS+Firewall mode, cannot enable WireGuard")
            uiCtx { toast(R.string.wireguard_dns_mode_conflict, Toast.LENGTH_LONG) }
            return
        }
        if (WireguardManager.isSocks5AllAppsOn()) {
            Logger.i(LOG_TAG_PROXY, "$TAG socks5 routes all apps, cannot enable one-wg")
            uiCtx { toast(R.string.wireguard_socks5_all_apps_conflict, Toast.LENGTH_LONG) }
            return
        }
        if (!WireguardManager.isValidConfig(config.id)) {
            Logger.i(LOG_TAG_PROXY, "$TAG invalid WireGuard config")
            uiCtx { showInvalidConfigDialog() }
            return
        }
        // One-WireGuard is mutually exclusive: swap out another active one-wg config rather
        // than blocking the user with an error.
        var replacedOthers = false
        if (WireguardManager.isAnyOtherOneWgEnabled(config.id)) {
            Logger.i(LOG_TAG_PROXY, "$TAG another one-wg config is active; replacing it")
            WireguardManager.disableOtherOneWireGuardConfigs(config.id)
            replacedOthers = true
        }
        Logger.i(LOG_TAG_PROXY, "$TAG enabling WireGuard, id: ${config.id}")
        WireguardManager.updateOneWireGuardConfig(config.id, owg = true)
        WireguardManager.enableConfig(config.copy(oneWireGuard = true))
        if (replacedOthers) uiCtx { toast(R.string.wireguard_replaced_active_config, Toast.LENGTH_SHORT) }
        logEvent("One-WireGuard enabled", "WG ID: ${config.id}")
    }

    private suspend fun disableOneWgIfPossible(config: WgConfigFilesImmutable) {
        if (!VpnController.hasTunnel()) {
            Logger.i(LOG_TAG_PROXY, "$TAG VPN not active, cannot disable WireGuard")
            uiCtx { toast(R.string.settings_socks5_vpn_disabled_error, Toast.LENGTH_LONG) }
            return
        }
        Logger.i(LOG_TAG_PROXY, "$TAG disabling WireGuard, id: ${config.id}")
        WireguardManager.updateOneWireGuardConfig(config.id, owg = false)
        WireguardManager.disableConfig(config.copy(oneWireGuard = false))
        logEvent("One-WireGuard disabled", "WG ID: ${config.id}")
    }

    // Advanced: same checks as the old WgConfigAdapter (and WgConfigDetailActivity)
    private suspend fun disableWgIfPossible(cfg: WgConfigFilesImmutable) {
        if (!VpnController.hasTunnel()) {
            Logger.i(LOG_TAG_PROXY, "$TAG VPN not active, cannot disable WireGuard")
            uiCtx { toast(R.string.settings_socks5_vpn_disabled_error, Toast.LENGTH_LONG) }
            return
        }
        if (WireguardManager.canDisableConfig(cfg)) {
            WireguardManager.disableConfig(cfg)
            logEvent("Wireguard disable", "Disabled WireGuard config: ${cfg.name} (id: ${cfg.id})")
        } else {
            val msg = if (cfg.isCatchAll) R.string.wireguard_disable_failure else R.string.wireguard_disable_failure_relay
            uiCtx { toast(msg, Toast.LENGTH_LONG) }
        }
    }

    private suspend fun enableWgIfPossible(cfg: WgConfigFilesImmutable) {
        if (!VpnController.hasTunnel()) {
            Logger.i(LOG_TAG_PROXY, "$TAG VPN not active, cannot enable WireGuard")
            uiCtx { toast(R.string.settings_socks5_vpn_disabled_error, Toast.LENGTH_LONG) }
            return
        }
        // checks only for app mode, so show appropriate error message if not enabled
        if (!WireguardManager.canEnableProxy()) {
            Logger.i(LOG_TAG_PROXY, "$TAG not in DNS+Firewall mode, cannot enable WireGuard")
            uiCtx { toast(R.string.wireguard_dns_mode_conflict, Toast.LENGTH_LONG) }
            return
        }
        if (WireguardManager.oneWireGuardEnabled()) {
            // the Advanced tab is only reachable once Simple configs are stopped
            Logger.w(LOG_TAG_PROXY, "$TAG one wireGuard is already enabled")
            uiCtx { toast(R.string.wireguard_one_wg_active_conflict, Toast.LENGTH_LONG) }
            return
        }
        if (!WireguardManager.isValidConfig(cfg.id)) {
            Logger.i(LOG_TAG_PROXY, "$TAG invalid WireGuard config")
            uiCtx { showInvalidConfigDialog() }
            return
        }
        // key overlap is a warning: the user can proceed or keep the config disabled
        if (!WireguardManager.canEnableProxy(cfg.id)) {
            Logger.i(LOG_TAG_PROXY, "$TAG wg keys overlap with an active config: ${cfg.id}")
            uiCtx { showDuplicateKeysWarningDialog(cfg) }
            return
        }
        enableWg(cfg)
    }

    private suspend fun enableWg(cfg: WgConfigFilesImmutable) {
        WireguardManager.enableConfig(cfg)
        logEvent("Wireguard enable", "Enabled WireGuard config: ${cfg.name} (id: ${cfg.id})")
    }

    /** Keys already used by an active config: Proceed enables anyway; OK keeps it off. */
    private fun showDuplicateKeysWarningDialog(cfg: WgConfigFilesImmutable) {
        state.confirm = ProxyConfirm(
            title = getString(R.string.wireguard_duplicate_keys_warning_title),
            message = getString(R.string.wireguard_duplicate_keys_warning),
            confirmLabel = getString(R.string.dns_info_positive),
            onConfirm = {},
            neutralLabel = getString(R.string.lbl_proceed),
            onNeutral = {
                io {
                    enableWg(cfg)
                    uiCtx { refreshDnsLine() }
                    rebuild()
                }
            },
        )
    }

    private fun showInvalidConfigDialog() {
        state.confirm = ProxyConfirm(
            title = getString(R.string.wireguard_invalid_config_title),
            message = getString(R.string.wireguard_invalid_config_message),
            confirmLabel = getString(R.string.lbl_dismiss),
            onConfirm = {},
        )
    }

    override fun importFile() {
        try {
            tunnelFileImportResultLauncher.launch(IMPORT_LAUNCH_INPUT)
        } catch (e: ActivityNotFoundException) {
            Logger.e(LOG_TAG_PROXY, "err; anf; while launching file import: ${e.message}", e)
            toast(R.string.blocklist_update_check_failure, Toast.LENGTH_SHORT)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "err while launching file import: ${e.message}", e)
            toast(R.string.blocklist_update_check_failure, Toast.LENGTH_SHORT)
        }
    }

    override fun scanQr() {
        try {
            qrImportResultLauncher.launch(
                ScanOptions()
                    .setOrientationLocked(false)
                    .setBeepEnabled(false)
                    .setPrompt(resources.getString(R.string.lbl_qr_code))
            )
        } catch (e: ActivityNotFoundException) {
            Logger.e(LOG_TAG_PROXY, "err; anf while launching QR scanner: ${e.message}", e)
            toast(R.string.blocklist_update_check_failure, Toast.LENGTH_SHORT)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "err while launching QR scanner: ${e.message}", e)
            toast(R.string.blocklist_update_check_failure, Toast.LENGTH_SHORT)
        }
    }

    override fun create() {
        startActivity(Intent(this, WgConfigEditorActivity::class.java))
    }

    override fun deleteAll() {
        val delText = getString(
            R.string.two_argument_space,
            getString(R.string.config_delete_all_dialog_title),
            getString(R.string.lbl_wireguard)
        )
        state.confirm = ProxyConfirm(
            title = delText,
            message = getString(R.string.config_delete_all_dialog_desc),
            confirmLabel = delText,
            onConfirm = {
                io {
                    WireguardManager.deleteAllConfigs()
                    logEvent("Delete All WireGuard configs", "User deleted all WireGuard configs")
                    uiCtx {
                        toast(R.string.config_add_success_toast, Toast.LENGTH_SHORT)
                        finish()
                    }
                }
            },
            dismissLabel = getString(R.string.lbl_cancel),
            destructive = true,
        )
    }

    // --- helpers ---

    private fun toast(res: Int, length: Int) {
        Utilities.showToastUiCentered(this, getString(res), length)
    }

    private fun logEvent(msg: String, details: String) {
        eventLogger.log(EventType.PROXY_SWITCH, Severity.LOW, msg, EventSource.UI, false, details)
    }

    private fun io(f: suspend () -> Unit): Job = lifecycleScope.launch(Dispatchers.IO) { f() }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            if (!isFinishing && !isDestroyed) {
                f()
            }
        }
    }
}
