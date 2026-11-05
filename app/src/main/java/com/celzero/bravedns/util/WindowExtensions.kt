/*
 * Copyright 2025 RethinkDNS and its authors
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
package com.celzero.bravedns.util

import android.app.Dialog
import android.graphics.Color
import android.view.WindowManager
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatDialog
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.DialogFragment
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/** Utility extension functions to configure Activity/Dialog/BottomSheet window appearance generically. */

fun Dialog.useTransparentNoDimBackground(
    @ColorInt color: Int = Color.TRANSPARENT
) {
    // clear the dim behind flag so the underlying activity is not dimmed
    window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    // explicitly set dim amount to 0
    window?.setDimAmount(0f)
    // set transparent (or given) background
    window?.setBackgroundDrawable(color.toDrawable())
}

fun AppCompatDialog.useTransparentNoDimBackground(
    @ColorInt color: Int = Color.TRANSPARENT
) {
    (this as Dialog?)?.useTransparentNoDimBackground(color)
}

fun BottomSheetDialog.useTransparentNoDimBackground(
    @ColorInt color: Int = Color.TRANSPARENT
) {
    (this as Dialog?)?.useTransparentNoDimBackground(color)
}

/** Allow calling the helper directly on a DialogFragment/BottomSheetDialogFragment. */
fun DialogFragment?.useTransparentNoDimBackground(
    @ColorInt color: Int = Color.TRANSPARENT
) {
    this?.dialog?.useTransparentNoDimBackground(color)
}

fun BottomSheetDialogFragment?.useTransparentNoDimBackground(
    @ColorInt color: Int = Color.TRANSPARENT
) {
    this?.dialog?.useTransparentNoDimBackground(color)
}
