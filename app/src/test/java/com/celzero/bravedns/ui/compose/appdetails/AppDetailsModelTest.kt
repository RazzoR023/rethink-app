/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.appdetails

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.celzero.bravedns.R
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.FirewallManager.ConnectionStatus
import com.celzero.bravedns.service.FirewallManager.FirewallStatus
import com.celzero.bravedns.service.ProxyChoices
import com.celzero.bravedns.service.ProxyChoices.AppSelection
import com.celzero.bravedns.service.ProxyChoices.Health
import com.celzero.bravedns.service.ProxyChoices.Kind
import com.celzero.bravedns.service.ProxyChoices.Pick
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.ui.compose.design.RethinkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JVM tests for the app details screen's state logic (AppDetailsModel.kt). */
class AppDetailsModelTest {

    private val wgOne = ProxyChoices.Choice("wg1", Kind.WIREGUARD, "wg-one", enabled = true, health = Health.UP)
    private val s5 = ProxyChoices.Choice("S5", Kind.SOCKS5, "", "10.0.2.2:1080", enabled = true, health = Health.WAITING)
    private val orbot = ProxyChoices.Choice("ORBOT", Kind.ORBOT, "", enabled = false, health = Health.OFF)
    private val choices = listOf(wgOne, s5, orbot)

    // ---- mode mapping ----

    @Test fun mode_mapsOneToOneOntoFirewallStatus() {
        assertEquals(FirewallStatus.entries.toSet(), AppNetworkMode.entries.map { it.status }.toSet())
        FirewallStatus.entries.forEach { assertEquals(it, AppNetworkMode.of(it).status) }
        assertEquals(AppNetworkMode.NORMAL, AppNetworkMode.of(FirewallStatus.NONE))
        assertEquals(AppNetworkMode.ISOLATE, AppNetworkMode.of(FirewallStatus.ISOLATE))
        assertEquals(AppNetworkMode.BYPASS_DNS_FIREWALL, AppNetworkMode.of(FirewallStatus.BYPASS_DNS_FIREWALL))
        assertEquals(AppNetworkMode.BYPASS_UNIVERSAL, AppNetworkMode.of(FirewallStatus.BYPASS_UNIVERSAL))
        assertEquals(AppNetworkMode.EXCLUDE, AppNetworkMode.of(FirewallStatus.EXCLUDE))
    }

    @Test fun modeChange_samesModeIsNoop_otherModesResetNetworkBlocks() {
        AppNetworkMode.entries.forEach { assertNull(AppDetailsLogic.modeChange(it.status, it)) }
        assertEquals(
            FirewallChange(FirewallStatus.ISOLATE, ConnectionStatus.ALLOW, ConnectionStatus.ALLOW),
            AppDetailsLogic.modeChange(FirewallStatus.NONE, AppNetworkMode.ISOLATE),
        )
        // back to Normal from any mode: allowed on all networks (the old toggle-off behaviour)
        listOf(FirewallStatus.ISOLATE, FirewallStatus.BYPASS_UNIVERSAL, FirewallStatus.BYPASS_DNS_FIREWALL, FirewallStatus.EXCLUDE).forEach {
            assertEquals(FirewallChange(FirewallStatus.NONE, ConnectionStatus.ALLOW), AppDetailsLogic.modeChange(it, AppNetworkMode.NORMAL))
        }
        assertEquals(
            FirewallChange(FirewallStatus.EXCLUDE, ConnectionStatus.ALLOW),
            AppDetailsLogic.modeChange(FirewallStatus.BYPASS_UNIVERSAL, AppNetworkMode.EXCLUDE),
        )
    }

