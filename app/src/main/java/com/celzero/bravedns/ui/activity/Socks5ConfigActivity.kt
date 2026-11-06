/*
 * Copyright 2026 RethinkDNS and its authors
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
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.CustomDomainDAO
import com.celzero.bravedns.database.CustomIpDao
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.ProxyEndpoint
import com.celzero.bravedns.database.ProxyEndpoint.Companion.DEFAULT_PROXY_TYPE
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.ProxyManager.ID_S5_BASE
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.service.WireguardManager.WG_UPTIME_THRESHOLD
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.ui.activity.NetworkLogsActivity.Companion.RULES_SEARCH_ID_RPN
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.proxy.ProxyDetailLogic
import com.celzero.bravedns.ui.compose.proxy.ProxyRulesController
import com.celzero.bravedns.ui.compose.proxy.Socks5Actions
import com.celzero.bravedns.ui.compose.proxy.Socks5Form
import com.celzero.bravedns.ui.compose.proxy.Socks5FormLogic
import com.celzero.bravedns.ui.compose.proxy.Socks5PageState
import com.celzero.bravedns.ui.compose.proxy.Socks5Saved
import com.celzero.bravedns.ui.compose.proxy.Socks5Screen
import com.celzero.bravedns.ui.compose.rules.RuleKind
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_PROXY
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.firestack.backend.RouterStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * Custom SOCKS5 proxy page, opened from proxy settings: on/off, the server endpoint, the apps
 * (All apps, or the ones picked in the shared exclusive picker), the IP / domain rules routed
 * via SOCKS5, the forwarder app options and logs. Layout: ui/compose/proxy (shared with the
 * WireGuard, HTTP and Orbot pages); all reads / writes are here.
 */
class Socks5ConfigActivity : BaseActivity(), Socks5Actions {
    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()
    private val eventLogger by inject<EventLogger>()
    private val rdb by inject<RefreshDatabase>()
    private val ipDao by inject<CustomIpDao>()
    private val domainDao by inject<CustomDomainDAO>()

    private lateinit var state: Socks5PageState
    private lateinit var rules: ProxyRulesController

    // last saved endpoint; never mutated, a new instance is saved on every change
    private var endpoint: ProxyEndpoint? = null
    private var statsJob: Job? = null

