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
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.asFlow
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.celzero.bravedns.R
import com.celzero.bravedns.database.ProxyApplicationMapping
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.proxy.ProxyConfirm
import com.celzero.bravedns.ui.compose.wireguard.AppPickerActions
import com.celzero.bravedns.ui.compose.wireguard.AppPickerFilter
import com.celzero.bravedns.ui.compose.wireguard.AppPickerLogic
import com.celzero.bravedns.ui.compose.wireguard.AppPickerRow
import com.celzero.bravedns.ui.compose.wireguard.AppPickerRows
import com.celzero.bravedns.ui.compose.wireguard.AppPickerScreen
import com.celzero.bravedns.ui.compose.wireguard.AppPickerState
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_PROXY
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.getDefaultIcon
import com.celzero.bravedns.util.Utilities.getIcon
import com.celzero.bravedns.viewmodel.ProxyAppsMappingViewModel
import com.celzero.bravedns.viewmodel.ProxyAppsMappingViewModel.TopLevelFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Per-proxy app picker ("Apps via <proxy>") for WireGuard, SOCKS5, HTTP, Orbot and RPN proxies.
 * Owns all reads/writes (ProxyManager / FirewallManager) and implements [AppPickerActions]; the
 * UI is [AppPickerScreen]. Sets RESULT_OK once the user changes an app by hand or removes all
 * apps, so callers (RPN config) can react, e.g. turn off catch-all.
 */
class WgIncludeAppsActivity : BaseActivity(), AppPickerActions {

    private val persistentState by inject<PersistentState>()
    private val refreshDatabase by inject<RefreshDatabase>()
    private val viewModel: ProxyAppsMappingViewModel by viewModel()

    private var proxyId: String = ""
    private var proxyName: String = ""

    private val state = AppPickerState()

    /** bumped after a write so the pager reloads (Room also invalidates, this matches the old adapter) */
    private var listGeneration by mutableIntStateOf(0)

