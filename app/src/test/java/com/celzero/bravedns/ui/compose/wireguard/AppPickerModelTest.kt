/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.wireguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure logic of the per-proxy app picker (WgIncludeAppsActivity). */
class AppPickerModelTest {

    private fun row(
        selected: Boolean = false,
        routedVia: String? = null,
        bypass: Boolean = false,
        noInternet: Boolean = false,
    ) = AppPickerRow(10001, "com.a", "A", selected, routedVia, bypass, noInternet)

    @Test
    fun `plain row has no notes`() {
        assertEquals(emptyList<AppPickerNote>(), AppPickerLogic.notes(row()))
        assertEquals(emptyList<AppPickerNote>(), AppPickerLogic.notes(row(selected = true)))
    }

    @Test
    fun `routed via shows only when not selected here`() {
        assertEquals(listOf(AppPickerNote.RoutedVia("wg1")), AppPickerLogic.notes(row(routedVia = "wg1")))
        assertEquals(emptyList<AppPickerNote>(), AppPickerLogic.notes(row(selected = true, routedVia = "wg1")))
        assertEquals(emptyList<AppPickerNote>(), AppPickerLogic.notes(row(routedVia = " ")))
    }

    @Test
    fun `bypass wins over routed via and keeps no internet`() {
        val notes = AppPickerLogic.notes(row(routedVia = "wg1", bypass = true, noInternet = true))
        assertEquals(listOf(AppPickerNote.BypassesProxies, AppPickerNote.NoInternet), notes)
    }

    @Test
    fun `bypassing app is disabled and never checked`() {
        val r = row(selected = true, bypass = true)
        assertFalse(r.enabled)
        assertFalse(r.checked)
        assertTrue(row(selected = true).checked)
        assertTrue(row(noInternet = true).enabled)
    }

    @Test
    fun `bulk availability`() {
        assertEquals(AppPickerBulk(canSelectAll = true, canUnselectAll = false), AppPickerLogic.bulk(0, 5))
        assertEquals(AppPickerBulk(canSelectAll = false, canUnselectAll = true), AppPickerLogic.bulk(3, 0))
        assertEquals(AppPickerBulk(canSelectAll = true, canUnselectAll = true), AppPickerLogic.bulk(3, 2))
        assertEquals(AppPickerBulk.None, AppPickerLogic.bulk(0, 0))
    }

    @Test
    fun `empty state depends on query then filter`() {
        assertEquals(AppPickerEmpty.NO_MATCH, AppPickerLogic.empty("fire", AppPickerFilter.SELECTED))
        assertEquals(AppPickerEmpty.NONE_SELECTED, AppPickerLogic.empty("", AppPickerFilter.SELECTED))
        assertEquals(AppPickerEmpty.ALL_SELECTED, AppPickerLogic.empty(" ", AppPickerFilter.UNSELECTED))
        assertEquals(AppPickerEmpty.NO_APPS, AppPickerLogic.empty("", AppPickerFilter.ALL))
    }

    @Test
    fun `shared uid confirm needs more than one package`() {
        assertFalse(AppPickerLogic.needsSharedUidConfirm(listOf("A")))
        assertTrue(AppPickerLogic.needsSharedUidConfirm(listOf("A", "B")))
    }

    @Test
    fun `other proxies skips this proxy and joins the rest`() {
        val name: (String) -> String = { it.uppercase() }
        assertNull(AppPickerLogic.otherProxies(emptyList(), "wg1", name))
        assertNull(AppPickerLogic.otherProxies(listOf("wg1"), "wg1", name))
        assertEquals("WG2, S5", AppPickerLogic.otherProxies(listOf("wg1", "wg2", "S5"), "wg1", name))
    }

    @Test
    fun `row key is uid and package`() {
        assertEquals("10001:com.a", row().key)
    }
}
