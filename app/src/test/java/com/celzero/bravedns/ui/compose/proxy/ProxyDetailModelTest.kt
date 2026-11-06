/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.proxy

import com.celzero.bravedns.R
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.service.IpRulesManager.IpRuleStatus
import com.celzero.bravedns.shadows.ShadowBackend
import com.celzero.bravedns.ui.compose.design.RethinkStatus
import com.celzero.bravedns.ui.compose.rules.RuleAction
import com.celzero.bravedns.ui.compose.rules.RuleItem
import com.celzero.bravedns.ui.compose.rules.RuleKind
import com.celzero.bravedns.ui.compose.rules.RuleDraft
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.firestack.backend.Backend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pure logic shared by the proxy pages (header status, apps, rules section, forms, Orbot, WireGuard). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [ShadowBackend::class])
class ProxyDetailModelTest {

    private val app = 10042

    // ---- header status ----

    @Test fun status_offWhenDisabled_evenIfUp() {
        assertEquals(RethinkStatus.Off, ProxyDetailLogic.status(false, true, Backend.TOK))
        assertEquals(RethinkStatus.Off, ProxyDetailLogic.status(false, true, Backend.TOK, failing = true, dnsFailing = true))
    }

    @Test fun status_activeWhenUp_waitingWhenNoHandshake() {
        assertEquals(RethinkStatus.Active, ProxyDetailLogic.status(true, true, Backend.TOK))
        assertEquals(RethinkStatus.Waiting, ProxyDetailLogic.status(true, true, Backend.TOK, failing = true))
    }

    @Test fun status_vpnOffIsWaiting_errorOnTko() {
        assertEquals(RethinkStatus.Waiting, ProxyDetailLogic.status(true, false, Backend.TOK))
        assertEquals(RethinkStatus.Error, ProxyDetailLogic.status(true, true, Backend.TKO))
    }

    @Test fun status_splitDnsFailureIsError_exceptWhenPaused() {
        assertEquals(RethinkStatus.Error, ProxyDetailLogic.status(true, true, Backend.TOK, dnsFailing = true))
        assertEquals(RethinkStatus.Waiting, ProxyDetailLogic.status(true, true, Backend.TPU, dnsFailing = true))
    }

    @Test fun isFailing_onlyWithoutHandshakePastThreshold() {
        assertTrue(ProxyDetailLogic.isFailing(nowMs = 10_000, sinceMs = 0, lastOkMs = 0, thresholdMs = 5_000))
        assertFalse(ProxyDetailLogic.isFailing(10_000, 0, lastOkMs = 9_000, thresholdMs = 5_000))
        assertFalse(ProxyDetailLogic.isFailing(10_000, sinceMs = 8_000, lastOkMs = 0, thresholdMs = 5_000))
    }

    // ---- apps ----

    @Test fun socks5Apps_allOrPicked_warnsWhenOnWithNone() {
        assertEquals(ProxyAppsUi.AllApps(R.string.proxy_page_apps_all_socks5), ProxyDetailLogic.socks5Apps(0, true, allApps = true))
        assertEquals(ProxyAppsUi.Picked(0, warnNone = true), ProxyDetailLogic.socks5Apps(0, enabled = true, allApps = false))
        assertEquals(ProxyAppsUi.Picked(0, warnNone = false), ProxyDetailLogic.socks5Apps(0, enabled = false, allApps = false))
        assertEquals(ProxyAppsUi.Picked(3, warnNone = false), ProxyDetailLogic.socks5Apps(3, enabled = true, allApps = false))
    }

    @Test fun wireguardApps_oneWgAndCatchAllRouteAll() {
        assertEquals(ProxyAppsUi.AllApps(R.string.proxy_page_apps_all_one_wg), ProxyDetailLogic.wireguardApps(2, true, oneWg = true, catchAll = true))
        assertEquals(ProxyAppsUi.AllApps(R.string.proxy_page_apps_all_catch_all), ProxyDetailLogic.wireguardApps(2, true, oneWg = false, catchAll = true))
        assertEquals(ProxyAppsUi.Picked(2, false), ProxyDetailLogic.wireguardApps(2, true, oneWg = false, catchAll = false))
        assertEquals(ProxyAppsUi.Picked(0, true), ProxyDetailLogic.wireguardApps(0, true, oneWg = false, catchAll = false))
    }

