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
import com.celzero.bravedns.service.ProxyManager.ID_HTTP_BASE
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.ui.activity.NetworkLogsActivity.Companion.RULES_SEARCH_ID_RPN
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.proxy.HttpFormLogic
import com.celzero.bravedns.ui.compose.proxy.HttpPageState
import com.celzero.bravedns.ui.compose.proxy.HttpProxyActions
import com.celzero.bravedns.ui.compose.proxy.HttpProxyScreen
import com.celzero.bravedns.ui.compose.proxy.ProxyDetailLogic
import com.celzero.bravedns.ui.compose.proxy.ProxyRulesController
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_PROXY
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

/**
 * Custom HTTP(S) CONNECT proxy page (was a switch + dialog on the proxy settings screen):
 * on/off, the proxy url, the IP / domain rules routed via it, the forwarder app options and
 * logs. HTTP routes every app not taken by another proxy, so it has no per-app list.
 * Layout: ui/compose/proxy; all reads / writes are here.
 */
class HttpProxyActivity : BaseActivity(), HttpProxyActions {
    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()
    private val eventLogger by inject<EventLogger>()
    private val rdb by inject<RefreshDatabase>()
    private val ipDao by inject<CustomIpDao>()
    private val domainDao by inject<CustomDomainDAO>()

    private lateinit var state: HttpPageState
    private lateinit var rules: ProxyRulesController

    private var endpoint: ProxyEndpoint? = null
    private var statusJob: Job? = null

