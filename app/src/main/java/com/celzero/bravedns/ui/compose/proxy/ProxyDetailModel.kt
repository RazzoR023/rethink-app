/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.proxy

import androidx.compose.runtime.Immutable
import com.celzero.bravedns.R
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.service.ProxyChoices
import com.celzero.bravedns.ui.compose.design.RethinkStatus
import com.celzero.bravedns.ui.compose.design.toStatus
import com.celzero.bravedns.ui.compose.rules.RuleAction
import com.celzero.bravedns.ui.compose.rules.RuleDraft
import com.celzero.bravedns.ui.compose.rules.RuleItem
import com.celzero.bravedns.ui.compose.rules.RuleKind
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.util.Utilities
import com.celzero.firestack.backend.Backend

/*
 * Pure logic of the proxy pages (WireGuard config, SOCKS5, HTTP, Orbot), shared by their
 * common layout (ProxyDetailScaffold): the header, the Apps section, the rules routed via the
 * proxy, and the SOCKS5 / HTTP forms. No Android beyond string resource ids; tested by
 * ProxyDetailModelTest.
 */

enum class ProxyType { WIREGUARD, SOCKS5, HTTP, ORBOT }

/** The page header: who the proxy is, its live state and the on/off switch. */
@Immutable
data class ProxyHeaderUi(
    val type: ProxyType,
    val name: String,
    /** "10.0.2.2:1080", a url, "id 3"; null when there is nothing to show */
    val detail: String? = null,
    val status: RethinkStatus = RethinkStatus.Off,
    /** the status chip's text, e.g. "Connected · 2 min ago"; null shows the plain status */
    val statusText: String? = null,
    val enabled: Boolean = false,
    /** the switch is shown (WireGuard one-wg pages have none) */
    val toggleable: Boolean = true,
    /** a write is in flight; the switch is disabled */
    val busy: Boolean = false,
    /** a flag emoji for WireGuard endpoints */
    val flag: String? = null,
)

/** What the Apps section shows. */
@Immutable
sealed interface ProxyAppsUi {
    /** The proxy routes all apps (no per-app list); [reason] is a string resource. */
    data class AllApps(val reason: Int) : ProxyAppsUi

    /** Apps picked in the shared per-proxy picker; [warnNone] when on and routing nothing. */
    data class Picked(val count: Int, val warnNone: Boolean) : ProxyAppsUi
}

object ProxyDetailLogic {

    /**
     * Status chip of a proxy: Off when turned off, Waiting while the VPN is off or the proxy is
     * starting / idle / unknown, Error when firestack reports it failing, Active when up.
     * [failing] is the "no handshake since start" check (see [isFailing]); [dnsFailing] is a
     * WireGuard split-DNS failure.
     */
    fun status(enabled: Boolean, vpnOn: Boolean, backend: Int?, failing: Boolean = false, dnsFailing: Boolean = false): RethinkStatus {
        val base = ProxyChoices.health(enabled, vpnOn, backend).toStatus()
        if (base == RethinkStatus.Off) return base
        if (dnsFailing && backend != Backend.TPU) return RethinkStatus.Error
        if (base == RethinkStatus.Active && failing) return RethinkStatus.Waiting
        return base
    }

    /** No successful handshake [thresholdMs] after the proxy started. */
    fun isFailing(nowMs: Long, sinceMs: Long, lastOkMs: Long, thresholdMs: Long): Boolean =
        nowMs - sinceMs > thresholdMs && lastOkMs == 0L

    fun socks5Apps(count: Int, enabled: Boolean, allApps: Boolean): ProxyAppsUi =
        if (allApps) ProxyAppsUi.AllApps(R.string.proxy_page_apps_all_socks5)
        else ProxyAppsUi.Picked(count, warnNone = enabled && count == 0)