    @Test fun wifiChange_flipsOnlyTheWifiBlock_andReturnsToNormal() {
        val expected = mapOf(
            ConnectionStatus.ALLOW to ConnectionStatus.UNMETERED,
            ConnectionStatus.UNMETERED to ConnectionStatus.ALLOW,
            ConnectionStatus.METERED to ConnectionStatus.BOTH,
            ConnectionStatus.BOTH to ConnectionStatus.METERED,
        )
        expected.forEach { (from, to) ->
            assertEquals(FirewallChange(FirewallStatus.NONE, to, from), AppDetailsLogic.wifiChange(from))
            assertEquals(!AppDetailsLogic.isWifiBlocked(from), AppDetailsLogic.isWifiBlocked(to))
            assertEquals(AppDetailsLogic.isMobileBlocked(from), AppDetailsLogic.isMobileBlocked(to))
        }
    }

    @Test fun mobileChange_flipsOnlyTheMobileBlock_andReturnsToNormal() {
        val expected = mapOf(
            ConnectionStatus.ALLOW to ConnectionStatus.METERED,
            ConnectionStatus.METERED to ConnectionStatus.ALLOW,
            ConnectionStatus.UNMETERED to ConnectionStatus.BOTH,
            ConnectionStatus.BOTH to ConnectionStatus.UNMETERED,
        )
        expected.forEach { (from, to) ->
            assertEquals(FirewallChange(FirewallStatus.NONE, to, from), AppDetailsLogic.mobileChange(from))
            assertEquals(!AppDetailsLogic.isMobileBlocked(from), AppDetailsLogic.isMobileBlocked(to))
            assertEquals(AppDetailsLogic.isWifiBlocked(from), AppDetailsLogic.isWifiBlocked(to))
        }
    }

    @Test fun sharedUidDialogLabel_matchesTheDirectionOfTheChange() {
        fun label(c: FirewallChange) = FirewallManager.getLabelForStatus(c.firewall, c.connection, c.previous)
        assertEquals(R.string.block, label(AppDetailsLogic.wifiChange(ConnectionStatus.ALLOW)))
        assertEquals(R.string.allow, label(AppDetailsLogic.wifiChange(ConnectionStatus.UNMETERED)))
        assertEquals(R.string.block, label(AppDetailsLogic.mobileChange(ConnectionStatus.ALLOW)))
        assertEquals(R.string.isolate, label(AppDetailsLogic.modeChange(FirewallStatus.NONE, AppNetworkMode.ISOLATE)!!))
        assertEquals(R.string.exclude, label(AppDetailsLogic.modeChange(FirewallStatus.NONE, AppNetworkMode.EXCLUDE)!!))
    }

    @Test fun excludeBlocker_unknownPackageWinsOverLockdown() {
        assertEquals(ExcludeBlocker.NONE, AppDetailsLogic.excludeBlocker(unknownPackage = false, vpnLockdown = false))
        assertEquals(ExcludeBlocker.VPN_LOCKDOWN, AppDetailsLogic.excludeBlocker(unknownPackage = false, vpnLockdown = true))
        assertEquals(ExcludeBlocker.UNKNOWN_PACKAGE, AppDetailsLogic.excludeBlocker(unknownPackage = true, vpnLockdown = true))
    }

    // ---- summary line ----

    @Test fun access_normalMode_followsNetworkBlocks() {
        assertEquals(AppAccess.ALLOWED, AppDetailsLogic.access(FirewallStatus.NONE, ConnectionStatus.ALLOW, false))
        assertEquals(AppAccess.BLOCKED, AppDetailsLogic.access(FirewallStatus.NONE, ConnectionStatus.BOTH, false))
        assertEquals(AppAccess.BLOCKED_WIFI, AppDetailsLogic.access(FirewallStatus.NONE, ConnectionStatus.UNMETERED, false))
        assertEquals(AppAccess.BLOCKED_MOBILE, AppDetailsLogic.access(FirewallStatus.NONE, ConnectionStatus.METERED, false))
    }

