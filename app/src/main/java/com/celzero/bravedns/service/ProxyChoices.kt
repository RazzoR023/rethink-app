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

import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.WgConfigFilesImmutable
import com.celzero.bravedns.service.ProxyManager.ID_ORBOT_BASE
import com.celzero.bravedns.service.ProxyManager.ID_S5_BASE
import com.celzero.bravedns.service.ProxyManager.ID_WG_BASE
import com.celzero.firestack.backend.Backend
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * The proxies an app or a rule can be routed through, with live status, for the shared proxy
 * picker (ui/compose/design/ProxyPickerSheet.kt). Lists every WireGuard config, the custom
 * SOCKS5 proxy and Orbot, whether they are on or not; nothing is persisted here.
 *
 * Also holds the picker's selection logic, kept free of Android so it can be unit tested:
 * [currentForApp] / [applyForApp] for app mode, [currentForRule] / [ruleProxyId] for rule mode.
 */
class ProxyChoices(private val source: Source = LiveSource) {

    enum class Kind { WIREGUARD, SOCKS5, ORBOT, OTHER }

    /** Live state of a proxy: [OFF] when it is turned off in the app. */
    enum class Health { UP, WAITING, OFF, ERROR }

    data class Choice(
        val proxyId: String,
        val kind: Kind,
        /** WireGuard config name; empty for SOCKS5 / Orbot / unknown (the UI names those). */
        val name: String,
        /** SOCKS5 "host:port", null if not set up or not SOCKS5. */
        val endpoint: String? = null,
        /** Turned on in the app (wg config active, SOCKS5 enabled, Orbot is the proxy). */
        val enabled: Boolean,
        val health: Health,
        val wgCatchAll: Boolean = false,
        val wgLockdown: Boolean = false,
        val wgOneWireGuard: Boolean = false,
        val wgMobileOnly: Boolean = false,
        val wgSsidOnly: Boolean = false,
    )

    /** What the picker reads; [LiveSource] in the app, fakes in tests. */
    interface Source {
        fun wireguardConfigs(): List<WgConfigFilesImmutable>
        fun isSocks5Enabled(): Boolean
        suspend fun socks5Endpoint(): String?
        fun isOrbotEnabled(): Boolean
        fun isVpnOn(): Boolean
        /** firestack status (Backend.TOK, ...) of a proxy, null if unknown / not running. */
        suspend fun backendStatus(proxyId: String): Int?
    }

    /** All selectable proxies: WireGuard configs by id, then SOCKS5, then Orbot. */
    suspend fun list(): List<Choice> {
        val vpnOn = source.isVpnOn()
        suspend fun healthOf(id: String, enabled: Boolean): Health {
            val status = if (enabled && vpnOn) source.backendStatus(id) else null
            return health(enabled, vpnOn, status)
        }
        val wgs = source.wireguardConfigs().sortedBy { it.id }.map { wg ->
            val id = ID_WG_BASE + wg.id
            Choice(
                proxyId = id,
                kind = Kind.WIREGUARD,
                name = wg.name,
                enabled = wg.isActive,
                health = healthOf(id, wg.isActive),
                wgCatchAll = wg.isCatchAll,
                wgLockdown = wg.isLockdown,
                wgOneWireGuard = wg.oneWireGuard,
                wgMobileOnly = wg.useOnlyOnMetered,
                wgSsidOnly = wg.ssidEnabled,
            )
        }
        val s5On = source.isSocks5Enabled()
        val s5 = Choice(ID_S5_BASE, Kind.SOCKS5, "", source.socks5Endpoint(), s5On, healthOf(ID_S5_BASE, s5On))
        val orbotOn = source.isOrbotEnabled()
        val orbot = Choice(ID_ORBOT_BASE, Kind.ORBOT, "", null, orbotOn, healthOf(ID_ORBOT_BASE, orbotOn))
        return wgs + s5 + orbot
    }

    /** What a picker row stands for. */
    sealed interface Pick {
        /** App mode: no mapping, follow the global settings. Rule mode: no proxy override. */
        data object FollowGlobal : Pick
        /** App mode only: bypass all proxies (the app's isProxyExcluded flag). */
        data object Bypass : Pick
        data class Via(val proxyId: String) : Pick
    }

    /** The app's current choice: [pick] is null when the app is legacy multi-mapped. */
    data class AppSelection(val pick: Pick?, val multipleIds: List<String> = emptyList())

    /** Writes an app's choice; [LiveAppWriter] in the app, fakes in tests. */
    interface AppWriter {
        suspend fun setProxy(uid: Int, packageName: String, proxyId: String?, proxyName: String): Boolean
        fun setExcluded(uid: Int, excluded: Boolean)
    }

