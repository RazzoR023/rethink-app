/*
 * Copyright 2026 RethinkDNS and its authors
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

/**
 * One-time migrations run on app update (HomeScreenActivity.updateNewVersion). Each runs only
 * when updating from a version older than the one that introduced it; [prevVersion] is the
 * stored app version (0 on a fresh install, which needs no migration).
 */
object UpdateMigrations {
    // v055o moved blocklist downloads to the in-app downloader; v055u is version 53
    private const val CUSTOM_DOWNLOADER_BEFORE = 53

    // the smart dns selection moved to the SmartDnsEndpoint table in version 67 (v057)
    private const val SMART_DNS_TABLE_UPTO = 67

    fun resetDownloader(prevVersion: Int): Boolean =
        prevVersion in 1 until CUSTOM_DOWNLOADER_BEFORE

    fun migrateSmartDns(prevVersion: Int): Boolean =
        prevVersion in 1..SMART_DNS_TABLE_UPTO
}
