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

import androidx.activity.compose.setContent
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.networksettings.*
import com.celzero.bravedns.ui.compose.wireguard.WgControlState

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.os.Bundle
import android.text.format.DateUtils
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import com.celzero.bravedns.ui.compose.wireguard.WgControlState as CompoundButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.ProxyEndpoint
import com.celzero.bravedns.database.ProxyEndpoint.Companion.DEFAULT_PROXY_TYPE
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.service.WireguardManager.WG_UPTIME_THRESHOLD
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.ui.fragment.RethinkPlusFragment
import com.celzero.bravedns.ui.fragment.ServerSelectionFragment
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_PROXY
import com.celzero.bravedns.util.OrbotHelper
import com.celzero.bravedns.util.SnackbarHelper.capitalizeWords
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.openUrl
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.delay
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.isValidPort
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.firestack.backend.Backend
import com.celzero.firestack.backend.RouterStats
import com.celzero.bravedns.ui.compose.networksettings.NetworkAlertDialogBuilder as MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.util.concurrent.TimeUnit

class ProxySettingsActivity : BaseActivity() {
    private val b by lazy { ProxySettingsState(this) }

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()
    private val orbotHelper by inject<OrbotHelper>()
    private val eventLogger by inject<EventLogger>()
    private val rdb by inject<RefreshDatabase>()
    private lateinit var animation: Animation
    private var statusJob: Job? = null

    companion object {
        private const val REFRESH_TIMEOUT: Long = 4000
        private const val STATUS_POLL_MS = 2_000L
        private const val ANIMATION_DURATION = 750L
        private const val ANIMATION_REPEAT_COUNT = -1
        private const val ANIMATION_PIVOT_VALUE = 0.5f
        private const val ANIMATION_START_DEGREE = 0.0f
        private const val ANIMATION_END_DEGREE = 360.0f
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        b.saveChecks(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        b.restoreChecks(savedInstanceState)
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)
        setContent { RethinkTheme { NetworkSettingsScreen(b.layout, b.refreshing.toMap()) } }


        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = Themes.isActivityLightTheme(isDarkThemeOn(), persistentState.theme)
            window.isNavigationBarContrastEnforced = false
        }

