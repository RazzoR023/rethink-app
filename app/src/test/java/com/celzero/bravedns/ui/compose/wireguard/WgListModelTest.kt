/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.wireguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WgListModelTest {

    @Test
    fun `opens on Advanced only while Advanced configs run`() {
        assertEquals(WgListMode.SIMPLE, WgListLogic.initialMode(anyActive = false, oneWgActive = false))
        assertEquals(WgListMode.SIMPLE, WgListLogic.initialMode(anyActive = true, oneWgActive = true))
        assertEquals(WgListMode.ADVANCED, WgListLogic.initialMode(anyActive = true, oneWgActive = false))
    }

    @Test
    fun `switching mode stops running configs of the other mode`() {
        assertTrue(WgListLogic.needsStopAll(WgListMode.ADVANCED, anyActive = true, oneWgActive = true))
        assertFalse(WgListLogic.needsStopAll(WgListMode.ADVANCED, anyActive = false, oneWgActive = false))
        assertTrue(WgListLogic.needsStopAll(WgListMode.SIMPLE, anyActive = true, oneWgActive = false))
        assertFalse(WgListLogic.needsStopAll(WgListMode.SIMPLE, anyActive = false, oneWgActive = false))
        assertFalse(WgListLogic.needsStopAll(WgListMode.SIMPLE, anyActive = true, oneWgActive = true))
    }

    @Test
    fun `dns target`() {
        assertEquals("wg1", WgListLogic.dnsTarget(true, listOf("wg1"), "RDNS", splitDns = true))
        assertNull(WgListLogic.dnsTarget(true, emptyList(), "RDNS", splitDns = true))
        assertEquals("RDNS", WgListLogic.dnsTarget(false, listOf("a", "b"), "RDNS", splitDns = false))
        assertEquals("RDNS, a,b", WgListLogic.dnsTarget(false, listOf("a", "b"), "RDNS", splitDns = true))
        assertEquals("RDNS", WgListLogic.dnsTarget(false, emptyList(), "RDNS", splitDns = true))
    }

    @Test
    fun `apps line`() {
        assertEquals(WgListApps.Hidden, WgListLogic.apps(WgListMode.ADVANCED, on = false, catchAll = true, count = 3))
        assertEquals(WgListApps.All, WgListLogic.apps(WgListMode.SIMPLE, on = true, catchAll = false, count = 0))
        assertEquals(WgListApps.Remaining, WgListLogic.apps(WgListMode.ADVANCED, on = true, catchAll = true, count = 0))
        assertEquals(WgListApps.Picked(0), WgListLogic.apps(WgListMode.ADVANCED, on = true, catchAll = false, count = 0))
    }

    @Test
    fun `rows keep their place, new configs go last, removed ones drop`() {
        assertEquals(listOf(3, 1, 2), WgListLogic.stableOrder(emptyList(), listOf(3, 1, 2)))
        assertEquals(listOf(1, 2, 3), WgListLogic.stableOrder(listOf(1, 2, 3), listOf(3, 1, 2)))
        assertEquals(listOf(1, 3, 4), WgListLogic.stableOrder(listOf(1, 2, 3), listOf(4, 3, 1)))
    }

    @Test
    fun `storage order is active first then name ignoring case`() {
        data class C(val name: String, val active: Boolean)
        val sorted = WgListLogic.storageOrder(
            listOf(C("b", false), C("A", false), C("z", true), C("c", true)), { it.active }, { it.name },
        )
        assertEquals(listOf("c", "z", "A", "b"), sorted.map { it.name })
    }
}
