/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.celzero.bravedns.ui.compose.wireguard

import com.celzero.bravedns.ui.compose.controls.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R

@Composable private fun WgChips(group: WgControlState, source: String, vararg nodes: WgControlState) {
    if (!group.isVisible) return
    val ids = if (source == "list_item_wg_hop") listOf("chip_ipv4", "chip_ipv6", "chip_split_tunnel", "chip_amnezia", "chip_hop_src", "chip_hopping", "chip_properties")
        else listOf("protocol_info_chip_ipv4", "protocol_info_chip_ipv6", "chip_split_tunnel", "chip_amnezia", "chip_hop_src", "chip_hopping", "chip_properties")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        nodes.forEachIndexed { index, node -> if (node.isVisible) {
            com.celzero.bravedns.ui.compose.controls.OriginalLabel("$source:${ids[index]}", Modifier.alpha(node.alpha),
                selected = node.isChecked, contentColor = node.textColor?.let(::Color),
                containerColor = node.backgroundColor?.let(::Color), backgroundTint = node.backgroundTintList) {
                Text(wgAnnotatedText(node.text))
            }
        } }
    }
}

@Composable fun WgStatsRow(b: WgStatsRowState) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WgIcon(b.ssIcon); WgText(b.ssFlag)
            Column(Modifier.weight(1f)) { WgText(b.ssName); WgText(b.ssDataUsage); WgProgress(b.ssProgress, Modifier.fillMaxWidth().padding(top = 6.dp)) }
            WgText(b.ssCount)
        }
    }
}
