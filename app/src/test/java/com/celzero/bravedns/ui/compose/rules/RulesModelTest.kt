/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.rules

import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.IpRulesManager.IpRuleStatus
import com.celzero.bravedns.service.ProxyChoices
import com.celzero.bravedns.service.ProxyChoices.Health
import com.celzero.bravedns.service.ProxyChoices.Kind
import com.celzero.bravedns.shadows.ShadowBackend
import com.celzero.bravedns.ui.activity.CustomRulesActivity
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The rules screens' pure logic (RulesModel.kt) and screen state (RulesPageState.kt). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [ShadowBackend::class])
class RulesModelTest {

    private val app = 10042

    private fun ip(addr: String, port: Int = 0, uid: Int = app, status: IpRuleStatus = IpRuleStatus.BLOCK, proxyId: String = "") =
        CustomIp().apply {
            this.uid = uid; ipAddress = addr; this.port = port; this.status = status.id; this.proxyId = proxyId
        }

    private fun domain(d: String, uid: Int = app, status: DomainRulesManager.Status = DomainRulesManager.Status.TRUST,
                       type: DomainRulesManager.DomainType = DomainRulesManager.DomainType.DOMAIN, proxyId: String = "") =
        CustomDomain(d, uid, "", type.id, status.id, proxyId, "", 1L, 0L, 1L)

    // ---- RuleItem ----

    @Test fun ipItem_mapsFieldsAndTarget() {
        val r = RuleItem.of(ip("1.2.3.4", proxyId = "wg1"))
        assertEquals(RuleKind.IP, r.kind)
        assertEquals(RuleAction.BLOCK, r.action)
        assertEquals("wg1", r.proxyId)
        assertEquals("1.2.3.4", r.target)
        assertEquals("1.2.3.4", r.editorInput)
        assertFalse(r.isUniversal)
    }

    @Test fun ipItem_targetWithPortAndPortOnly() {
        assertEquals("1.2.3.4:443", RuleItem.of(ip("1.2.3.4", 443)).target)
        assertEquals("1.2.3.4:443", RuleItem.of(ip("1.2.3.4", 443)).editorInput)
        assertEquals("*:53", RuleItem.of(ip("", 53)).target)
    }

    @Test fun domainItem_wildcardAndAction() {
        val r = RuleItem.of(domain("*.example.com", type = DomainRulesManager.DomainType.WILDCARD, proxyId = "S5"))
        assertEquals(RuleKind.DOMAIN, r.kind)
        assertTrue(r.wildcard)
        assertEquals(RuleAction.TRUST, r.action)
        assertEquals("*.example.com", r.target)
        assertEquals("S5", r.proxyId)
    }

    @Test fun item_sourceIsACopy() {
        val c = ip("1.2.3.4", proxyId = "wg1")
        val r = RuleItem.of(c)
        c.proxyId = "changed"
        assertEquals("wg1", r.copyIp().proxyId)
        val d = domain("a.com", proxyId = "S5")
        val rd = RuleItem.of(d)
        d.proxyId = "changed"
        assertEquals("S5", rd.copyDomain().proxyId)
    }

    @Test fun keys_differByUidPortAndKind() {
        val a = RuleItem.of(ip("1.2.3.4", 0))
        assertNotEquals(a.key, RuleItem.of(ip("1.2.3.4", 443)).key)
        assertNotEquals(a.key, RuleItem.of(ip("1.2.3.4", 0, uid = UID_EVERYBODY)).key)
        assertNotEquals(RuleItem.of(domain("a.com")).key, RuleItem.of(domain("a.com", uid = 1)).key)
        assertEquals(a.key, RuleItem.of(ip("1.2.3.4", 0, status = IpRuleStatus.TRUST)).key)
    }

    // ---- actions ----

    @Test fun actionOf_roundTripsStatuses() {
        IpRuleStatus.entries.forEach { s ->
            assertEquals(s, RulesLogic.ipStatus(RulesLogic.actionOf(RuleKind.IP, s.id)))
        }
        DomainRulesManager.Status.entries.forEach { s ->
            assertEquals(s, RulesLogic.domainStatus(RulesLogic.actionOf(RuleKind.DOMAIN, s.id)))
        }
    }

