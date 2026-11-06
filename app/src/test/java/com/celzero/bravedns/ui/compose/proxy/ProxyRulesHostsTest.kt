/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.proxy

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.rules.CustomRuleActions
import com.celzero.bravedns.ui.compose.rules.FakePort
import com.celzero.bravedns.ui.compose.rules.RuleItem
import com.celzero.bravedns.ui.compose.rules.RuleKind
import com.celzero.bravedns.ui.compose.rules.RulesScope
import com.celzero.bravedns.ui.compose.rules.RulesScreenState
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * The editor's "Applies to" chooser on a proxy page lists the apps: the editor only flips
 * `scopePicker`, so the host must load them (regression: it stayed on "Loading…" forever).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w360dp-h800dp")
class ProxyRulesHostsTest {
    @get:Rule val compose = createComposeRule()

    private val source = object : ProxyRulesSource {
        var asked = 0
        override fun rules(proxyId: String): Flow<List<RuleItem>> = flowOf(emptyList())
        override suspend fun apps(): List<Pair<Int, String>> { asked++; return listOf(10042 to "Chrome", 10050 to "Firefox") }
    }

    private fun controller(): ProxyRulesController {
        val state = RulesScreenState(RulesScope(UID_EVERYBODY, allApps = false), scopePickable = true)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val actions = CustomRuleActions(
            state, scope, FakePort(), notify = { _, _ -> }, log = { _, _ -> },
            ioDispatcher = Dispatchers.Unconfined, mainDispatcher = Dispatchers.Unconfined,
        )
        return ProxyRulesController(
            "S5", state, actions, source, scope, "All apps", notify = { _, _ -> },
            ioDispatcher = Dispatchers.Unconfined, mainDispatcher = Dispatchers.Unconfined,
        )
    }

    private fun frames() {
        repeat(5) {
            compose.mainClock.advanceTimeByFrame()
            ShadowLooper.idleMainLooper()
        }
    }

    @Test fun appliesTo_openedLikeTheEditor_loadsAppsAndPicksOne() {
        val c = controller()
        // a text field in the dialog keeps compose busy (cursor); step frames by hand
        compose.mainClock.autoAdvance = false
        compose.setContent { RethinkTheme(darkTheme = false) { ScopePickerHost(c) } }
        compose.runOnUiThread {
            c.add(RuleKind.DOMAIN)
            // what the editor's "Applies to" row does: only the flag, no openScopePicker()
            c.state.scopePicker = true
        }
        frames()
        compose.onNodeWithText("Firefox").assertExists()
        compose.onNodeWithText("All apps").assertExists()
        compose.onNodeWithText("Firefox").performClick()
        frames()
        assertEquals(10050, c.state.draft!!.uid)
        assertEquals(false, c.state.scopePicker)
        assertEquals(1, source.asked)
    }
}
