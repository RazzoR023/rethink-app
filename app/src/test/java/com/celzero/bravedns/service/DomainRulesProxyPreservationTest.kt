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

import com.celzero.bravedns.service.DomainRulesManager.DomainType
import com.celzero.bravedns.service.DomainRulesManager.Status
import com.celzero.bravedns.shadows.ShadowBackend
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DomainRulesManager against an in-memory db and tries.
 *
 * Regression: changeStatus, block(domain...), addDomainRule and updateDomainRule rebuilt the
 * rule with an empty proxyId / proxyCC, so any status change wiped the rule's proxy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [ShadowBackend::class])
class DomainRulesProxyPreservationTest {

    private val uid = 10042
    private val none = Pair("", "")
    private lateinit var store: FakeDomainStore

    @Before
    fun setUp() {
        store = FakeDomainStore()
        DomainRulesManager.overrideForTest(store.repo, FakeRadixTree(), FakeRadixTree())
    }

    @After
    fun tearDown() {
        DomainRulesManager.overrideForTest(null, null, null)
    }

    private fun stored(d: String, u: Int = uid) = store.get(u, d)

    private suspend fun addWithProxy(d: String, status: Status, proxyId: String, u: Int = uid) {
        DomainRulesManager.addDomainRule(d, status, DomainType.DOMAIN, u, proxyId)
    }

    @Test
    fun `changeStatus keeps the proxy`() = runBlocking {
        addWithProxy("example.com", Status.TRUST, "S5")
        DomainRulesManager.changeStatus("example.com", uid, "", DomainType.DOMAIN, Status.BLOCK)
        assertEquals("S5", stored("example.com")!!.proxyId)
        assertEquals(Status.BLOCK.id, stored("example.com")!!.status)
        assertEquals(Status.BLOCK, DomainRulesManager.getDomainRule("example.com", uid))
        assertEquals(Pair("S5", ""), DomainRulesManager.getProxyForDomain(uid, "example.com"))
    }

    @Test
    fun `block by name keeps the proxy`() = runBlocking {
        addWithProxy("example.com", Status.NONE, "wg1")
        DomainRulesManager.block("example.com", uid, type = DomainType.DOMAIN)
        assertEquals("wg1", stored("example.com")!!.proxyId)
        assertEquals(Status.BLOCK.id, stored("example.com")!!.status)
    }

    @Test
    fun `addDomainRule keeps the proxy unless one is given`() = runBlocking {
        addWithProxy("example.com", Status.BLOCK, "wg1")
        DomainRulesManager.addDomainRule("example.com", Status.TRUST, DomainType.DOMAIN, uid)
        assertEquals("wg1", stored("example.com")!!.proxyId)
        assertTrue(DomainRulesManager.isDomainTrusted("example.com"))
        addWithProxy("example.com", Status.TRUST, "")
        assertEquals("", stored("example.com")!!.proxyId)
        assertEquals(none, DomainRulesManager.getProxyForDomain(uid, "example.com"))
    }

    @Test
    fun `trust, block and noRule from a stale copy keep the stored proxy`() = runBlocking {
        DomainRulesManager.addDomainRule("example.com", Status.BLOCK, DomainType.DOMAIN, uid)
        val stale = stored("example.com")!!
        DomainRulesManager.setProxy(stored("example.com")!!, "S5")
        DomainRulesManager.trust(stale)
        assertEquals("S5", stored("example.com")!!.proxyId)
        DomainRulesManager.block(stale)
        DomainRulesManager.noRule(stale)
        assertEquals("S5", stored("example.com")!!.proxyId)
        assertEquals(Status.NONE.id, stored("example.com")!!.status)
        assertEquals(Pair("S5", ""), DomainRulesManager.getProxyForDomain(uid, "example.com"))
    }

