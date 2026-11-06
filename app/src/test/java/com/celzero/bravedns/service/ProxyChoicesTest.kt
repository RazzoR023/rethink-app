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

import com.celzero.bravedns.database.WgConfigFilesImmutable
import com.celzero.bravedns.service.ProxyChoices.AppSelection
import com.celzero.bravedns.service.ProxyChoices.Health
import com.celzero.bravedns.service.ProxyChoices.Kind
import com.celzero.bravedns.service.ProxyChoices.Pick
import com.celzero.firestack.backend.Backend
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JVM tests for the shared proxy picker's data source and selection logic. */
class ProxyChoicesTest {

    private class FakeSource(
        var wgs: List<WgConfigFilesImmutable> = emptyList(),
        var s5On: Boolean = false,
        var s5Endpoint: String? = null,
        var orbotOn: Boolean = false,
        var httpOn: Boolean = false,
        var httpUrl: String? = null,
        var vpnOn: Boolean = true,
        var statuses: Map<String, Int?> = emptyMap(),
    ) : ProxyChoices.Source {
        val asked = mutableListOf<String>()
        override fun wireguardConfigs() = wgs
        override fun isSocks5Enabled() = s5On
        override suspend fun socks5Endpoint() = s5Endpoint
        override fun isOrbotEnabled() = orbotOn
        override fun isHttpEnabled() = httpOn
        override suspend fun httpEndpoint() = httpUrl
        override fun isVpnOn() = vpnOn
        override suspend fun backendStatus(proxyId: String): Int? { asked += proxyId; return statuses[proxyId] }
    }

    private class FakeWriter(private val accept: Boolean = true) : ProxyChoices.AppWriter {
        val calls = mutableListOf<String>()
        override suspend fun setProxy(uid: Int, packageName: String, proxyId: String?, proxyName: String): Boolean {
            calls += "proxy:$uid:$packageName:$proxyId:$proxyName"
            return accept
        }
        override fun setExcluded(uid: Int, excluded: Boolean) { calls += "excluded:$uid:$excluded" }
    }

    private fun wg(
        id: Int, name: String, active: Boolean, catchAll: Boolean = false, lockdown: Boolean = false,
        oneWg: Boolean = false, metered: Boolean = false, ssid: Boolean = false,
    ) = WgConfigFilesImmutable(
        id, name, "/wg$id.conf", "", active, catchAll, lockdown, oneWg, metered, true, ssid, "", false,
    )

    private val uid = 10145
    private val pkg = "com.android.chrome"
    private val names: (String) -> String = { "name-$it" }

    // ---- health -------------------------------------------------------------------------

    @Test fun health_offWhenNotEnabled_evenIfRunning() {
        assertEquals(Health.OFF, ProxyChoices.health(enabled = false, vpnOn = true, status = Backend.TOK))
    }

    @Test fun health_waitingWhenVpnOff() {
        assertEquals(Health.WAITING, ProxyChoices.health(enabled = true, vpnOn = false, status = Backend.TOK))
    }

    @Test fun health_mapsBackendStatus() {
        assertEquals(Health.UP, ProxyChoices.health(true, true, Backend.TOK))
        assertEquals(Health.UP, ProxyChoices.health(true, true, Backend.TZZ))
        assertEquals(Health.ERROR, ProxyChoices.health(true, true, Backend.TKO))
        assertEquals(Health.WAITING, ProxyChoices.health(true, true, Backend.TUP))
        assertEquals(Health.WAITING, ProxyChoices.health(true, true, Backend.TNT))
        assertEquals(Health.WAITING, ProxyChoices.health(true, true, Backend.TPU))
        assertEquals(Health.WAITING, ProxyChoices.health(true, true, Backend.END))
        assertEquals(Health.WAITING, ProxyChoices.health(true, true, null))
    }

    // ---- list ---------------------------------------------------------------------------

    @Test fun list_wireguardByIdThenSocks5ThenOrbotThenHttp() = runBlocking {
        val src = FakeSource(wgs = listOf(wg(3, "home", false), wg(1, "wg-one", true)), s5Endpoint = "10.0.2.2:1080")
        val ids = ProxyChoices(src).list().map { it.proxyId }
        assertEquals(
            listOf("wg1", "wg3", ProxyManager.ID_S5_BASE, ProxyManager.ID_ORBOT_BASE, ProxyManager.ID_HTTP_BASE),
            ids,
        )
    }

    @Test fun list_offProxiesAreListedAndMarked() = runBlocking {
        val src = FakeSource(wgs = listOf(wg(2, "off-wg", active = false)), s5On = false, orbotOn = false)
        val list = ProxyChoices(src).list()
        assertEquals(4, list.size)
        assertTrue(list.all { !it.enabled && it.health == Health.OFF })
        // off proxies are not asked for a status
        assertTrue(src.asked.isEmpty())
    }

