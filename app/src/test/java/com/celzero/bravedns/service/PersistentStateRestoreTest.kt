package com.celzero.bravedns.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.celzero.bravedns.shadows.ShadowBackend
import io.mockk.every
import io.mockk.spyk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [ShadowBackend::class])
class PersistentStateRestoreTest {

    private fun realState(): PersistentState {
        // the constructor resolves a flavour string missing from Robolectric's resources
        val ctx = spyk(ApplicationProvider.getApplicationContext<Context>())
        every { ctx.getString(any()) } returns "default"
        return PersistentState(ctx)
    }

    @Test
    fun restoreDnsDefaults_resetsCaptivePortalSetting() {
        val ps = realState()
        ps.useSystemDnsOnCaptivePortal = false

        ps.restoreDnsSettingsDefaults()

        assertTrue(ps.useSystemDnsOnCaptivePortal)
    }

    @Test
    fun restoreDnsDefaults_resetsTheOtherDnsRows() {
        val ps = realState()
        ps.useCustomDownloadManager = false
        ps.blockDnsForUnknownApp = true
        ps.preventDnsLeaks = false
        ps.proxyDns = false

        ps.restoreDnsSettingsDefaults()

        assertTrue(ps.useCustomDownloadManager)
        assertFalse(ps.blockDnsForUnknownApp)
        assertTrue(ps.preventDnsLeaks)
        assertTrue(ps.proxyDns)
    }
}
