/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.appdetails

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.ui.compose.design.RethinkSpacing
import com.celzero.bravedns.ui.stats.StatsInsightsMath
import com.celzero.bravedns.util.UIUtils.getCountryNameFromFlag

private const val TOP_ITEMS = 5
private val SLICE_ALPHAS = listOf(1f, 0.75f, 0.55f, 0.4f, 0.28f)

/**
 * Insights view of one activity section: a donut of the top five plus ranked bars (countries:
 * the world map plus bars). Allowed sections use the primary colour, blocked ones the error colour.
 */
@Composable
fun AppDetailsInsights(section: AppInsightsSection, items: List<AppConnection>, modifier: Modifier = Modifier) {
    val fractions = StatsInsightsMath.normalizeFractions(items.map { it.count.toLong() })
    val good = MaterialTheme.colorScheme.primary
    val bad = MaterialTheme.colorScheme.error
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val base = if (section.blocked) bad else good
    val rows: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(RethinkSpacing.sm)) {
            items.take(TOP_ITEMS).forEachIndexed { i, item ->
                val label = insightLabel(section, item)
                val description = stringResource(R.string.ci_desc, label, item.count.toString())
                Column(Modifier.semantics(mergeDescendants = true) { contentDescription = description }) {
                    Row {
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            item.count.toString(),
                            Modifier.padding(start = RethinkSpacing.sm),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (fractions[i] * 100).toInt() / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = RethinkSpacing.xs).height(3.dp),
                        color = if (!section.blocked && item.blocked) bad else base,
                        trackColor = track,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                }
            }
        }
    }
    if (section == AppInsightsSection.COUNTRIES) {
        Column(modifier.padding(horizontal = RethinkSpacing.screenGutter)) {
            AppCountryMap(items)
            rows()
        }
    } else {
        Row(
            modifier.fillMaxWidth().padding(horizontal = RethinkSpacing.screenGutter, vertical = RethinkSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.lg),
        ) {
            val title = stringResource(section.title)
            val total = items.sumOf { it.count.toLong() }.toString()
            Box(
                Modifier.size(96.dp).semantics(mergeDescendants = true) { contentDescription = "$title: $total" },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val width = minOf(size.width, size.height) * .14f
                    val diameter = minOf(size.width, size.height) - width
                    val pos = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
                    val area = Size(diameter, diameter)
                    drawArc(track, 0f, 360f, false, pos, area, style = Stroke(width))
                    var start = -90f
                    appDonutSweeps(fractions.take(TOP_ITEMS)).forEachIndexed { i, sweep ->
                        if (sweep > 2f) drawArc(base.copy(alpha = SLICE_ALPHAS[i]), start, sweep - 1.5f, false, pos, area, style = Stroke(width))
                        start += sweep
                    }
                }
                Text(total, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            Box(Modifier.weight(1f)) { rows() }
        }
    }
}

@Composable
private fun insightLabel(section: AppInsightsSection, item: AppConnection): String = when (section) {
    AppInsightsSection.ACTIVE_CONNS, AppInsightsSection.IPS, AppInsightsSection.BLOCKED_IPS -> item.ipAddress
    AppInsightsSection.DOMAINS, AppInsightsSection.BLOCKED_DOMAINS -> item.appOrDnsName?.dropLastWhile { it == '.' }.orEmpty()
    AppInsightsSection.COUNTRIES -> getCountryNameFromFlag(item.flag).takeIf { it.isNotEmpty() && !it.all { c -> c == '-' } }
        ?: stringResource(R.string.two_argument_space, stringResource(R.string.network_log_app_name_unknown), item.flag)
    AppInsightsSection.ASN, AppInsightsSection.BLOCKED_ASN -> stringResource(R.string.two_argument_space, item.flag, item.appOrDnsName.orEmpty())
    AppInsightsSection.BLOCKLISTS -> item.appOrDnsName.orEmpty()
}