    @Test fun list_liveStatusForEnabledProxies() = runBlocking {
        val src = FakeSource(
            wgs = listOf(wg(1, "a", true), wg(2, "b", true)),
            s5On = true, s5Endpoint = "1.2.3.4:9050", orbotOn = true,
            statuses = mapOf("wg1" to Backend.TOK, "wg2" to Backend.TKO, "S5" to Backend.TUP, "ORBOT" to Backend.TZZ),
        )
        val byId = ProxyChoices(src).list().associateBy { it.proxyId }
        assertEquals(Health.UP, byId.getValue("wg1").health)
        assertEquals(Health.ERROR, byId.getValue("wg2").health)
        assertEquals(Health.WAITING, byId.getValue("S5").health)
        assertEquals(Health.UP, byId.getValue("ORBOT").health)
        assertEquals("1.2.3.4:9050", byId.getValue("S5").endpoint)
        assertEquals(Kind.SOCKS5, byId.getValue("S5").kind)
        assertEquals(Kind.ORBOT, byId.getValue("ORBOT").kind)
    }

    @Test fun list_vpnOff_enabledIsWaiting_noStatusQueries() = runBlocking {
        val src = FakeSource(wgs = listOf(wg(1, "a", true)), s5On = true, vpnOn = false)
        val byId = ProxyChoices(src).list().associateBy { it.proxyId }
        assertEquals(Health.WAITING, byId.getValue("wg1").health)
        assertEquals(Health.WAITING, byId.getValue("S5").health)
        assertEquals(Health.OFF, byId.getValue("ORBOT").health)
        assertTrue(src.asked.isEmpty())
    }

    @Test fun list_wireguardNameAndFlags() = runBlocking {
        val src = FakeSource(wgs = listOf(wg(4, "work", true, catchAll = true, lockdown = true, oneWg = false, metered = true, ssid = true)))
        val c = ProxyChoices(src).list().first()
        assertEquals("wg4", c.proxyId)
        assertEquals(Kind.WIREGUARD, c.kind)
        assertEquals("work", c.name)
        assertTrue(c.wgCatchAll && c.wgLockdown && c.wgMobileOnly && c.wgSsidOnly)
        assertFalse(c.wgOneWireGuard)
    }

    @Test fun list_socks5NotSetUp_hasNoEndpoint() = runBlocking {
        val s5 = ProxyChoices(FakeSource()).list().first { it.kind == Kind.SOCKS5 }
        assertNull(s5.endpoint)
    }

    // ---- app mode: current selection ---------------------------------------------------

    @Test fun currentForApp_excludedWinsOverMapping() {
        val sel = ProxyChoices.currentForApp(ProxyManager.AppProxy.Single("wg1"), excluded = true)
        assertEquals(AppSelection(Pick.Bypass), sel)
    }

    @Test fun currentForApp_noneIsFollowGlobal() {
        assertEquals(AppSelection(Pick.FollowGlobal), ProxyChoices.currentForApp(ProxyManager.AppProxy.None, false))
    }

    @Test fun currentForApp_single() {
        assertEquals(AppSelection(Pick.Via("S5")), ProxyChoices.currentForApp(ProxyManager.AppProxy.Single("S5"), false))
    }

    @Test fun currentForApp_multipleHasNoPick() {
        val sel = ProxyChoices.currentForApp(ProxyManager.AppProxy.Multiple(listOf("wg1", "wg2")), false)
        assertNull(sel.pick)
        assertEquals(listOf("wg1", "wg2"), sel.multipleIds)
    }

    // ---- app mode: applying a choice ----------------------------------------------------

    @Test fun applyForApp_via_setsTheOneProxyWithName() = runBlocking {
        val w = FakeWriter()
        val changed = ProxyChoices.applyForApp(Pick.Via("wg2"), AppSelection(Pick.FollowGlobal), false, uid, pkg, names, w)
        assertTrue(changed)
        assertEquals(listOf("proxy:$uid:$pkg:wg2:name-wg2"), w.calls)
    }

    @Test fun applyForApp_via_whenExcluded_alsoClearsExclusion() = runBlocking {
        val w = FakeWriter()
        ProxyChoices.applyForApp(Pick.Via("ORBOT"), AppSelection(Pick.Bypass), true, uid, pkg, names, w)
        assertEquals(listOf("proxy:$uid:$pkg:ORBOT:name-ORBOT", "excluded:$uid:false"), w.calls)
    }

    @Test fun applyForApp_via_refused_keepsExclusion() = runBlocking {
        val w = FakeWriter(accept = false)
        val changed = ProxyChoices.applyForApp(Pick.Via("wg9"), AppSelection(Pick.Bypass), true, uid, pkg, names, w)
        assertFalse(changed)
        assertEquals(listOf("proxy:$uid:$pkg:wg9:name-wg9"), w.calls)
    }

