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

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
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
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.ProxyManager.ID_ORBOT_BASE
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.ui.activity.NetworkLogsActivity.Companion.RULES_SEARCH_ID_RPN
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.proxy.OrbotActions
import com.celzero.bravedns.ui.compose.proxy.OrbotLogic
import com.celzero.bravedns.ui.compose.proxy.OrbotMode
import com.celzero.bravedns.ui.compose.proxy.OrbotPageState
import com.celzero.bravedns.ui.compose.proxy.OrbotScreen
import com.celzero.bravedns.ui.compose.proxy.ProxyDetailLogic
import com.celzero.bravedns.ui.compose.proxy.ProxyRulesController
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.OrbotHelper
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.viewmodel.ProxyAppsMappingViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Orbot (Tor as a proxy) page; replaces the old Orbot bottom sheet. Starting / stopping still
 * goes through OrbotHelper (intents to the Orbot app, its status broadcast); this page shows
 * the mode (TCP / HTTP / both), install state, the apps routed via Orbot, the IP / domain
 * rules routed via it and logs. Layout: ui/compose/proxy; all reads / writes are here.
 */
class OrbotProxyActivity : BaseActivity(), OrbotActions {
    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()
    private val orbotHelper by inject<OrbotHelper>()
    private val eventLogger by inject<EventLogger>()
    private val ipDao by inject<CustomIpDao>()
    private val domainDao by inject<CustomDomainDAO>()
    private val mappingViewModel: ProxyAppsMappingViewModel by viewModel()

    private lateinit var state: OrbotPageState
    private lateinit var rules: ProxyRulesController
    private var statusJob: Job? = null