    @Test fun orbotAndHttpApps() {
        assertEquals(ProxyAppsUi.Picked(0, warnNone = false), ProxyDetailLogic.orbotApps(0))
        assertEquals(ProxyAppsUi.AllApps(R.string.proxy_page_apps_all_http), ProxyDetailLogic.httpApps())
    }

    // ---- rules section ----

    private fun ip(addr: String, uid: Int = app, proxyId: String, ts: Long, status: IpRuleStatus = IpRuleStatus.NONE) =
        CustomIp().apply { this.uid = uid; ipAddress = addr; port = 0; this.status = status.id; this.proxyId = proxyId; modifiedDateTime = ts }

    private fun domain(d: String, uid: Int = app, proxyId: String, ts: Long) = CustomDomain(d, uid, "", 0, 0, proxyId, "", ts, 0L, 1L)

    @Test fun merge_filtersByProxy_keepsAllUids_newestFirst() {
        val list = ProxyRulesLogic.merge(
            "S5",
            listOf(ip("1.1.1.1", proxyId = "S5", ts = 5), ip("2.2.2.2", proxyId = "wg1", ts = 9), ip("3.3.3.3", UID_EVERYBODY, "S5", 7)),
            listOf(domain("a.example", proxyId = "S5", ts = 6), domain("b.example", proxyId = "", ts = 10)),
        )
        assertEquals(listOf("3.3.3.3", "a.example", "1.1.1.1"), list.map { it.value })
        assertEquals(listOf(UID_EVERYBODY, app, app), list.map { it.uid })
    }

    @Test fun merge_sameTimestamp_isStable_byKindThenTarget() {
        val list = ProxyRulesLogic.merge(
            "ORBOT",
            listOf(ip("9.9.9.9", proxyId = "ORBOT", ts = 1), ip("1.1.1.1", proxyId = "ORBOT", ts = 1)),
            listOf(domain("z.example", proxyId = "ORBOT", ts = 1)),
        )
        assertEquals(listOf("1.1.1.1", "9.9.9.9", "z.example"), list.map { it.value })
    }

    @Test fun merge_keepsTheRuleAction() {
        val list = ProxyRulesLogic.merge("S5", listOf(ip("1.1.1.1", proxyId = "S5", ts = 1, status = IpRuleStatus.BLOCK)), emptyList())
        assertEquals(RuleAction.BLOCK, list.single().action)
    }

    @Test fun newDraft_routesViaProxy_noAction_allApps() {
        val d = ProxyRulesLogic.newDraft(RuleKind.DOMAIN, "wg3")
        assertEquals("wg3", d.proxyId)
        assertEquals(RuleAction.NONE, d.action)
        assertEquals(UID_EVERYBODY, d.uid)
        assertFalse(d.isEdit)
    }

    @Test fun withScope_changesNewDrafts_notEdits() {
        val d = ProxyRulesLogic.newDraft(RuleKind.IP, "S5")
        assertEquals(app, ProxyRulesLogic.withScope(d, app).uid)
        val edit = RuleDraft(RuleKind.IP, UID_EVERYBODY, editing = RuleItem.of(ip("1.1.1.1", UID_EVERYBODY, "S5", 1)))
        assertEquals(UID_EVERYBODY, ProxyRulesLogic.withScope(edit, app).uid)
    }

    @Test fun scopeChoices_allAppsFirst_oneEntryPerUid_sortedByName() {
        val list = AppScopeChoice.list("All apps", listOf(20 to "zeta", 10 to "Alpha", 20 to "beta", UID_EVERYBODY to "x"))
        assertEquals(listOf(UID_EVERYBODY, 10, 20), list.map { it.uid })
        assertEquals("beta, zeta", list[2].name)
        assertTrue(list.first().isAllApps)
    }