    @Test fun applyForApp_followGlobal_clearsMappingAndExclusion() = runBlocking {
        val w = FakeWriter()
        ProxyChoices.applyForApp(Pick.FollowGlobal, AppSelection(Pick.Bypass), true, uid, pkg, names, w)
        assertEquals(listOf("proxy:$uid:$pkg:null:", "excluded:$uid:false"), w.calls)
    }

    @Test fun applyForApp_bypass_onlySetsExclusion() = runBlocking {
        val w = FakeWriter()
        val changed = ProxyChoices.applyForApp(Pick.Bypass, AppSelection(Pick.Via("wg1")), false, uid, pkg, names, w)
        assertTrue(changed)
        assertEquals(listOf("excluded:$uid:true"), w.calls)
    }

    @Test fun applyForApp_sameChoice_isNoOp() = runBlocking {
        val w = FakeWriter()
        assertFalse(ProxyChoices.applyForApp(Pick.Via("wg1"), AppSelection(Pick.Via("wg1")), false, uid, pkg, names, w))
        assertFalse(ProxyChoices.applyForApp(Pick.FollowGlobal, AppSelection(Pick.FollowGlobal), false, uid, pkg, names, w))
        assertFalse(ProxyChoices.applyForApp(Pick.Bypass, AppSelection(Pick.Bypass), true, uid, pkg, names, w))
        assertTrue(w.calls.isEmpty())
    }

    @Test fun applyForApp_multiple_pickingOneReplacesThem() = runBlocking {
        val w = FakeWriter()
        val current = AppSelection(null, listOf("wg1", "wg2"))
        assertTrue(ProxyChoices.applyForApp(Pick.Via("wg1"), current, false, uid, pkg, names, w))
        assertEquals(listOf("proxy:$uid:$pkg:wg1:name-wg1"), w.calls)
    }

    // ---- rule mode ---------------------------------------------------------------------

    @Test fun rule_blankIsNoOverride() {
        assertEquals(Pick.FollowGlobal, ProxyChoices.currentForRule(null))
        assertEquals(Pick.FollowGlobal, ProxyChoices.currentForRule(""))
        assertEquals(Pick.FollowGlobal, ProxyChoices.currentForRule("  "))
        assertEquals(Pick.Via("wg2"), ProxyChoices.currentForRule("wg2"))
    }

    @Test fun rule_pickToStoredProxyId() {
        assertEquals("", ProxyChoices.ruleProxyId(Pick.FollowGlobal))
        assertEquals("", ProxyChoices.ruleProxyId(Pick.Bypass))
        assertEquals("S5", ProxyChoices.ruleProxyId(Pick.Via("S5")))
    }

    // ---- unknown ids -------------------------------------------------------------------

    @Test fun withUnknown_addsMissingSelectedIdsOnce() = runBlocking {
        val list = ProxyChoices(FakeSource(wgs = listOf(wg(1, "a", true)))).list()
        val shown = ProxyChoices.withUnknown(list, listOf("wg1", "wg7", "wg7", "", "S5"))
        val extra = shown.drop(list.size)
        assertEquals(listOf("wg7"), extra.map { it.proxyId })
        assertEquals(Kind.OTHER, extra.single().kind)
        assertEquals(Health.OFF, extra.single().health)
    }

    // ---- http ---------------------------------------------------------------------------

    @Test fun list_httpCarriesUrlAndLiveStatus() = runBlocking {
        val src = FakeSource(httpOn = true, httpUrl = "http://127.0.0.1:8118", statuses = mapOf("HTTP" to Backend.TOK))
        val http = ProxyChoices(src).list().single { it.kind == Kind.HTTP }
        assertEquals(ProxyManager.ID_HTTP_BASE, http.proxyId)
        assertEquals("http://127.0.0.1:8118", http.endpoint)
        assertTrue(http.enabled)
        assertEquals(Health.UP, http.health)
    }

    @Test fun list_httpOffIsNotAsked() = runBlocking {
        val src = FakeSource(httpOn = false)
        val http = ProxyChoices(src).list().single { it.kind == Kind.HTTP }
        assertEquals(Health.OFF, http.health)
        assertFalse("HTTP" in src.asked)
    }

    @Test fun forApps_dropsHttp_keepsTheRestInOrder() = runBlocking {
        val src = FakeSource(wgs = listOf(wg(1, "a", true)), httpOn = true)
        val all = ProxyChoices(src).list()
        val apps = ProxyChoices.forApps(all)
        assertEquals(all.map { it.proxyId } - ProxyManager.ID_HTTP_BASE, apps.map { it.proxyId })
        assertTrue(apps.none { it.kind == Kind.HTTP })
    }
}