    fun wireguardApps(count: Int, enabled: Boolean, oneWg: Boolean, catchAll: Boolean): ProxyAppsUi = when {
        oneWg -> ProxyAppsUi.AllApps(R.string.proxy_page_apps_all_one_wg)
        catchAll -> ProxyAppsUi.AllApps(R.string.proxy_page_apps_all_catch_all)
        else -> ProxyAppsUi.Picked(count, warnNone = enabled && count == 0)
    }

    /** Orbot only routes picked apps; it can't be turned on with none (orbot_no_app_toast). */
    fun orbotApps(count: Int): ProxyAppsUi = ProxyAppsUi.Picked(count, warnNone = false)

    /** HTTP routes every app that no other proxy took; there is no per-app list. */
    fun httpApps(): ProxyAppsUi = ProxyAppsUi.AllApps(R.string.proxy_page_apps_all_http)
}

/** The rules (of any app, incl. All apps) that route via one proxy. */
object ProxyRulesLogic {

    /** Domain and IP rules routed via [proxyId], most recently changed first. */
    fun merge(proxyId: String, ips: List<CustomIp>, domains: List<CustomDomain>): List<RuleItem> {
        val items = ips.filter { it.proxyId == proxyId }.map { RuleItem.of(it) } +
            domains.filter { it.proxyId == proxyId }.map { RuleItem.of(it) }
        return items.sortedWith(compareByDescending<RuleItem> { it.modifiedTs }.thenBy { it.kind }.thenBy { it.target })
    }

    /**
     * A new rule from a proxy page: routed via [proxyId], no firewall action (it's a routing
     * rule), for All apps until an app is picked.
     */
    fun newDraft(kind: RuleKind, proxyId: String, uid: Int = UID_EVERYBODY): RuleDraft =
        RuleDraft(kind = kind, uid = uid, action = RuleAction.NONE, proxyId = proxyId)

    /** The editor's scope choice for the rule: [uid] or All apps. */
    fun withScope(draft: RuleDraft, uid: Int): RuleDraft =
        if (draft.isEdit) draft else draft.copy(uid = uid)
}

/** One entry of the editor's "Applies to" chooser. */
@Immutable
data class AppScopeChoice(val uid: Int, val name: String) {
    val isAllApps: Boolean get() = uid == UID_EVERYBODY

    companion object {
        /** All apps first, then apps by name; one entry per uid. */
        fun list(allAppsName: String, apps: List<Pair<Int, String>>): List<AppScopeChoice> {
            val byUid = apps.filter { it.first != UID_EVERYBODY }
                .groupBy { it.first }
                .map { (uid, names) -> AppScopeChoice(uid, names.map { it.second }.distinct().sorted().joinToString(", ")) }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            return listOf(AppScopeChoice(UID_EVERYBODY, allAppsName)) + byUid
        }

        fun filter(choices: List<AppScopeChoice>, query: String): List<AppScopeChoice> {
            val q = query.trim()
            if (q.isEmpty()) return choices
            return choices.filter { it.isAllApps || it.name.contains(q, ignoreCase = true) || it.uid.toString() == q }
        }
    }
}

/** SOCKS5 server form. */
@Immutable
data class Socks5Form(
    val host: String = "",
    val port: String = "",
    val userName: String = "",
    val password: String = "",
    val hostError: Int? = null,
    val portError: Int? = null,
) {
    fun clearErrors() = copy(hostError = null, portError = null)
}

/** The stored SOCKS5 endpoint, as far as the form cares. */
@Immutable
data class Socks5Saved(val ip: String?, val port: Int, val userName: String?, val password: String?)

object Socks5FormLogic {
    const val DEFAULT_HOST = Constants.SOCKS_DEFAULT_IP
    const val DEFAULT_PORT = Constants.SOCKS_DEFAULT_PORT

