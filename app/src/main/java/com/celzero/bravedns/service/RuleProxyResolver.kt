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
package com.celzero.bravedns.service

import com.celzero.bravedns.service.ProxyManager.ID_HTTP_BASE
import com.celzero.bravedns.service.ProxyManager.ID_ORBOT_BASE
import com.celzero.bravedns.service.ProxyManager.ID_S5_BASE
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.util.Utilities.isUnspecifiedIp
import com.celzero.firestack.backend.Backend

/**
 * Per-rule proxy routing: a custom domain / ip rule may route matching connections via a proxy
 * ("Route via wg2"). The rule's proxy is independent of its status (block / trust / none).
 *
 * Precedence, first match wins:
 *   1. domain rule of the app, 2. universal (all apps) domain rule,
 *   3. ip:port rule of the app, 4. universal ip:port rule,
 *   5. ip (any port) rule of the app, 6. universal ip rule,
 *   then the app's own proxy mapping and the global proxies (TunFlowManager).
 * Per-app rules beat universal ones at the same level, as the firewall checks app rules before
 * universal ones. Within a level the most specific rule wins: the exact domain, then wildcards
 * from the most to the least specific; the exact ip, then cidrs from the narrowest. A rule that
 * doesn't route via a proxy doesn't hide a less specific one that does.
 *
 * Only rules with a proxyId take part; rules that only set a proxyCC (rpn country) are skipped
 * as per-country rpn routing is not wired up yet.
 *
 * Once a rule matched, [decide] checks the rule's proxy can be used for this flow; if it can't,
 * the flow falls through to the app's normal routing (not to the next rule), except for a
 * wireguard lockdown config not eligible on this network, which blocks.
 *
 * Apps in a wireguard lockdown config ignore rule proxies, unless
 * PersistentState.ruleProxyInLockdown is on: then a usable rule proxy goes first, followed by
 * the app's lockdown config, and direct exits are dropped.
 */
object RuleProxyResolver {

    enum class Level {
        DOMAIN,
        IP_PORT,
        IP
    }

    /** a matched rule proxy; [ruleUid] is the app's uid or [UID_EVERYBODY] for universal rules */
    data class RuleProxy(val proxyId: String, val level: Level, val ruleUid: Int)

    /** rule look-ups, (proxyId, proxyCC) or ("", ""); abstracted for tests */
    interface Lookup {
        fun domainProxy(uid: Int, domain: String): Pair<String, String>

        /** rules on exactly [port]; port 0 for the any-port rules */
        fun ipProxy(uid: Int, ip: String, port: Int): Pair<String, String>
    }

    /** look-ups over the in-memory rule tries of [DomainRulesManager] and [IpRulesManager] */
    object RulesLookup : Lookup {
        override fun domainProxy(uid: Int, domain: String): Pair<String, String> {
            return DomainRulesManager.getProxyForDomain(uid, domain)
        }

        override fun ipProxy(uid: Int, ip: String, port: Int): Pair<String, String> {
            return IpRulesManager.getProxyForIpPort(uid, ip, port)
        }
    }

    /** state of a socks5 / orbot / http proxy; [forwarderApp] is the package forwarding to it */
    data class Endpoint(val enabled: Boolean, val forwarderApp: String?)

    /** flow / proxy state for [decide]; only consulted once a rule matched */
    interface Env {
        /** rpn proxy ids to prepend, as the other proxy paths do */
        val rpnIds: List<String>

        suspend fun isAppExcludedFromProxy(): Boolean

        /** app is routed via a wireguard lockdown config */
        fun isAppInWgLockdown(): Boolean

        /** rules route apps in a lockdown config too (setting); see [decide] */
        val rulesInLockdown: Boolean get() = false

        /** the app's lockdown config ids usable on this network, the fallback after a rule proxy */
        suspend fun lockdownIds(): List<String> = emptyList()

        /** see [WireguardManager.getConfigIdsForRule] */
        fun wgIds(pid: String): List<String>

