package com.celzero.bravedns.ui.compose.wireguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayLogicTest {
    private val neth = RelayOption(2, "Neth", active = true, ip4 = true, ip6 = false)
    private val home = RelayOption(3, "Home", active = true, ip4 = true, ip6 = true, restricted = true)
    private val ui = RelayPickerUi("sing", listOf(neth, home))

    @Test fun directIsSelectedByDefault() {
        assertEquals(RelayLogic.DIRECT, ui.selected)
        assertNull(RelayLogic.selectedName(ui))
    }

    @Test fun canPickAnUnrestrictedRelay() = assertTrue(RelayLogic.canPick(ui, 2))

    @Test fun cannotPickTheCurrentSelection() = assertFalse(RelayLogic.canPick(ui, RelayLogic.DIRECT))

    @Test fun cannotPickARestrictedRelay() = assertFalse(RelayLogic.canPick(ui, 3))

    @Test fun cannotPickAnUnknownId() = assertFalse(RelayLogic.canPick(ui, 9))

    @Test fun noSecondChangeWhileOneIsApplying() {
        val busy = RelayLogic.startApplying(ui, 2)
        assertEquals(2, busy.applying)
        assertFalse(RelayLogic.canPick(busy, RelayLogic.DIRECT))
    }

    @Test fun startingClearsAnOldError() {
        val failed = ui.copy(error = "boom")
        assertNull(RelayLogic.startApplying(failed, 2).error)
    }

    @Test fun successSelectsTheNewRelay() {
        val done = RelayLogic.finish(RelayLogic.startApplying(ui, 2), current = 2, error = null)
        assertEquals(2, done.selected)
        assertNull(done.applying)
        assertEquals("Neth", RelayLogic.selectedName(done))
    }

    @Test fun failureFollowsWhatIsActuallySet() {
        // a switch that removed the old relay but failed to add the new one ends on Direct
        val relayed = ui.copy(selected = 2)
        val done = RelayLogic.finish(RelayLogic.startApplying(relayed, 3), current = RelayLogic.DIRECT, error = "can't route")
        assertEquals(RelayLogic.DIRECT, done.selected)
        assertEquals("can't route", done.error)
        assertNull(done.applying)
    }

    @Test fun removingTheRelayCanBePicked() = assertTrue(RelayLogic.canPick(ui.copy(selected = 2), RelayLogic.DIRECT))
}
