/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.debug

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import com.celzero.bravedns.service.ProxyChoices.Pick
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.design.ChoiceOption
import com.celzero.bravedns.ui.compose.design.EmptyState
import com.celzero.bravedns.ui.compose.design.ProxyPickerContent
import com.celzero.bravedns.ui.compose.design.ProxyPickerMode
import com.celzero.bravedns.ui.compose.design.ProxyPickerSamples
import com.celzero.bravedns.ui.compose.design.ProxyPickerSheet
import com.celzero.bravedns.ui.compose.design.RethinkSpacing
import com.celzero.bravedns.ui.compose.design.RethinkStatus
import com.celzero.bravedns.ui.compose.design.RuleProxyPickerSheet
import com.celzero.bravedns.ui.compose.design.SectionDivider
import com.celzero.bravedns.ui.compose.design.SectionHeader
import com.celzero.bravedns.ui.compose.design.SegmentOption
import com.celzero.bravedns.ui.compose.design.SegmentedChoice
import com.celzero.bravedns.ui.compose.design.SettingRow
import com.celzero.bravedns.ui.compose.design.SettingTrailing
import com.celzero.bravedns.ui.compose.design.SettingsSection
import com.celzero.bravedns.ui.compose.design.SingleChoiceList
import com.celzero.bravedns.ui.compose.design.StatusChip
import com.celzero.bravedns.ui.compose.design.StatusDot
import com.celzero.bravedns.ui.compose.design.SwitchRow
import com.celzero.bravedns.ui.compose.design.statusColors

/**
 * Debug-only design gallery (a mini Storybook) showing every ui/compose/design component and
 * the proxy picker, with a Light / AMOLED dark toggle. Lives in src/debug, so release builds
 * do not contain it.
 *
 *   adb shell am start -n com.celzero.bravedns/.debug.DesignGalleryActivity [--ez dark true]
 */
class DesignGalleryActivity : ComponentActivity() {
    companion object {
        const val EXTRA_DARK = "dark"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val startDark = intent.getBooleanExtra(EXTRA_DARK, false)
        setContent {
            var dark by rememberSaveable { mutableStateOf(startDark) }
            LaunchedEffect(dark) {
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            RethinkTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Gallery(dark, onDarkChange = { dark = it }, toast = { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() })
                }
            }
        }
    }
}