    @Test fun access_modes_andIsolateStillHonoursNetworkBlocks() {
        assertEquals(AppAccess.ISOLATED, AppDetailsLogic.access(FirewallStatus.ISOLATE, ConnectionStatus.ALLOW, false))
        assertEquals(AppAccess.BLOCKED_WIFI, AppDetailsLogic.access(FirewallStatus.ISOLATE, ConnectionStatus.UNMETERED, false))
        assertEquals(AppAccess.BYPASS_UNIVERSAL, AppDetailsLogic.access(FirewallStatus.BYPASS_UNIVERSAL, ConnectionStatus.ALLOW, false))
        assertEquals(AppAccess.BYPASS_DNS_FIREWALL, AppDetailsLogic.access(FirewallStatus.BYPASS_DNS_FIREWALL, ConnectionStatus.ALLOW, false))
        assertEquals(AppAccess.EXCLUDED, AppDetailsLogic.access(FirewallStatus.EXCLUDE, ConnectionStatus.ALLOW, false))
    }

    @Test fun access_tempAllowOverridesBlocks_butNotExclude() {
        ConnectionStatus.entries.forEach {
            assertEquals(AppAccess.TEMP_ALLOWED, AppDetailsLogic.access(FirewallStatus.NONE, it, true))
            assertEquals(AppAccess.TEMP_ALLOWED, AppDetailsLogic.access(FirewallStatus.ISOLATE, it, true))
        }
        assertEquals(AppAccess.EXCLUDED, AppDetailsLogic.access(FirewallStatus.EXCLUDE, ConnectionStatus.ALLOW, true))
    }

    @Test fun status_colourOfEachAccess() {
        assertEquals(RethinkStatus.Active, AppDetailsLogic.status(AppAccess.ALLOWED))
        assertEquals(RethinkStatus.Active, AppDetailsLogic.status(AppAccess.TEMP_ALLOWED))
        assertEquals(RethinkStatus.Active, AppDetailsLogic.status(AppAccess.BYPASS_UNIVERSAL))
        assertEquals(RethinkStatus.Active, AppDetailsLogic.status(AppAccess.BYPASS_DNS_FIREWALL))
        assertEquals(RethinkStatus.Waiting, AppDetailsLogic.status(AppAccess.BLOCKED_WIFI))
        assertEquals(RethinkStatus.Waiting, AppDetailsLogic.status(AppAccess.BLOCKED_MOBILE))
        assertEquals(RethinkStatus.Waiting, AppDetailsLogic.status(AppAccess.ISOLATED))
        assertEquals(RethinkStatus.Error, AppDetailsLogic.status(AppAccess.BLOCKED))
        assertEquals(RethinkStatus.Off, AppDetailsLogic.status(AppAccess.EXCLUDED))
    }

    @Test fun summary_proxyPart_onlyWhenAppSpecific() {
        fun proxyOf(row: ProxyRowState, firewall: FirewallStatus = FirewallStatus.NONE) =
            AppDetailsLogic.summary(firewall, ConnectionStatus.ALLOW, false, row).proxy
        assertNull(proxyOf(ProxyRowState.Loading))
        assertNull(proxyOf(ProxyRowState.Default))
        assertEquals(SummaryProxy.Direct, proxyOf(ProxyRowState.NoProxy))
        assertEquals(SummaryProxy.Via(wgOne), proxyOf(ProxyRowState.Via(wgOne)))
        assertEquals(SummaryProxy.Multiple(2), proxyOf(ProxyRowState.Multiple(listOf(wgOne, s5))))
        // excluded apps never reach a proxy
        assertNull(proxyOf(ProxyRowState.Via(wgOne), FirewallStatus.EXCLUDE))
    }

    @Test fun summary_combinesAccessStatusAndProxy() {
        val ui = AppDetailsUi(
            firewall = FirewallStatus.NONE, connection = ConnectionStatus.UNMETERED,
            proxy = ProxyRowState.Via(s5),
        )
        assertEquals(AppSummary(RethinkStatus.Waiting, AppAccess.BLOCKED_WIFI, SummaryProxy.Via(s5)), ui.summary)
        assertTrue(ui.wifiBlocked)
        assertEquals(false, ui.mobileBlocked)
        assertEquals(AppNetworkMode.NORMAL, ui.mode)
    }

    // ---- proxy row ----

