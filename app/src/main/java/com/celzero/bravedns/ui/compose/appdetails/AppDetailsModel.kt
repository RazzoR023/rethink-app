/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.appdetails

import com.celzero.bravedns.R
import com.celzero.bravedns.service.FirewallManager.ConnectionStatus
import com.celzero.bravedns.service.FirewallManager.FirewallStatus
import com.celzero.bravedns.service.ProxyChoices
import com.celzero.bravedns.service.ProxyChoices.Pick
import com.celzero.bravedns.ui.compose.design.RethinkStatus
import com.celzero.bravedns.ui.compose.design.toStatus

/*
 * App details screen: the data it shows and the rules that turn user actions into
 * FirewallManager states. Everything here is free of Android so it can be unit tested
 * (AppDetailsModelTest); AppInfoActivity does the reads and writes.
 */

/**
 * The exclusive firewall mode of an app, one-to-one with [FirewallStatus]. Wi-Fi / mobile data
 * blocking is the separate [ConnectionStatus].
 */
enum class AppNetworkMode(val status: FirewallStatus) {
    NORMAL(FirewallStatus.NONE),
    ISOLATE(FirewallStatus.ISOLATE),
    BYPASS_DNS_FIREWALL(FirewallStatus.BYPASS_DNS_FIREWALL),
    BYPASS_UNIVERSAL(FirewallStatus.BYPASS_UNIVERSAL),
    EXCLUDE(FirewallStatus.EXCLUDE);

    companion object {
        fun of(status: FirewallStatus): AppNetworkMode = entries.first { it.status == status }
    }
}

/** A firewall write: [previous] is the connection status before it (for the dialog label). */
data class FirewallChange(
    val firewall: FirewallStatus,
    val connection: ConnectionStatus,
    val previous: ConnectionStatus = ConnectionStatus.ALLOW,
)

/** Why the Exclude mode can't be picked, if it can't. */
enum class ExcludeBlocker { NONE, UNKNOWN_PACKAGE, VPN_LOCKDOWN }

/** One-line state of the app, as the header shows it. */
enum class AppAccess {
    ALLOWED, BLOCKED, BLOCKED_WIFI, BLOCKED_MOBILE, ISOLATED,
    BYPASS_UNIVERSAL, BYPASS_DNS_FIREWALL, EXCLUDED, TEMP_ALLOWED,
}

/** The proxy part of the header summary. */
sealed interface SummaryProxy {
    data object Direct : SummaryProxy
    data class Via(val choice: ProxyChoices.Choice) : SummaryProxy
    data class Multiple(val count: Int) : SummaryProxy
}

data class AppSummary(val status: RethinkStatus, val access: AppAccess, val proxy: SummaryProxy?)

/** What the Proxy row shows. */
sealed interface ProxyRowState {
    data object Loading : ProxyRowState
    /** No mapping: follows the global proxy settings. */
    data object Default : ProxyRowState
    /** Excluded from all proxies (the app's isProxyExcluded flag). */
    data object NoProxy : ProxyRowState
    data class Via(val choice: ProxyChoices.Choice) : ProxyRowState
    /** Legacy: mapped to several proxies; still routes until changed. */
    data class Multiple(val choices: List<ProxyChoices.Choice>) : ProxyRowState
}

/** Activity sections, in screen order. [title] is the section heading. */
enum class AppInsightsSection(val title: Int, val blocked: Boolean = false) {
    ACTIVE_CONNS(R.string.top_active_conns),
    COUNTRIES(R.string.ssv_most_contacted_countries_heading),
    ASN(R.string.most_contacted_asn),
    BLOCKED_ASN(R.string.most_blocked_asn, true),
    BLOCKLISTS(R.string.ssv_blocklists_heading, true),
    DOMAINS(R.string.ssv_most_contacted_domain_heading),
    BLOCKED_DOMAINS(R.string.ssv_most_blocked_domain_heading, true),
    IPS(R.string.ssv_most_contacted_ips_heading),
    BLOCKED_IPS(R.string.ssv_most_blocked_ips_heading, true),
}

