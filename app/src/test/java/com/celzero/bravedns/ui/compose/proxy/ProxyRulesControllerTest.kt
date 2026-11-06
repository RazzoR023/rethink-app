/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.proxy

import com.celzero.bravedns.R
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.IpRulesManager.IpRuleStatus
import com.celzero.bravedns.shadows.ShadowBackend
import com.celzero.bravedns.ui.compose.rules.CustomRuleActions
import com.celzero.bravedns.ui.compose.rules.FakePort
import com.celzero.bravedns.ui.compose.rules.RuleAction
import com.celzero.bravedns.ui.compose.rules.RuleItem
import com.celzero.bravedns.ui.compose.rules.RuleKind
import com.celzero.bravedns.ui.compose.rules.RulesDialog
import com.celzero.bravedns.ui.compose.rules.RulesScope
import com.celzero.bravedns.ui.compose.rules.RulesScreenState
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The rules section of a proxy page: lists what the source emits, "Add" opens the shared
 * editor routed via the proxy (no action, All apps unless an app is picked), saving stores
 * the route, and "stop routing" clears only the proxy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [ShadowBackend::class])
class ProxyRulesControllerTest {

    private val app = 10042
    private val proxyId = "S5"
    private val port = FakePort()
    private val notices = mutableListOf<Int>()

    /** Rules from the fake port's maps, like the DAO flows (re-emitted on [bump]). */
    private inner class FakeSource : ProxyRulesSource {
        val tick = MutableStateFlow(0)
        var appsAsked = 0
        fun bump() { tick.value++ }
        override fun rules(proxyId: String): Flow<List<RuleItem>> = tick.map {
            ProxyRulesLogic.merge(proxyId, port.ips.values.map { it.deepCopy() }, port.domains.values.toList())
        }
        override suspend fun apps(): List<Pair<Int, String>> { appsAsked++; return listOf(app to "Chrome", 10050 to "Firefox") }
    }

    private val source = FakeSource()

    private fun controller(): ProxyRulesController {
        val state = RulesScreenState(RulesScope(UID_EVERYBODY, allApps = false), scopePickable = true)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val actions = CustomRuleActions(
            state, scope, port,
            notify = { id, _ -> notices += id }, log = { _, _ -> },
            ioDispatcher = Dispatchers.Unconfined, mainDispatcher = Dispatchers.Unconfined,
        )
        return ProxyRulesController(
            proxyId, state, actions, source, scope, "All apps",
            notify = { id, _ -> notices += id },
            ioDispatcher = Dispatchers.Unconfined, mainDispatcher = Dispatchers.Unconfined,
        )
    }

    private fun putIp(uid: Int, ip: String, status: IpRuleStatus, proxy: String) {
        port.ips[Triple(uid, ip, 0)] = CustomIp().apply { this.uid = uid; ipAddress = ip; port = 0; this.status = status.id; proxyId = proxy }
    }

    @Test fun start_listsOnlyThisProxysRules_andFollowsChanges() {
        putIp(app, "1.1.1.1", IpRuleStatus.BLOCK, "S5")
        putIp(UID_EVERYBODY, "8.8.8.8", IpRuleStatus.NONE, "wg1")
        port.domains[UID_EVERYBODY to "a.example"] = CustomDomain("a.example", UID_EVERYBODY, "", 0, 0, "S5", "", 1L, 0L, 1L)
        val c = controller()
        assertNull(c.rules)
        c.start()
        assertEquals(setOf("1.1.1.1", "a.example"), c.rules!!.map { it.value }.toSet())

        putIp(app, "9.9.9.9", IpRuleStatus.NONE, "S5")
        source.bump()
        assertEquals(3, c.rules!!.size)
    }

    @Test fun add_opensEditorRoutedViaProxy_forAllApps() {
        val c = controller()
        c.add(RuleKind.DOMAIN)
        val draft = (c.state.dialog as RulesDialog.Editor).draft
        assertEquals(proxyId, draft.proxyId)
        assertEquals(RuleAction.NONE, draft.action)
        assertEquals(UID_EVERYBODY, draft.uid)
        assertEquals(RuleKind.DOMAIN, draft.kind)
    }

    @Test fun pickScope_setsTheAppAndClosesThePicker() = runBlocking {
        val c = controller()
        c.add(RuleKind.IP)
        c.openScopePicker().join()
        assertTrue(c.state.scopePicker)
        assertEquals(listOf(UID_EVERYBODY, app, 10050), c.appChoices!!.map { it.uid })
        c.pickScope(app)
        assertFalse(c.state.scopePicker)
        assertEquals(app, c.state.draft!!.uid)
        // apps are loaded once
        c.openScopePicker().join()
        assertEquals(1, source.appsAsked)
    }

    @Test fun save_storesRouteOnlyRuleForThePickedApp() = runBlocking {
        val c = controller()
        c.add(RuleKind.IP)
        c.pickScope(app)
        c.state.updateDraft { it.withInput("10.0.0.1") }
        c.actions.saveDraft()?.join()
        val stored = port.ips[Triple(app, "10.0.0.1", 0)]
        assertNotNull(stored)
        assertEquals(proxyId, stored!!.proxyId)
        assertEquals(IpRuleStatus.NONE.id, stored.status)
        assertNull(c.state.dialog)
    }

    @Test fun save_domainForAllApps() = runBlocking {
        val c = controller()
        c.add(RuleKind.DOMAIN)
        c.state.updateDraft { it.withInput("example.org") }
        c.actions.saveDraft()?.join()
        val stored = port.domains[UID_EVERYBODY to "example.org"]!!
        assertEquals(proxyId, stored.proxyId)
        assertEquals(DomainRulesManager.Status.NONE.id, stored.status)
    }

    @Test fun stopRouting_clearsProxy_keepsAction_andNotifies() = runBlocking {
        putIp(app, "1.1.1.1", IpRuleStatus.BLOCK, "S5")
        val c = controller()
        c.start()
        val item = c.rules!!.single()
        c.stopRouting(item)?.join()
        val stored = port.ips[Triple(app, "1.1.1.1", 0)]!!
        assertEquals("", stored.proxyId)
        assertEquals(IpRuleStatus.BLOCK.id, stored.status)
        assertTrue(R.string.proxy_page_rule_unrouted in notices)
        source.bump()
        assertTrue(c.rules!!.isEmpty())
    }

    @Test fun stopRouting_alreadyUnrouted_isANoOp() {
        putIp(app, "1.1.1.1", IpRuleStatus.BLOCK, "")
        val c = controller()
        val item = RuleItem.of(port.ips.values.single())
        assertNull(c.stopRouting(item))
        assertTrue(notices.isEmpty())
    }

    @Test fun open_showsDetails() {
        putIp(app, "1.1.1.1", IpRuleStatus.NONE, "S5")
        val c = controller()
        val item = RuleItem.of(port.ips.values.single())
        c.open(item)
        assertEquals(item, c.state.details)
    }
}