    @Test
    fun `updateDomainRule keeps the proxy by default and can set or clear it`() = runBlocking {
        addWithProxy("a.com", Status.BLOCK, "wg1")
        DomainRulesManager.updateDomainRule("b.com", Status.TRUST, DomainType.DOMAIN, stored("a.com")!!)
        assertNull(stored("a.com"))
        assertEquals("wg1", stored("b.com")!!.proxyId)
        assertEquals(none, DomainRulesManager.getProxyForDomain(uid, "a.com"))
        assertEquals(Pair("wg1", ""), DomainRulesManager.getProxyForDomain(uid, "b.com"))
        // the edited rule is trusted (was always recorded as blocked in the trusted map)
        assertTrue(DomainRulesManager.isDomainTrusted("b.com"))

        DomainRulesManager.updateDomainRule("b.com", Status.TRUST, DomainType.DOMAIN, stored("b.com")!!, "S5")
        assertEquals("S5", stored("b.com")!!.proxyId)
        DomainRulesManager.updateDomainRule("b.com", Status.BLOCK, DomainType.DOMAIN, stored("b.com")!!, "")
        assertEquals("", stored("b.com")!!.proxyId)
        assertFalse(DomainRulesManager.isDomainTrusted("b.com"))
    }

    @Test
    fun `setProxy keeps the status, clearProxy removes the proxy`() = runBlocking {
        DomainRulesManager.addDomainRule("*.example.com", Status.BLOCK, DomainType.WILDCARD, uid)
        val stale = stored("*.example.com")!!.also { it.status = Status.TRUST.id }
        DomainRulesManager.setProxy(stale, "wg2")
        assertEquals(Status.BLOCK.id, stored("*.example.com")!!.status)
        assertEquals("wg2", stored("*.example.com")!!.proxyId)
        assertEquals(Pair("wg2", ""), DomainRulesManager.getProxyForDomain(uid, "www.example.com"))

        DomainRulesManager.clearProxy(stale)
        assertEquals("", stored("*.example.com")!!.proxyId)
        assertEquals(Status.BLOCK.id, stored("*.example.com")!!.status)
        assertEquals(none, DomainRulesManager.getProxyForDomain(uid, "www.example.com"))
    }

    @Test
    fun `setProxy on a domain without a rule creates a route-only rule`() = runBlocking {
        val cd = DomainRulesManager.makeCustomDomain(uid, "new.com")
        DomainRulesManager.setProxy(cd, "S5")
        assertEquals(Status.NONE.id, stored("new.com")!!.status)
        assertEquals("S5", stored("new.com")!!.proxyId)
        assertEquals(Pair("S5", ""), DomainRulesManager.getProxyForDomain(uid, "new.com"))
        assertEquals(Status.NONE, DomainRulesManager.status("new.com", uid))
    }

    @Test
    fun `setCC keeps the proxyId and status`() = runBlocking {
        addWithProxy("example.com", Status.BLOCK, "wg1")
        DomainRulesManager.setCC(stored("example.com")!!.also { it.proxyId = ""; it.status = 0 }, "US")
        val s = stored("example.com")!!
        assertEquals("wg1", s.proxyId)
        assertEquals("US", s.proxyCC)
        assertEquals(Status.BLOCK.id, s.status)
        assertEquals(Pair("wg1", "US"), DomainRulesManager.getProxyForDomain(uid, "example.com"))
    }

    @Test
    fun `deleteAllAppsRules keeps universal rules`() = runBlocking {
        addWithProxy("u.com", Status.BLOCK, "S5", UID_EVERYBODY)
        addWithProxy("a.com", Status.TRUST, "wg1")
        DomainRulesManager.addDomainRule("b.com", Status.BLOCK, DomainType.DOMAIN, 10043)
        DomainRulesManager.deleteAllAppsRules()
        assertEquals(listOf("u.com"), store.rows.values.map { it.domain })
        assertEquals(Status.BLOCK, DomainRulesManager.getDomainRule("u.com", UID_EVERYBODY))
        assertEquals(Pair("S5", ""), DomainRulesManager.getProxyForDomain(UID_EVERYBODY, "u.com"))
        assertEquals(Status.NONE, DomainRulesManager.getDomainRule("a.com", uid))
        assertFalse(DomainRulesManager.isDomainTrusted("a.com"))
    }
}
