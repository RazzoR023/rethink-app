/*
 * Copyright 2021 RethinkDNS and its authors
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

import android.view.Window
import androidx.core.view.WindowInsetsControllerCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Utilities.isAtleastQ

/**
 * App themes. There are two: Light and AMOLED dark (pure black, stored as [TRUE_BLACK]);
 * [SYSTEM_DEFAULT] follows the system dark mode and picks one of them.
 *
 * Ids are what [PersistentState.theme] stores. They stay as they were so saved settings keep
 * their meaning; ids of the removed themes (2 Dark, 4 Light Plus, 5 Dark Plus, 6 Frost) are
 * mapped to the nearest remaining theme by [migrate].
 */
enum class Themes(val id: Int) {
    SYSTEM_DEFAULT(0),
    LIGHT(1),
    TRUE_BLACK(3); // "AMOLED dark"

    companion object {
        // ids of themes that no longer exist
        private const val REMOVED_DARK = 2
        private const val REMOVED_LIGHT_PLUS = 4
        private const val REMOVED_DARK_PLUS = 5
        private const val REMOVED_DARK_FROST = 6

        /** The choices the settings theme picker shows, in order. */
        val choices: List<Themes> = listOf(SYSTEM_DEFAULT, LIGHT, TRUE_BLACK)

        fun fromId(id: Int): Themes = entries.firstOrNull { it.id == migrate(id) } ?: SYSTEM_DEFAULT

        /**
         * Maps a stored theme id to one of the remaining themes: Light Plus -> Light;
         * Dark, Dark Plus, Frost -> AMOLED dark; unknown ids -> Set by system.
         */
        fun migrate(id: Int): Int {
            return when (id) {
                SYSTEM_DEFAULT.id, LIGHT.id, TRUE_BLACK.id -> id
                REMOVED_LIGHT_PLUS -> LIGHT.id
                REMOVED_DARK, REMOVED_DARK_PLUS, REMOVED_DARK_FROST -> TRUE_BLACK.id
                else -> SYSTEM_DEFAULT.id
            }
        }

        /** One-time rewrite of a saved removed-theme id; returns true if the value changed. */
        fun migrateSavedTheme(persistentState: PersistentState): Boolean {
            val saved = persistentState.theme
            val migrated = migrate(saved)
            if (migrated == saved) return false
            persistentState.theme = migrated
            return true
        }

        /** True when [theme] resolves to the dark (AMOLED) theme. */
        fun isDark(isDarkThemeOn: Boolean, theme: Int): Boolean {
            return when (fromId(theme)) {
                SYSTEM_DEFAULT -> isDarkThemeOn
                LIGHT -> false
                TRUE_BLACK -> true
            }
        }

        fun getTheme(id: Int): Int {
            return when (fromId(id)) {
                SYSTEM_DEFAULT -> 0 // system default
                LIGHT -> R.style.AppThemeWhite
                TRUE_BLACK -> R.style.AppThemeTrueBlack
            }
        }

        fun getCurrentTheme(isDarkThemeOn: Boolean, theme: Int): Int {
            return if (isDark(isDarkThemeOn, theme)) {
                R.style.AppThemeTrueBlack
            } else {
                R.style.AppThemeWhite
            }
        }

        fun getBottomSheetCurrentTheme(isDarkThemeOn: Boolean, theme: Int): Int {
            return if (isDark(isDarkThemeOn, theme)) {
                R.style.BottomSheetDialogThemeTrueBlack
            } else {
                R.style.BottomSheetDialogThemeWhite
            }
        }

        fun isBottomSheetLightTheme(isDarkThemeOn: Boolean, theme: Int): Boolean {
            return !isDark(isDarkThemeOn, theme)
        }

        fun isActivityLightTheme(isDarkThemeOn: Boolean, theme: Int): Boolean {
            return !isDark(isDarkThemeOn, theme)
        }

        fun applyBottomSheetSystemBarAppearance(
            window: Window,
            isDarkThemeOn: Boolean,
            theme: Int
        ) {
            if (!isAtleastQ()) return
            val isLight = isBottomSheetLightTheme(isDarkThemeOn, theme)
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = isLight
                isAppearanceLightNavigationBars = isLight
            }
            window.isNavigationBarContrastEnforced = false
        }
    }
}
