/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.appdetails

import android.graphics.drawable.Drawable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.service.ProxyChoices
import com.celzero.bravedns.ui.stats.StatsViewMode

/** Holds a Paging presenter for a Compose list (also used by the per-app log screens). */
class AppDetailListState { var adapter by mutableStateOf<AppConnectionsPresenter?>(null) }

/** A dialog the app details screen is showing. */
sealed interface AppDetailsDialog {
    /** The app is gone (uninstalled / invalid uid); OK finishes the screen. */
    data object NoApp : AppDetailsDialog
    data class Notes(val draft: AppNotesDraft) : AppDetailsDialog
    data object CloseConnections : AppDetailsDialog
    /** Shared uid: pick which app's Android settings to open. */
    data class PickAppInfo(val appNames: List<String>) : AppDetailsDialog
    /** Shared uid: a firewall change applies to every app of the uid; confirm first. */
    data class ConfirmSharedUid(val appNames: List<String>, val change: FirewallChange) : AppDetailsDialog
}

/**
 * Observable state of the app details screen (AppInfoActivity owns it and does the I/O;
 * AppDetailsScreen draws it). Replaces the per-view WgControlState shims of the Compose port.
 */
class AppDetailsState {
    var ui by mutableStateOf(AppDetailsUi())
    var icon by mutableStateOf<Drawable?>(null)

    /** Shown instead of the screen when the app is Rethink itself, until acknowledged. */
    var showRethinkWarning by mutableStateOf(false)

    var dialog by mutableStateOf<AppDetailsDialog?>(null)
    var showProxyPicker by mutableStateOf(false)

    /** List or Insights; mirrors the stats screen's persisted preference. */
    var viewMode by mutableStateOf(StatsViewMode.LIST)

    /** Live list presenter of each activity section that is wired up. */
    val presenters = mutableStateMapOf<AppInsightsSection, AppConnectionsPresenter>()

    /** Sections whose data has loaded and is not empty; others stay hidden. */
    val sectionHasData = mutableStateMapOf<AppInsightsSection, Boolean>()

    /** Latest items per section, for the Insights view. */
    val insightsSnapshots = mutableStateMapOf<AppInsightsSection, List<AppConnection>>()

    val anyActivity: Boolean get() = sectionHasData.values.any { it }
}

/** What the screen asks its host to do. */
interface AppDetailsActions {
    fun acknowledgeRethinkWarning()
    fun openAndroidAppInfo()
    fun openAndroidAppInfoFor(appName: String)
    fun openNotes()
    fun saveNotes(text: String)
    fun dismissDialog()
    fun finishNoApp()
    fun requestCloseConnections()
    fun closeConnections()
    fun setTempAllow(enabled: Boolean)
    fun toggleWifi()
    fun toggleMobileData()
    fun selectMode(mode: AppNetworkMode)
    fun confirmFirewallChange(change: FirewallChange)
    fun openProxyPicker()
    fun onProxyChanged(pick: ProxyChoices.Pick)
    fun closeProxyPicker()
    fun openIpRules()
    fun openDomainRules()
    fun setViewMode(mode: StatsViewMode)
    fun openSection(section: AppInsightsSection)
    fun openDomainLogs()
    fun openIpLogs()
}