    @Test fun proxyRow_eachSelection() {
        assertEquals(ProxyRowState.Default, AppDetailsLogic.proxyRow(AppSelection(Pick.FollowGlobal), choices))
        assertEquals(ProxyRowState.NoProxy, AppDetailsLogic.proxyRow(AppSelection(Pick.Bypass), choices))
        assertEquals(ProxyRowState.Via(s5), AppDetailsLogic.proxyRow(AppSelection(Pick.Via("S5")), choices))
        assertEquals(
            ProxyRowState.Multiple(listOf(wgOne, s5)),
            AppDetailsLogic.proxyRow(AppSelection(null, listOf("wg1", "S5")), choices),
        )
    }

    @Test fun proxyRow_unknownProxy_isShownNotHidden() {
        val row = AppDetailsLogic.proxyRow(AppSelection(Pick.Via("wg9")), choices) as ProxyRowState.Via
        assertEquals("wg9", row.choice.proxyId)
        assertEquals(Kind.OTHER, row.choice.kind)
        assertEquals(RethinkStatus.Off, AppDetailsLogic.proxyStatus(row))
        val multi = AppDetailsLogic.proxyRow(AppSelection(null, listOf("wg1", "wg9")), choices) as ProxyRowState.Multiple
        assertEquals(listOf("wg1", "wg9"), multi.choices.map { it.proxyId })
    }

    @Test fun proxyRow_followsPickerSemantics_excludedWins() {
        // the row is built from the same selection the picker uses (ProxyChoices.currentForApp)
        fun row(appProxy: ProxyManager.AppProxy, excluded: Boolean) =
            AppDetailsLogic.proxyRow(ProxyChoices.currentForApp(appProxy, excluded), choices)
        assertEquals(ProxyRowState.NoProxy, row(ProxyManager.AppProxy.Single("S5"), excluded = true))
        assertEquals(ProxyRowState.NoProxy, row(ProxyManager.AppProxy.None, excluded = true))
        assertEquals(ProxyRowState.Via(s5), row(ProxyManager.AppProxy.Single("S5"), excluded = false))
        assertEquals(ProxyRowState.Default, row(ProxyManager.AppProxy.None, excluded = false))
        assertEquals(ProxyRowState.Multiple(listOf(wgOne, s5)), row(ProxyManager.AppProxy.Multiple(listOf("wg1", "S5")), false))
    }

    @Test fun proxyStatus_onlyForASingleProxy() {
        assertEquals(RethinkStatus.Active, AppDetailsLogic.proxyStatus(ProxyRowState.Via(wgOne)))
        assertEquals(RethinkStatus.Waiting, AppDetailsLogic.proxyStatus(ProxyRowState.Via(s5)))
        assertEquals(RethinkStatus.Off, AppDetailsLogic.proxyStatus(ProxyRowState.Via(orbot)))
        assertNull(AppDetailsLogic.proxyStatus(ProxyRowState.Default))
        assertNull(AppDetailsLogic.proxyStatus(ProxyRowState.NoProxy))
        assertNull(AppDetailsLogic.proxyStatus(ProxyRowState.Multiple(listOf(wgOne))))
    }

    // ---- notes ----

    @Test fun notesFilter_capsInsertedText_keepsSurroundingText() {
        val old = TextFieldValue("a".repeat(495) + "zz", TextRange(495))
        val pasted = TextFieldValue("a".repeat(495) + "0123456789" + "zz", TextRange(505))
        val result = filterAppNotes(old, pasted)
        assertEquals(APP_NOTES_MAX_LENGTH, result.text.length)
        assertEquals("a".repeat(495) + "012" + "zz", result.text)
        assertEquals(TextRange(498), result.selection)
        val short = TextFieldValue("hello")
        assertEquals(short, filterAppNotes(TextFieldValue(""), short))
    }

    @Test fun notesFilter_neverSplitsASurrogatePair() {
        val old = TextFieldValue("a".repeat(499))
        val next = TextFieldValue("a".repeat(499) + "😀")
        assertEquals("a".repeat(499), filterAppNotes(old, next).text)
    }
}
