/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.bottomsheet

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.Toast
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.ui.compose.rules.ManagerRuleSheetPort
import com.celzero.bravedns.ui.compose.rules.RuleSheetActions
import com.celzero.bravedns.ui.compose.rules.RuleSheetState
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Glue shared by the rule sheet fragments (AppIpRulesBottomSheet, AppDomainRulesBottomSheet,
 * ConnTrackerBottomSheet): rule actions on the managers, toasts, the event log, the app header.
 */

internal fun Fragment.ruleSheetActions(state: RuleSheetState, eventLogger: EventLogger, logTitle: String) =
    RuleSheetActions(
        state = state,
        scope = lifecycleScope,
        port = ManagerRuleSheetPort(),
        notify = { res -> if (isAdded) Utilities.showToastUiCentered(requireContext(), getString(res), Toast.LENGTH_SHORT) },
        log = { details -> eventLogger.log(EventType.FW_RULE_MODIFIED, Severity.LOW, logTitle, EventSource.UI, false, details) },
    )

/**
 * The Compose sheets draw their own rounded surface; clear the Material sheet frame's themed
 * background so it does not show around it. Call from onStart.
 */
internal fun DialogFragment.transparentSheetFrame() {
    dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(Color.TRANSPARENT)
}

/** The app a sheet's rules are for: name ("App +n other apps" for shared uids) and icon; null name = not an app. */
@Stable
internal class AppSheetHeader {
    var name by mutableStateOf<String?>(null)
    var icon by mutableStateOf<Drawable?>(null)

    fun load(fragment: Fragment, uid: Int) {
        val context = fragment.requireContext().applicationContext
        fragment.lifecycleScope.launch {
            val (n, pkg) = withContext(Dispatchers.IO) {
                val names = FirewallManager.getAppNamesByUid(uid)
                if (names.isEmpty()) return@withContext null to null
                val label = if (names.size >= 2) context.getString(R.string.ctbs_app_other_apps, names[0], (names.size - 1).toString()) else names[0]
                label to FirewallManager.getPackageNameByAppName(names[0])
            }
            name = n
            icon = pkg?.let { withContext(Dispatchers.IO) { Utilities.getIcon(context, it) } }
        }
    }
}
