/* Copyright 2025 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.activity

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Looper
import android.os.Process
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.AppInfoDAO
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.database.ConnectionTrackerDAO
import com.celzero.bravedns.database.CustomDomainDAO
import com.celzero.bravedns.database.CustomIpDao
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.RethinkLogDao
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.database.StatsSummaryDao
import com.celzero.bravedns.glide.FavIconDownloader
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.FirewallManager.ConnectionStatus
import com.celzero.bravedns.service.FirewallManager.FirewallStatus
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyChoices
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.compose.appdetails.AppDetailsDialog
import com.celzero.bravedns.ui.compose.appdetails.AppDetailsState
import androidx.compose.ui.text.input.TextFieldValue
import com.celzero.bravedns.ui.compose.appdetails.AppInsightsSection
import com.celzero.bravedns.ui.compose.appdetails.ProxyRowState
import com.celzero.bravedns.ui.stats.StatsViewMode
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.AppConnectionsViewModel
import com.celzero.bravedns.viewmodel.AppInfoViewModel
import com.celzero.bravedns.viewmodel.CustomDomainViewModel
import com.celzero.bravedns.viewmodel.CustomIpViewModel
import io.mockk.Called
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowProcess
import java.util.Collections

/**
 * AppInfoActivity (app details) host tests in a plain-Application JVM sandbox, so launching never
 * starts RethinkDnsApplication or its services.
 *
 * Real lifecycle-owned ViewModels use strict constructor-injected DAO doubles and in-memory
 * PagingSources. No Room/repository constructor, disk cache, network client, VPN service, native
 * trie or permission flow is reachable. FirewallManager (the only singleton with an eager worker)
 * is initialized after its strict empty repository is installed; its two startup jobs are joined
 * BEFORE its operations are mocked. The proxy picker's live source is mocked (no WireGuard /
 * AppConfig access).
 *
 * The "Performance"/memory cases run the old workloads deterministically; they do not certify
 * device timings or heap limits.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w420dp-h900dp")
class AppInfoActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var persistent: PersistentState
    private lateinit var config: AppConfig
    private lateinit var events: EventLogger
    private lateinit var startupRepository: AppInfoRepository
    private lateinit var appDao: AppInfoDAO
    private lateinit var ipDao: CustomIpDao
    private lateinit var domainDao: CustomDomainDAO
    private lateinit var connectionDao: ConnectionTrackerDAO
    private lateinit var rethinkDao: RethinkLogDao
    private lateinit var statsDao: StatsSummaryDao
    private val apps = mutableMapOf<Int, AppInfo>()
    private val controllers = mutableListOf<ActivityController<AppInfoActivity>>()
    private lateinit var controller: ActivityController<AppInfoActivity>
    private val firewallCommands = Collections.synchronizedList(mutableListOf<Triple<Int, FirewallStatus, ConnectionStatus>>())
    private val androidInfoPackages = Collections.synchronizedList(mutableListOf<String>())
    private val ipCount = MutableLiveData(3)
    private val domainCount = MutableLiveData(5)
    private var statsMode = StatsViewMode.LIST.id
    private var missing = false
    private var unknownPackage = false
    private var lockdown = false
    private var emptyLogs = false
    private var sharedUidNames: List<String>? = null
    private var appProxy: ProxyManager.AppProxy = ProxyManager.AppProxy.None
    private var proxyExcluded = false
    private var appStatus = FirewallStatus.NONE
    private var connStatus = ConnectionStatus.ALLOW

    companion object {
        private const val TEST_UID = 12345
        private const val TEST_PACKAGE = "example.synthetic.app"
        private const val TEST_NAME = "Synthetic app"
        private const val OTHER_NAME = "Synthetic sibling"
        private const val DOMAIN = "allowed.example"
        private const val ACTIVE_IP = "192.0.2.1"
        private const val DESTINATION_IP = "192.0.2.2"
        private const val S5_ENDPOINT = "198.51.100.7:1080"
    }

    @Before fun setUp() {
        assertEquals(Application::class.java, RuntimeEnvironment.getApplication().javaClass)
        ShadowProcess.setUid(10001)
        assertNotEquals("Fixture must not accidentally enter the self-app warning", TEST_UID, Process.myUid())
        check(GlobalContext.getOrNull() == null) { "This fixture requires its own empty Koin graph" }
        persistent = mockk(); config = mockk(); events = mockk(); startupRepository = mockk()
        appDao = mockk(); ipDao = mockk(); domainDao = mockk(); connectionDao = mockk(); rethinkDao = mockk(); statsDao = mockk()
        every { persistent.theme } returns 0
        every { persistent.statsViewMode } answers { statsMode }
        every { persistent.statsViewMode = any() } answers { statsMode = firstArg() }
        every { persistent.downloadIpInfo } returns true
        every { config.getBraveMode() } returns AppConfig.BraveMode.DNS_FIREWALL
        every { events.log(EventType.FW_RULE_MODIFIED, Severity.LOW, any(), EventSource.UI, true, any()) } just Runs
        // These two and only these two repository reads are safe during singleton initialization.
        coEvery { startupRepository.getAppInfo() } returns emptyList()
        coEvery { startupRepository.getTempAllowedApps() } returns emptyList()
        every { ipDao.getAppWiseIpRulesCount(any()) } returns ipCount
        every { domainDao.getAppWiseDomainRulesCount(any()) } returns domainCount
        every { statsDao.getTopActiveConns(any<Int>(), any()) } answers { page(firstArg(), ACTIVE_IP, "active.example") }
        every { statsDao.getMostDomainsByUid(any(), any()) } answers { page(firstArg(), "", DOMAIN) }
        every { statsDao.getAsnLogsLimited(any(), any()) } answers { page(firstArg(), "AS64500", "Synthetic ASN") }
        every { statsDao.getMostContactedCountriesByUid(any(), any()) } answers { page(firstArg(), "", "", flag = "🇺🇸") }
        every { statsDao.getBlockedDomainsByUid(any(), any()) } answers { emptyPage() }
        every { statsDao.getBlockedAsnByUid(any(), any()) } answers { emptyPage() }
        every { connectionDao.getAppIpLogsLimited(any(), any()) } answers { page(firstArg(), DESTINATION_IP, "destination.example") }
        every { connectionDao.getBlockedAppIpLogsLimited(any(), any()) } answers { emptyPage() }
        coEvery { statsDao.getBlockedBlocklistCombosByUid(any(), any()) } returns emptyList()
        startKoin { modules(module {
            single { persistent }; single { config }; single { events }; single { startupRepository }
            viewModel { AppInfoViewModel(appDao) }
            viewModel { CustomIpViewModel(ipDao) }
            viewModel { CustomDomainViewModel(domainDao) }
            viewModel { AppConnectionsViewModel(connectionDao, rethinkDao, statsDao, mockk<com.celzero.bravedns.service.LogActivityAggregator>(relaxed = true)) }
        }) }
        // Install log sinks BEFORE FirewallManager's first reference: its empty startup logs cannot
        // fall through to VpnController/console storage. Logger has only lazy injected properties.
        mockkObject(Logger)
        every { Logger.vv(any(), any()) } just Runs
        every { Logger.v(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
        initializeEmptyFirewallManager()
        mockkObject(FirewallManager, ProxyManager, ProxyChoices.LiveSource, VpnController, DomainRulesManager, IpRulesManager, Utilities, UIUtils, FavIconDownloader.Companion)
        listOf(TEST_UID, 0, 1000, Int.MAX_VALUE).forEach { apps[it] = syntheticApp(it) }
        coEvery { FirewallManager.getAppInfoByUid(any()) } answers { if (missing) null else apps[firstArg()] }
        coEvery { FirewallManager.getAppInfoByUidAndPackage(any(), any()) } answers { if (missing) null else apps[firstArg()] }
        coEvery { FirewallManager.getPackageNamesByUid(any()) } answers { listOf(requireNotNull(apps[firstArg()]).packageName) }
        coEvery { FirewallManager.getAppNamesByUid(any()) } answers { sharedUidNames ?: listOf(requireNotNull(apps[firstArg()]).appName) }
        coEvery { FirewallManager.getPackageNameByAppName(any()) } answers { "pkg.of." + firstArg<String>() }
        coEvery { FirewallManager.appStatus(any()) } answers { appStatus }
        coEvery { FirewallManager.connectionStatus(any()) } answers { connStatus }
        coEvery { FirewallManager.isTempAllowed(any()) } returns false
        coEvery { FirewallManager.isAppExcludedFromProxy(any()) } answers { proxyExcluded }
        every { FirewallManager.isUnknownPackage(any()) } answers { unknownPackage }
        every { FirewallManager.updateIsProxyExcluded(any(), any()) } just Runs
        coEvery { FirewallManager.updateTempAllow(any(), any()) } just Runs
        coEvery { FirewallManager.updateAppNotes(any(), any(), any()) } just Runs
        coEvery { FirewallManager.updateFirewallStatus(any(), any(), any()) } answers {
            firewallCommands.add(Triple(firstArg(), secondArg(), thirdArg())); Unit
        }
        every { ProxyManager.getProxyIdForApp(any()) } returns emptyList()
        coEvery { ProxyManager.getProxyForApp(any()) } answers { appProxy }
        coEvery { ProxyManager.setProxyForApp(any(), any(), any(), any()) } returns true
        every { ProxyManager.proxyDisplayName(any()) } answers { firstArg() }
        every { ProxyChoices.LiveSource.wireguardConfigs() } returns emptyList()
        every { ProxyChoices.LiveSource.isSocks5Enabled() } returns true
        coEvery { ProxyChoices.LiveSource.socks5Endpoint() } returns S5_ENDPOINT
        every { ProxyChoices.LiveSource.isOrbotEnabled() } returns false
        every { ProxyChoices.LiveSource.isVpnOn() } returns false
        coEvery { ProxyChoices.LiveSource.backendStatus(any()) } returns null
        every { VpnController.uptimeMs() } returns 60_000L
        every { VpnController.isVpnLockdown() } answers { lockdown }
        every { VpnController.closeConnectionsIfNeeded(any(), any()) } just Runs
        // Explicit denial trips if future lifecycle code tries to start/stop the real service.
        every { VpnController.start(any(), any()) } answers { error("Forbidden service start in AppInfo host fixture") }
        every { VpnController.stop(any(), any(), any()) } answers { error("Forbidden service stop in AppInfo host fixture") }
        every { DomainRulesManager.status(any(), any()) } returns DomainRulesManager.Status.NONE
        every { IpRulesManager.getMostSpecificRuleMatch(any(), any(), any()) } returns IpRulesManager.IpRuleStatus.NONE
        every { Utilities.getIcon(any(), any(), any()) } answers { ColorDrawable(Color.BLUE) }
        every { UIUtils.openAndroidAppInfo(any(), any()) } answers {
            androidInfoPackages.add(secondArg<String?>().orEmpty()); Unit
        }
        // A failed-cache hit renders the flag and never constructs a Glide request/cache target.
        every { FavIconDownloader.isUrlAvailableInFailedCache(any()) } returns true
        ShadowDialog.reset()
    }

    private fun initializeEmptyFirewallManager() {
        // Reading the singleton starts ONLY the two initialization coroutines, whose repository is
        // already the strict double above. Wait for both before installing object interception.
        val singleton = FirewallManager
        val field = FirewallManager::class.java.getDeclaredField("scope").apply { isAccessible = true }
        val scope = field.get(singleton) as CoroutineScope
        runBlocking { withTimeout(5000) { requireNotNull(scope.coroutineContext[Job]).children.toList().joinAll() } }
        assertFalse(requireNotNull(scope.coroutineContext[Job]).children.any())
    }

    @After fun tearDown() {
        ShadowDialog.getShownDialogs().filter { it.isShowing }.forEach { it.dismiss() }
        controllers.asReversed().forEach { if (!it.get().isDestroyed) it.pause().stop().destroy() }
        val lifecycleJobs = controllers.map { requireNotNull(it.get().lifecycleScope.coroutineContext[Job]) }
        // IO children can have cancelled continuations queued on Robolectric's paused main looper.
        // Never block that looper in joinAll: drain it until every job is complete.
        compose.waitUntil(5000) {
            shadowOf(Looper.getMainLooper()).idle()
            lifecycleJobs.all { it.isCompleted }
        }
        runBlocking { withTimeout(5000) { lifecycleJobs.joinAll() } }
        verify { appDao wasNot Called }
        verify { rethinkDao wasNot Called }
        coVerify(exactly = 0) { startupRepository.insert(any()) }
        coVerify(exactly = 0) { startupRepository.updateFirewallStatusByUid(any(), any(), any()) }
        verify(exactly = 0) { VpnController.start(any(), any()) }
        verify(exactly = 0) { VpnController.stop(any(), any(), any()) }
        unmockkObject(FirewallManager, ProxyManager, ProxyChoices.LiveSource, VpnController, DomainRulesManager, IpRulesManager, Utilities, UIUtils, FavIconDownloader.Companion)
        unmockkObject(Logger)
        stopKoin(); ShadowDialog.reset(); ShadowProcess.reset()
    }

    private fun syntheticApp(uid: Int) = AppInfo(null).apply {
        this.uid = uid; packageName = TEST_PACKAGE; appName = if (uid == TEST_UID) TEST_NAME else "Synthetic UID $uid"
        isSystemApp = uid == 1000; uploadBytes = 1200; downloadBytes = 3400
    }
    private fun page(uid: Int, ip: String, name: String, flag: String = "🇺🇸"): PagingSource<Int, AppConnection> =
        SyntheticConnectionPage(if (emptyLogs) emptyList() else listOf(AppConnection(uid, ip, 443, 3, flag, false, name)))
    private fun emptyPage(): PagingSource<Int, AppConnection> = SyntheticConnectionPage(emptyList())
    private class SyntheticConnectionPage(private val rows: List<AppConnection>) : PagingSource<Int, AppConnection>() {
        override val jumpingSupported = true
        override fun getRefreshKey(state: PagingState<Int, AppConnection>): Int? = null
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, AppConnection> = LoadResult.Page(rows, null, null, 0, 0)
    }
    private fun launch(uid: Int? = TEST_UID, emptyExtras: Boolean = false): AppInfoActivity {
        val intent = Intent(RuntimeEnvironment.getApplication(), AppInfoActivity::class.java).apply {
            uid?.let { putExtra(AppInfoActivity.INTENT_UID, it) }
            if (emptyExtras) putExtras(android.os.Bundle())
        }
        controller = Robolectric.buildActivity(AppInfoActivity::class.java, intent)
        controllers.add(controller)
        controller.create().start().resume().visible()
        compose.waitForIdle()
        return controller.get()
    }
    private fun host() = controller.get()
    private fun str(id: Int, vararg args: Any) = host().getString(id, *args)
    private fun state(activity: AppInfoActivity = host()): AppDetailsState = AppInfoActivity::class.java.getDeclaredField("state").let {
        it.isAccessible = true; it.get(activity) as AppDetailsState
    }
    private fun awaitMain(condition: () -> Boolean) {
        // waitUntil advances Compose frames, but does not drain Android's paused main looper.
        compose.waitUntil(5000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.runOnIdle(condition)
        }
    }
    private fun ready(uid: Int = TEST_UID) {
        awaitMain { state().ui.loaded && state().ui.appName == requireNotNull(apps[uid]).appName }
        compose.onAllNodesWithText(requireNotNull(apps[uid]).appName, substring = true, useUnmergedTree = true).onLast().assertExists()
        assertFalse(host().isFinishing)
    }
    private fun noApp(uid: Int? = null, emptyExtras: Boolean = false) {
        launch(uid, emptyExtras)
        compose.waitUntil(5000) { compose.onAllNodesWithText(str(R.string.ada_noapp_dialog_title)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(str(R.string.ada_noapp_dialog_title)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.ada_noapp_dialog_positive)).performClick()
        assertTrue(host().isFinishing)
        assertTrue(firewallCommands.isEmpty())
    }
    private fun mode(title: Int): SemanticsNodeInteraction = compose.onNode(hasText(str(title)) and isSelectable())
    private fun showAllSections() {
        listOf(R.string.top_active_conns, R.string.most_contacted_asn, R.string.ssv_most_contacted_domain_heading,
            R.string.ssv_most_contacted_ips_heading, R.string.ssv_most_contacted_countries_heading).forEach {
            compose.waitUntil(5000) { compose.onAllNodesWithText(str(it)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(str(it)).performScrollTo().assertIsDisplayed()
        }
    }
    private fun destroyCurrent() { controller.pause().stop().destroy(); assertTrue(host().isDestroyed) }
    private fun assertNoObserverLeaks() {
        assertFalse(ipCount.hasObservers()); assertFalse(domainCount.hasObservers())
        assertTrue(state().insightsSnapshots.isEmpty())
    }
    private fun nextStarted(): Intent = requireNotNull(shadowOf(host()).nextStartedActivity)

    // ---- launch and header ----

    @Test fun testLaunchesSuccessfullyWithValidUid() {
        launch(); ready(); compose.onNodeWithTag("app_details").assertIsDisplayed()
        verify(exactly = 1) { ipDao.getAppWiseIpRulesCount(TEST_UID) }
        verify(exactly = 1) { domainDao.getAppWiseDomainRulesCount(TEST_UID) }
    }
    @Test fun testLaunchWithInvalidUid() { noApp(INVALID_UID) }
    @Test fun testUiElementsAreDisplayedWithValidData() {
        launch(); ready()
        compose.onNodeWithText(str(R.string.app_details_package_uid, TEST_PACKAGE, TEST_UID), useUnmergedTree = true).assertIsDisplayed()
        assertNotNull(state().icon)
        compose.onNodeWithContentDescription(str(R.string.about_settings_app_info)).assertIsDisplayed()
        compose.onNodeWithTag("app_details_summary").assertIsDisplayed().assert(hasText(str(R.string.app_details_access_allowed)))
        compose.onNodeWithTag("app_details_wifi").performScrollTo().assertIsOn()
        compose.onNodeWithTag("app_details_mobile").performScrollTo().assertIsOn()
        compose.onNodeWithTag("app_details_temp_allow").performScrollTo().assertIsOff()
        listOf(R.string.app_details_mode_normal, R.string.app_details_mode_isolate, R.string.app_details_mode_bypass_dns_firewall,
            R.string.app_details_mode_bypass_universal, R.string.app_details_mode_exclude).forEach {
            mode(it).performScrollTo().assertIsDisplayed()
        }
        mode(R.string.app_details_mode_normal).assertIsSelected()
        compose.onNodeWithTag("app_details_proxy").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(str(R.string.proxy_picker_follow_global), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("app_details_ip_rules").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("app_details_domain_rules").performScrollTo().assertIsDisplayed()
        assertEquals(3, state().ui.ipRuleCount); assertEquals(5, state().ui.domainRuleCount)
    }
    @Test fun testUiElementsContentAndInteractions() {
        launch(); ready()
        compose.onNodeWithContentDescription(str(R.string.about_settings_app_info)).performClick()
        awaitMain { androidInfoPackages.size == 1 }
        assertEquals(listOf(TEST_PACKAGE), androidInfoPackages.toList())
        showAllSections()
        // close connections asks first; cancel does nothing, proceed closes
        compose.onNodeWithTag("app_details_close_conns").performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.close_conns_dialog_title)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.lbl_cancel)).performClick()
        verify(exactly = 0) { VpnController.closeConnectionsIfNeeded(any(), any()) }
        compose.onNodeWithTag("app_details_close_conns").performScrollTo().performClick()
        compose.onNodeWithText(str(R.string.lbl_proceed)).performClick()
        verify(exactly = 1) { VpnController.closeConnectionsIfNeeded(TEST_UID, any()) }
    }
    @Test fun testAppSettingsToggleStates() {
        val actions = listOf<Pair<() -> Unit, Pair<FirewallStatus, ConnectionStatus>>>(
            { compose.onNodeWithTag("app_details_wifi").performScrollTo().performClick(); Unit } to (FirewallStatus.NONE to ConnectionStatus.UNMETERED),
            { compose.onNodeWithTag("app_details_mobile").performScrollTo().performClick(); Unit } to (FirewallStatus.NONE to ConnectionStatus.METERED),
            { mode(R.string.app_details_mode_isolate).performScrollTo().performClick(); Unit } to (FirewallStatus.ISOLATE to ConnectionStatus.ALLOW),
            { mode(R.string.app_details_mode_bypass_dns_firewall).performScrollTo().performClick(); Unit } to (FirewallStatus.BYPASS_DNS_FIREWALL to ConnectionStatus.ALLOW),
            { mode(R.string.app_details_mode_bypass_universal).performScrollTo().performClick(); Unit } to (FirewallStatus.BYPASS_UNIVERSAL to ConnectionStatus.ALLOW),
            { mode(R.string.app_details_mode_exclude).performScrollTo().performClick(); Unit } to (FirewallStatus.EXCLUDE to ConnectionStatus.ALLOW))
        actions.forEachIndexed { index, (act, expected) ->
            launch(); ready(); act()
            awaitMain { firewallCommands.size == index + 1 }
            assertEquals(Triple(TEST_UID, expected.first, expected.second), firewallCommands[index])
            destroyCurrent(); assertNoObserverLeaks()
        }
    }
    @Test fun modeRows_reflectStoredState_andSelectingCurrentModeIsNoop() {
        appStatus = FirewallStatus.ISOLATE; connStatus = ConnectionStatus.UNMETERED
        launch(); ready()
        mode(R.string.app_details_mode_isolate).performScrollTo().assertIsSelected()
        compose.onNodeWithTag("app_details_wifi").performScrollTo().assertIsOff()
        compose.onNodeWithText(str(R.string.app_details_network_mode_note)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(str(R.string.app_details_access_blocked_wifi), useUnmergedTree = true).assertIsDisplayed()
        mode(R.string.app_details_mode_isolate).performClick()
        compose.waitForIdle()
        assertTrue(firewallCommands.isEmpty())
        // back to Normal: allowed everywhere again
        mode(R.string.app_details_mode_normal).performScrollTo().performClick()
        awaitMain { firewallCommands.size == 1 }
        assertEquals(Triple(TEST_UID, FirewallStatus.NONE, ConnectionStatus.ALLOW), firewallCommands[0])
        mode(R.string.app_details_mode_normal).assertIsSelected()
    }
    @Test fun sharedUid_asksBeforeChangingEveryApp() {
        sharedUidNames = listOf(TEST_NAME, OTHER_NAME)
        launch(); ready()
        compose.onNodeWithText(str(R.string.ctbs_app_other_apps, TEST_NAME, "1"), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("app_details_wifi").performScrollTo().performClick()
        val title = str(R.string.ctbs_block_other_apps, TEST_NAME, "2")
        compose.waitUntil(5000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(OTHER_NAME).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.ctbs_dialog_negative_btn)).performClick()
        compose.waitForIdle()
        assertTrue(firewallCommands.isEmpty())
        compose.onNodeWithTag("app_details_wifi").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(str(R.string.block)).performClick()
        awaitMain { firewallCommands.size == 1 }
        assertEquals(Triple(TEST_UID, FirewallStatus.NONE, ConnectionStatus.UNMETERED), firewallCommands[0])
    }
    @Test fun sharedUid_appInfoAsksWhichApp() {
        sharedUidNames = listOf(TEST_NAME, OTHER_NAME)
        launch(); ready()
        compose.onNodeWithContentDescription(str(R.string.about_settings_app_info)).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText(OTHER_NAME).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(OTHER_NAME).performClick()
        awaitMain { androidInfoPackages.size == 1 }
        assertEquals(listOf("pkg.of.$OTHER_NAME"), androidInfoPackages.toList())
    }

    // ---- proxy ----

    @Test fun proxyRow_showsCurrentProxyInRowAndSummary() {
        appProxy = ProxyManager.AppProxy.Single(ProxyManager.ID_S5_BASE)
        launch(); ready()
        awaitMain { state().ui.proxy !is ProxyRowState.Loading }
        compose.onNodeWithTag("app_details_proxy").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(S5_ENDPOINT, useUnmergedTree = true).assertIsDisplayed()
        val summary = str(R.string.app_details_access_allowed) + str(R.string.app_details_summary_separator) +
            str(R.string.app_details_summary_via, str(R.string.proxy_picker_socks5))
        compose.onNodeWithText(summary, useUnmergedTree = true).assertExists()
    }
    @Test fun proxyRow_legacyMultipleMapping() {
        appProxy = ProxyManager.AppProxy.Multiple(listOf("wg1", ProxyManager.ID_S5_BASE))
        launch(); ready()
        compose.onNodeWithText(str(R.string.proxy_picker_multiple, 2), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }
    @Test fun proxyPicker_noProxyBypassesAllProxies_andRowRefreshes() {
        appProxy = ProxyManager.AppProxy.Single(ProxyManager.ID_S5_BASE)
        launch(); ready()
        compose.onNodeWithTag("app_details_proxy").performScrollTo().performClick()
        val title = str(R.string.proxy_picker_title_app, TEST_NAME)
        compose.waitUntil(5000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText(str(R.string.proxy_picker_bypass)) and isSelectable()).performClick()
        verify(timeout = 2000, exactly = 1) { FirewallManager.updateIsProxyExcluded(TEST_UID, true) }
        // the old switch's semantics: the mapping is kept
        coVerify(exactly = 0) { ProxyManager.setProxyForApp(any(), any(), any(), any()) }
        awaitMain { !state().showProxyPicker && state().ui.proxy == ProxyRowState.NoProxy }
        compose.onNodeWithText(str(R.string.proxy_picker_bypass_desc), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
    }
    @Test fun proxyPicker_pickingDefaultClearsTheMapping() {
        appProxy = ProxyManager.AppProxy.Single(ProxyManager.ID_S5_BASE)
        launch(); ready()
        compose.onNodeWithTag("app_details_proxy").performScrollTo().performClick()
        val title = str(R.string.proxy_picker_title_app, TEST_NAME)
        compose.waitUntil(5000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
        appProxy = ProxyManager.AppProxy.None
        compose.onNode(hasText(str(R.string.proxy_picker_follow_global)) and isSelectable()).performClick()
        coVerify(timeout = 2000, exactly = 1) { ProxyManager.setProxyForApp(TEST_UID, TEST_PACKAGE, null, "") }
        awaitMain { state().ui.proxy == ProxyRowState.Default }
    }

    // ---- other controls ----

    @Test fun testInteractionResponseTimes() {
        launch(); ready(); repeat(4) { compose.onNodeWithTag("app_details_temp_allow").performScrollTo().performClick() }
        compose.onNodeWithTag("app_details_temp_allow").assertIsOff()
        coVerify(timeout = 2000, exactly = 2) { FirewallManager.updateTempAllow(TEST_UID, true) }
        coVerify(timeout = 2000, exactly = 2) { FirewallManager.updateTempAllow(TEST_UID, false) }
    }
    @Test fun tempAllow_showsWhenItEnds() {
        launch(); ready()
        compose.onNodeWithTag("app_details_temp_allow").performScrollTo().performClick().assertIsOn()
        coVerify(timeout = 2000, exactly = 1) { FirewallManager.updateTempAllow(TEST_UID, true) }
        compose.onNodeWithText(str(R.string.app_details_access_temp_allowed), useUnmergedTree = true).assertExists()
        assertTrue(state().ui.tempAllowExpiry > System.currentTimeMillis())
    }
    @Test fun notes_saveShowsInHeader_andSurvivesRecreateWhileOpen() {
        fun frames() = repeat(10) { compose.mainClock.advanceTimeBy(100); shadowOf(Looper.getMainLooper()).idle() }
        launch(); ready()
        // the text field never idles under Robolectric; drive frames by hand while it is shown
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("app_details_notes").performClick()
        frames()
        compose.onNodeWithTag("app_notes_input").assertExists()
        fun draft() = (state().dialog as AppDetailsDialog.Notes).draft
        draft().value = TextFieldValue("draft text"); frames()
        controller.recreate()
        frames()
        repeat(50) { if (!(state().ui.loaded && state().dialog is AppDetailsDialog.Notes)) { Thread.sleep(50); frames() } }
        frames()
        compose.onNodeWithText("draft text").assertExists()
        assertEquals("draft text", draft().text)
        // Save does what the dialog's Save button does (clicking inside the open dialog never idles)
        host().saveNotes("remember this")
        frames()
        assertNull(state().dialog)
        frames()
        compose.mainClock.autoAdvance = true
        coVerify(timeout = 2000, exactly = 1) { FirewallManager.updateAppNotes(TEST_UID, TEST_PACKAGE, "remember this") }
        awaitMain { state().ui.notes == "remember this" }
        assertNull(state().dialog)
        compose.onNodeWithText(str(R.string.app_details_notes_label, "remember this"), useUnmergedTree = true).assertIsDisplayed()
    }
    @Test fun testRecyclerViewsAreInitialized() {
        launch(); ready(); showAllSections()
        listOf(AppInsightsSection.ACTIVE_CONNS, AppInsightsSection.ASN, AppInsightsSection.DOMAINS, AppInsightsSection.IPS).forEach {
            assertEquals(1, requireNotNull(state().presenters[it]).itemCount)
        }
        compose.onNode(hasText(DOMAIN, substring = true)).performScrollTo().assertIsDisplayed()
        verify { statsDao.getMostDomainsByUid(TEST_UID, any()) }
        verify { connectionDao.getAppIpLogsLimited(TEST_UID, any()) }
    }
    @Test fun viewMode_switchesToInsights_andPersists() {
        launch(); ready(); showAllSections()
        compose.onNode(hasText(str(R.string.app_details_view_insights)) and hasClickAction()).performScrollTo().performClick()
        assertEquals(StatsViewMode.INSIGHTS.id, statsMode)
        assertEquals(StatsViewMode.INSIGHTS, state().viewMode)
        compose.onNodeWithText(str(R.string.top_active_conns)).performScrollTo().assertIsDisplayed()
    }
    @Test fun activityLinks_openTheLogScreens() {
        launch(); ready()
        compose.onNodeWithTag("app_details_domain_logs").performScrollTo().performClick()
        val domains = nextStarted()
        assertEquals(AppWiseDomainLogsActivity::class.java.name, domains.component?.className)
        assertEquals(TEST_UID, domains.getIntExtra(AppInfoActivity.INTENT_UID, INVALID_UID))
        assertFalse(domains.hasExtra(AppInfoActivity.INTENT_IS_BLOCKED))
        compose.onNodeWithTag("app_details_ip_logs").performScrollTo().performClick()
        assertEquals(AppWiseIpLogsActivity::class.java.name, nextStarted().component?.className)
        showAllSections()
        compose.onAllNodesWithText(str(R.string.ssv_see_more)).onLast().performScrollTo().performClick()
        assertNotNull(shadowOf(host()).nextStartedActivity)
    }
    @Test fun testActivityHandlesConfigurationChanges() {
        launch(); ready(); controller.recreate(); ready()
        compose.onNodeWithTag("app_details_wifi").assertExists()
        assertEquals(TEST_UID, host().intent.getIntExtra(AppInfoActivity.INTENT_UID, INVALID_UID))
        assertTrue(firewallCommands.isEmpty())
    }
    @Test fun testLaunchWithMissingUidExtra() { noApp() }
    @Test fun testLaunchWithNegativeUid() { noApp(-1) }
    @Test fun testLaunchWithZeroUid() { launch(0); ready(0); verify(exactly = 1) { ipDao.getAppWiseIpRulesCount(0) } }
    @Test fun testLaunchWithVeryLargeUid() { launch(Int.MAX_VALUE); ready(Int.MAX_VALUE); verify(exactly = 1) { domainDao.getAppWiseDomainRulesCount(Int.MAX_VALUE) } }
    @Test fun testLaunchWithSystemAppUid() { launch(1000); ready(1000); compose.onNodeWithTag("app_details_wifi").assertIsEnabled() }
    @Test fun testActivityStateWithMultipleIntents() {
        launch(); ready()
        val replacement = Intent(host(), AppInfoActivity::class.java).putExtra(AppInfoActivity.INTENT_UID, INVALID_UID)
        compose.runOnIdle { host().intent = replacement }
        // setIntent alone neither delivers onNewIntent nor recreates; the mounted content stays.
        assertSame(replacement, host().intent)
        compose.onNodeWithText(TEST_NAME, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.app_details_package_uid, TEST_PACKAGE, TEST_UID), useUnmergedTree = true).assertIsDisplayed()
        assertFalse(host().isFinishing)
        assertEquals(Lifecycle.State.RESUMED, host().lifecycle.currentState)
        // Robolectric recreates from the controller's original launch Intent.
        controller.recreate(); ready()
        assertEquals(TEST_UID, host().intent.getIntExtra(AppInfoActivity.INTENT_UID, INVALID_UID))
        assertNull(state().dialog)
        coVerify(exactly = 0) { FirewallManager.getAppInfoByUid(INVALID_UID) }
        assertTrue(firewallCommands.isEmpty())
    }
    @Test fun testActivityLifecycleStates() {
        launch(); ready(); controller.pause(); assertEquals(Lifecycle.State.STARTED, host().lifecycle.currentState)
        controller.resume(); assertEquals(Lifecycle.State.RESUMED, host().lifecycle.currentState)
        controller.pause(); assertEquals(Lifecycle.State.STARTED, host().lifecycle.currentState)
        controller.resume(); compose.onNodeWithText(TEST_NAME, useUnmergedTree = true).assertExists()
    }
    @Test fun resume_reloadsState() {
        launch(); ready()
        appStatus = FirewallStatus.BYPASS_UNIVERSAL
        controller.pause(); controller.resume()
        awaitMain { state().ui.firewall == FirewallStatus.BYPASS_UNIVERSAL }
        mode(R.string.app_details_mode_bypass_universal).performScrollTo().assertIsSelected()
    }
    @Test fun testMemoryPressureHandling() {
        launch(); ready(); host().onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertFalse(host().isFinishing); compose.onNodeWithText(TEST_NAME, useUnmergedTree = true).assertExists()
    }
    @Test fun testActivityWithEmptyIntentExtras() { noApp(emptyExtras = true) }
    @Test fun testActivityErrorRecovery() {
        missing = true; noApp(TEST_UID); destroyCurrent()
        missing = false; launch(); ready(); assertNull(state().dialog)
    }
    @Test fun testActivityLaunchPerformance() { launch(); ready(); assertEquals(Lifecycle.State.RESUMED, host().lifecycle.currentState) }
    @Test fun testUiRenderingPerformance() { launch(); ready(); showAllSections(); assertNotNull(state().icon) }
    @Test fun testMultipleActivityInstancesPerformance() {
        val owners = (1..3).map { launch(); ready(); host() }
        assertEquals(3, owners.toSet().size)
        owners.forEach { assertFalse(it.isDestroyed); assertEquals(TEST_NAME, state(it).ui.appName) }
        controllers.asReversed().forEach { it.pause().stop().destroy() }
        assertFalse(ipCount.hasObservers()); assertFalse(domainCount.hasObservers())
    }
    @Test fun testConfigurationChangePerformance() {
        launch(); ready(); repeat(3) { controller.recreate(); ready() }
        assertTrue(firewallCommands.isEmpty())
        destroyCurrent(); assertNoObserverLeaks()
    }
    @Test fun testMemoryUsageStability() {
        repeat(3) { launch(); ready(); showAllSections(); destroyCurrent(); assertNoObserverLeaks() }
        assertTrue(firewallCommands.isEmpty())
    }
    @Test fun testScrollingPerformance() {
        launch(); ready(); repeat(3) {
            showAllSections(); compose.onNodeWithText(TEST_NAME, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun ownUid_warningDefersQueries_andBackFinishesWithoutCommands() {
        launch(Process.myUid())
        compose.onNodeWithText(str(R.string.app_info_rethink_warning_proceed)).assertIsDisplayed()
        compose.onNodeWithTag("app_details_wifi").assertDoesNotExist()
        verify(exactly = 0) { ipDao.getAppWiseIpRulesCount(any()) }
        verify(exactly = 0) { domainDao.getAppWiseDomainRulesCount(any()) }
        host().onBackPressedDispatcher.onBackPressed()
        assertTrue(host().isFinishing); assertTrue(firewallCommands.isEmpty())
    }
    @Test fun ownUid_proceedShowsTheScreen() {
        apps[Process.myUid()] = syntheticApp(Process.myUid())
        launch(Process.myUid())
        compose.onNodeWithText(str(R.string.app_info_rethink_warning_proceed)).performClick()
        ready(Process.myUid())
        verify(exactly = 1) { ipDao.getAppWiseIpRulesCount(Process.myUid()) }
    }

    @Test fun unknownUid_excludeRuleIsDisabled_withoutIssuingCommands() {
        unknownPackage = true; launch(); ready()
        mode(R.string.app_details_mode_exclude).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(str(R.string.exclude_no_package_err_toast), useUnmergedTree = true).assertExists()
        assertTrue(firewallCommands.isEmpty())
    }
    @Test fun lockdown_excludeIsDisabledWithReason() {
        lockdown = true; launch(); ready()
        mode(R.string.app_details_mode_exclude).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(str(R.string.hsf_exclude_error), useUnmergedTree = true).assertExists()
    }
    @Test fun emptySyntheticLogs_hideSections_andViewModeToggle() {
        emptyLogs = true; launch(); ready()
        awaitMain { state().sectionHasData[AppInsightsSection.IPS] == false }
        compose.onNodeWithText(str(R.string.top_active_conns)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.app_details_view_insights)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.app_details_activity_empty_title)).performScrollTo().assertIsDisplayed()
        // the log screens stay reachable
        compose.onNodeWithTag("app_details_domain_logs").performScrollTo().assertIsDisplayed()
    }
    @Test fun ruleCounts_reemitThroughActualLifecycleObservers() {
        launch(); ready(); compose.runOnIdle { ipCount.value = 8; domainCount.value = 13 }
        compose.onNodeWithText("8", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("13", useUnmergedTree = true).assertExists()
        destroyCurrent(); assertNoObserverLeaks()
    }
    @Test fun customRulesNavigation_preservesTabAndUidWithoutLaunchingTarget() {
        launch(); ready()
        compose.onNodeWithTag("app_details_ip_rules").performScrollTo().performClick()
        val ip = nextStarted()
        assertEquals(CustomRulesActivity::class.java.name, ip.component?.className)
        assertEquals(TEST_UID, ip.getIntExtra(Constants.INTENT_UID, INVALID_UID))
        assertEquals(CustomRulesActivity.Tabs.IP_RULES.screen, ip.getIntExtra(Constants.VIEW_PAGER_SCREEN_TO_LOAD, -1))
        compose.onNodeWithTag("app_details_domain_rules").performScrollTo().performClick()
        val domain = nextStarted()
        assertEquals(CustomRulesActivity.Tabs.DOMAIN_RULES.screen, domain.getIntExtra(Constants.VIEW_PAGER_SCREEN_TO_LOAD, -1))
        assertEquals(TEST_UID, domain.getIntExtra(Constants.INTENT_UID, INVALID_UID))
    }
}