    companion object {
        private const val STATUS_POLL_MS = 2_000L
        private const val PROXY_REFRESH_MS = 5_000L

        fun newIntent(context: Context): Intent = Intent(context, OrbotProxyActivity::class.java)
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

        state = OrbotPageState(getString(R.string.orbot))
        // http proxy is only supported on Android Q and above
        state.modes = OrbotLogic.modes(isAtleastQ())
        rules = ProxyRulesController.create(this, ID_ORBOT_BASE, lifecycleScope, ipDao, domainDao,
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
        setContent { RethinkTheme { OrbotScreen(state, this, rules) } }

        mappingViewModel.getAppCountById(ID_ORBOT_BASE).observe(this) { state.appCount = it ?: 0 }
        // set while Orbot is being started (OrbotHelper waits for its status broadcast)
        persistentState.orbotConnectionStatus.observe(this) { connecting ->
            state.connecting = connecting == true
            state.header = state.header.copy(busy = state.connecting)
            refreshUi()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        startStatusPolling()
    }

    override fun onPause() {
        super.onPause()
        statusJob?.cancel()
        statusJob = null
    }

    private fun refreshUi() {
        io {
            val installed = FirewallManager.isOrbotInstalled()
            val orbotDns = appConfig.isOrbotDns()
            val enabled = appConfig.isOrbotProxyEnabled()
            val mode = if (enabled) OrbotLogic.modeOf(OrbotHelper.selectedProxyType) else OrbotMode.NONE
            uiCtx {
                state.installed = installed
                state.lockdown = persistentState.wgGlobalLockdown
                state.mode = mode
                if (mode != OrbotMode.NONE) state.lastMode = mode
                state.header = state.header.copy(
                    enabled = enabled && mode != OrbotMode.NONE,
                    detail = summary(installed, mode, orbotDns),
                )
            }
        }
    }

    // the old sheet's status line ("Forwarding TCP connections to Orbot.", ...)
    private fun summary(installed: Boolean, mode: OrbotMode, orbotDns: Boolean): String {
        if (state.connecting) return getString(R.string.orbot_bs_status_trying_connect)
        if (!installed) return getString(R.string.proxy_page_orbot_not_installed)
        val what = getString(if (orbotDns) R.string.orbot_status_arg_3 else R.string.orbot_status_arg_2)
        return when (mode) {
            OrbotMode.SOCKS5 -> getString(R.string.orbot_bs_status_1, what)
            OrbotMode.HTTP -> getString(R.string.orbot_bs_status_2)
            OrbotMode.BOTH -> getString(R.string.orbot_bs_status_3, what)
            OrbotMode.NONE -> getString(R.string.orbot_bs_status_4)
        }
    }

    override fun back() {
        onBackPressedDispatcher.onBackPressed()
    }

    override fun toggle(on: Boolean) {
        if (on) pickMode(state.lastMode) else stop()
    }

    override fun pickMode(mode: OrbotMode) {
        if (mode == OrbotMode.NONE) {
            stop()
            return
        }
        if (state.connecting) return
        // proxy lockdown: Orbot cannot be used. Inform instead of silently ignoring.
        if (persistentState.wgGlobalLockdown) {
            showToastUiCentered(this, getString(R.string.lockdown_check_setting_disabled), Toast.LENGTH_SHORT)
            return
        }
        if (!appConfig.canEnableOrbotProxy()) {
            val s = persistentState.proxyProvider.lowercase().replaceFirstChar(Char::titlecase)
            val msg =
                if (s.equals(AppConfig.ProxyProvider.CUSTOM.name, ignoreCase = true)) {
                    getString(R.string.settings_orbot_disabled_error)
                } else {
                    getString(R.string.settings_socks5_disabled_error, s)
                }
            showToastUiCentered(this, msg, Toast.LENGTH_SHORT)
            return
        }
        if (mode == state.mode) return
        io {
            val installed = FirewallManager.isOrbotInstalled()
            val count = ProxyManager.getAppCountForProxy(ID_ORBOT_BASE)
            val blocker = OrbotLogic.startBlocker(mode, installed, count, VpnController.hasTunnel())
            uiCtx {
                if (blocker != null) {
                    showToastUiCentered(this, getString(blocker), Toast.LENGTH_SHORT)
                    return@uiCtx
                }
                state.lastMode = mode
                persistentState.orbotConnectionStatus.postValue(true)
                val type = OrbotLogic.proxyTypeOf(mode)
                io { orbotHelper.startOrbot(type) }
                logEvent("Orbot Started with type: $type", "User started Orbot from the Orbot page with type: $type")
            }
        }
    }

    private fun stop() {
        appConfig.removeAllProxies()
        orbotHelper.stopOrbot(isInteractive = true)
        state.mode = OrbotMode.NONE
        state.header = state.header.copy(enabled = false)
        io {
            val isOrbotDns = appConfig.isOrbotDns()
            uiCtx { state.stopDialog = isOrbotDns }
            logEvent("Orbot Stopped", "User stopped Orbot from the Orbot page")
        }
        refreshUi()
    }

    override fun openOrbotApp() {
        orbotHelper.openOrbotApp()
    }

    override fun install() {
        val intent = orbotHelper.getIntentForDownload()
        if (intent == null) {
            showToastUiCentered(this, getString(R.string.orbot_install_activity_error), Toast.LENGTH_SHORT)
            return
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            showToastUiCentered(this, getString(R.string.orbot_install_activity_error), Toast.LENGTH_SHORT)
        }
    }

    override fun openWebsite() {
        UIUtils.openUrl(this, getString(R.string.orbot_website_link))
    }

    override fun openApps() {
        // treat proxyId and proxyName of Orbot as base
        startActivity(WgIncludeAppsActivity.newIntent(this, ID_ORBOT_BASE, ProxyManager.ORBOT_PROXY_NAME))
    }

    override fun openLogs() {
        val intent = Intent(this, NetworkLogsActivity::class.java)
        intent.putExtra(Constants.SEARCH_QUERY, RULES_SEARCH_ID_RPN + ID_ORBOT_BASE)
        startActivity(intent)
    }

    override fun showInfo(show: Boolean) {
        state.infoDialog = show
    }

    override fun dismissStopDialog() {
        state.stopDialog = null
    }

    override fun stopDialogOpenOrbot() {
        state.stopDialog = null
        orbotHelper.openOrbotApp()
    }

    override fun stopDialogChangeDns() {
        state.stopDialog = null
        val intent = Intent(this, DnsDetailActivity::class.java)
        intent.putExtra(Constants.VIEW_PAGER_SCREEN_TO_LOAD, DnsDetailActivity.Tabs.CONFIGURE.screen)
        startActivity(intent)
    }

    private fun startStatusPolling() {
        statusJob?.cancel()
        statusJob = io {
            while (true) {
                val enabled = appConfig.isOrbotProxyEnabled()
                val vpnOn = VpnController.isOn()
                val status = if (enabled && vpnOn) VpnController.getProxyStatusById(ID_ORBOT_BASE).first else null
                val ps = UIUtils.ProxyStatus.entries.find { it.id == status }
                uiCtx {
                    state.header = state.header.copy(
                        status = ProxyDetailLogic.status(enabled, vpnOn, status),
                        statusText = when {
                            state.connecting -> getString(R.string.orbot_bs_status_trying_connect)
                            !enabled -> null
                            !vpnOn -> getString(R.string.proxy_page_status_vpn_off)
                            ps != null -> getString(UIUtils.getProxyStatusStringRes(ps.id)).replaceFirstChar(Char::titlecase)
                            else -> getString(R.string.status_waiting).replaceFirstChar(Char::titlecase)
                        },
                    )
                }
                delay(STATUS_POLL_MS)
            }
        }
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
