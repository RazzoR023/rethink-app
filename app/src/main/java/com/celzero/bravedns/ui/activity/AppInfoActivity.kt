/*
 * Copyright 2021 RethinkDNS and its authors
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
import android.os.Process
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingData
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.AppWiseCountriesAdapter
import com.celzero.bravedns.adapter.AppWiseDomainsAdapter
import com.celzero.bravedns.adapter.AppWiseIpsAdapter
import com.celzero.bravedns.adapter.SummaryStatisticsAdapter
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyChoices
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.ProxyManager.ID_NONE
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.appdetails.AppConnectionsPresenter
import com.celzero.bravedns.ui.compose.appdetails.AppDetailsActions
import com.celzero.bravedns.ui.compose.appdetails.AppDetailsDialog
import com.celzero.bravedns.ui.compose.appdetails.AppDetailsLogic
import com.celzero.bravedns.ui.compose.appdetails.AppDetailsScreen
import com.celzero.bravedns.ui.compose.appdetails.AppDetailsState
import com.celzero.bravedns.ui.compose.appdetails.AppDetailsUi
import com.celzero.bravedns.ui.compose.appdetails.AppInsightsSection
import com.celzero.bravedns.ui.compose.appdetails.AppNetworkMode
import com.celzero.bravedns.ui.compose.appdetails.AppNotesDraft
import com.celzero.bravedns.ui.compose.appdetails.ExcludeBlocker
import com.celzero.bravedns.ui.compose.appdetails.FirewallChange
import com.celzero.bravedns.ui.fragment.SummaryStatisticsFragment
import com.celzero.bravedns.ui.stats.StatsViewMode
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Constants.Companion.RETHINK_PACKAGE
import com.celzero.bravedns.util.Constants.Companion.VIEW_PAGER_SCREEN_TO_LOAD
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils.openAndroidAppInfo
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.viewmodel.AppConnectionsViewModel
import com.celzero.bravedns.viewmodel.AppInfoViewModel
import com.celzero.bravedns.viewmodel.CustomDomainViewModel
import com.celzero.bravedns.viewmodel.CustomIpViewModel
import com.celzero.bravedns.viewmodel.SummaryStatisticsViewModel
import com.celzero.firestack.backend.Backend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * App details: what an app is allowed to do, how it is routed, its rules and recent activity.
 * Draws [AppDetailsScreen] from [AppDetailsState] and performs every read / write for it
 * ([AppDetailsActions]).
 */
class AppInfoActivity : BaseActivity(), AppDetailsActions {
    private val state = AppDetailsState()

    private val persistentState by inject<PersistentState>()
    private val eventLogger by inject<EventLogger>()
    private val appConfig by inject<AppConfig>()

    private val appInfoViewModel: AppInfoViewModel by viewModel()
    private val ipRulesViewModel: CustomIpViewModel by viewModel()
    private val domainRulesViewModel: CustomDomainViewModel by viewModel()
    private val networkLogsViewModel: AppConnectionsViewModel by viewModel()

    private val proxyChoices = ProxyChoices()

    private var uid: Int = INVALID_UID
    private var requestedPackageName: String? = null
    private var appInfo: AppInfo? = null

    private var isWarningAcknowledged = false
    private var isNotesSaveInFlight = false
    private var sectionsWired = false

    // notes dialog to reopen after recreation (its draft text)
    private var pendingNotesDraft: String? = null

    // isProxyExcluded is written asynchronously by FirewallManager; after a picker change this
    // holds the value just written until FirewallManager reports it
    private var excludedHint: Boolean? = null

    // (presenter, observer) pairs mirrored into insightsSnapshots; removed in onDestroy
    private val insightsObservers = mutableListOf<Pair<AppConnectionsPresenter, () -> Unit>>()

