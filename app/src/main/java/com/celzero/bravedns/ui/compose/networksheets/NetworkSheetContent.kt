/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.networksheets

import com.celzero.bravedns.ui.compose.controls.*
import android.graphics.Camera
import android.graphics.Matrix
import android.view.View
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.utilities.utilityTextStyle
import com.celzero.bravedns.ui.compose.wireguard.WgControlState
import com.celzero.bravedns.ui.compose.wireguard.wgTextDimensionSp

@Composable
private fun dimen(id: Int) = wgTextDimensionSp(LocalContext.current, id)

@Composable
private fun SheetHandle(width: Int = 60, height: Int = 3, top: Int = 10, bottom: Int = 10) {
    Box(Modifier.fillMaxWidth().padding(top = top.dp, bottom = bottom.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width.dp, height.dp).background(themeColor(R.attr.border)))
    }
}

@Composable
fun BackupRestoreSheetContent(b: BackupRestoreSheetState) {
    Surface(color = themeColor(R.attr.background)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 40.dp)) {
            SheetHandle()
            SelectionContainer(Modifier.align(Alignment.CenterHorizontally).padding(top = 15.dp, bottom = 35.dp)) {
                Text(stringResource(R.string.brbs_title), textAlign = TextAlign.Center, color = themeColor(R.attr.primaryLightColorText),
                    style = utilityTextStyle(size = dimen(R.dimen.heading_font_text_view), weight = android.graphics.Typeface.BOLD))
            }
            Column(Modifier.padding(horizontal = 5.dp)) {
                BackupRow(b.brbsBackup, R.drawable.ic_backup, R.string.brbs_backup_title, R.string.brbs_backup_desc)
                Spacer(Modifier.height(20.dp))
                BackupRow(b.brbsRestore, R.drawable.ic_restore, R.string.brbs_restore_title, R.string.brbs_restore_desc)
                Text(stringResource(R.string.brbs_backup_restore_desc), Modifier.fillMaxWidth().padding(top = 20.dp).alpha(.75f),
                    color = themeColor(R.attr.primaryLightColorText), textAlign = TextAlign.Center, style = utilityTextStyle(size = dimen(R.dimen.default_font_text_view)))
                SelectionContainer {
                    Text(b.brbsAppVersion.text.toString(), Modifier.fillMaxWidth().alpha(.75f), textAlign = TextAlign.Center,
                        color = themeColor(android.R.attr.textColorPrimary), style = utilityTextStyle(size = dimen(R.dimen.default_font_text_view), family = "monospace"))
                }
            }
        }
    }
}

@Composable
private fun BackupRow(control: WgControlState, icon: Int, title: Int, description: Int) {
    Row(Modifier.fillMaxWidth().testTag("backup_row_$title").padding(bottom = 1.dp).clickable(role = Role.Button) { control.performClick() }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(icon), null, Modifier.padding(5.dp).size(32.dp).padding(5.dp).alpha(.5f))
        Column(Modifier.weight(1f).padding(horizontal = 5.dp)) {
            Text(stringResource(title), color = themeColor(R.attr.primaryTextColor), style = utilityTextStyle(size = dimen(R.dimen.extra_large_font_text_view)))
            Text(stringResource(description), color = themeColor(R.attr.primaryLightColorText), style = utilityTextStyle(size = dimen(R.dimen.large_font_text_view)))
        }
        Image(painterResource(R.drawable.ic_right_arrow_white), null, Modifier.padding(10.dp))
    }
}

@Composable
fun RethinkWarningSheetContent(b: RethinkWarningSheetState) {
    Surface(color = themeColor(R.attr.background)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            SheetHandle(40, 4, 20, 0)
            Row(Modifier.padding(top = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Image(painterResource(R.drawable.ic_loopback), null, Modifier.padding(end = 12.dp).size(48.dp).alpha(.7f))
                Text(b.tvTitle.text.toString(), Modifier.weight(1f, false), color = themeColor(R.attr.primaryTextColor),
                    style = utilityTextStyle(size = dimen(R.dimen.extra_large_font_text_view), family = "sans-serif-medium", weight = android.graphics.Typeface.BOLD))
            }
            Text(b.tvDescription.text.toString(), Modifier.fillMaxWidth().padding(top = 14.dp), color = themeColor(R.attr.primaryLightColorText),
                textAlign = TextAlign.Center, style = sheetLineSpacing(utilityTextStyle(size = 14f), multiplier = 1.45f))
            WarningAction(b.btnExemptRethink, Modifier.padding(top = 40.dp))
            WarningAction(b.btnUnderstand, Modifier.padding(top = 20.dp))
        }
    }
}

@Composable
private fun WarningAction(control: WgControlState, modifier: Modifier) {
    Text(control.text.toString(), modifier.border(.75.dp, themeColor(R.attr.border), RoundedCornerShape(16.dp))
        .clickable(role = Role.Button) { control.performClick() }.heightIn(min = 48.dp).padding(horizontal = 25.dp, vertical = 10.dp),
        color = themeColor(R.attr.primaryTextColor), textAlign = TextAlign.Center,
        style = utilityTextStyle(androidx.appcompat.R.style.TextAppearance_AppCompat_Subhead, dimen(R.dimen.large_font_text_view)))
}

@Composable
fun ProxyCountriesSheetContent(b: ProxyCountriesSheetState) {
    val selected = b.selectedCountry()
    Surface(color = themeColor(R.attr.background)) {
        LazyColumn(Modifier.fillMaxWidth().padding(16.dp)) {
            item {
                SheetHandle()
                Text(b.ipDomainInfo.text.toString(), Modifier.fillMaxWidth(), color = themeColor(R.attr.primaryTextColor), textAlign = TextAlign.Center,
                    style = utilityTextStyle(size = dimen(R.dimen.extra_large_font_text_view)))
                Text(b.title.text.toString(), Modifier.padding(top = 20.dp).padding(16.dp), color = themeColor(R.attr.primaryTextColor),
                    style = utilityTextStyle(size = dimen(R.dimen.extra_large_font_text_view)))
            }
            itemsIndexed(b.rows) { _, conf ->
                val checked = b.supportsCountryDetails && conf == selected
                Row(Modifier.fillMaxWidth().testTag("country_row_$conf").padding(.5.dp).clickable(role = Role.RadioButton) { b.click(conf) }.semantics { this.selected = checked }, verticalAlignment = Alignment.CenterVertically) {
                    Text(if (b.supportsCountryDetails) b.flagFor(conf) else "", Modifier.padding(5.dp).size(40.dp).alpha(.75f), textAlign = TextAlign.Center,
                        style = utilityTextStyle(androidx.appcompat.R.style.TextAppearance_AppCompat_Headline, 26f))
                    Column(Modifier.weight(1f).padding(10.dp).padding(horizontal = 5.dp)) {
                        Text(if (b.supportsCountryDetails) conf else "", color = themeColor(R.attr.primaryTextColor), style = utilityTextStyle(size = dimen(R.dimen.large_font_text_view)))
                        Text(if (b.supportsCountryDetails) conf else "", color = themeColor(R.attr.primaryLightColorText), style = utilityTextStyle(size = dimen(R.dimen.default_font_text_view)))
                    }
                    OriginalRadioButton("list_item_proxy_cc_wg:proxy_radio_cc", checked, { b.click(conf) }, Modifier.testTag("country_radio_$conf").semantics { contentDescription = conf })
                }
            }
        }
    }
}