    private val discardCallback =
        object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                state.discardDialog = true
            }
        }

    companion object {
        private const val STATUS_POLL_MS = 2_000L
        private const val PROXY_REFRESH_MS = 5_000L

        fun newIntent(context: Context): Intent = Intent(context, HttpProxyActivity::class.java)
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

        state = HttpPageState(getString(R.string.proxy_page_name_http))
        rules = ProxyRulesController.create(this, ID_HTTP_BASE, lifecycleScope, ipDao, domainDao,
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
        setContent { RethinkTheme { HttpProxyScreen(state, this, rules) } }

        onBackPressedDispatcher.addCallback(this, discardCallback)
        loadEndpoint()
    }

    override fun onResume() {
        super.onResume()
        refreshStateUi()
        startStatusPolling()
    }

    override fun onPause() {
        super.onPause()
        statusJob?.cancel()
        statusJob = null
    }

    private fun loadEndpoint() {
        io {
            val ep =
                try {
                    appConfig.getHttpProxyDetails()
                } catch (e: Exception) {
                    Logger.e(LOG_TAG_PROXY, "err fetching HTTP proxy details: ${e.message}", e)
                    null
                }
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
                if (state.savedUrl == null) state.url = HttpFormLogic.initial(ep.proxyIP)
                applyEndpoint(ep, fwdrName)
            }
        }
    }

    private fun applyEndpoint(ep: ProxyEndpoint, fwdrName: String?) {
        state.savedUrl = ep.proxyIP.orEmpty()
        state.forwarder = fwdrName
        state.header = state.header.copy(detail = ep.proxyIP?.takeUnless { it.isBlank() })
        discardCallback.isEnabled = state.dirty
    }

    private fun refreshStateUi() {
        state.header = state.header.copy(enabled = appConfig.isCustomHttpProxyEnabled())
        state.vpnLockdown = VpnController.isVpnLockdown()
        state.loopback = !persistentState.excludeAppsInProxy
        state.proxyLockdown = persistentState.wgGlobalLockdown
    }

    override fun back() {
        onBackPressedDispatcher.onBackPressed()
    }

    override fun toggle(on: Boolean) {
        if (!on) {
            appConfig.removeProxy(AppConfig.ProxyType.HTTP, AppConfig.ProxyProvider.CUSTOM)
            state.header = state.header.copy(enabled = false)
            logEvent("Custom HTTP Proxy disabled", "disabled custom HTTP proxy")
            startStatusPolling()
            return
        }
        if (appConfig.getBraveMode().isDnsMode()) {
            showToastUiCentered(this, getString(R.string.settings_lock_down_proxy_desc), Toast.LENGTH_SHORT)
            return
        }
        if (!appConfig.canEnableHttpProxy()) {
            val s = persistentState.proxyProvider.lowercase().replaceFirstChar(Char::titlecase)
            showToastUiCentered(this, getString(R.string.settings_https_disabled_error, s), Toast.LENGTH_SHORT)
            return
        }
        // turning on uses what the form shows, saved or not (the old dialog's Apply)
        saveForm(enable = true)
    }

    override fun editUrl(url: String) {
        state.url = url
        state.urlError = null
        discardCallback.isEnabled = state.dirty
    }

    override fun save() = saveForm(enable = false)

    override fun revert() {
        state.url = HttpFormLogic.initial(state.savedUrl)
        state.urlError = null
        discardCallback.isEnabled = false
    }

    private fun saveForm(enable: Boolean) {
        val ep = endpoint ?: return
        val url = state.url.trim()
        val error = HttpFormLogic.validate(url)
        if (error != null) {
            state.urlError = error
            return
        }
        persist(copyOf(ep, url, ep.proxyAppName.orEmpty()), enable, resetForm = true)
        logEvent("Custom HTTP Proxy set", "custom HTTP proxy to $url, enable: $enable")
    }

    private fun persist(updated: ProxyEndpoint, enable: Boolean, resetForm: Boolean) {
        state.header = state.header.copy(busy = true)
        io {
            if (enable) {
                // initiate the refresh database, so that newly added apps can be listed
                rdb.refresh(RefreshDatabase.ACTION_REFRESH_FORCE)
                appConfig.updateCustomHttpProxy(updated)
            } else {
                appConfig.saveCustomHttpEndpoint(updated)
            }
            val fwdrName = forwarderAppName(updated)
            uiCtx {
                endpoint = updated
                if (resetForm) state.url = HttpFormLogic.initial(updated.proxyIP)
                state.header = state.header.copy(busy = false, enabled = appConfig.isCustomHttpProxyEnabled())
                applyEndpoint(updated, fwdrName)
                showToastUiCentered(
                    this,
                    getString(if (enable) R.string.settings_http_proxy_toast_success else R.string.proxy_page_http_saved),
                    Toast.LENGTH_SHORT,
                )
                startStatusPolling()
            }
        }
    }

    private fun copyOf(ep: ProxyEndpoint, url: String?, fwdrPkg: String): ProxyEndpoint {
        return ProxyEndpoint(
            ep.id,
            Constants.HTTP,
            ProxyManager.ProxyMode.HTTP.value,
            proxyType = DEFAULT_PROXY_TYPE,
            fwdrPkg,
            url,
            0,
            userName = "",
            password = "",
            isSelected = true,
            isCustom = true,
            isUDP = false,
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
        // proxy lockdown: app selection is not allowed (as the old dialog)
        if (persistentState.wgGlobalLockdown) {
            showToastUiCentered(this, getString(R.string.lockdown_check_setting_disabled), Toast.LENGTH_SHORT)
            return
        }
        val ep = endpoint ?: return
        io {
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
            val updated = copyOf(ep, ep.proxyIP, pkg)
            uiCtx { persist(updated, enable = false, resetForm = false) }
            logEvent("Custom HTTP forwarder set", "custom HTTP proxy forwarder app: $pkg")
        }
    }

    override fun setLoopback(on: Boolean) {
        if (state.vpnLockdown) return
        state.loopback = on
        if (persistentState.excludeAppsInProxy == !on) return
        persistentState.excludeAppsInProxy = !on
        logEvent(
            "loopback proxy forwarder apps in HTTP Proxy toggled",
            "loopback proxy forwarder apps in HTTP proxy: $on",
        )
    }

    override fun openVpnSettings() {
        UIUtils.openVpnProfile(this)
    }

    override fun openLogs() {
        val intent = Intent(this, NetworkLogsActivity::class.java)
        intent.putExtra(Constants.SEARCH_QUERY, RULES_SEARCH_ID_RPN + ID_HTTP_BASE)
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

    private fun startStatusPolling() {
        statusJob?.cancel()
        statusJob = io {
            while (true) {
                try {
                    updateStatusUi()
                } catch (e: Exception) {
                    Logger.w(LOG_TAG_PROXY, "http status poll err: ${e.message}")
                }
                if (!appConfig.isCustomHttpProxyEnabled()) break
                delay(STATUS_POLL_MS)
            }
        }
    }

    private suspend fun updateStatusUi() {
        val enabled = appConfig.isCustomHttpProxyEnabled()
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
        val statusPair = VpnController.getProxyStatusById(ID_HTTP_BASE)
        val ps = UIUtils.ProxyStatus.entries.find { it.id == statusPair.first }
        val text =
            if (ps == null) {
                val w = getString(R.string.status_waiting)
                (if (statusPair.second.isNotEmpty()) "$w (${statusPair.second})" else w).replaceFirstChar(Char::titlecase)
            } else {
                getString(UIUtils.getProxyStatusStringRes(ps.id)).replaceFirstChar(Char::titlecase)
            }
        uiCtx {
            state.header = state.header.copy(status = ProxyDetailLogic.status(true, true, statusPair.first), statusText = text)
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
