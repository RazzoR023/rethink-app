/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.wireguard

import androidx.compose.runtime.Immutable
import com.celzero.bravedns.ui.compose.design.RethinkStatus

/*
 * Pure logic of the WireGuard config list (WgMainActivity / WgListScreen). No Android; tested
 * by WgListModelTest.
 */

/** Simple = one WireGuard routes everything (one-wg); Advanced = several configs, per-app. */
enum class WgListMode { SIMPLE, ADVANCED }

/** What the row says about the apps a config routes. */
@Immutable
sealed interface WgListApps {
    /** off: nothing to say */
    data object Hidden : WgListApps

    /** Simple mode: all apps */
    data object All : WgListApps

    /** Advanced always-on: every app no other config takes */
    data object Remaining : WgListApps

    /** Advanced: apps picked for this config; 0 is a warning */
    data class Picked(val count: Int) : WgListApps
}

/** A short property tag under a row ("IPv4", "Always-on", "Relayed via x", ...). */
@Immutable
data class WgListTag(val label: String, val warning: Boolean = false)

/** One config row. */
@Immutable
data class WgListItem(
    val id: Int,
    val name: String,
    /** flag emoji of the endpoint's country; null shows the WireGuard icon */
    val flag: String? = null,
    /** the switch: on and the tunnel is up */
    val checked: Boolean = false,
    /** a toggle is in flight: the switch shows the requested state and is disabled */
    val busy: Boolean = false,
    val status: RethinkStatus = RethinkStatus.Off,
    /** the status chip's text ("Connected (2 min. ago)"); null shows the plain status */
    val statusText: String? = null,
    val apps: WgListApps = WgListApps.Hidden,
    /** "Active since 4 min. ago" while on */
    val uptime: String? = null,
    /** "6.3 KB ▲ 21.5 KB ▼" while on */
    val traffic: String? = null,
    val tags: List<WgListTag> = emptyList(),
)

object WgListLogic {

    /** The tab to open on: Advanced while Advanced configs run, else Simple. */
    fun initialMode(anyActive: Boolean, oneWgActive: Boolean): WgListMode =
        if (anyActive && !oneWgActive) WgListMode.ADVANCED else WgListMode.SIMPLE

    /**
     * Whether switching to [target] must first stop the running configs: Advanced while a
     * Simple config runs, Simple while Advanced configs run.
     */
    fun needsStopAll(target: WgListMode, anyActive: Boolean, oneWgActive: Boolean): Boolean = when (target) {
        WgListMode.ADVANCED -> oneWgActive
        WgListMode.SIMPLE -> anyActive && !oneWgActive
    }

    /**
     * Where DNS goes. Simple: the active config. Otherwise the connected DNS, plus the active
     * configs when split DNS is on. Null when Simple has no active config (line unchanged).
     */
    fun dnsTarget(oneWgActive: Boolean, activeNames: List<String>, connectedDns: String, splitDns: Boolean): String? {
        if (oneWgActive) return activeNames.firstOrNull()
        var s = connectedDns
        if (splitDns) {
            if (activeNames.isNotEmpty()) s += ", "
            s += activeNames.joinToString(",")
        }
        return s
    }

    /** What the row says about apps. */
    fun apps(mode: WgListMode, on: Boolean, catchAll: Boolean, count: Int): WgListApps = when {
        !on -> WgListApps.Hidden
        mode == WgListMode.SIMPLE -> WgListApps.All
        catchAll -> WgListApps.Remaining
        else -> WgListApps.Picked(count)
    }

    /**
     * Keeps rows where they were while the screen is open (so a row doesn't jump away from
     * the finger when it is switched on), new configs go at the end. [fresh] is in the
     * storage order (active first, then by name) and decides the order on first load.
     */
    fun stableOrder(previous: List<Int>, fresh: List<Int>): List<Int> {
        if (previous.isEmpty()) return fresh
        val present = fresh.toSet()
        val kept = previous.filter { it in present }
        val keptSet = kept.toSet()
        return kept + fresh.filter { it !in keptSet }
    }

    /** Storage order: active first, then by name (case-insensitive), like WgConfigFilesDAO. */
    fun <T> storageOrder(items: List<T>, active: (T) -> Boolean, name: (T) -> String): List<T> =
        items.sortedWith(compareByDescending<T> { active(it) }.thenBy(String.CASE_INSENSITIVE_ORDER) { name(it) })
}
