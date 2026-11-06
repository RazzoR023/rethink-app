/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.networksheets

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.compose.runtime.*
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.wireguard.WgControlState

class BackupRestoreSheetState(context: Context) {
    val brbsBackup = WgControlState(context)
    val brbsRestore = WgControlState(context)
    val brbsAppVersion = WgControlState(context)
    // The original version TextView is selectable and therefore freezes its text.
    fun saveText(bundle: Bundle) { bundle.putCharSequence("compose.backup.version", brbsAppVersion.text) }
    fun restoreText(bundle: Bundle?) {
        if (bundle?.containsKey("compose.backup.version") == true) brbsAppVersion.text = bundle.getCharSequence("compose.backup.version") ?: ""
    }
}

class RethinkWarningSheetState(context: Context) {
    val tvTitle = WgControlState(context).apply { text = context.getString(R.string.settings_rinr_btmsht_title) }
    val tvDescription = WgControlState(context)
    val btnExemptRethink = WgControlState(context).apply { text = context.getString(R.string.settings_rinr_exempt_btn) }
    val btnUnderstand = WgControlState(context).apply { text = context.getString(R.string.app_info_rethink_warning_proceed) }
}

class ProxyCountriesSheetState(context: Context) {
    val title = WgControlState(context).apply { text = "Choose country" }
    val ipDomainInfo = WgControlState(context)
    var rows: List<String> by mutableStateOf(emptyList())
    private var revision by mutableIntStateOf(0)
    var flagFor: (String) -> String = { "" }
    var selection: () -> String? = { null }
    var onClick: (String) -> Unit = {}
    var supportsCountryDetails by mutableStateOf(false)
    fun selectedCountry(): String? { revision; return selection() }
    fun click(conf: String) {
        // The old adapter schedules its rebind before dispatching the original callback.
        revision++
        onClick(conf)
    }
}