/** Everything the screen shows except the icon and the activity lists. */
data class AppDetailsUi(
    val loaded: Boolean = false,
    val uid: Int = 0,
    val appName: String = "",
    val packageName: String = "",
    /** Names of the other apps sharing this uid (rules apply per uid). */
    val otherAppNames: List<String> = emptyList(),
    val uploadBytes: Long = 0,
    val downloadBytes: Long = 0,
    /** When the app's rules last changed, 0 if never. */
    val modifiedTs: Long = 0,
    val firewall: FirewallStatus = FirewallStatus.NONE,
    val connection: ConnectionStatus = ConnectionStatus.ALLOW,
    val tempAllowed: Boolean = false,
    val tempAllowExpiry: Long = 0,
    val notes: String = "",
    val excludeBlocker: ExcludeBlocker = ExcludeBlocker.NONE,
    val proxy: ProxyRowState = ProxyRowState.Loading,
    /** RPN exits the app is also routed via (display names); RPN chains with the app proxy. */
    val rpnNames: List<String> = emptyList(),
    val ipRuleCount: Int? = null,
    val domainRuleCount: Int? = null,
) {
    val mode: AppNetworkMode get() = AppNetworkMode.of(firewall)
    val wifiBlocked: Boolean get() = AppDetailsLogic.isWifiBlocked(connection)
    val mobileBlocked: Boolean get() = AppDetailsLogic.isMobileBlocked(connection)
    val summary: AppSummary get() = AppDetailsLogic.summary(firewall, connection, tempAllowed, proxy)
}

object AppDetailsLogic {

    fun isWifiBlocked(c: ConnectionStatus) = c == ConnectionStatus.UNMETERED || c == ConnectionStatus.BOTH

    fun isMobileBlocked(c: ConnectionStatus) = c == ConnectionStatus.METERED || c == ConnectionStatus.BOTH

    /**
     * Wi-Fi switch: flips the unmetered block, keeps the mobile one, and puts the app back in
     * Normal mode (as the apps list and the old icon grid do).
     */
    fun wifiChange(current: ConnectionStatus): FirewallChange {
        val next = when (current) {
            ConnectionStatus.UNMETERED -> ConnectionStatus.ALLOW
            ConnectionStatus.BOTH -> ConnectionStatus.METERED
            ConnectionStatus.METERED -> ConnectionStatus.BOTH
            ConnectionStatus.ALLOW -> ConnectionStatus.UNMETERED
        }
        return FirewallChange(FirewallStatus.NONE, next, current)
    }

    /** Mobile data switch: as [wifiChange] for the metered block. */
    fun mobileChange(current: ConnectionStatus): FirewallChange {
        val next = when (current) {
            ConnectionStatus.METERED -> ConnectionStatus.ALLOW
            ConnectionStatus.UNMETERED -> ConnectionStatus.BOTH
            ConnectionStatus.BOTH -> ConnectionStatus.UNMETERED
            ConnectionStatus.ALLOW -> ConnectionStatus.METERED
        }
        return FirewallChange(FirewallStatus.NONE, next, current)
    }

    /**
     * Picking a mode: any mode change resets Wi-Fi / mobile blocks to allowed (as the old
     * toggles did); picking the current mode does nothing (null).
     */
    fun modeChange(current: FirewallStatus, target: AppNetworkMode): FirewallChange? {
        if (current == target.status) return null
        return FirewallChange(target.status, ConnectionStatus.ALLOW)
    }

    fun excludeBlocker(unknownPackage: Boolean, vpnLockdown: Boolean): ExcludeBlocker = when {
        unknownPackage -> ExcludeBlocker.UNKNOWN_PACKAGE
        vpnLockdown -> ExcludeBlocker.VPN_LOCKDOWN
        else -> ExcludeBlocker.NONE
    }

