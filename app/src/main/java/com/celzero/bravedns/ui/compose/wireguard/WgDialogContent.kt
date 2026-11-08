/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.wireguard

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import android.app.Dialog
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.celzero.bravedns.ui.compose.RethinkTheme

fun Dialog.setWgContent(activity: Activity, content: @Composable () -> Unit) {
    val view = ComposeView(context).apply {
        id = View.generateViewId()
        setViewTreeLifecycleOwner(activity as LifecycleOwner)
        setViewTreeSavedStateRegistryOwner(activity as SavedStateRegistryOwner)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setContent { RethinkTheme { content() } }
    }
    setContentView(view)
}