    @Test fun scopeFilter_matchesNameOrUid_keepsAllApps() {
        val list = AppScopeChoice.list("All apps", listOf(10 to "Alpha", 20 to "Beta"))
        assertEquals(listOf(UID_EVERYBODY, 20), AppScopeChoice.filter(list, "bet").map { it.uid })
        assertEquals(listOf(UID_EVERYBODY, 10), AppScopeChoice.filter(list, "10").map { it.uid })
        assertEquals(list, AppScopeChoice.filter(list, "  "))
    }

    // ---- SOCKS5 form ----

    @Test fun socks5Form_defaultsWhenNeverSet() {
        val f = Socks5FormLogic.form(Socks5Saved(null, 0, null, null))
        assertEquals(Socks5FormLogic.DEFAULT_HOST, f.host)
        assertEquals(Socks5FormLogic.DEFAULT_PORT.toString(), f.port)
    }

    @Test fun socks5Form_dirtyOnlyWhenChanged() {
        val saved = Socks5Saved("10.0.2.2", 1080, "u", "p")
        val f = Socks5FormLogic.form(saved)
        assertFalse(Socks5FormLogic.isDirty(f, saved))
        assertFalse(Socks5FormLogic.isDirty(f.copy(host = " 10.0.2.2 "), saved))
        assertTrue(Socks5FormLogic.isDirty(f.copy(port = "1081"), saved))
        assertTrue(Socks5FormLogic.isDirty(f.copy(password = ""), saved))
    }

    @Test fun socks5Validate_errors() {
        val blank = Socks5FormLogic.validate(Socks5Form(host = " ", port = "1080")) as Socks5FormLogic.Result.Invalid
        assertEquals(R.string.settings_http_proxy_error_text3, blank.form.hostError)
        val nan = Socks5FormLogic.validate(Socks5Form(host = "1.2.3.4", port = "x")) as Socks5FormLogic.Result.Invalid
        assertEquals(R.string.settings_http_proxy_error_text2, nan.form.portError)
        val range = Socks5FormLogic.validate(Socks5Form(host = "1.2.3.4", port = "70000")) as Socks5FormLogic.Result.Invalid
        assertEquals(R.string.settings_http_proxy_error_text1, range.form.portError)
    }

    @Test fun socks5Validate_ok_trims() {
        assertEquals(Socks5FormLogic.Result.Ok("10.0.2.2", 1080), Socks5FormLogic.validate(Socks5Form(host = " 10.0.2.2 ", port = " 1080 ")))
    }

    // ---- HTTP form ----

    @Test fun httpForm_defaultUrl_dirty_validate() {
        assertEquals(HttpFormLogic.DEFAULT_URL, HttpFormLogic.initial(null))
        assertEquals(HttpFormLogic.DEFAULT_URL, HttpFormLogic.initial(""))
        assertEquals("http://h:1", HttpFormLogic.initial("http://h:1"))
        assertFalse(HttpFormLogic.isDirty(HttpFormLogic.DEFAULT_URL, null))
        assertTrue(HttpFormLogic.isDirty("http://other:1", null))
        assertEquals(R.string.settings_http_proxy_error_text3, HttpFormLogic.validate(" "))
        assertNull(HttpFormLogic.validate("http://127.0.0.1:8118"))
    }

    // ---- Orbot ----

    @Test fun orbotMode_roundTrips() {
        OrbotMode.entries.forEach { assertEquals(it, OrbotLogic.modeOf(OrbotLogic.proxyTypeOf(it))) }
        assertEquals(OrbotMode.NONE, OrbotLogic.modeOf("garbage"))
    }

    @Test fun orbotModes_httpNeedsQ() {
        assertEquals(listOf(OrbotMode.SOCKS5, OrbotMode.NONE), OrbotLogic.modes(atLeastQ = false))
        assertTrue(OrbotMode.HTTP in OrbotLogic.modes(atLeastQ = true))
        assertTrue(OrbotMode.BOTH in OrbotLogic.modes(atLeastQ = true))
    }