    fun access(firewall: FirewallStatus, connection: ConnectionStatus, tempAllowed: Boolean): AppAccess {
        return when (firewall) {
            // excluded apps never reach the firewall, so temp allow means nothing for them
            FirewallStatus.EXCLUDE -> AppAccess.EXCLUDED
            // temp allow is checked before every app rule (TunFirewallManager)
            else -> if (tempAllowed) AppAccess.TEMP_ALLOWED else when (firewall) {
                FirewallStatus.BYPASS_UNIVERSAL -> AppAccess.BYPASS_UNIVERSAL
                FirewallStatus.BYPASS_DNS_FIREWALL -> AppAccess.BYPASS_DNS_FIREWALL
                // wifi / mobile blocks still apply in isolate mode
                FirewallStatus.ISOLATE, FirewallStatus.NONE -> when (connection) {
                    ConnectionStatus.BOTH -> AppAccess.BLOCKED
                    ConnectionStatus.UNMETERED -> AppAccess.BLOCKED_WIFI
                    ConnectionStatus.METERED -> AppAccess.BLOCKED_MOBILE
                    ConnectionStatus.ALLOW ->
                        if (firewall == FirewallStatus.ISOLATE) AppAccess.ISOLATED else AppAccess.ALLOWED
                }
                FirewallStatus.EXCLUDE -> AppAccess.EXCLUDED
            }
        }
    }

    fun status(access: AppAccess): RethinkStatus = when (access) {
        AppAccess.ALLOWED, AppAccess.TEMP_ALLOWED,
        AppAccess.BYPASS_UNIVERSAL, AppAccess.BYPASS_DNS_FIREWALL -> RethinkStatus.Active
        AppAccess.BLOCKED_WIFI, AppAccess.BLOCKED_MOBILE, AppAccess.ISOLATED -> RethinkStatus.Waiting
        AppAccess.BLOCKED -> RethinkStatus.Error
        AppAccess.EXCLUDED -> RethinkStatus.Off
    }

    /**
     * The header's one-line summary. The proxy part is left out when the app follows the
     * global settings (nothing app-specific to say) and when it is excluded from the VPN
     * (no proxy applies then).
     */
    fun summary(
        firewall: FirewallStatus,
        connection: ConnectionStatus,
        tempAllowed: Boolean,
        proxy: ProxyRowState,
    ): AppSummary {
        val access = access(firewall, connection, tempAllowed)
        val proxyPart = if (access == AppAccess.EXCLUDED) null else when (proxy) {
            ProxyRowState.Loading, ProxyRowState.Default -> null
            ProxyRowState.NoProxy -> SummaryProxy.Direct
            is ProxyRowState.Via -> SummaryProxy.Via(proxy.choice)
            is ProxyRowState.Multiple -> SummaryProxy.Multiple(proxy.choices.size)
        }
        return AppSummary(status(access), access, proxyPart)
    }

    /**
     * The Proxy row for the app's current selection (ProxyChoices.currentForApp). Ids not among
     * [choices] (a deleted WireGuard config, ...) show as an "unknown" proxy, never as nothing.
     */
    fun proxyRow(selection: ProxyChoices.AppSelection, choices: List<ProxyChoices.Choice>): ProxyRowState {
        fun choiceOf(id: String) = ProxyChoices.withUnknown(choices, listOf(id)).first { it.proxyId == id }
        return when (val pick = selection.pick) {
            null -> ProxyRowState.Multiple(selection.multipleIds.map(::choiceOf))
            Pick.FollowGlobal -> ProxyRowState.Default
            Pick.Bypass -> ProxyRowState.NoProxy
            is Pick.Via -> ProxyRowState.Via(choiceOf(pick.proxyId))
        }
    }

    /** The status chip of a proxy, null when there is no single proxy. */
    fun proxyStatus(row: ProxyRowState): RethinkStatus? = (row as? ProxyRowState.Via)?.choice?.health?.toStatus()
}