    @Test fun actionOptions_universalIpUsesBypassUniversal() {
        assertEquals(listOf(RuleAction.BYPASS_UNIVERSAL, RuleAction.BLOCK, RuleAction.NONE),
            RulesLogic.actionOptions(RuleKind.IP, UID_EVERYBODY))
        assertEquals(listOf(RuleAction.TRUST, RuleAction.BLOCK, RuleAction.NONE), RulesLogic.actionOptions(RuleKind.IP, app))
        assertEquals(listOf(RuleAction.TRUST, RuleAction.BLOCK, RuleAction.NONE), RulesLogic.actionOptions(RuleKind.DOMAIN, UID_EVERYBODY))
    }

    @Test fun actionOptions_keepsAnUnofferedCurrentAction() {
        val opts = RulesLogic.actionOptions(RuleKind.IP, app, RuleAction.BYPASS_UNIVERSAL)
        assertEquals(RuleAction.BYPASS_UNIVERSAL, opts.last())
        assertEquals(4, opts.size)
        assertEquals(3, RulesLogic.actionOptions(RuleKind.IP, app, RuleAction.BLOCK).size)
    }

    @Test fun trustAction_followsScope() {
        assertEquals(RuleAction.BYPASS_UNIVERSAL, RulesLogic.trustAction(RuleKind.IP, UID_EVERYBODY))
        assertEquals(RuleAction.TRUST, RulesLogic.trustAction(RuleKind.IP, app))
        assertEquals(RuleAction.TRUST, RulesLogic.trustAction(RuleKind.DOMAIN, UID_EVERYBODY))
    }

    @Test fun domainStatus_bypassMapsToTrust() {
        assertEquals(DomainRulesManager.Status.TRUST, RulesLogic.domainStatus(RuleAction.BYPASS_UNIVERSAL))
    }

    // ---- scope ----

    @Test fun scope_fromIntent() {
        val universal = RulesScope.of(UID_EVERYBODY, CustomRulesActivity.RULES.APP_SPECIFIC_RULES)
        assertTrue(universal.isUniversal); assertTrue(universal.canAdd); assertFalse(universal.grouped)
        assertEquals(DeleteAllScope.ONE_UID, RulesLogic.deleteAllScope(universal))

        val oneApp = RulesScope.of(app, CustomRulesActivity.RULES.APP_SPECIFIC_RULES)
        assertFalse(oneApp.isUniversal); assertTrue(oneApp.canAdd)
        assertEquals(DeleteAllScope.ONE_UID, RulesLogic.deleteAllScope(oneApp))

        val all = RulesScope.of(UID_EVERYBODY, CustomRulesActivity.RULES.ALL_RULES)
        assertFalse(all.isUniversal); assertFalse(all.canAdd); assertTrue(all.grouped)
        assertEquals(DeleteAllScope.ALL_APP_RULES, RulesLogic.deleteAllScope(all))
    }

    @Test fun showHeader_onlyAtUidChangesWhenGrouped() {
        assertTrue(RulesLogic.showHeader(true, 0, null, 1))
        assertFalse(RulesLogic.showHeader(true, 1, 1, 1))
        assertTrue(RulesLogic.showHeader(true, 2, 1, 2))
        assertFalse(RulesLogic.showHeader(false, 0, null, 1))
    }

    // ---- proxy ----

    private val wg = ProxyChoices.Choice("wg1", Kind.WIREGUARD, "wg-one", enabled = true, health = Health.UP)

    @Test fun proxyChoice_knownUnknownAndNone() {
        assertNull(RulesLogic.proxyChoice("", listOf(wg)))
        assertEquals(wg, RulesLogic.proxyChoice("wg1", listOf(wg)))
        val gone = RulesLogic.proxyChoice("wg9", listOf(wg))!!
        assertEquals("wg9", gone.proxyId)
        assertEquals(Health.OFF, gone.health)
        // not loaded yet: still named, as unknown
        assertEquals("wg1", RulesLogic.proxyChoice("wg1", null)!!.proxyId)
    }