        /** null if [pid] is not a socks5 / orbot / http proxy id */
        suspend fun endpoint(pid: String): Endpoint?

        suspend fun packageName(): String?
    }

    sealed interface Decision {
        /** no usable rule proxy; continue with the app's normal routing */
        data object None : Decision

        /** rule's wireguard lockdown config is not eligible on this network (rule#17) */
        data object Block : Decision

        /** route via [pids], in order */
        data class Route(val pids: List<String>) : Decision
    }

    /** [domain] and [ip] may be csv; only the first entry is considered */
    fun findRuleProxy(
        uid: Int,
        domain: String?,
        ip: String?,
        port: Int,
        lookup: Lookup = RulesLookup
    ): RuleProxy? {
        val uids = if (uid == UID_EVERYBODY) intArrayOf(uid) else intArrayOf(uid, UID_EVERYBODY)

        val d = first(domain)
        if (d.isNotEmpty()) {
            for (u in uids) {
                val pid = lookup.domainProxy(u, d).first
                if (pid.isNotEmpty()) return RuleProxy(pid, Level.DOMAIN, u)
            }
        }

        val addr = first(ip)
        if (addr.isEmpty() || isUnspecifiedIp(addr)) return null

        if (port > 0) {
            for (u in uids) {
                val pid = lookup.ipProxy(u, addr, port).first
                if (pid.isNotEmpty()) return RuleProxy(pid, Level.IP_PORT, u)
            }
        }
        for (u in uids) {
            val pid = lookup.ipProxy(u, addr, 0).first
            if (pid.isNotEmpty()) return RuleProxy(pid, Level.IP, u)
        }
        return null
    }

    suspend fun decide(rule: RuleProxy, env: Env): Decision {
        // excluded apps go direct; app-level wireguard lockdown pins the app to its config
        if (env.isAppExcludedFromProxy()) return Decision.None
        // app-level wireguard lockdown pins the app to its config unless the user lets rules
        // apply in lockdown; then the rule's proxy goes first, the app's lockdown config after
        // it, and never a direct exit, so lockdown still never leaks
        val lockdown = env.isAppInWgLockdown()
        if (lockdown && !env.rulesInLockdown) return Decision.None
        val fallback = if (lockdown) env.lockdownIds().filterNot { it == Backend.Block || isDirect(it) } else emptyList()

        val pid = rule.proxyId
        if (WireguardManager.isWgProxyId(pid)) {
            val wg = env.wgIds(pid)
            val ids = if (lockdown) wg.filterNot(::isDirect) else wg
            if (ids.isEmpty()) return Decision.None
            if (ids.contains(Backend.Block)) return Decision.Block
            return Decision.Route(env.rpnIds + ids + fallback.filterNot(ids::contains))
        }

        if (pid != ID_S5_BASE && pid != ID_ORBOT_BASE && pid != ID_HTTP_BASE) {
            // ID_NONE, rpn or unknown ids: not supported as a rule proxy
            return Decision.None
        }
        val ep = env.endpoint(pid) ?: return Decision.None
        if (!ep.enabled) return Decision.None
        // the app forwarding to the proxy must never be routed back into it
        if (ep.forwarderApp == env.packageName()) return Decision.None
        return Decision.Route(env.rpnIds + pid + fallback)
    }

    /** ids that leave without a proxy (or via whatever auto picks) */
    private fun isDirect(id: String): Boolean = id == Backend.Exit || id == Backend.Base || id == Backend.Auto

    suspend fun resolve(
        uid: Int,
        domain: String?,
        ip: String?,
        port: Int,
        env: Env,
        lookup: Lookup = RulesLookup
    ): Decision {
        val rule = findRuleProxy(uid, domain, ip, port, lookup) ?: return Decision.None
        return decide(rule, env)
    }

    private fun first(csv: String?): String {
        if (csv.isNullOrEmpty()) return ""
        val i = csv.indexOf(',')
        return (if (i < 0) csv else csv.substring(0, i)).trim()
    }
}