    private val warningBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            finish()
        }
    }

    companion object {
        const val INTENT_UID = "UID"
        const val INTENT_PACKAGE_NAME = "PACKAGE_NAME"
        const val INTENT_ACTIVE_CONNS = "ACTIVE_CONNS"
        const val INTENT_ASN = "ASN"
        const val INTENT_COUNTRY = "COUNTRY"
        const val INTENT_IS_BLOCKED = "IS_BLOCKED"
        private const val TAG = "AppInfoActivity"

        private const val TEMP_ALLOW_DURATION_MS = 15 * 60 * 1000L
        private const val IS_WARNING_ACKNOWLEDGED = "IS_WARNING_ACKNOWLEDGED"
        private const val NOTES_DRAFT = "NOTES_DRAFT"
        private const val IS_NOTES_DIALOG_OPEN = "IS_NOTES_DIALOG_OPEN"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)
        setContent { RethinkTheme { AppDetailsScreen(state, this) } }
        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = Themes.isActivityLightTheme(isDarkThemeOn(), persistentState.theme)
            window.isNavigationBarContrastEnforced = false
        }

        uid = intent.getIntExtra(INTENT_UID, INVALID_UID)
        requestedPackageName = intent.getStringExtra(INTENT_PACKAGE_NAME)
        Logger.d(LOG_TAG_UI, "$TAG, intent uid: $uid, package: $requestedPackageName")
        onBackPressedDispatcher.addCallback(this, warningBackCallback)

        isWarningAcknowledged = savedInstanceState?.getBoolean(IS_WARNING_ACKNOWLEDGED, false) == true
        if (savedInstanceState?.getBoolean(IS_NOTES_DIALOG_OPEN, false) == true) {
            pendingNotesDraft = savedInstanceState.getString(NOTES_DRAFT).orEmpty()
        }

        if (!isWarningAcknowledged && uid == Process.myUid()) {
            state.showRethinkWarning = true
            warningBackCallback.isEnabled = true
        } else {
            proceedWithInit()
        }
    }

    override fun onResume() {
        super.onResume()
        // pick up changes made elsewhere (proxy screens, rules, proxy health) on return
        if (sectionsWired) load()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(IS_WARNING_ACKNOWLEDGED, isWarningAcknowledged)
        val notes = state.dialog as? AppDetailsDialog.Notes
        outState.putBoolean(IS_NOTES_DIALOG_OPEN, notes != null)
        outState.putString(NOTES_DRAFT, notes?.draft?.text.orEmpty())
    }

    override fun onDestroy() {
        insightsObservers.forEach { (presenter, observer) ->
            runCatching { presenter.removeSnapshotListener(observer) }
        }
        insightsObservers.clear()
        state.insightsSnapshots.clear()
        super.onDestroy()
    }

    private fun proceedWithInit() {
        isWarningAcknowledged = true
        state.showRethinkWarning = false
        warningBackCallback.isEnabled = false

        ipRulesViewModel.setUid(uid)
        domainRulesViewModel.setUid(uid)
        networkLogsViewModel.setUid(uid)
        state.viewMode = StatsViewMode.fromId(persistentState.statsViewMode)
        load()
        observeRuleCounts()
        observeNotesEvents()
    }

    // ---- loading ----

    /** Reads the app and everything the screen shows; wires the activity lists on first load. */
    private fun load() {
        io {
            val info = if (requestedPackageName.isNullOrBlank()) {
                FirewallManager.getAppInfoByUid(uid)
            } else {
                FirewallManager.getAppInfoByUidAndPackage(uid, requestedPackageName)
                    ?: FirewallManager.getAppInfoByUid(uid)
            }
            // the app is uninstalled but still in Rethink's database, or the uid is invalid
            if (info == null || uid == INVALID_UID || info.tombstoneTs > 0) {
                uiCtx { state.dialog = AppDetailsDialog.NoApp }
                return@io
            }
            val ui = readUi(info)
            uiCtx {
                appInfo = info
                state.ui = ui.copy(ipRuleCount = state.ui.ipRuleCount, domainRuleCount = state.ui.domainRuleCount)
                if (!sectionsWired) {
                    sectionsWired = true
                    state.icon = Utilities.getIcon(this, info.packageName, info.appName)
                        ?: Utilities.getDefaultIcon(this)
                    wireActivitySections(info.packageName == RETHINK_PACKAGE)
                    pendingNotesDraft?.let { draft ->
                        pendingNotesDraft = null
                        state.dialog = AppDetailsDialog.Notes(AppNotesDraft(draft))
                    }
                }
            }
        }
    }

    private suspend fun readUi(info: AppInfo): AppDetailsUi {
        val names = FirewallManager.getAppNamesByUid(info.uid).toMutableList()
        names.remove(info.appName)
        val (proxy, rpn) = readProxy()
        return AppDetailsUi(
            loaded = true,
            uid = info.uid,
            appName = info.appName,
            packageName = info.packageName,
            otherAppNames = names,
            uploadBytes = info.uploadBytes,
            downloadBytes = info.downloadBytes,
            modifiedTs = info.modifiedTs,
            firewall = FirewallManager.appStatus(info.uid),
            connection = FirewallManager.connectionStatus(info.uid),
            tempAllowed = FirewallManager.isTempAllowed(info.uid),
            tempAllowExpiry = info.tempAllowExpiryTime,
            notes = info.notes,
            excludeBlocker = AppDetailsLogic.excludeBlocker(
                FirewallManager.isUnknownPackage(uid), VpnController.isVpnLockdown()
            ),
            proxy = proxy,
            rpnNames = rpn,
        )
    }

    private suspend fun readProxy() = run {
        val choices = try {
            proxyChoices.list()
        } catch (e: Exception) {
            Logger.w(LOG_TAG_UI, "$TAG: proxy list failed: ${e.message}")
            emptyList()
        }
        val actual = FirewallManager.isAppExcludedFromProxy(uid)
        val excluded = excludedHint ?: actual
        if (excludedHint == actual) excludedHint = null
        val selection = ProxyChoices.currentForApp(ProxyManager.getProxyForApp(uid), excluded)
        AppDetailsLogic.proxyRow(selection, choices) to rpnNames(ProxyManager.getProxyIdForApp(uid))
    }

    /** RPN exits the app is mapped to, as names ("RPN Auto", "RPN <country>"). */
    private suspend fun rpnNames(ids: List<String>): List<String> {
        val rpnIds = ids.filter { it != ID_NONE && it.startsWith(Backend.RpnWin) }
        return rpnIds.map {
            val name = RpnProxyManager.getCountryConfigByKey(it.substring(Backend.RpnWin.length))?.name ?: it
            if (name == RpnProxyManager.AUTO_SERVER_ID) {
                getString(R.string.rpn_title) + " " + getString(R.string.server_settings_config_mode_auto)
            } else {
                getString(R.string.rpn_title) + " " + name
            }
        }
    }

    private fun refreshProxy() {
        io {
            val (proxy, rpn) = readProxy()
            uiCtx { state.ui = state.ui.copy(proxy = proxy, rpnNames = rpn) }
        }
    }

    private fun observeRuleCounts() {
        ipRulesViewModel.ipRulesCount(uid).observe(this) { state.ui = state.ui.copy(ipRuleCount = it) }
        domainRulesViewModel.domainRulesCount(uid).observe(this) { state.ui = state.ui.copy(domainRuleCount = it) }
    }

    private fun observeNotesEvents() {
        appInfoViewModel.notesSaveSuccessEvent.observe(this) { event ->
            event.getIfNotHandled()?.let { saved ->
                isNotesSaveInFlight = false
                val info = appInfo
                if (info == null) {
                    Logger.w(LOG_TAG_UI, "$TAG: notes saved before app info loaded, uid: $uid")
                    return@let
                }
                info.notes = saved
                state.ui = state.ui.copy(notes = saved)
                logEvent("app notes updated", "Notes updated for ${info.appName} ($uid)")
            }
        }
        appInfoViewModel.notesErrorEvent.observe(this) { event ->
            event.getIfNotHandled()?.let { messageResId ->
                isNotesSaveInFlight = false
                showToastUiCentered(this, getString(messageResId), Toast.LENGTH_SHORT)
            }
        }
    }

    // ---- activity sections ----

    private fun wireActivitySections(isRethink: Boolean) {
        val vm = networkLogsViewModel
        val uptime = VpnController.uptimeMs()
        Logger.i(LOG_TAG_UI, "$TAG: active conns, uptime: $uptime ms")
        wireSection(
            AppInsightsSection.ACTIVE_CONNS,
            AppWiseDomainsAdapter(this, this, uid, isActiveConn = true),
            if (isRethink) vm.getRethinkActiveConnsLimited(uptime) else vm.fetchTopActiveConnections(uid, uptime),
        )
        // rethink's own logs live in RethinkLog, so they have their own queries; no ASN for them
        wireSection(
            AppInsightsSection.COUNTRIES,
            AppWiseCountriesAdapter(this, this),
            if (isRethink) vm.getRethinkCountriesLimited() else vm.getMostContactedCountriesLimited(uid),
        )
        if (!isRethink && persistentState.downloadIpInfo) {
            wireSection(AppInsightsSection.ASN, AppWiseIpsAdapter(this, this, uid, isAsn = true), vm.getAsnLogsLimited(uid))
            wireSection(AppInsightsSection.BLOCKED_ASN, AppWiseIpsAdapter(this, this, uid, isAsn = true), vm.getBlockedAsnLogsLimited(uid))
        }
        wireBlocklists()
        wireSection(
            AppInsightsSection.DOMAINS,
            AppWiseDomainsAdapter(this, this, uid),
            if (isRethink) vm.getRethinkDomainLogsLimited() else vm.getDomainLogsLimited(uid),
        )
        wireSection(
            AppInsightsSection.BLOCKED_DOMAINS,
            AppWiseDomainsAdapter(this, this, uid),
            if (isRethink) vm.getRethinkBlockedDomainLogsLimited() else vm.getBlockedDomainLogsLimited(uid),
        )
        wireSection(
            AppInsightsSection.IPS,
            AppWiseIpsAdapter(this, this, uid),
            if (isRethink) vm.getRethinkIpLogsLimited() else vm.getIpLogsLimited(uid),
        )
        wireSection(
            AppInsightsSection.BLOCKED_IPS,
            AppWiseIpsAdapter(this, this, uid),
            if (isRethink) vm.getRethinkBlockedIpLogsLimited() else vm.getBlockedIpLogsLimited(uid),
        )
    }

    /** A section is shown once its first load completes with at least one row. */
    private fun wireSection(
        section: AppInsightsSection,
        presenter: AppConnectionsPresenter,
        data: LiveData<PagingData<AppConnection>>,
    ) {
        hookInsightsSnapshot(section, presenter)
        state.presenters[section] = presenter
        data.observe(this) { presenter.submitData(this.lifecycle, it) }
        presenter.addLoadStateListener {
            if (it.append.endOfPaginationReached) state.sectionHasData[section] = presenter.itemCount >= 1
        }
    }

    /**
     * Most-blocked blocklists: aggregated in memory (the blocklist CSV can't be grouped in SQL),
     * so the list arrives whole. Blocklists only block when DNS is on.
     */
    private fun wireBlocklists() {
        if (!appConfig.getBraveMode().isDnsActive()) return
        val presenter = SummaryStatisticsAdapter(
            this, persistentState, appConfig,
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_BLOCKED_BLOCKLISTS
        )
        // scope row drill-downs to this app
        presenter.setUid(uid)
        presenter.setTimeCategory(SummaryStatisticsViewModel.TimeCategory.SEVEN_DAYS)
        hookInsightsSnapshot(AppInsightsSection.BLOCKLISTS, presenter)
        state.presenters[AppInsightsSection.BLOCKLISTS] = presenter
        networkLogsViewModel.mostBlockedBlocklists.observe(this) { items ->
            presenter.submitData(this.lifecycle, PagingData.from(items))
            state.sectionHasData[AppInsightsSection.BLOCKLISTS] = items.isNotEmpty()
        }
    }

    private fun hookInsightsSnapshot(section: AppInsightsSection, presenter: AppConnectionsPresenter) {
        val observer: () -> Unit = { state.insightsSnapshots[section] = presenter.snapshot().items }
        presenter.addSnapshotListener(observer)
        insightsObservers.add(presenter to observer)
    }

    // ---- AppDetailsActions ----

    override fun acknowledgeRethinkWarning() = proceedWithInit()

    override fun openAndroidAppInfo() {
        val info = appInfo
        if (info == null) {
            showToastUiCentered(this, getString(R.string.ctbs_app_info_not_available_toast), Toast.LENGTH_SHORT)
            return
        }
        io {
            val names = FirewallManager.getAppNamesByUid(uid)
            uiCtx {
                when {
                    names.size == 1 -> openAndroidAppInfo(this, info.packageName)
                    names.size > 1 -> state.dialog = AppDetailsDialog.PickAppInfo(names)
                    else -> showToastUiCentered(this, getString(R.string.ctbs_app_info_not_available_toast), Toast.LENGTH_SHORT)
                }
            }
        }
    }

    override fun openAndroidAppInfoFor(appName: String) {
        io {
            val pkg = FirewallManager.getPackageNameByAppName(appName)
            uiCtx {
                Logger.i(LOG_TAG_UI, "$TAG: app info for package: $pkg")
                state.dialog = null
                openAndroidAppInfo(applicationContext, pkg)
            }
        }
    }

    override fun openNotes() {
        if (isNotesSaveInFlight) {
            showToastUiCentered(this, getString(R.string.lbl_saving), Toast.LENGTH_SHORT)
            return
        }
        if (!guardLoaded()) return
        state.dialog = AppDetailsDialog.Notes(AppNotesDraft(state.ui.notes))
    }

    override fun saveNotes(text: String) {
        val info = appInfo ?: return
        if (isNotesSaveInFlight) {
            showToastUiCentered(this, getString(R.string.lbl_saving), Toast.LENGTH_SHORT)
            return
        }
        state.dialog = null
        isNotesSaveInFlight = true
        appInfoViewModel.updateAppNotes(info.uid, info.packageName, text)
    }

    override fun dismissDialog() {
        state.dialog = null
    }

    override fun finishNoApp() {
        state.dialog = null
        finish()
    }

    override fun requestCloseConnections() {
        state.dialog = AppDetailsDialog.CloseConnections
    }

    override fun closeConnections() {
        state.dialog = null
        VpnController.closeConnectionsIfNeeded(uid, "app-info-dialog-manual-close")
        Logger.i(LOG_TAG_UI, "$TAG closed connections for uid: $uid")
        showToastUiCentered(this, getString(R.string.config_add_success_toast), Toast.LENGTH_LONG)
        logEvent("close connections", "Closed active connections for ${state.ui.appName} ($uid) from AppInfoActivity")
    }

    override fun setTempAllow(enabled: Boolean) {
        if (!guardLoaded()) return
        val expiry = if (enabled) System.currentTimeMillis() + TEMP_ALLOW_DURATION_MS else 0L
        state.ui = state.ui.copy(tempAllowed = enabled, tempAllowExpiry = expiry)
        io {
            FirewallManager.updateTempAllow(uid, enabled)
            logEvent(
                "temp allow change",
                "Temporary allow status changed for ${state.ui.appName} ($uid), new status: $enabled"
            )
        }
    }

    override fun toggleWifi() {
        if (!guardLoaded()) return
        io {
            val change = AppDetailsLogic.wifiChange(FirewallManager.connectionStatus(uid))
            requestFirewallChange(change)
        }
    }

    override fun toggleMobileData() {
        if (!guardLoaded()) return
        io {
            val change = AppDetailsLogic.mobileChange(FirewallManager.connectionStatus(uid))
            requestFirewallChange(change)
        }
    }

    override fun selectMode(mode: AppNetworkMode) {
        if (!guardLoaded()) return
        if (mode == AppNetworkMode.EXCLUDE) {
            when (state.ui.excludeBlocker) {
                ExcludeBlocker.VPN_LOCKDOWN -> {
                    showToastUiCentered(this, getString(R.string.hsf_exclude_error), Toast.LENGTH_SHORT)
                    return
                }
                ExcludeBlocker.UNKNOWN_PACKAGE -> {
                    showToastUiCentered(this, getString(R.string.exclude_no_package_err_toast), Toast.LENGTH_LONG)
                    return
                }
                ExcludeBlocker.NONE -> Unit
            }
        }
        val change = AppDetailsLogic.modeChange(state.ui.firewall, mode) ?: return
        io { requestFirewallChange(change) }
    }

    /** Rules apply per uid: when other apps share it, ask before changing them all. */
    private suspend fun requestFirewallChange(change: FirewallChange) {
        val names = FirewallManager.getAppNamesByUid(uid)
        uiCtx {
            if (names.size > 1) {
                state.dialog = AppDetailsDialog.ConfirmSharedUid(names, change)
            } else {
                applyFirewallChange(change)
            }
        }
    }

    override fun confirmFirewallChange(change: FirewallChange) {
        state.dialog = null
        applyFirewallChange(change)
    }

    private fun applyFirewallChange(change: FirewallChange) {
        state.ui = state.ui.copy(firewall = change.firewall, connection = change.connection)
        io { FirewallManager.updateFirewallStatus(uid, change.firewall, change.connection) }
        logEvent(
            "firewall rule change",
            "Firewall status changed for ${state.ui.appName} ($uid), new status: ${change.firewall}, conn status: ${change.connection}"
        )
    }

    override fun openProxyPicker() {
        if (!guardLoaded()) return
        state.showProxyPicker = true
    }

    override fun onProxyChanged(pick: ProxyChoices.Pick) {
        excludedHint = pick == ProxyChoices.Pick.Bypass
        logEvent("proxy change", "Proxy for ${state.ui.appName} ($uid) set to $pick")
    }

    override fun closeProxyPicker() {
        state.showProxyPicker = false
        refreshProxy()
    }

    override fun openIpRules() = openCustomRules(CustomRulesActivity.Tabs.IP_RULES.screen)

    override fun openDomainRules() = openCustomRules(CustomRulesActivity.Tabs.DOMAIN_RULES.screen)

    private fun openCustomRules(tab: Int) {
        val intent = Intent(this, CustomRulesActivity::class.java)
        intent.putExtra(VIEW_PAGER_SCREEN_TO_LOAD, tab)
        intent.putExtra(Constants.INTENT_UID, uid)
        startActivity(intent)
    }

    override fun setViewMode(mode: StatsViewMode) {
        if (mode == state.viewMode) return
        state.viewMode = mode
        persistentState.statsViewMode = mode.id
    }

    override fun openSection(section: AppInsightsSection) {
        when (section) {
            AppInsightsSection.ACTIVE_CONNS -> openAppWiseDomainLogs(activeConns = true)
            AppInsightsSection.COUNTRIES -> openAppWiseIpLogs(country = true)
            AppInsightsSection.ASN -> openAppWiseIpLogs(asn = true, isBlocked = false)
            AppInsightsSection.BLOCKED_ASN -> openAppWiseIpLogs(asn = true, isBlocked = true)
            AppInsightsSection.BLOCKLISTS ->
                openDetailedStats(SummaryStatisticsFragment.SummaryStatisticsType.MOST_BLOCKED_BLOCKLISTS)
            AppInsightsSection.DOMAINS -> openAppWiseDomainLogs(isBlocked = false)
            // rethink's blocked domains live in RethinkLog, which the detail screen doesn't read
            AppInsightsSection.BLOCKED_DOMAINS ->
                if (Process.myUid() == uid) openAppWiseDomainLogs(isBlocked = true)
                else openDetailedStats(SummaryStatisticsFragment.SummaryStatisticsType.MOST_BLOCKED_DOMAINS)
            AppInsightsSection.IPS -> openAppWiseIpLogs(isBlocked = false)
            AppInsightsSection.BLOCKED_IPS -> openAppWiseIpLogs(isBlocked = true)
        }
    }

    override fun openDomainLogs() = openAppWiseDomainLogs()

    override fun openIpLogs() = openAppWiseIpLogs()

    private fun openAppWiseDomainLogs(activeConns: Boolean = false, isBlocked: Boolean? = null) {
        val intent = Intent(this, AppWiseDomainLogsActivity::class.java)
        intent.putExtra(INTENT_UID, uid)
        intent.putExtra(INTENT_ACTIVE_CONNS, activeConns)
        isBlocked?.let { intent.putExtra(INTENT_IS_BLOCKED, it) }
        startActivity(intent)
    }

    private fun openAppWiseIpLogs(asn: Boolean = false, country: Boolean = false, isBlocked: Boolean? = null) {
        val intent = Intent(this, AppWiseIpLogsActivity::class.java)
        intent.putExtra(INTENT_UID, uid)
        intent.putExtra(INTENT_ASN, asn)
        intent.putExtra(INTENT_COUNTRY, country)
        isBlocked?.let { intent.putExtra(INTENT_IS_BLOCKED, it) }
        startActivity(intent)
    }

    /** The stats screen's per-type drill-down, scoped to this uid over 7 days. */
    private fun openDetailedStats(type: SummaryStatisticsFragment.SummaryStatisticsType) {
        val intent = Intent(this, DetailedStatisticsActivity::class.java)
        intent.putExtra(DetailedStatisticsActivity.INTENT_TYPE, type.tid)
        intent.putExtra(
            DetailedStatisticsActivity.INTENT_TIME_CATEGORY,
            SummaryStatisticsViewModel.TimeCategory.SEVEN_DAYS.value
        )
        intent.putExtra(DetailedStatisticsActivity.INTENT_UID, uid)
        startActivity(intent)
    }

    // ---- helpers ----

    private fun guardLoaded(): Boolean {
        if (appInfo != null) return true
        Logger.w(LOG_TAG_UI, "$TAG: app info not loaded yet, uid: $uid")
        showToastUiCentered(this, getString(R.string.ctbs_app_info_not_available_toast), Toast.LENGTH_SHORT)
        return false
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun logEvent(msg: String, details: String) {
        eventLogger.log(EventType.FW_RULE_MODIFIED, Severity.LOW, msg, EventSource.UI, true, details)
    }

    private fun io(f: suspend () -> Unit): Job {
        return lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            if (!isFinishing && !isDestroyed) f()
        }
    }
}