    @Test fun orbotStartBlocker_order() {
        assertNull(OrbotLogic.startBlocker(OrbotMode.NONE, false, 0, false))
        assertEquals(R.string.proxy_page_orbot_not_installed, OrbotLogic.startBlocker(OrbotMode.SOCKS5, false, 0, false))
        assertEquals(R.string.orbot_no_app_toast, OrbotLogic.startBlocker(OrbotMode.SOCKS5, true, 0, false))
        assertEquals(R.string.settings_socks5_vpn_disabled_error, OrbotLogic.startBlocker(OrbotMode.BOTH, true, 2, false))
        assertNull(OrbotLogic.startBlocker(OrbotMode.HTTP, true, 2, true))
    }

    // ---- WireGuard ----

    @Test fun wg_simpleConfigsHaveNoSwitchRoutingOrAppPicker() {
        assertFalse(WgPageLogic.toggleable(oneWg = true))
        assertTrue(WgPageLogic.toggleable(oneWg = false))
        assertFalse(WgPageLogic.showRouting(oneWg = true))
        assertFalse(WgPageLogic.appsEnabled(oneWg = true, catchAll = false))
        assertFalse(WgPageLogic.appsEnabled(oneWg = false, catchAll = true))
        assertTrue(WgPageLogic.appsEnabled(oneWg = false, catchAll = false))
    }

    @Test fun wg_ssidErrors_onlyForAConfiguredList_permissionBeforeLocation() {
        assertEquals(false to false, WgPageLogic.ssidErrors(enabled = true, hasConfigured = false, hasPermissions = false, locationOn = false))
        assertEquals(true to false, WgPageLogic.ssidErrors(true, true, hasPermissions = false, locationOn = false))
        assertEquals(false to true, WgPageLogic.ssidErrors(true, true, hasPermissions = true, locationOn = false))
        assertEquals(false to false, WgPageLogic.ssidErrors(true, true, true, true))
        assertEquals(false to false, WgPageLogic.ssidErrors(false, true, false, false))
    }

    @Test fun wg_configIdOf() {
        assertEquals(3, WgPageLogic.configIdOf("wg3", "wg", -1))
        assertEquals(-1, WgPageLogic.configIdOf("", "wg", -1))
        assertEquals(-1, WgPageLogic.configIdOf("S5", "wg", -1))
        assertEquals(-1, WgPageLogic.configIdOf("wgx", "wg", -1))
    }

    @Test fun wg_dnsLine_appendsSearchDomains() {
        assertEquals("1.1.1.1, 8.8.8.8", WgPageLogic.dnsLine(listOf("1.1.1.1", "8.8.8.8"), emptyList()))
        assertEquals("1.1.1.1,lan", WgPageLogic.dnsLine(listOf("1.1.1.1"), listOf("lan")))
    }

    @Test fun wg_ipDetail_linesOnlyWhenKnown() {
        assertNull(WgPageLogic.ipDetail("", "AS1", null, null, null, null, 0.0, 0.0))
        assertEquals("10.0.0.1", WgPageLogic.ipDetail("10.0.0.1", "", null, "", null, null, 0.0, 0.0))
        assertEquals(
            "10.0.0.1\nASN  AS13335  ·  Cloudflare\nLOC  Frankfurt  ·  DE  ·  50.1109°, 8.6821°",
            WgPageLogic.ipDetail("10.0.0.1", "AS13335", "Cloudflare", null, "Frankfurt", "DE", 50.1109, 8.6821),
        )
    }

    @Test fun wg_peerTitle_endpointElseShortKey() {
        val key = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG="
        assertEquals("1.2.3.4:51820", WgPeerUi(0, key, "1.2.3.4:51820", null, null).title)
        assertEquals("abcdefgh…CDEFG=", WgPeerUi(0, key, null, null, null).title)
        assertEquals("short", WgPageLogic.shortKey("short"))
    }
}