    companion object {
        fun health(enabled: Boolean, vpnOn: Boolean, status: Int?): Health {
            if (!enabled) return Health.OFF
            if (!vpnOn) return Health.WAITING
            return when (status) {
                Backend.TOK, Backend.TZZ -> Health.UP
                Backend.TKO -> Health.ERROR
                else -> Health.WAITING // starting, waiting, paused, stopped or unknown
            }
        }

        /** Excluded-from-proxy wins in routing, so it wins here too. */
        fun currentForApp(appProxy: ProxyManager.AppProxy, excluded: Boolean): AppSelection {
            if (excluded) return AppSelection(Pick.Bypass)
            return when (appProxy) {
                is ProxyManager.AppProxy.None -> AppSelection(Pick.FollowGlobal)
                is ProxyManager.AppProxy.Single -> AppSelection(Pick.Via(appProxy.proxyId))
                is ProxyManager.AppProxy.Multiple -> AppSelection(null, appProxy.proxyIds)
            }
        }

        /**
         * Applies [pick] for an app: Via sets the one proxy (replacing others), FollowGlobal
         * clears it, Bypass sets the exclude flag (the mapping is kept, as with the old switch).
         * Picking Via / FollowGlobal clears the exclude flag. Off proxies are not turned on.
         * Returns false if nothing was written (same choice) or the write was refused.
         */
        suspend fun applyForApp(
            pick: Pick,
            current: AppSelection,
            excluded: Boolean,
            uid: Int,
            packageName: String,
            proxyName: (String) -> String,
            writer: AppWriter,
        ): Boolean {
            if (current.pick == pick && current.multipleIds.isEmpty()) return false
            return when (pick) {
                Pick.Bypass -> {
                    if (!excluded) writer.setExcluded(uid, true)
                    !excluded
                }
                Pick.FollowGlobal -> {
                    val ok = writer.setProxy(uid, packageName, null, "")
                    if (excluded) writer.setExcluded(uid, false)
                    ok
                }
                is Pick.Via -> {
                    val ok = writer.setProxy(uid, packageName, pick.proxyId, proxyName(pick.proxyId))
                    if (ok && excluded) writer.setExcluded(uid, false)
                    ok
                }
            }
        }

        /** A rule's stored proxyId ("" when none) as a pick. */
        fun currentForRule(proxyId: String?): Pick {
            return if (proxyId.isNullOrBlank()) Pick.FollowGlobal else Pick.Via(proxyId)
        }

        /** The proxyId to store on a rule for [pick]: "" for no override. */
        fun ruleProxyId(pick: Pick): String {
            return when (pick) {
                is Pick.Via -> pick.proxyId
                Pick.FollowGlobal, Pick.Bypass -> ""
            }
        }

        /**
         * [choices] plus a row for each of [selectedIds] not among them (a deleted WireGuard
         * config, an RPN id, ...), so the current selection is never shown as "nothing".
         */
        fun withUnknown(choices: List<Choice>, selectedIds: Collection<String>): List<Choice> {
            val known = choices.map { it.proxyId }.toSet()
            val extra = selectedIds.filter { it.isNotBlank() && it !in known }.distinct().map {
                Choice(it, Kind.OTHER, "", null, enabled = false, health = Health.OFF)
            }
            return choices + extra
        }
    }

    object LiveSource : Source, KoinComponent {
        private val appConfig: AppConfig by inject()

        override fun wireguardConfigs(): List<WgConfigFilesImmutable> = WireguardManager.getAllMappings()

        override fun isSocks5Enabled(): Boolean = appConfig.isCustomSocks5Enabled()

        override suspend fun socks5Endpoint(): String? {
            val ep = appConfig.getSocks5ProxyDetails() ?: return null
            val ip = ep.proxyIP?.takeUnless { it.isBlank() } ?: return null
            return if (ep.proxyPort > 0) "$ip:${ep.proxyPort}" else ip
        }

        override fun isOrbotEnabled(): Boolean = appConfig.isOrbotProxyEnabled()

        override fun isVpnOn(): Boolean = VpnController.hasTunnel()

        override suspend fun backendStatus(proxyId: String): Int? = VpnController.getProxyStatusById(proxyId).first
    }

    object LiveAppWriter : AppWriter {
        override suspend fun setProxy(uid: Int, packageName: String, proxyId: String?, proxyName: String): Boolean {
            return ProxyManager.setProxyForApp(uid, packageName, proxyId, proxyName)
        }

        override fun setExcluded(uid: Int, excluded: Boolean) {
            FirewallManager.updateIsProxyExcluded(uid, excluded)
        }
    }
}
