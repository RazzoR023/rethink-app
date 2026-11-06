/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.proxy

import java.util.Locale

/** A WireGuard peer as shown on the config page. [index] points into the activity's peer list. */
data class WgPeerUi(
    val index: Int,
    val publicKey: String,
    val endpoint: String?,
    val allowedIps: String?,
    val keepalive: String?,
) {
    /** row title: the endpoint, else the (shortened) public key */
    val title: String get() = endpoint ?: WgPageLogic.shortKey(publicKey)
}

/** Exit IP (client info) of an active config; [loading] while firestack is asked. */
data class WgExitIpUi(val loading: Boolean, val ip4: String? = null, val ip6: String? = null)

/** Which "keys already in use" warning is pending: turning the config on, or always-on. */
enum class WgDuplicateKeysFor { ENABLE, CATCH_ALL }

/** Pure logic of the WireGuard config page (WgConfigDetailActivity). */
object WgPageLogic {
    /** Simple-mode (one-wg) configs are turned on from the list's Simple tab, not here. */
    fun toggleable(oneWg: Boolean): Boolean = !oneWg

    /** The app picker is only for Advanced configs that aren't always-on. */
    fun appsEnabled(oneWg: Boolean, catchAll: Boolean): Boolean = !oneWg && !catchAll

    /** Lockdown, always-on, relays and automation only exist for Advanced configs. */
    fun showRouting(oneWg: Boolean): Boolean = !oneWg

    /**
     * SSID error rows. Location access is only needed when a configured SSID list must be
     * evaluated: permission first, then location services.
     */
    fun ssidErrors(enabled: Boolean, hasConfigured: Boolean, hasPermissions: Boolean, locationOn: Boolean): Pair<Boolean, Boolean> {
        val permission = enabled && hasConfigured && !hasPermissions
        val location = enabled && hasConfigured && hasPermissions && !locationOn
        return permission to location
    }

    /** "wg3" → 3; anything else → [invalid]. */
    fun configIdOf(proxyId: String, prefix: String, invalid: Int): Int =
        if (!proxyId.startsWith(prefix)) invalid else proxyId.substring(prefix.length).toIntOrNull() ?: invalid

    /** One-wg "DNS servers" value: servers, then search domains. */
    fun dnsLine(servers: List<String>, searchDomains: List<String>): String {
        val dns = servers.joinToString()
        val search = searchDomains.joinToString()
        return if (search.isNotEmpty()) "$dns,$search" else dns
    }

    /** Multi-line exit IP description: ip, then ASN and location lines when known. */
    fun ipDetail(
        ip: String?,
        asn: String?,
        asnOrg: String?,
        asnDom: String?,
        city: String?,
        cc: String?,
        lat: Double,
        lon: Double,
    ): String? {
        if (ip.isNullOrBlank()) return null
        val lines = mutableListOf(ip)
        val asnParts = listOfNotNull(asn, asnOrg, asnDom).filter { it.isNotBlank() }
        if (asnParts.isNotEmpty()) lines += "ASN  " + asnParts.joinToString("  ·  ")
        val loc = buildList {
            if (!city.isNullOrBlank()) add(city)
            if (!cc.isNullOrBlank()) add(cc)
            if (lat != 0.0 || lon != 0.0) add(String.format(Locale.US, "%.4f°, %.4f°", lat, lon))
        }
        if (loc.isNotEmpty()) lines += "LOC  " + loc.joinToString("  ·  ")
        return lines.joinToString("\n")
    }

    fun shortKey(key: String): String = if (key.length <= 16) key else key.take(8) + "…" + key.takeLast(6)
}
