/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.wireguard

import android.graphics.drawable.Drawable

/*
 * Pure model of the per-proxy app picker ("Apps via <proxy>", WgIncludeAppsActivity). No
 * Android or manager calls here, so the rules are unit-testable (AppPickerModelTest).
 */

/** The list filters, in chip order. Maps 1:1 to ProxyAppsMappingViewModel.TopLevelFilter. */
enum class AppPickerFilter { ALL, SELECTED, UNSELECTED }

/**
 * What one row shows. [selected] is membership in this proxy; [routedVia] names the other
 * proxy (or proxies) an unselected app currently uses; [bypassesProxies] means the firewall
 * excludes the app from all proxies, so it cannot be ticked; [noInternet] apps lack the
 * INTERNET permission and are dimmed (they can still be ticked).
 */
data class AppPickerRow(
    val uid: Int,
    val packageName: String,
    val appName: String,
    val selected: Boolean,
    val routedVia: String? = null,
    val bypassesProxies: Boolean = false,
    val noInternet: Boolean = false,
    val icon: Drawable? = null,
) {
    val key: String get() = key(uid, packageName)

    /** A bypassing app can't be put on a proxy; tapping it does nothing. */
    val enabled: Boolean get() = !bypassesProxies

    /** The checkbox: a bypassing app is never shown as selected. */
    val checked: Boolean get() = selected && !bypassesProxies

    companion object {
        fun key(uid: Int, packageName: String) = "$uid:$packageName"
    }
}

/** Why a row looks different; the screen turns these into its supporting line. */
sealed interface AppPickerNote {
    data object BypassesProxies : AppPickerNote
    data class RoutedVia(val names: String) : AppPickerNote
    data object NoInternet : AppPickerNote
}

/** Which bulk actions in the top-bar menu can do anything. */
data class AppPickerBulk(val canSelectAll: Boolean, val canUnselectAll: Boolean) {
    companion object {
        val None = AppPickerBulk(canSelectAll = false, canUnselectAll = false)
    }
}

/** What the list shows when it has no rows. */
enum class AppPickerEmpty { NO_MATCH, NONE_SELECTED, ALL_SELECTED, NO_APPS }

object AppPickerLogic {

    /** Supporting-line notes for a row, most important first. */
    fun notes(row: AppPickerRow): List<AppPickerNote> = buildList {
        if (row.bypassesProxies) add(AppPickerNote.BypassesProxies)
        else if (!row.selected && !row.routedVia.isNullOrBlank()) add(AppPickerNote.RoutedVia(row.routedVia))
        if (row.noInternet) add(AppPickerNote.NoInternet)
    }

    /**
     * "Select all" only adds apps not already here and not on another exclusive proxy
     * (ProxyManager.getBulkAddableAppCount); "Unselect all" needs at least one app here.
     */
    fun bulk(selectedCount: Int, addableCount: Int) =
        AppPickerBulk(canSelectAll = addableCount > 0, canUnselectAll = selectedCount > 0)

    fun empty(query: String, filter: AppPickerFilter): AppPickerEmpty = when {
        query.isNotBlank() -> AppPickerEmpty.NO_MATCH
        filter == AppPickerFilter.SELECTED -> AppPickerEmpty.NONE_SELECTED
        filter == AppPickerFilter.UNSELECTED -> AppPickerEmpty.ALL_SELECTED
        else -> AppPickerEmpty.NO_APPS
    }

    /**
     * Apps that share a uid (shared-user packages) are routed together; with more than one
     * the user confirms the whole group first.
     */
    fun needsSharedUidConfirm(packagesForUid: List<String>) = packagesForUid.size > 1

    /**
     * "Routed via" label for an app on proxies other than [proxyId]; legacy apps on several
     * proxies list all of them. Null when the app is on none of them.
     */
    fun otherProxies(proxyIds: List<String>, proxyId: String, displayName: (String) -> String): String? {
        val others = proxyIds.filter { it != proxyId }
        if (others.isEmpty()) return null
        return others.joinToString(", ") { displayName(it) }
    }
}
