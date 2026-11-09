package com.celzero.bravedns.ui.activity

import com.celzero.bravedns.ui.activity.AntiCensorshipActivity.DialStrategies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DialStrategiesTest {

    @Test
    fun fromInt_neverReturnsHybrid_becauseItSharesAutosValue() {
        // the root of the bug: TCP_PROXY and SPLIT_AUTO store the same Go value
        assertEquals(DialStrategies.SPLIT_AUTO.mode, DialStrategies.TCP_PROXY.mode)
        assertEquals(DialStrategies.SPLIT_AUTO, DialStrategies.fromInt(DialStrategies.TCP_PROXY.mode))
    }

    @Test
    fun effective_autoWithFlag_isHybrid() {
        assertEquals(
            DialStrategies.TCP_PROXY,
            DialStrategies.effective(DialStrategies.SPLIT_AUTO.mode, autoProxyEnabled = true)
        )
    }

    @Test
    fun effective_autoWithoutFlag_isAuto() {
        assertEquals(
            DialStrategies.SPLIT_AUTO,
            DialStrategies.effective(DialStrategies.SPLIT_AUTO.mode, autoProxyEnabled = false)
        )
    }

    @Test
    fun effective_otherStrategiesIgnoreStaleFlag() {
        listOf(
            DialStrategies.NEVER_SPLIT,
            DialStrategies.SPLIT_TCP,
            DialStrategies.SPLIT_TCP_TLS,
            DialStrategies.DESYNC
        ).forEach { ds ->
            assertEquals(ds, DialStrategies.effective(ds.mode, autoProxyEnabled = true))
            assertEquals(ds, DialStrategies.effective(ds.mode, autoProxyEnabled = false))
        }
    }

    @Test
    fun effective_unknownValue_isNull() {
        assertNull(DialStrategies.effective(-12345, autoProxyEnabled = true))
    }
}
