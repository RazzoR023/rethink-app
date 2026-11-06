/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.rules

import com.celzero.bravedns.shadows.ShadowBackend
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** RuleSheetLogic: rows of each sheet, offered actions, locking, and status vs proxy changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [ShadowBackend::class])
class RuleSheetModelTest {

    private val app = 10042

    private fun ip(action: RuleAction = RuleAction.NONE, proxy: String = "", uid: Int = app, lock: Boolean = false) =
        RuleSheetRow(RuleKind.IP, uid, "1.2.3.4", action, proxy, loaded = true, lockUnlisted = lock)

    private fun domain(action: RuleAction = RuleAction.NONE, proxy: String = "", uid: Int = app) =
        RuleSheetRow(RuleKind.DOMAIN, uid, "example.com", action, proxy, loaded = true)

    @Test
    fun `ip sheet lists the ip then its domains, trimmed and without repeats`() {
        val rows = RuleSheetLogic.ipSheetRows(app, "1.2.3.4", " a.com, b.com,,a.com ")
        assertEquals(listOf(RuleKind.IP, RuleKind.DOMAIN, RuleKind.DOMAIN), rows.map { it.kind })
        assertEquals(listOf("1.2.3.4", "a.com", "b.com"), rows.map { it.value })
        assertTrue(rows.all { it.uid == app && !it.loaded })
        assertEquals(1, RuleSheetLogic.ipSheetRows(app, "1.2.3.4", null).size)
        assertEquals(1, RuleSheetLogic.ipSheetRows(app, "1.2.3.4", "").size)
    }

    @Test
    fun `domain sheet counts wildcard rules, the others do not`() {
        assertTrue(RuleSheetLogic.domainSheetRows(app, "a.com").single().matchWildcards)
        assertFalse(RuleSheetLogic.ipSheetRows(app, "1.2.3.4", "a.com")[1].matchWildcards)
        assertFalse(RuleSheetLogic.connectionRows(app, "1.2.3.4", "a.com")[1].matchWildcards)
    }

    @Test
    fun `connection sheet has the ip, plus the domain when there was a query`() {
        assertEquals(listOf(RuleKind.IP, RuleKind.DOMAIN), RuleSheetLogic.connectionRows(app, "1.2.3.4", "a.com").map { it.kind })
        assertEquals(listOf(RuleKind.IP), RuleSheetLogic.connectionRows(app, "1.2.3.4", null).map { it.kind })
        assertEquals(listOf(RuleKind.IP), RuleSheetLogic.connectionRows(app, "1.2.3.4", " ").map { it.kind })
        assertTrue(RuleSheetLogic.connectionRows(app, "1.2.3.4", null).single().lockUnlisted)
    }

    @Test
    fun `app rows offer trust, universal ip rows offer bypass universal`() {
        assertEquals(listOf(RuleAction.TRUST, RuleAction.BLOCK, RuleAction.NONE), RuleSheetLogic.options(ip()))
        assertEquals(listOf(RuleAction.TRUST, RuleAction.BLOCK, RuleAction.NONE), RuleSheetLogic.options(domain()))
        assertEquals(listOf(RuleAction.BYPASS_UNIVERSAL, RuleAction.BLOCK, RuleAction.NONE), RuleSheetLogic.options(ip(uid = UID_EVERYBODY)))
        assertEquals(listOf(RuleAction.TRUST, RuleAction.BLOCK, RuleAction.NONE), RuleSheetLogic.options(domain(uid = UID_EVERYBODY)))
        assertTrue(ip(uid = UID_EVERYBODY).isUniversal)
    }

    @Test
    fun `bypass universal on an app ip is read-only on the connection sheet only`() {
        val conn = ip(RuleAction.BYPASS_UNIVERSAL, lock = true)
        assertTrue(RuleSheetLogic.locked(conn))
        assertEquals(listOf(RuleAction.BYPASS_UNIVERSAL), RuleSheetLogic.options(conn))
        assertNull(RuleSheetLogic.withAction(conn, RuleAction.BLOCK))
        // the proxy can still be set
        assertEquals("S5", RuleSheetLogic.withProxy(conn, "S5")!!.proxyId)

        val app = ip(RuleAction.BYPASS_UNIVERSAL)
        assertFalse(RuleSheetLogic.locked(app))
        assertEquals(RuleAction.BLOCK, RuleSheetLogic.withAction(app, RuleAction.BLOCK)!!.action)
        // a listed action is never locked
        assertFalse(RuleSheetLogic.locked(ip(RuleAction.TRUST, lock = true)))
    }

    @Test
    fun `changing the action keeps the proxy`() {
        val next = RuleSheetLogic.withAction(ip(RuleAction.TRUST, "wg1"), RuleAction.BLOCK)!!
        assertEquals(RuleAction.BLOCK, next.action)
        assertEquals("wg1", next.proxyId)
        assertEquals("S5", RuleSheetLogic.withAction(domain(RuleAction.BLOCK, "S5"), RuleAction.NONE)!!.proxyId)
    }

    @Test
    fun `changing the proxy keeps the action`() {
        val next = RuleSheetLogic.withProxy(domain(RuleAction.BLOCK), "wg2")!!
        assertEquals(RuleAction.BLOCK, next.action)
        assertEquals("wg2", next.proxyId)
        val cleared = RuleSheetLogic.withProxy(next, "")!!
        assertEquals("", cleared.proxyId)
        assertEquals(RuleAction.BLOCK, cleared.action)
    }

    @Test
    fun `no-op changes and rows still loading are ignored`() {
        assertNull(RuleSheetLogic.withAction(ip(RuleAction.BLOCK), RuleAction.BLOCK))
        assertNull(RuleSheetLogic.withProxy(ip(proxy = "S5"), "S5"))
        val loading = ip().copy(loaded = false)
        assertNull(RuleSheetLogic.withAction(loading, RuleAction.BLOCK))
        assertNull(RuleSheetLogic.withProxy(loading, "S5"))
        // bypass universal isn't offered for app rows
        assertNull(RuleSheetLogic.withAction(ip(), RuleAction.BYPASS_UNIVERSAL))
    }

    @Test
    fun `blocked rows with a proxy get the proxy-ignored note`() {
        assertTrue(RuleSheetLogic.proxyIgnored(ip(RuleAction.BLOCK, "S5")))
        assertFalse(RuleSheetLogic.proxyIgnored(ip(RuleAction.BLOCK)))
        assertFalse(RuleSheetLogic.proxyIgnored(ip(RuleAction.TRUST, "S5")))
        assertFalse(RuleSheetLogic.proxyIgnored(domain(RuleAction.NONE, "S5")))
    }

    @Test
    fun `row keys are unique per kind, uid and value`() {
        val keys = setOf(ip().key, domain().key, ip(uid = UID_EVERYBODY).key, ip().copy(value = "5.6.7.8").key)
        assertEquals(4, keys.size)
    }
}
