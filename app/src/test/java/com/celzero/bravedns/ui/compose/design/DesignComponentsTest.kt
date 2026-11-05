/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.design

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.celzero.bravedns.service.ProxyChoices.Pick
import com.celzero.bravedns.ui.compose.RethinkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w360dp-h800dp")
class DesignComponentsTest {
    @get:Rule val compose = createComposeRule()

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    @Test fun amoledScheme_isPureBlack_lightKeepsAppAccent() {
        var background = Color.Unspecified
        var lightPrimary = Color.Unspecified
        compose.setContent {
            RethinkTheme(darkTheme = true) { background = MaterialTheme.colorScheme.background }
            RethinkTheme(darkTheme = false) { lightPrimary = MaterialTheme.colorScheme.primary }
        }
        compose.waitForIdle()
        assertEquals(Color.Black, background)
        assertEquals(Color(0xFF005CB2), lightPrimary)
    }

    @Test fun switchRow_wholeRowToggles_withSwitchRole() {
        var checked by mutableStateOf(false)
        compose.setContent { RethinkTheme(darkTheme = false) { SwitchRow("Notifications", checked, { checked = it }) } }
        val row = compose.onNodeWithText("Notifications")
        row.assert(hasRole(Role.Switch)).assertIsOff()
        row.performClick()
        row.assertIsOn()
        assertEquals(true, checked)
    }

    @Test fun switchRow_disabled_ignoresClicks() {
        var checked by mutableStateOf(false)
        compose.setContent { RethinkTheme(darkTheme = false) { SwitchRow("Lockdown", checked, { checked = it }, enabled = false) } }
        compose.onNodeWithText("Lockdown").assertIsNotEnabled().performClick()
        assertEquals(false, checked)
    }

    @Test fun settingRow_isOneButton() {
        var clicks = 0
        compose.setContent { RethinkTheme(darkTheme = false) { SettingRow("DNS", supportingText = "RDNS", onClick = { clicks++ }) { SettingTrailing.Chevron() } } }
        compose.onNodeWithText("DNS").assert(hasRole(Role.Button)).performClick()
        assertEquals(1, clicks)
    }

    @Test fun singleChoiceList_radioRoles_disabledShowsReason() {
        var selected by mutableStateOf("a")
        compose.setContent {
            RethinkTheme(darkTheme = true) {
                SingleChoiceList(
                    listOf(
                        ChoiceOption("a", "Normal", "desc a"),
                        ChoiceOption("b", "Isolate", "desc b"),
                        ChoiceOption("c", "Exclude", "desc c", enabled = false, disabledReason = "Not now"),
                    ),
                    selected, { selected = it },
                )
            }
        }
        compose.onNodeWithText("Normal").assert(hasRole(Role.RadioButton)).assertIsSelected()
        compose.onNodeWithText("Isolate").performClick()
        assertEquals("b", selected)
        compose.onNodeWithText("Isolate").assertIsSelected()
        compose.onNodeWithText("Normal").assertIsNotSelected()
        compose.onNodeWithText("Exclude").assertIsNotEnabled().performClick()
        assertEquals("b", selected)
        compose.onNodeWithText("Not now").assertExists()
        compose.onNodeWithText("desc c").assertDoesNotExist()
    }

    @Test fun statusChip_readsAsStatus() {
        compose.setContent { RethinkTheme(darkTheme = false) { StatusChip(RethinkStatus.Waiting) } }
        compose.onAllNodesWithContentDescription("Status: Waiting").assertCountEquals(1)
    }

    @Test fun segmentedChoice_selects() {
        var value by mutableStateOf(true)
        compose.setContent {
            RethinkTheme(darkTheme = false) {
                SegmentedChoice(listOf(SegmentOption(true, "Allowed"), SegmentOption(false, "Blocked")), value, { value = it })
            }
        }
        compose.onNodeWithText("Blocked").performClick()
        assertEquals(false, value)
    }

    @Test fun proxyPicker_appMode_listsDefaultBypassAndProxies() {
        var picked: Pick? = null
        compose.setContent {
            RethinkTheme(darkTheme = true) {
                ProxyPickerContent(ProxyPickerMode.APP, "Proxy for Chrome", null, ProxyPickerSamples.proxies, Pick.Via("wg1"), { picked = it })
            }
        }
        compose.onNodeWithText("Default").assertExists()
        compose.onNodeWithText("No proxy").assertExists()
        compose.onNodeWithText("wg-one").assertIsSelected()
        // 2 base rows + 5 proxies
        compose.onAllNodes(isSelectable()).assertCountEquals(7)
        // off proxies (home, Orbot) are marked and still selectable
        compose.onAllNodesWithContentDescription("Status: Off").assertCountEquals(2)
        compose.onNodeWithText("Orbot (Tor)").performClick()
        assertEquals(Pick.Via("ORBOT"), picked)
        compose.onNodeWithText("No proxy").performClick()
        assertEquals(Pick.Bypass, picked)
    }

    @Test fun proxyPicker_multiple_showsNoteAndNoSelection() {
        compose.setContent {
            RethinkTheme(darkTheme = false) {
                ProxyPickerContent(ProxyPickerMode.APP, "Proxy for Firefox", null, ProxyPickerSamples.proxies, null, {}, multiple = listOf("wg-one", "wg-two"))
            }
        }
        compose.onNodeWithText("Multiple (2)").assertExists()
        compose.onAllNodes(isSelectable() and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).assertCountEquals(0)
    }

    @Test fun proxyPicker_ruleMode_noOverrideFirst() {
        var picked: Pick? = null
        compose.setContent {
            RethinkTheme(darkTheme = false) {
                ProxyPickerContent(ProxyPickerMode.RULE, "Route via proxy", "1.1.1.1", ProxyPickerSamples.proxies, Pick.FollowGlobal, { picked = it })
            }
        }
        compose.onNodeWithText("No proxy override").assertIsSelected()
        compose.onNodeWithText("No proxy").assertDoesNotExist()
        compose.onNodeWithText("SOCKS5").performClick()
        assertEquals(Pick.Via("S5"), picked)
    }
}
