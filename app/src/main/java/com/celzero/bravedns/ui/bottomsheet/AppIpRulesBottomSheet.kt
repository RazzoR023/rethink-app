/*
 * Copyright 2020 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.ui.bottomsheet

import android.content.DialogInterface
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.AppWiseIpsAdapter
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.rules.AppRuleSheetScreen
import com.celzero.bravedns.ui.compose.rules.RuleSheetActions
import com.celzero.bravedns.ui.compose.rules.RuleSheetLogic
import com.celzero.bravedns.ui.compose.rules.RuleSheetState
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Themes.Companion.getBottomSheetCurrentTheme
import org.koin.android.ext.android.inject

/**
 * App details -> IP addresses -> a row: trust / block the IP and each of its domains for the
 * app, and route each via a proxy ("Route via", independent of trust / block).
 */
class AppIpRulesBottomSheet : BaseBottomSheetDialogFragment() {

    private val persistentState by inject<PersistentState>()
    private val eventLogger by inject<EventLogger>()

    // listener to inform dataset change to the adapter
    private var dismissListener: OnBottomSheetDialogFragmentDismiss? = null
    private var adapter: AppWiseIpsAdapter? = null
    private var position: Int = -1

    private val state = RuleSheetState()
    private val header = AppSheetHeader()
    private lateinit var actions: RuleSheetActions

    override fun getTheme(): Int = getBottomSheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    companion object {
        const val UID = "UID"
        const val IP_ADDRESS = "IP_ADDRESS"
        const val DOMAINS = "DOMAINS"
    }

    private fun isDarkThemeOn(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    interface OnBottomSheetDialogFragmentDismiss {
        fun notifyDataset(position: Int)
    }

    fun dismissListener(aca: AppWiseIpsAdapter?, pos: Int) {
        adapter = aca
        position = pos
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        actions = ruleSheetActions(state, eventLogger, "App IP rule")
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                RethinkTheme {
                    AppRuleSheetScreen(stringResource(R.string.rules_sheet_title_ip), header.name, header.icon, state, actions)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        transparentSheetFrame()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.window?.let { Themes.applyBottomSheetSystemBarAppearance(it, isDarkThemeOn(), persistentState.theme) }
        val uid = arguments?.getInt(UID) ?: INVALID_UID
        val ip = arguments?.getString(IP_ADDRESS).orEmpty()
        dismissListener = adapter
        if (uid == INVALID_UID) {
            dismiss()
            return
        }
        header.load(this, uid)
        if (state.rows.isEmpty()) actions.load(RuleSheetLogic.ipSheetRows(uid, ip, arguments?.getString(DOMAINS)))
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        dismissListener?.notifyDataset(position)
    }
}