@Composable
private fun Gallery(dark: Boolean, onDarkChange: (Boolean) -> Unit, toast: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            // inset the viewport (not the content) so rows never scroll under the status bar
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(RethinkSpacing.lg),
    ) {
        Column(Modifier.padding(horizontal = RethinkSpacing.lg, vertical = RethinkSpacing.sm), verticalArrangement = Arrangement.spacedBy(RethinkSpacing.sm)) {
            Text("Design gallery", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
            SegmentedChoice(
                listOf(SegmentOption(false, "Light"), SegmentOption(true, "AMOLED dark")),
                selected = dark, onSelect = onDarkChange,
            )
        }
        ColorTokens()
        TypeTokens()
        RowsDemo()
        ChoiceDemo()
        StatusDemo()
        SegmentedDemo()
        EmptyStateDemo()
        PickerDemo(toast)
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorTokens() {
    val c = MaterialTheme.colorScheme
    val s = MaterialTheme.statusColors
    val swatches = listOf(
        "primary" to c.primary, "onPrimary" to c.onPrimary, "primaryCont" to c.primaryContainer,
        "secondary" to c.secondary, "secondaryCont" to c.secondaryContainer, "tertiary" to c.tertiary,
        "error" to c.error, "errorCont" to c.errorContainer, "background" to c.background,
        "surface" to c.surface, "contLowest" to c.surfaceContainerLowest, "contLow" to c.surfaceContainerLow,
        "container" to c.surfaceContainer, "contHigh" to c.surfaceContainerHigh, "contHighest" to c.surfaceContainerHighest,
        "onSurface" to c.onSurface, "onSurfVariant" to c.onSurfaceVariant, "outline" to c.outline,
        "outlineVar" to c.outlineVariant, "status.active" to s.active, "status.waiting" to s.waiting,
        "status.off" to s.off, "status.error" to s.error,
    )
    SectionHeader("Colour tokens")
    FlowRow(
        Modifier.padding(horizontal = RethinkSpacing.lg),
        horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(RethinkSpacing.sm),
    ) {
        swatches.forEach { (name, color) -> Swatch(name, color) }
    }
}

@Composable
private fun Swatch(name: String, color: Color) {
    Column(Modifier.width(96.dp)) {
        Box(
            Modifier.fillMaxWidth().height(36.dp)
                .background(color, MaterialTheme.shapes.small)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
        )
        Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun TypeTokens() {
    val t = MaterialTheme.typography
    SettingsSection(title = "Typography") {
        Column(Modifier.padding(horizontal = RethinkSpacing.lg, vertical = RethinkSpacing.sm), verticalArrangement = Arrangement.spacedBy(RethinkSpacing.xs)) {
            listOf(
                "headlineSmall" to t.headlineSmall, "titleLarge" to t.titleLarge, "titleMedium" to t.titleMedium,
                "titleSmall" to t.titleSmall, "bodyLarge" to t.bodyLarge, "bodyMedium" to t.bodyMedium,
                "bodySmall" to t.bodySmall, "labelLarge" to t.labelLarge, "labelMedium" to t.labelMedium, "labelSmall" to t.labelSmall,
            ).forEach { (name, style) -> Text(name, style = style, color = MaterialTheme.colorScheme.onSurface) }
            Row(horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.sm), verticalAlignment = Alignment.Bottom) {
                listOf(RethinkSpacing.xs, RethinkSpacing.sm, RethinkSpacing.md, RethinkSpacing.lg, RethinkSpacing.xl, RethinkSpacing.xxl).forEach {
                    Box(Modifier.size(it).background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall))
                }
                Text("spacing 4 · 8 · 12 · 16 · 24 · 32", style = t.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun RowsDemo() {
    var notifications by rememberSaveable { mutableStateOf(true) }
    var wifi by rememberSaveable { mutableStateOf(false) }
    SettingsSection(title = "SettingsSection · SettingRow · SwitchRow", actionLabel = "See all", onAction = {}) {
        SettingRow("Plain row", supportingText = "No trailing content")
        SectionDivider()
        SettingRow("Theme", icon = Icons.Filled.Palette, onClick = {}) { SettingTrailing.Value("AMOLED dark") }
        SectionDivider()
        SettingRow("DNS", supportingText = "RDNS Default", icon = Icons.Filled.Dns, onClick = {}) { SettingTrailing.Chevron() }
        SectionDivider()
        SettingRow("WireGuard · wg-one", supportingText = "Routes 3 apps", icon = Icons.Filled.VpnKey, onClick = {}) { StatusChip(RethinkStatus.Active) }
        SectionDivider()
        SwitchRow("Notifications", notifications, { notifications = it }, icon = Icons.Filled.Notifications, supportingText = "Whole row toggles the switch")
        SectionDivider()
        SwitchRow("Use on Wi-Fi only", wifi, { wifi = it }, icon = Icons.Filled.Wifi)
        SectionDivider()
        SwitchRow("Lockdown (disabled)", true, {}, icon = Icons.Filled.Shield, enabled = false, supportingText = "Needs the VPN to be on")
        SectionDivider()
        SettingRow("Disabled row", supportingText = "Not clickable", enabled = false, onClick = {}) { SettingTrailing.Chevron() }
    }
}

@Composable
private fun ChoiceDemo() {
    var mode by rememberSaveable { mutableStateOf("normal") }
    SettingsSection(title = "SingleChoiceList") {
        SingleChoiceList(
            listOf(
                ChoiceOption("normal", "Normal", "Firewall and DNS rules apply"),
                ChoiceOption("isolate", "Isolate", "Only trusted IPs and domains are allowed"),
                ChoiceOption("bypass", "Bypass DNS & firewall", "Skip all blocking"),
                ChoiceOption("exclude", "Exclude from VPN", enabled = false, disabledReason = "Not available while lockdown is on"),
            ),
            selected = mode, onSelect = { mode = it },
        )
    }
}

@Composable
private fun StatusDemo() {
    SettingsSection(title = "StatusDot · StatusChip") {
        Column(Modifier.padding(horizontal = RethinkSpacing.lg, vertical = RethinkSpacing.sm), verticalArrangement = Arrangement.spacedBy(RethinkSpacing.sm)) {
            RethinkStatus.entries.forEach { status ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.md)) {
                    StatusDot(status, contentDescription = status.name)
                    StatusChip(status)
                    Text(status.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SegmentedDemo() {
    var two by rememberSaveable { mutableStateOf(true) }
    var three by rememberSaveable { mutableStateOf(1) }
    var four by rememberSaveable { mutableStateOf("ip") }
    SectionHeader("SegmentedChoice")
    Column(Modifier.padding(horizontal = RethinkSpacing.lg), verticalArrangement = Arrangement.spacedBy(RethinkSpacing.sm)) {
        SegmentedChoice(listOf(SegmentOption(true, "Allowed"), SegmentOption(false, "Blocked")), two, { two = it })
        SegmentedChoice(listOf(SegmentOption(0, "List"), SegmentOption(1, "Charts"), SegmentOption(2, "Map")), three, { three = it })
        SegmentedChoice(
            listOf(SegmentOption("none", "None"), SegmentOption("block", "Block"), SegmentOption("trust", "Trust"), SegmentOption("ip", "Route", enabled = true)),
            four, { four = it },
        )
    }
}

@Composable
private fun EmptyStateDemo() {
    SettingsSection(title = "EmptyState") {
        EmptyState(
            icon = Icons.Filled.FilterList,
            title = "No rules for this app",
            body = "Rules you add for IPs or domains used by this app show up here.",
            actionLabel = "Add rule",
            onAction = {},
        )
    }
}

@Composable
private fun PickerDemo(toast: (String) -> Unit) {
    var appPick by rememberSaveable { mutableStateOf<String?>("wg2") }
    var showSample by rememberSaveable { mutableStateOf(false) }
    var showLiveRule by rememberSaveable { mutableStateOf(false) }
    var ruleProxy by rememberSaveable { mutableStateOf("") }
    val selected = when (appPick) {
        null -> Pick.FollowGlobal
        "" -> Pick.Bypass
        else -> Pick.Via(appPick!!)
    }
    SettingsSection(title = "Proxy picker · app mode (sample data)") {
        ProxyPickerContent(
            ProxyPickerMode.APP, "Proxy for Chrome", null, ProxyPickerSamples.proxies, selected,
            onSelect = { appPick = when (it) { Pick.FollowGlobal -> null; Pick.Bypass -> ""; is Pick.Via -> it.proxyId } },
        )
    }
    SettingsSection(title = "Proxy picker · legacy multi-mapped app") {
        ProxyPickerContent(ProxyPickerMode.APP, "Proxy for Firefox", null, ProxyPickerSamples.proxies, null, {}, multiple = listOf("wg-one", "wg-two"))
    }
    SettingsSection(title = "Proxy picker · rule mode (sample data)") {
        ProxyPickerContent(ProxyPickerMode.RULE, "Route via proxy", "1.1.1.1:443", ProxyPickerSamples.proxies, Pick.Via("wg3"), {})
    }
    Row(Modifier.padding(horizontal = RethinkSpacing.lg), horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.sm)) {
        OutlinedButton(onClick = { showSample = true }) { Text("Open app-mode sheet (sample)") }
        OutlinedButton(onClick = { showLiveRule = true }) { Text("Open live rule picker") }
    }
    if (showSample) {
        ProxyPickerSheet(
            ProxyPickerMode.APP, "Proxy for Chrome", "com.android.chrome", ProxyPickerSamples.proxies, selected,
            onSelect = {
                appPick = when (it) { Pick.FollowGlobal -> null; Pick.Bypass -> ""; is Pick.Via -> it.proxyId }
                showSample = false
            },
            onDismiss = { showSample = false },
        )
    }
    if (showLiveRule) {
        RuleProxyPickerSheet(
            currentProxyId = ruleProxy, subject = "example.com",
            onSelect = { ruleProxy = it; toast("rule proxyId = \"$it\"") },
            onDismiss = { showLiveRule = false },
        )
    }
}