    @Test fun proxyIgnored_onlyForBlockWithProxy() {
        assertTrue(RulesLogic.proxyIgnored(RuleAction.BLOCK, "wg1"))
        assertFalse(RulesLogic.proxyIgnored(RuleAction.BLOCK, ""))
        assertFalse(RulesLogic.proxyIgnored(RuleAction.TRUST, "wg1"))
        assertFalse(RulesLogic.proxyIgnored(RuleAction.NONE, "wg1"))
    }

    // ---- draft ----

    @Test fun draft_addDefaults() {
        val d = RuleDraft(RuleKind.IP, app)
        assertFalse(d.isEdit)
        assertEquals(RuleAction.BLOCK, d.action)
        assertEquals("", d.proxyId)
        assertFalse(d.canSave)
        assertTrue(d.withInput("1.2.3.4").canSave)
        assertFalse(d.withInput("1.2.3.4").copy(saving = true).canSave)
    }

    @Test fun draft_editStartsFromTheRule() {
        val item = RuleItem.of(domain("*.a.com", type = DomainRulesManager.DomainType.WILDCARD, proxyId = "S5"))
        val d = RuleDraft(RuleKind.DOMAIN, app, item)
        assertTrue(d.isEdit)
        assertEquals("*.a.com", d.input)
        assertEquals(DomainRulesManager.DomainType.WILDCARD, d.domainType)
        assertEquals(RuleAction.TRUST, d.action)
        assertEquals("S5", d.proxyId)
    }

    @Test fun draft_withInputSwitchesDomainTypeAndClearsError() {
        val d = RuleDraft(RuleKind.DOMAIN, app, error = 1)
        assertEquals(DomainRulesManager.DomainType.WILDCARD, d.withInput("*.a.com").domainType)
        assertEquals(DomainRulesManager.DomainType.WILDCARD, d.withInput(".a.com").domainType)
        assertEquals(DomainRulesManager.DomainType.DOMAIN, d.withInput("a.com").domainType)
        assertNull(d.withInput("a").error)
        assertNull(RuleDraft(RuleKind.IP, app, error = 1).withInput("1").error)
    }

    // ---- screen state ----

    @Test fun state_editorDraftUpdatesAndPicker() {
        val s = RulesScreenState(RulesScope(app, false))
        assertNull(s.draft)
        s.updateDraft { it.copy(input = "ignored") } // no editor: no-op
        assertNull(s.dialog)
        s.openEditor(RuleKind.IP)
        assertEquals(app, s.draft!!.uid)
        s.updateDraft { it.copy(proxyId = "wg1") }
        assertEquals("wg1", s.draft!!.proxyId)
    }

    @Test fun state_selectionToggleRefreshAndRemove() {
        val s = RulesScreenState(RulesScope(app, false))
        val a = RuleItem.of(ip("1.1.1.1"))
        val tab = s.tab(RuleKind.IP)
        assertFalse(tab.selecting)
        tab.toggle(a)
        assertTrue(tab.selecting)
        s.details = a
        val updated = a.copy(proxyId = "wg1")
        s.refreshed(updated)
        assertEquals("wg1", s.details!!.proxyId)
        assertEquals("wg1", tab.selected[a.key]!!.proxyId)
        s.removed(RuleKind.IP, a.key)
        assertNull(s.details)
        assertFalse(tab.selecting)
        tab.toggle(a); tab.toggle(a)
        assertFalse(tab.selecting)
    }

    @Test fun tab_searchCallsFilterOnlyOnChange() {
        val tab = RulesTabState()
        val calls = mutableListOf<String>()
        tab.search("a", calls::add)
        tab.search("a", calls::add)
        tab.search("", calls::add)
        assertEquals(listOf("a", ""), calls)
        assertEquals("", tab.query)
    }
}