    private val discardCallback =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                state.discardDialog = true
            }
        }

    companion object {
        private const val STATS_POLL_MS = 2_000L
        private const val PROXY_REFRESH_MS = 5_000L

        fun newIntent(context: Context): Intent = Intent(context, Socks5ConfigActivity::class.java)
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars =
                Themes.isActivityLightTheme(isDarkThemeOn(), persistentState.theme)
            window.isNavigationBarContrastEnforced = false
        }

        state = Socks5PageState(getString(R.string.proxy_page_name_socks5))
        rules = ProxyRulesController.create(this, ID_S5_BASE, lifecycleScope, ipDao, domainDao,
            notify = { id, args -> showToastUiCentered(this, getString(id, *args), Toast.LENGTH_SHORT) },
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
        setContent { RethinkTheme { Socks5Screen(state, this, rules) } }

        onBackPressedDispatcher.addCallback(this, discardCallback)
        loadEndpoint()
    }

    override fun onResume() {
        super.onResume()
        // app count may have changed in the picker, lockdown / dns mode in other screens
        refreshStateUi()
        startStatsPolling()
    }

    override fun onPause() {
        super.onPause()
        statsJob?.cancel()
        statsJob = null
    }

    private fun loadEndpoint() {
        io {
            val ep = appConfig.getSocks5ProxyDetails()
            if (ep == null) {
                uiCtx {
                    showToastUiCentered(this, getString(R.string.blocklist_update_check_failure), Toast.LENGTH_SHORT)
                    finish()
                }
                return@io
            }
            val fwdrName = forwarderAppName(ep)
            uiCtx {
                endpoint = ep
                // a form restored by the system is not overwritten
                if (state.saved == null) state.form = Socks5FormLogic.form(saved(ep))
                applyEndpoint(ep, fwdrName)
            }
        }
    }

    private fun saved(ep: ProxyEndpoint) = Socks5Saved(ep.proxyIP, ep.proxyPort, ep.userName, ep.password)

    private fun applyEndpoint(ep: ProxyEndpoint, fwdrName: String?) {
        state.saved = saved(ep)
        state.forwarder = fwdrName
        val detail = if (ep.proxyIP.isNullOrBlank()) null else "${ep.proxyIP}:${ep.proxyPort}"
        val auth = if (ep.userName.isNullOrBlank()) null else getString(R.string.socks5_auth_user, ep.userName)
        state.header = state.header.copy(detail = listOfNotNull(detail, auth).joinToString(" · ").ifEmpty { null })
        updateDirtyState()
    }

    // on/off, mode, lockdown hints, app count; everything that may change outside this screen
    private fun refreshStateUi() {
        state.header = state.header.copy(enabled = appConfig.isCustomSocks5Enabled())
        state.allApps = persistentState.customSocks5AllApps
        // in android's vpn lockdown the forwarder can't be kept out of the tunnel (see
        // BraveVPNService), so it is always looped back; shown instead of the setting
        state.vpnLockdown = VpnController.isVpnLockdown()
        state.loopback = !persistentState.excludeAppsInProxy
        // proxy lockdown forces the forwarder to none (see the old socks5 dialog)
        state.proxyLockdown = persistentState.wgGlobalLockdown
        refreshAppCount()
    }

    private fun refreshAppCount() {
        io {
            ProxyManager.ensureLoaded()
            val count = ProxyManager.getAppCountForProxy(ID_S5_BASE)
            uiCtx { state.appCount = count }
        }
    }

    // one-wg and catch-all wireguard also take every app; only one of them can
    private fun isWireguardRoutingAllApps(): Boolean {
        return WireguardManager.oneWireGuardEnabled() ||
            WireguardManager.getActiveCatchAllConfig().isNotEmpty()
    }

    override fun back() {
        onBackPressedDispatcher.onBackPressed()
    }

    override fun setAllApps(allApps: Boolean) {
        if (allApps == persistentState.customSocks5AllApps) return
        if (allApps && isWireguardRoutingAllApps()) {
            showToastUiCentered(this, getString(R.string.socks5_all_apps_wg_conflict), Toast.LENGTH_LONG)
            return
        }
        persistentState.customSocks5AllApps = allApps
        state.allApps = allApps
        refreshAppCount()
        logEvent("Custom SOCKS5 mode changed", "custom SOCKS5 all apps: $allApps")
    }

    override fun toggle(on: Boolean) {
        if (!on) {
            appConfig.removeProxy(AppConfig.ProxyType.SOCKS5, AppConfig.ProxyProvider.CUSTOM)
            logEvent("Custom SOCKS5 Proxy disabled", "disabled custom SOCKS5 proxy")
            state.header = state.header.copy(enabled = false)
            afterStateChange()
            return
        }

        if (!appConfig.canEnableSocks5Proxy()) {
            showToastUiCentered(this, getString(R.string.settings_lock_down_proxy_desc), Toast.LENGTH_SHORT)
            return
        }

        if (persistentState.customSocks5AllApps && isWireguardRoutingAllApps()) {
            showToastUiCentered(this, getString(R.string.socks5_all_apps_wg_conflict), Toast.LENGTH_LONG)
            return
        }

        // turning on uses what the form shows, saved or not
        saveForm(enable = true)
    }

    private fun afterStateChange() {
        refreshAppCount()
        startStatsPolling()
    }

    override fun edit(form: Socks5Form) {
        state.form = form
        updateDirtyState()
    }

    override fun save() = saveForm(enable = false)

    override fun revert() {
        val ep = endpoint ?: return
        state.form = Socks5FormLogic.form(saved(ep))
        updateDirtyState()
    }

    private fun updateDirtyState() {
        discardCallback.isEnabled = state.dirty
    }

    private fun saveForm(enable: Boolean) {
        val ep = endpoint ?: return
        val form = state.form
        val (host, port) = when (val r = Socks5FormLogic.validate(form)) {
            is Socks5FormLogic.Result.Invalid -> { state.form = r.form; return }
            is Socks5FormLogic.Result.Ok -> r.host to r.port
        }
        val updated = copyOf(ep, host, port, form.userName, form.password, ep.proxyAppName.orEmpty())
        persist(updated, enable)
        logEvent("Custom SOCKS5 Proxy set", "custom SOCKS5 proxy to $host:$port, enable: $enable")
    }

    private fun persist(updated: ProxyEndpoint, enable: Boolean, resetForm: Boolean = true) {
        state.header = state.header.copy(busy = true)
        io {
            if (enable) {
                appConfig.updateCustomSocks5Proxy(updated)
            } else {
                appConfig.saveCustomSocks5Endpoint(updated)
            }
            val fwdrName = forwarderAppName(updated)
            ProxyManager.ensureLoaded()
            val noApps = ProxyManager.getAppCountForProxy(ID_S5_BASE) == 0
            uiCtx {
                endpoint = updated
                state.header = state.header.copy(busy = false, enabled = appConfig.isCustomSocks5Enabled())
                // the form now matches what is stored (trimmed); a forwarder change keeps edits
                if (resetForm) state.form = Socks5FormLogic.form(saved(updated))
                applyEndpoint(updated, fwdrName)
                afterStateChange()
                if (!enable) {
                    showToastUiCentered(this, getString(R.string.socks5_saved_toast), Toast.LENGTH_SHORT)
                }
                // in advanced mode socks5 routes no app until some are picked
                if (enable && noApps && !persistentState.customSocks5AllApps) openApps()
            }
        }
    }

    private fun copyOf(
        ep: ProxyEndpoint,
        ip: String?,
        port: Int,
        userName: String?,
        password: String?,
        fwdrPkg: String,
    ): ProxyEndpoint {
        return ProxyEndpoint(
            ep.id,
            ProxyManager.ProxyMode.SOCKS5.name,
            ProxyManager.ProxyMode.SOCKS5.value,
            proxyType = DEFAULT_PROXY_TYPE,
            fwdrPkg,
            ip,
            port,
            userName,
            password,
            isSelected = true,
            isCustom = true,
            isUDP = ep.isUDP,
            modifiedDataTime = 0L,
            latency = 0,
        )
    }

    private suspend fun forwarderAppName(ep: ProxyEndpoint): String? {
        return ep.proxyAppName
            ?.takeUnless { it.isBlank() || it == getString(R.string.settings_app_list_default_app) }
            ?.let { FirewallManager.getAppInfoByPackage(it)?.appName ?: it }
    }

    override fun openForwarderPicker() {
        if (persistentState.wgGlobalLockdown) {
            showToastUiCentered(this, getString(R.string.lockdown_check_setting_disabled), Toast.LENGTH_SHORT)
            return
        }
        val ep = endpoint ?: return
        io {
            // newly installed apps should be listed too
            rdb.refresh(RefreshDatabase.ACTION_REFRESH_FORCE)
            val none = getString(R.string.settings_app_list_default_app)
            val names = listOf(none) + FirewallManager.getAllAppNamesSortedByVpnPermission(this)
            val current = forwarderAppName(ep) ?: none
            uiCtx { state.forwarderChoices = names to names.indexOf(current).coerceAtLeast(0) }
        }
    }

    override fun dismissForwarderPicker() {
        state.forwarderChoices = null
    }

    override fun forwarderPicked(index: Int) {
        val (names, checked) = state.forwarderChoices ?: return
        state.forwarderChoices = null
        if (index == checked) return
        val ep = endpoint ?: return
        io {
            val pkg = if (index == 0) "" else FirewallManager.getPackageNameByAppName(names[index]).orEmpty()
            // only the forwarder changes; unsaved server edits stay in the form
            val updated = copyOf(ep, ep.proxyIP, ep.proxyPort, ep.userName, ep.password, pkg)
            uiCtx { persist(updated, enable = false, resetForm = false) }
            logEvent("Custom SOCKS5 forwarder set", "custom SOCKS5 forwarder app: $pkg")
        }
    }

    override fun setLoopback(on: Boolean) {
        if (state.vpnLockdown) return
        state.loopback = on
        if (persistentState.excludeAppsInProxy == !on) return
        persistentState.excludeAppsInProxy = !on
        logEvent(
            "loopback proxy forwarder apps SOCKS5 Proxy toggled",
            "loopback proxy forwarder apps in SOCKS5 proxy: $on",
        )
    }

    override fun openVpnSettings() {
        UIUtils.openVpnProfile(this)
    }

    override fun openApps() {
        startActivity(WgIncludeAppsActivity.newIntent(this, ID_S5_BASE, AppConfig.ProxyType.SOCKS5.name))
    }

    override fun openLogs() {
        // the rpn log view is a plain proxy-id filter over connection and dns logs
        val intent = Intent(this, NetworkLogsActivity::class.java)
        intent.putExtra(Constants.SEARCH_QUERY, RULES_SEARCH_ID_RPN + ID_S5_BASE)
        startActivity(intent)
    }

    override fun discard() {
        state.discardDialog = false
        discardCallback.isEnabled = false
        onBackPressedDispatcher.onBackPressed()
    }

    override fun dismissDiscard() {
        state.discardDialog = false
    }

    private fun startStatsPolling() {
        statsJob?.cancel()
        statsJob = io {
            while (true) {
                try {
                    updateStatusUi()
                } catch (e: Exception) {
                    Logger.w(LOG_TAG_PROXY, "socks5 status poll err: ${e.message}")
                }
                if (!appConfig.isCustomSocks5Enabled()) break
                delay(STATS_POLL_MS.milliseconds)
            }
        }
    }

    private suspend fun updateStatusUi() {
        val enabled = appConfig.isCustomSocks5Enabled()
        val vpnOn = VpnController.isOn()
        if (!enabled || !vpnOn) {
            uiCtx {
                state.header = state.header.copy(
                    status = ProxyDetailLogic.status(enabled, vpnOn, null),
                    statusText = if (!enabled) null else getString(R.string.proxy_page_status_vpn_off),
                )
            }
            return
        }

        val statusPair = VpnController.getProxyStatusById(ID_S5_BASE)
        val stats = VpnController.getProxyStats(ID_S5_BASE)
        val ps = UIUtils.ProxyStatus.entries.find { it.id == statusPair.first }
        val text = getStatusText(ps, stats, statusPair.second)
        val now = System.currentTimeMillis()
        val failing = ProxyDetailLogic.isFailing(now, stats?.since ?: 0L, stats?.lastOK ?: 0L, WG_UPTIME_THRESHOLD)
        uiCtx {
            state.header = state.header.copy(
                status = ProxyDetailLogic.status(true, true, statusPair.first, failing),
                statusText = text,
            )
        }
    }

    private fun getStatusText(status: UIUtils.ProxyStatus?, stats: RouterStats?, errMsg: String?): String {
        if (status == null) {
            val txt =
                if (!errMsg.isNullOrEmpty()) {
                    getString(R.string.status_waiting) + " ($errMsg)"
                } else {
                    getString(R.string.status_waiting)
                }
            return txt.replaceFirstChar(Char::titlecase)
        }

        val baseText = getString(UIUtils.getProxyStatusStringRes(status.id)).replaceFirstChar(Char::titlecase)
        // firestack reports a socks5 lastOK of "now" on every read, so show the uptime instead
        val since = stats?.since ?: 0L
        val upMs = System.currentTimeMillis() - since
        if (since <= 0L || upMs < 0L) return baseText

        val uptime = DateUtils.formatElapsedTime(upMs / DateUtils.SECOND_IN_MILLIS)
        return getString(R.string.socks5_uptime, baseText, uptime)
    }

    private fun logEvent(msg: String, details: String) {
        eventLogger.log(EventType.PROXY_SWITCH, Severity.LOW, msg, EventSource.UI, false, details)
    }

    private fun io(f: suspend () -> Unit): Job {
        return lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            if (!isFinishing && !isDestroyed) {
                f()
            }
        }
    }
}

/** Rule edits from a proxy page are logged like the rules page's. */
internal fun logRule(eventLogger: EventLogger, kind: RuleKind, details: String) {
    eventLogger.log(
        EventType.FW_RULE_MODIFIED, Severity.LOW,
        if (kind == RuleKind.DOMAIN) "Custom Domain" else "Custom IP", EventSource.UI, false, details,
    )
}