    /** never configured: the form suggests the usual local forwarder address */
    fun form(saved: Socks5Saved, defaultHost: String = DEFAULT_HOST, defaultPort: Int = DEFAULT_PORT): Socks5Form = Socks5Form(
        host = saved.ip?.takeUnless { it.isBlank() } ?: defaultHost,
        port = if (saved.ip.isNullOrBlank()) defaultPort.toString() else saved.port.toString(),
        userName = saved.userName.orEmpty(),
        password = saved.password.orEmpty(),
    )

    fun isDirty(form: Socks5Form, saved: Socks5Saved, defaultHost: String = DEFAULT_HOST, defaultPort: Int = DEFAULT_PORT): Boolean {
        val base = form(saved, defaultHost, defaultPort)
        return form.host.trim() != base.host || form.port.trim() != base.port ||
            form.userName != base.userName || form.password != base.password
    }

    sealed interface Result {
        data class Ok(val host: String, val port: Int) : Result
        data class Invalid(val form: Socks5Form) : Result
    }

    /** Same checks as the old screen: host set, port a number in range (any port for LAN hosts). */
    fun validate(form: Socks5Form): Result {
        val host = form.host.trim()
        if (host.isBlank()) return Result.Invalid(form.copy(hostError = R.string.settings_http_proxy_error_text3))
        val port = form.port.trim().toIntOrNull()
            ?: return Result.Invalid(form.copy(portError = R.string.settings_http_proxy_error_text2))
        val valid = if (Utilities.isLanIpv4(host)) Utilities.isValidLocalPort(port) else Utilities.isValidPort(port)
        if (!valid || !Utilities.isValidPort(port)) return Result.Invalid(form.copy(portError = R.string.settings_http_proxy_error_text1))
        return Result.Ok(host, port)
    }
}

/** HTTP(S) CONNECT proxy form: one url. */
object HttpFormLogic {
    const val DEFAULT_URL = "http://127.0.0.1:8118"

    fun initial(savedUrl: String?): String = savedUrl?.takeUnless { it.isBlank() } ?: DEFAULT_URL

    fun isDirty(url: String, savedUrl: String?): Boolean = url.trim() != initial(savedUrl)

    /** null when valid, else the error string resource (as the old dialog: host must be set). */
    fun validate(url: String): Int? = if (url.isBlank()) R.string.settings_http_proxy_error_text3 else null
}

/** Orbot's mode, as the old sheet's radio buttons. */
enum class OrbotMode { NONE, SOCKS5, HTTP, BOTH }

object OrbotLogic {
    fun modeOf(proxyType: String): OrbotMode = when (proxyType) {
        "SOCKS5" -> OrbotMode.SOCKS5
        "HTTP" -> OrbotMode.HTTP
        "HTTP_SOCKS5" -> OrbotMode.BOTH
        else -> OrbotMode.NONE
    }

    fun proxyTypeOf(mode: OrbotMode): String = when (mode) {
        OrbotMode.SOCKS5 -> "SOCKS5"
        OrbotMode.HTTP -> "HTTP"
        OrbotMode.BOTH -> "HTTP_SOCKS5"
        OrbotMode.NONE -> "NONE"
    }

    /** Modes offered: HTTP needs Android Q+ (as before). */
    fun modes(atLeastQ: Boolean): List<OrbotMode> =
        if (atLeastQ) listOf(OrbotMode.HTTP, OrbotMode.SOCKS5, OrbotMode.BOTH, OrbotMode.NONE)
        else listOf(OrbotMode.SOCKS5, OrbotMode.NONE)

    /** Why a mode can't be started now (string resource), or null. Turning off is always allowed. */
    fun startBlocker(mode: OrbotMode, installed: Boolean, appCount: Int, vpnOn: Boolean): Int? = when {
        mode == OrbotMode.NONE -> null
        !installed -> R.string.proxy_page_orbot_not_installed
        appCount == 0 -> R.string.orbot_no_app_toast
        !vpnOn -> R.string.settings_socks5_vpn_disabled_error
        else -> null
    }
}