        initAnimation()
        initView()
        initClickListeners()
    }

    private fun initAnimation() {
        animation =
            RotateAnimation(
                ANIMATION_START_DEGREE,
                ANIMATION_END_DEGREE,
                Animation.RELATIVE_TO_SELF,
                ANIMATION_PIVOT_VALUE,
                Animation.RELATIVE_TO_SELF,
                ANIMATION_PIVOT_VALUE,
            )
        animation.repeatCount = ANIMATION_REPEAT_COUNT
        animation.duration = ANIMATION_DURATION
    }

    override fun onResume() {
        super.onResume()
        refreshOrbotUi()
        // the HTTP page may have changed the url / state
        displayHttpProxyUi()
        handleProxyUi()
        displayWireguardUi()
        displayRpnUi()
        // socks5 may have changed in its own screen
        refreshSocks5Status()
        startStatusPolling()
    }

    override fun onPause() {
        super.onPause()
        statusJob?.cancel()
        statusJob = null
    }

    // keeps the wireguard / socks5 status lines (and their "x ago" times) current
    private fun startStatusPolling() {
        statusJob?.cancel()
        statusJob =
            lifecycleScope.launch {
                while (true) {
                    kotlinx.coroutines.delay(STATUS_POLL_MS)
                    displayWireguardUi()
                    refreshSocks5Status()
                }
            }
    }

    private fun initView() {
        if (Utilities.isFdroidFlavour()) {
            b.rpnHeader.visibility = View.GONE
            b.settingsActivityRpnContainer.visibility = View.GONE
        }
        displayHttpProxyUi()
    }

    private fun initClickListeners() {

        b.settingsActivityRpnContainer.setOnClickListener { openRpnScreen() }

        b.rpnRefresh.setOnClickListener { refresh(b.rpnRefresh) }

        b.wgRefresh.setOnClickListener { refresh(b.wgRefresh) }

        b.settingsActivitySocks5Rl.setOnClickListener { openSocks5Screen() }

        b.settingsActivitySocks5Img.setOnClickListener { openSocks5Screen() }

        b.settingsActivityOrbotImg.setOnClickListener { handleOrbotUiEvent() }

        b.settingsActivityOrbotContainer.setOnClickListener { handleOrbotUiEvent() }

        b.settingsActivityWireguardContainer.setOnClickListener { openWireguardActivity() }

        b.settingsActivityWireguardImg.setOnClickListener { openWireguardActivity() }

        b.settingsActivityHttpProxyContainer.setOnClickListener { openHttpScreen() }

        b.settingsActivityHttpProxyImg.setOnClickListener { openHttpScreen() }
    }

    private fun openHttpScreen() {
        startActivity(HttpProxyActivity.newIntent(this))
    }

    private fun refresh(iv: WgControlState) {
        iv.isEnabled = false
        b.startRefresh(iv, animation)
        io {
            if (RpnProxyManager.isRpnActive()) {
                VpnController.refreshRpnProxy(Backend.RpnWin)
            }
            VpnController.refreshOrPauseOrResumeOrReAddProxies()
        }
        delay(REFRESH_TIMEOUT, lifecycleScope) {
            if (isFinishing) return@delay

            iv.isEnabled = true
            b.stopRefresh(iv)
            showToastUiCentered(this, getString(R.string.dc_refresh_toast), Toast.LENGTH_SHORT)
        }
    }

    private fun handleOrbotUiEvent() {
        // Proxy lockdown: Orbot cannot be used. Inform instead of silently ignoring.
        if (persistentState.wgGlobalLockdown) {
            showToastUiCentered(
                this,
                getString(R.string.lockdown_check_setting_disabled),
                Toast.LENGTH_SHORT,
            )
            return
        }
        // install / mode / apps / checks live on the Orbot page
        startActivity(OrbotProxyActivity.newIntent(this))
    }

    private fun openWireguardActivity() {
        val intent = Intent(this, WgMainActivity::class.java)
        startActivity(intent)
    }

    private fun displayHttpProxyUi() {
        b.settingsActivityHttpProxyContainer.visibility = View.VISIBLE
        if (!appConfig.isCustomHttpProxyEnabled()) {
            b.settingsActivityHttpProxyDesc.text = getString(R.string.settings_https_desc)
            return
        }

        io {
            val endpoint =
                try {
                    appConfig.getHttpProxyDetails()
                } catch (e: Exception) {
                    Logger.e(LOG_TAG_PROXY, "err fetching HTTP proxy details: ${e.message}", e)
                    null
                }
            val m = endpoint?.let { ProxyManager.ProxyMode.get(it.proxyMode) } ?: return@io
            // only update below ui if its custom http proxy
            if (!m.isCustomHttp()) return@io
            uiCtx {
                b.settingsActivityHttpProxyDesc.text =
                    getString(R.string.settings_http_proxy_desc, endpoint.proxyIP)
            }
        }
    }

    /**
     * Updates the RPN row description to reflect the current subscription / activation state.
     *
     * - RPN enabled & active → show selected-country summary (same style as WireGuard desc)
     * - RPN enabled / valid sub but not yet active → show plan name
     * - No valid subscription → default "Subscribe" call-to-action
     *
     * Always visible, the row is the entry point to either subscribe or manage the proxy.
     */
    private fun displayRpnUi() {
        val isEnabled = RpnProxyManager.isRpnEnabled()
        val hasValidSub = RpnProxyManager.hasValidSubscription()
        val isActive = RpnProxyManager.isRpnActive()

        when {
            isEnabled && isActive -> {
                io {
                    val selectedConfigs = RpnProxyManager.getSelectedCCs()
                    val ccs = selectedConfigs.map { it.city.capitalizeWords() }
                    val desc =
                        if (selectedConfigs.isNotEmpty()) {
                            val countryList =
                                ccs.take(6).joinToString(", ")
                            getString(
                                R.string.two_argument_dot,
                                getString(R.string.lbl_active),
                                countryList,
                            )
                        } else {
                            getString(
                                R.string.two_argument_dot,
                                getString(R.string.lbl_active),
                                getString(R.string.rpn_title),
                            )
                        }
                    uiCtx {
                        b.settingsActivityRpnDesc.text = desc
                        b.settingsActivityRpnIcon.alpha = 1.0f
                        b.wgRefresh.visibility = View.GONE
                    }
                }
            }
            isEnabled || hasValidSub -> {
                // Subscription valid but proxy not yet active
                b.settingsActivityRpnDesc.text = getString(R.string.rpn_title)
                b.settingsActivityRpnIcon.alpha = 1.0f
                b.wgRefresh.visibility = View.GONE
            }
            else -> {
                b.settingsActivityRpnDesc.text = getString(R.string.proxy_rpn_desc_inactive)
                b.settingsActivityRpnIcon.alpha = 0.5f
                b.wgRefresh.visibility = View.VISIBLE
            }
        }
    }

    /**
     * Navigates to the appropriate RPN screen:
     * - If the user has an active/valid subscription → open [ServerSelectionFragment] so they can
     *   manage which countries to proxy through.
     * - Otherwise → open [RethinkPlusFragment] (the purchase / sign-up flow).
     */
    private fun openRpnScreen() {
        val fragmentClass: Class<out androidx.fragment.app.Fragment> =
            if (RpnProxyManager.isRpnEnabled() || RpnProxyManager.hasValidSubscription()) {
                ServerSelectionFragment::class.java
            } else {
                RethinkPlusFragment::class.java
            }

        val intent =
            FragmentHostActivity.createIntent(context = this, fragmentClass = fragmentClass)
        startActivity(intent)
    }

    private fun displayWireguardUi() {

        val activeWgs = WireguardManager.getActiveConfigs()

        if (activeWgs.isEmpty()) {
            b.settingsActivityWireguardHeading.text = getString(R.string.setup_wireguard)
            b.settingsActivityWireguardDesc.text = getString(R.string.wireguard_description)
            return
        }
        b.settingsActivityWireguardHeading.text =
            resources.getQuantityString(
                R.plurals.settings_wireguard_heading_on,
                activeWgs.size,
                activeWgs.size,
            )
        io {
            var wgStatus = ""
            activeWgs.forEach {
                val id = ProxyManager.ID_WG_BASE + it.getId()
                val statusPair = VpnController.getProxyStatusById(id)
                val stats = VpnController.getProxyStats(id)
                val dnsStatusId = VpnController.getDnsStatus(id)

                val statusText =
                    if (statusPair.first == Backend.TPU) {
                        getString(UIUtils.getProxyStatusStringRes(UIUtils.ProxyStatus.TPU.id))
                            .replaceFirstChar(Char::titlecase)
                    } else if (dnsStatusId != null && isDnsError(dnsStatusId)) {
                        // DNS is failing, show failing status
                        getString(R.string.status_failing).replaceFirstChar(Char::titlecase)
                    } else {
                        // DNS is okay, show proxy status
                        getProxyStatusText(statusPair, stats)
                    }

                wgStatus +=
                    getString(R.string.ci_ip_label, it.getName(), statusText.padStart(1, ' ')) +
                        "\n"
                Logger.d(LOG_TAG_PROXY, "current proxy status for $id: $statusText")
            }
            wgStatus = wgStatus.trimEnd()
            uiCtx { b.settingsActivityWireguardDesc.text = wgStatus }
        }
    }

    private fun isDnsError(statusId: Int?): Boolean {
        if (statusId == null) return true

        val s = Transaction.Status.fromId(statusId)
        return s == Transaction.Status.BAD_QUERY ||
            s == Transaction.Status.BAD_RESPONSE ||
            s == Transaction.Status.NO_RESPONSE ||
            s == Transaction.Status.SEND_FAIL ||
            s == Transaction.Status.CLIENT_ERROR ||
            s == Transaction.Status.INTERNAL_ERROR ||
            s == Transaction.Status.TRANSPORT_ERROR
    }

    private fun getProxyStatusText(statusPair: Pair<Int?, String>, stats: RouterStats?): String {
        val status = UIUtils.ProxyStatus.entries.find { it.id == statusPair.first }

        return getStatusText(status, stats, statusPair.second)
    }

    private fun getStatusText(
        status: UIUtils.ProxyStatus?,
        stats: RouterStats?,
        errMsg: String?,
    ): String {
        if (status == null) {
            val txt =
                if (!errMsg.isNullOrEmpty()) {
                    getString(R.string.status_waiting) + " ($errMsg)"
                } else {
                    getString(R.string.status_waiting)
                }
            return txt.replaceFirstChar(Char::titlecase)
        }

        val now = System.currentTimeMillis()
        val lastOk = stats?.lastOK ?: 0L
        val since = stats?.since ?: 0L
        if (now - since > WG_UPTIME_THRESHOLD && lastOk == 0L) {
            return getString(R.string.status_failing).replaceFirstChar(Char::titlecase)
        }

        val baseText =
            getString(UIUtils.getProxyStatusStringRes(status.id)).replaceFirstChar(Char::titlecase)

        val handshakeTime =
            if (stats != null && stats.lastOK > 0L) {
                DateUtils.getRelativeTimeSpanString(
                        stats.lastOK,
                        now,
                        DateUtils.SECOND_IN_MILLIS,
                        DateUtils.FORMAT_ABBREV_RELATIVE,
                    )
                    .toString()
            } else {
                null
            }

        return if (stats?.lastOK != 0L && handshakeTime != null) {
            getString(R.string.about_version_install_source, baseText, handshakeTime)
        } else {
            baseText
        }
    }

    // socks5 row: "host:port · status" while on; apps and settings live in Socks5ConfigActivity
    private fun refreshSocks5Status() {
        if (!appConfig.isCustomSocks5Enabled()) {
            b.settingsActivitySocks5Heading.text = getString(R.string.settings_socks5_heading)
            b.settingsActivitySocks5Desc.text =
                getString(R.string.settings_socks_forwarding_default_desc)
            return
        }
        b.settingsActivitySocks5Heading.text = getString(R.string.settings_socks5_heading_on)

        io {
            val endpoint: ProxyEndpoint = appConfig.getSocks5ProxyDetails() ?: return@io
            val statusPair = VpnController.getProxyStatusById(ProxyManager.ID_S5_BASE)
            val status = UIUtils.ProxyStatus.entries.find { it.id == statusPair.first }
            val statusText =
                if (status == null) {
                    getString(R.string.status_waiting)
                } else {
                    getString(UIUtils.getProxyStatusStringRes(status.id))
                }.replaceFirstChar(Char::titlecase)

            val desc =
                getString(
                    R.string.settings_socks5_status,
                    endpoint.proxyIP,
                    endpoint.proxyPort.toString(),
                    statusText,
                )
            uiCtx { b.settingsActivitySocks5Desc.text = desc }
        }
    }

    private fun openSocks5Screen() {
        startActivity(Socks5ConfigActivity.newIntent(this))
    }

    private fun refreshOrbotUi() {
        // Checks whether the Orbot is installed.
        // If not, then prompt the user for installation.
        // Else, enable the Orbot bottom sheet fragment.
        io {
            val isOrbotInstalled = FirewallManager.isOrbotInstalled()
            val isOrbotDns = appConfig.isOrbotDns()
            uiCtx {
                if (!isOrbotInstalled) {
                    b.settingsActivityHttpOrbotDesc.text =
                        getString(R.string.settings_orbot_install_desc)
                    return@uiCtx
                }

                if (!appConfig.isOrbotProxyEnabled()) {
                    b.settingsActivityHttpOrbotDesc.text = getString(R.string.orbot_bs_status_4)
                    return@uiCtx
                }

                when (appConfig.getProxyType()) {
                    AppConfig.ProxyType.HTTP.name -> {
                        b.settingsActivityHttpOrbotDesc.text = getString(R.string.orbot_bs_status_2)
                    }
                    AppConfig.ProxyType.SOCKS5.name -> {
                        if (isOrbotDns) {
                            b.settingsActivityHttpOrbotDesc.text =
                                getString(
                                    R.string.orbot_bs_status_1,
                                    getString(R.string.orbot_status_arg_3),
                                )
                        } else {
                            b.settingsActivityHttpOrbotDesc.text =
                                getString(
                                    R.string.orbot_bs_status_1,
                                    getString(R.string.orbot_status_arg_2),
                                )
                        }
                    }
                    AppConfig.ProxyType.HTTP_SOCKS5.name -> {
                        if (isOrbotDns) {
                            b.settingsActivityHttpOrbotDesc.text =
                                getString(
                                    R.string.orbot_bs_status_3,
                                    getString(R.string.orbot_status_arg_3),
                                )
                        } else {
                            b.settingsActivityHttpOrbotDesc.text =
                                getString(
                                    R.string.orbot_bs_status_3,
                                    getString(R.string.orbot_status_arg_2),
                                )
                        }
                    }
                    else -> {
                        b.settingsActivityHttpOrbotDesc.text = getString(R.string.orbot_bs_status_4)
                    }
                }
            }
        }
    }

    private fun handleProxyUi() {
        val canEnableProxy = appConfig.canEnableProxy()
        val isProxyLockdown = persistentState.wgGlobalLockdown

        if (!canEnableProxy) {
            b.settingsActivityVpnLockdownDesc.visibility = View.VISIBLE
            b.settingsActivityVpnLockdownDesc.text =
                getString(R.string.settings_lock_down_proxy_desc)
        } else {
            b.settingsActivityVpnLockdownDesc.visibility = View.GONE
        }

        // Orbot is unavailable under proxy lockdown (or DNS mode); the row stays
        // clickable so tapping surfaces the lockdown toast.
        val isOrbotUsable = canEnableProxy && !isProxyLockdown
        b.settingsActivityOrbotContainer.alpha = if (isOrbotUsable) 1f else 0.5f
        b.settingsActivityOrbotImg.isEnabled = canEnableProxy
        b.settingsActivityOrbotContainer.isEnabled = canEnableProxy

        // SOCKS5 and HTTP remain usable under proxy lockdown (app selection is
        // disallowed in the proxy dialog / socks5 screen instead).
        b.settingsActivitySocks5Rl.alpha = if (canEnableProxy) 1f else 0.5f
        b.settingsActivityHttpProxyContainer.alpha = if (canEnableProxy) 1f else 0.5f
        b.settingsActivityHttpProxyImg.isEnabled = canEnableProxy

        // Wireguard (gated only on canEnableProxy; it remains usable under proxy lockdown)
        b.settingsActivityWireguardImg.isEnabled = canEnableProxy
        b.settingsActivityWireguardContainer.isEnabled = canEnableProxy
        b.settingsActivityWireguardContainer.alpha = if (canEnableProxy) 1f else 0.5f
    }

    private fun logEvent(msg: String, details: String) {
        eventLogger.log(EventType.PROXY_SWITCH, Severity.LOW, msg, EventSource.UI, false, details)
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            if (!isFinishing && !isDestroyed) {
                f()
            }
        }
    }
}