    companion object {
        private const val REFRESH_TIMEOUT: Long = 4000

        private const val INTENT_EXTRA_PROXY_ID = "proxy_id"
        private const val INTENT_EXTRA_PROXY_NAME = "proxy_name"

        private const val STATE_QUERY = "wg.apps.query"
        private const val STATE_FILTER = "wg.apps.filter"

        fun newIntent(context: Context, proxyId: String, proxyName: String): Intent {
            val intent = Intent(context, WgIncludeAppsActivity::class.java)
            intent.putExtra(INTENT_EXTRA_PROXY_ID, proxyId)
            intent.putExtra(INTENT_EXTRA_PROXY_NAME, proxyName)
            return intent
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        proxyId = intent.getStringExtra(INTENT_EXTRA_PROXY_ID) ?: ""
        proxyName = intent.getStringExtra(INTENT_EXTRA_PROXY_NAME) ?: ""
        if (proxyId.isBlank()) {
            Logger.e(LOG_TAG_PROXY, "WgIncludeAppsActivity started without a proxyId, finishing")
            finish()
            return
        }

        savedInstanceState?.let { saved ->
            state.query = saved.getString(STATE_QUERY).orEmpty()
            state.filter = saved.getString(STATE_FILTER)
                ?.let { name -> AppPickerFilter.entries.firstOrNull { it.name == name } }
                ?: AppPickerFilter.ALL
        }
        applyFilter()

        // live count of apps on this proxy; any change (here or elsewhere) reloads row states
        viewModel.getAppCountById(proxyId).observe(this) { count ->
            state.selectedCount = count ?: 0
            state.changed()
            syncBulkState()
        }

        setContent { RethinkTheme { PickerContent() } }
    }

    @Composable
    private fun PickerContent() {
        val items = remember { viewModel.apps.asFlow() }.collectAsLazyPagingItems()
        LaunchedEffect(listGeneration) { if (listGeneration > 0) items.refresh() }
        val rows = AppPickerRows(
            count = { items.itemCount },
            loading = items.loadState.refresh is LoadState.Loading,
            rowKey = items.itemKey { AppPickerRow.key(it.uid, it.packageName) },
            rowAt = { index -> items[index]?.let { rememberRow(it) } },
        )
        AppPickerScreen(proxyName, state, rows, this)
    }

    /** Shows the mapping at once (name, guessed selection) and fills in the rest off the main thread. */
    @Composable
    private fun rememberRow(mapping: ProxyApplicationMapping): AppPickerRow {
        val version = state.version
        val row by produceState(
            initialValue = AppPickerRow(mapping.uid, mapping.packageName, mapping.appName, selected = mapping.proxyId == proxyId),
            mapping.uid, mapping.packageName, mapping.proxyId, version,
        ) {
            value = withContext(Dispatchers.IO) { loadRow(mapping) }
        }
        return row
    }

    private suspend fun loadRow(mapping: ProxyApplicationMapping): AppPickerRow {
        ProxyManager.ensureLoaded()
        val included = ProxyManager.getProxyIdsForApp(mapping.uid, mapping.packageName).contains(proxyId)
        // one proxy per app: an app on another wireguard / socks5 / orbot proxy says so; ticking
        // it here moves it (see ProxyManager.setProxyForApp)
        val routedVia =
            if (!included && ProxyManager.isExclusiveProxyId(proxyId)) {
                val others = when (val current = ProxyManager.getProxyForApp(mapping.uid)) {
                    is ProxyManager.AppProxy.None -> emptyList()
                    is ProxyManager.AppProxy.Single -> listOf(current.proxyId)
                    is ProxyManager.AppProxy.Multiple -> current.proxyIds
                }
                AppPickerLogic.otherProxies(others, proxyId, ProxyManager::proxyDisplayName)
            } else {
                null
            }
        return AppPickerRow(
            uid = mapping.uid,
            packageName = mapping.packageName,
            appName = mapping.appName,
            selected = included,
            routedVia = routedVia,
            bypassesProxies = FirewallManager.isAppExcludedFromProxy(mapping.uid),
            noInternet = !mapping.hasInternetPermission(packageManager),
            icon = getIcon(this, mapping.packageName, mapping.appName) ?: getDefaultIcon(this),
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_QUERY, state.query)
        outState.putString(STATE_FILTER, state.filter.name)
        super.onSaveInstanceState(outState)
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun applyFilter() {
        val type = when (state.filter) {
            AppPickerFilter.ALL -> TopLevelFilter.ALL_APPS
            AppPickerFilter.SELECTED -> TopLevelFilter.SELECTED_APPS
            AppPickerFilter.UNSELECTED -> TopLevelFilter.UNSELECTED_APPS
        }
        viewModel.setFilter(state.query, type, proxyId)
    }

    // ---- AppPickerActions ----

    override fun back() = finish()

    override fun search(query: String) {
        if (query == state.query) return
        state.query = query
        applyFilter()
    }

    override fun filter(filter: AppPickerFilter) {
        if (filter == state.filter) return
        state.filter = filter
        applyFilter()
    }

    override fun refreshApps() {
        if (state.refreshing) return
        state.refreshing = true
        io { refreshDatabase.refresh(RefreshDatabase.ACTION_REFRESH_INTERACTIVE) }
        Utilities.delay(REFRESH_TIMEOUT, lifecycleScope) {
            if (!isDestroyed && !isFinishing) {
                state.refreshing = false
                Utilities.showToastUiCentered(this, getString(R.string.refresh_complete), Toast.LENGTH_SHORT)
            }
        }
    }

    override fun toggle(row: AppPickerRow) {
        if (!row.enabled) return
        val include = !row.checked
        Logger.i(LOG_TAG_PROXY, "app picker: ${row.appName}, include? $include")
        lifecycleScope.launch {
            val (excluded, names) = withContext(Dispatchers.IO) {
                FirewallManager.isAppExcludedFromProxy(row.uid) to FirewallManager.getAppNamesByUid(row.uid)
            }
            if (excluded) {
                Utilities.showToastUiCentered(
                    this@WgIncludeAppsActivity,
                    getString(R.string.exclude_apps_from_proxy_failure_toast),
                    Toast.LENGTH_LONG
                )
                state.changed()
                return@launch
            }
            if (!AppPickerLogic.needsSharedUidConfirm(names)) {
                setProxy(row.uid, row.packageName, include)
                return@launch
            }
            // the change applies to every package sharing this uid; list them first
            val count = names.size.toString()
            state.confirm = ProxyConfirm(
                title = if (include) getString(R.string.wg_apps_dialog_title_include, count)
                    else getString(R.string.wg_apps_dialog_title_exclude, count),
                message = getString(R.string.app_picker_shared_uid_body, names.joinToString("\n")),
                confirmLabel = getString(if (include) R.string.lbl_include else R.string.lbl_remove),
                onConfirm = { setProxyForSharedUid(row.uid, include) },
                dismissLabel = getString(R.string.ctbs_dialog_negative_btn),
                cancelable = false,
            )
        }
    }

    private fun setProxy(uid: Int, packageName: String, include: Boolean) {
        io {
            if (include) {
                // moves the app here if it was on another wireguard / socks5 / orbot proxy
                val added = ProxyManager.addProxyToApp(uid, packageName, proxyId, proxyName)
                Logger.i(LOG_TAG_PROXY, "Included app: $uid, $proxyId, $proxyName, added? $added")
            } else {
                ProxyManager.removeProxyFromApp(uid, packageName, proxyId)
                Logger.i(LOG_TAG_PROXY, "Removed app: $uid, $proxyId, $proxyName")
            }
            withContext(Dispatchers.Main) { onAppModified() }
        }
    }

    private fun setProxyForSharedUid(uid: Int, include: Boolean) {
        io {
            FirewallManager.getPackageNamesByUid(uid).forEach { pkg ->
                val appInfo = FirewallManager.getAppInfoByPackage(pkg) ?: return@forEach
                if (include) {
                    ProxyManager.addProxyToApp(appInfo.uid, appInfo.packageName, proxyId, proxyName)
                } else {
                    ProxyManager.removeProxyFromApp(appInfo.uid, appInfo.packageName, proxyId)
                }
            }
            withContext(Dispatchers.Main) { onAppModified() }
        }
    }

    override fun selectAll() {
        if (state.bulkBusy) return
        state.confirm = ProxyConfirm(
            title = getString(R.string.app_picker_select_all_title, proxyName),
            message = getString(R.string.app_picker_select_all_body, proxyName),
            confirmLabel = getString(R.string.lbl_include),
            onConfirm = { performBulkAction(include = true) },
            dismissLabel = getString(R.string.lbl_cancel),
        )
    }

    override fun unselectAll() {
        if (state.bulkBusy) return
        state.confirm = ProxyConfirm(
            title = getString(R.string.app_picker_unselect_all_title, proxyName),
            message = getString(R.string.app_picker_unselect_all_body, proxyName),
            confirmLabel = getString(R.string.lbl_remove),
            onConfirm = { performBulkAction(include = false) },
            dismissLabel = getString(R.string.lbl_cancel),
            destructive = true,
        )
    }

    private fun performBulkAction(include: Boolean) {
        if (state.bulkBusy) return
        state.bulkBusy = true
        io {
            try {
                if (include) {
                    Logger.i(LOG_TAG_PROXY, "Adding all apps to proxy $proxyId, $proxyName")
                    ProxyManager.setProxyIdForAllApps(proxyId, proxyName)
                } else {
                    Logger.i(LOG_TAG_PROXY, "Removing all apps from proxy $proxyId, $proxyName")
                    ProxyManager.setNoProxyForAllAppsForProxy(proxyId)
                }
            } catch (e: Exception) {
                Logger.e(LOG_TAG_PROXY, "bulk action failed for $proxyId: ${e.message}", e)
            }
            withContext(Dispatchers.Main) {
                state.bulkBusy = false
                if (isFinishing || isDestroyed) return@withContext
                // a bulk remove-all changes routing intent; inform the caller
                if (!include) setResult(RESULT_OK)
                state.changed()
                listGeneration++
                syncBulkState()
            }
        }
    }

    /** After a single app (or a shared-uid group) changed: mark the result and reload. */
    private fun onAppModified() {
        setResult(RESULT_OK)
        if (isFinishing || isDestroyed) return
        state.changed()
        listGeneration++
        syncBulkState()
    }

    /** Which bulk actions the overflow menu enables (see [AppPickerLogic.bulk]). */
    private fun syncBulkState() {
        io {
            ProxyManager.ensureLoaded()
            val selected = ProxyManager.getAppCountForProxy(proxyId)
            val addable = ProxyManager.getBulkAddableAppCount(proxyId)
            withContext(Dispatchers.Main) { state.bulk = AppPickerLogic.bulk(selected, addable) }
        }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }
}
