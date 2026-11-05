/*
Copyright 2020 RethinkDNS and its authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.celzero.bravedns.ui.activity

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.livedata.observeAsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LiveData
import androidx.lifecycle.asFlow
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.PagingData
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.map
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.rules.CustomRuleActions
import com.celzero.bravedns.ui.compose.rules.ManagerCustomRulePort
import com.celzero.bravedns.ui.compose.rules.RuleItem
import com.celzero.bravedns.ui.compose.rules.RuleKind
import com.celzero.bravedns.ui.compose.rules.RulesHost
import com.celzero.bravedns.ui.compose.rules.RulesScope
import com.celzero.bravedns.ui.compose.rules.RulesScreen
import com.celzero.bravedns.ui.compose.rules.RulesScreenState
import com.celzero.bravedns.ui.compose.rules.RulesTabData
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.CustomDomainViewModel
import com.celzero.bravedns.viewmodel.CustomIpViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Custom IP and domain rules of one app, of all apps (universal) or of every app grouped by
 * app (RULES.ALL_RULES). Draws [RulesScreen]; reads come from the paging view models, writes
 * go through [CustomRuleActions].
 */
class CustomRulesActivity : BaseActivity(), RulesHost {
    private var fragmentIndex = 0
    private var rulesType = RULES.APP_SPECIFIC_RULES.ordinal
    private var uid = Constants.UID_EVERYBODY
    private val persistentState by inject<PersistentState>()
    private val eventLogger by inject<EventLogger>()
    private val ipModel: CustomIpViewModel by viewModel()
    private val domainModel by inject<CustomDomainViewModel>()

    private lateinit var state: RulesScreenState
    private lateinit var actions: CustomRuleActions
    private var importKind = RuleKind.IP

    private val importPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (DEBUG && uri != null) actions.parseImport(importKind, uri)
    }

    enum class Tabs(val screen: Int) {
        IP_RULES(0),
        DOMAIN_RULES(1);

        companion object {
            fun getCount(): Int {
                return entries.size
            }
        }
    }

    enum class RULES(val type: Int) {
        ALL_RULES(0),
        APP_SPECIFIC_RULES(1);

        companion object {
            fun getType(type: Int): RULES {
                return when (type) {
                    0 -> ALL_RULES
                    1 -> APP_SPECIFIC_RULES
                    else -> APP_SPECIFIC_RULES
                }
            }
        }
    }

    companion object {
        const val INTENT_RULES = "INTENT_RULES"
        private const val PROXY_REFRESH_MS = 5_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        if (Utilities.isAtleastQ()) {
            val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = Themes.isActivityLightTheme(isDarkThemeOn(), persistentState.theme)
            controller.isAppearanceLightStatusBars = Themes.isActivityLightTheme(isDarkThemeOn(), persistentState.theme)
            window.isNavigationBarContrastEnforced = false
        }

        fragmentIndex = intent.getIntExtra(Constants.VIEW_PAGER_SCREEN_TO_LOAD, 0)
        rulesType = intent.getIntExtra(INTENT_RULES, RULES.APP_SPECIFIC_RULES.type)
        uid = intent.getIntExtra(Constants.INTENT_UID, Constants.UID_EVERYBODY)
        init()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun init() {
        val scope = RulesScope.of(uid, RULES.getType(rulesType))
        state = RulesScreenState(scope)
        actions = CustomRuleActions(
            state = state,
            scope = lifecycleScope,
            port = ManagerCustomRulePort(applicationContext),
            notify = { id, args -> Utilities.showToastUiCentered(this, getString(id, *args), Toast.LENGTH_SHORT) },
            log = { kind, details ->
                eventLogger.log(EventType.FW_RULE_MODIFIED, Severity.LOW, if (kind == RuleKind.DOMAIN) "Custom Domain" else "Custom IP", EventSource.UI, false, details)
            },
        )
        ipModel.setUid(uid)
        domainModel.setUid(uid)
        val ipSource = if (scope.allApps) ipModel.allIpRules else ipModel.customIpDetails
        val domainSource = if (scope.allApps) domainModel.allDomainRules else domainModel.customDomains
        val ipCount = if (scope.allApps) ipModel.allIpRulesCount() else ipModel.ipRulesCount(uid)
        val domainCount = if (scope.allApps) domainModel.allDomainRulesCount() else domainModel.domainRulesCount(uid)
        val ipRows = ipSource.asFlow().map { pd -> pd.map { RuleItem.of(it) } }
        val domainRows = domainSource.asFlow().map { pd -> pd.map { RuleItem.of(it) } }

        setContent {
            RethinkTheme {
                val ip = tabData(RuleKind.IP, ipRows, ipCount, ipModel::setFilter)
                val domain = tabData(RuleKind.DOMAIN, domainRows, domainCount, domainModel::setFilter)
                LiveProxies()
                ClearSearchOnResume(ip, domain)
                RulesScreen(state, actions, this, fragmentIndex, listOf(ip, domain))
            }
        }
        observeAppState()
    }

    @Composable
    private fun tabData(kind: RuleKind, rows: Flow<PagingData<RuleItem>>, count: LiveData<Int>, onQuery: (String) -> Unit): RulesTabData {
        val owner = LocalLifecycleOwner.current
        val items = remember(rows, owner) { rows.flowWithLifecycle(owner.lifecycle, Lifecycle.State.STARTED) }.collectAsLazyPagingItems()
        val n by count.observeAsState(-1)
        return RulesTabData(kind, items, n, onQuery)
    }

    // proxy status chips stay live while the screen is visible
    @Composable
    private fun LiveProxies() {
        val owner = LocalLifecycleOwner.current
        LaunchedEffect(owner) {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    actions.refreshProxies()
                    delay(PROXY_REFRESH_MS)
                }
            }
        }
    }

    // as before the redesign: coming back to the screen clears the search
    @Composable
    private fun ClearSearchOnResume(ip: RulesTabData, domain: RulesTabData) {
        val owner = LocalLifecycleOwner.current
        val tabs by rememberUpdatedState(listOf(ip, domain))
        DisposableEffect(owner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) tabs.forEach { state.tab(it.kind).search("", it.onQuery) }
            }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }
    }

    override fun back() = finish()

    override fun openAppRules(kind: RuleKind, uid: Int) {
        startActivity(Intent(this, CustomRulesActivity::class.java).apply {
            putExtra(Constants.VIEW_PAGER_SCREEN_TO_LOAD, if (kind == RuleKind.DOMAIN) Tabs.DOMAIN_RULES.screen else Tabs.IP_RULES.screen)
            putExtra(Constants.INTENT_UID, uid)
        })
    }

    override fun pickImportFile(kind: RuleKind) {
        if (!DEBUG) return
        importKind = kind
        importPicker.launch(arrayOf("text/plain"))
    }

    private fun observeAppState() {
        VpnController.connectionStatus.observe(this) {
            if (it == BraveVPNService.State.PAUSED) {
                startActivity(Intent().setClass(this, PauseActivity::class.java))
                finish()
            }
        }
    }
}
