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
package com.celzero.bravedns.ui.compose.blocklists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.service.RethinkServers
import com.celzero.bravedns.ui.compose.design.RethinkSpacing
import com.celzero.bravedns.ui.compose.design.RethinkStatus
import com.celzero.bravedns.ui.compose.design.SettingRow
import com.celzero.bravedns.ui.compose.design.SettingTrailing
import com.celzero.bravedns.ui.compose.design.SettingsSection
import com.celzero.bravedns.ui.compose.design.StatusChip
import com.celzero.bravedns.ui.compose.design.SwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Result of the dialog's Test button. */
sealed interface ServerTest {
    data object Idle : ServerTest
    data object Running : ServerTest
    data object Ok : ServerTest
    data class Failed(val reason: String) : ServerTest
}

/**
 * "Rethink DNS server" row: default (rethinkdns.com) or the user's self-hosted serverless-dns.
 * Tapping it opens [RethinkServerDialog]. Reads and writes through [AppConfig].
 */
@Composable
fun RethinkServerSetting(appConfig: AppConfig, modifier: Modifier = Modifier) {
    var cfg by remember { mutableStateOf(appConfig.getRethinkServerConfig()) }
    var editing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val value = if (cfg.isCustom) {
        stringResource(if (cfg.useDot) R.string.rdns_server_custom_dot else R.string.rdns_server_custom_doh, cfg.customHost)
    } else {
        stringResource(R.string.rdns_server_default, RethinkServers.DEFAULT_HOST_LABEL)
    }
    SettingsSection(modifier.padding(vertical = RethinkSpacing.sm)) {
        SettingRow(
            title = stringResource(R.string.rdns_server_title),
            supportingText = value,
            modifier = Modifier.testTag("rdns-server-row"),
            onClick = { editing = true },
        ) { SettingTrailing.Chevron() }
    }

    if (editing) {
        RethinkServerDialog(
            initial = cfg,
            onDismiss = { editing = false },
            onSave = { base, dot, secret ->
                editing = false
                scope.launch {
                    withContext(Dispatchers.IO) { appConfig.setCustomRethinkServer(base, dot, secret) }
                    cfg = appConfig.getRethinkServerConfig()
                }
            },
        )
    }
}

@Composable
fun RethinkServerDialog(
    initial: RethinkServers.Config,
    onDismiss: () -> Unit,
    onSave: (base: String, useDot: Boolean, secret: String) -> Unit,
    test: suspend (base: String, secret: String) -> String? = { b, k ->
        withContext(Dispatchers.IO) { RethinkServers.testServer(b, k) }
    },
) {
    var input by remember { mutableStateOf(initial.customBase) }
    var dot by remember { mutableStateOf(initial.customDot) }
    var secret by remember { mutableStateOf(initial.secret) }
    val clipboard = LocalClipboardManager.current
    var result by remember { mutableStateOf<ServerTest>(ServerTest.Idle) }
    val scope = rememberCoroutineScope()

    val normalized = RethinkServers.normalize(input)
    val invalid = normalized == null
    val host = normalized?.let { RethinkServers.hostOf(it) }.orEmpty()
    val secretInvalid = secret.isNotEmpty() && !RethinkServers.isValidSecret(secret)
    val accessKey = if (host.isNotEmpty() && secret.isNotEmpty() && !secretInvalid) {
        RethinkServers.accessKey(secret, host)
    } else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rdns_server_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(RethinkSpacing.md),
            ) {
                Text(
                    stringResource(R.string.rdns_server_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; result = ServerTest.Idle },
                    modifier = Modifier.fillMaxWidth().testTag("rdns-server-input"),
                    label = { Text(stringResource(R.string.rdns_server_url_label)) },
                    placeholder = { Text(stringResource(R.string.rdns_server_url_hint)) },
                    singleLine = true,
                    isError = invalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    supportingText = {
                        Text(stringResource(if (invalid) R.string.rdns_server_url_invalid else R.string.rdns_server_url_help))
                    },
                )
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it.trim(); result = ServerTest.Idle },
                    modifier = Modifier.fillMaxWidth().testTag("rdns-server-secret"),
                    label = { Text(stringResource(R.string.rdns_server_secret_label)) },
                    singleLine = true,
                    enabled = host.isNotEmpty(),
                    isError = secretInvalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    trailingIcon = {
                        TextButton(
                            onClick = { secret = RethinkServers.generateSecret(); result = ServerTest.Idle },
                            enabled = host.isNotEmpty(),
                            modifier = Modifier.testTag("rdns-server-generate"),
                        ) { Text(stringResource(R.string.rdns_server_secret_generate)) }
                    },
                    supportingText = {
                        Text(stringResource(if (secretInvalid) R.string.rdns_server_secret_invalid else R.string.rdns_server_secret_help))
                    },
                )
                if (accessKey != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(RethinkSpacing.xs)) {
                        Text(
                            stringResource(R.string.rdns_server_access_key_label),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                accessKey,
                                modifier = Modifier.weight(1f).testTag("rdns-server-access-key"),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                            TextButton(
                                onClick = { clipboard.setText(AnnotatedString(accessKey)) },
                                modifier = Modifier.testTag("rdns-server-copy-key"),
                            ) { Text(stringResource(R.string.rdns_server_copy)) }
                        }
                    }
                }
                SwitchRow(
                    title = stringResource(R.string.rdns_server_dot),
                    checked = dot && host.isNotEmpty(),
                    onCheckedChange = { dot = it },
                    supportingText = stringResource(R.string.rdns_server_dot_desc, host.ifEmpty { "dns.example.com" }),
                    enabled = host.isNotEmpty(),
                    modifier = Modifier.testTag("rdns-server-dot"),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RethinkSpacing.md),
                ) {
                    OutlinedButton(
                        onClick = {
                            val base = normalized ?: return@OutlinedButton
                            result = ServerTest.Running
                            scope.launch {
                                val err = test(base, if (secretInvalid) "" else secret)
                                result = if (err == null) ServerTest.Ok else ServerTest.Failed(err)
                            }
                        },
                        enabled = host.isNotEmpty() && !secretInvalid && result != ServerTest.Running,
                        modifier = Modifier.testTag("rdns-server-test"),
                    ) { Text(stringResource(R.string.rdns_server_test)) }
                    when (val r = result) {
                        ServerTest.Idle -> Unit
                        ServerTest.Running -> StatusChip(RethinkStatus.Waiting, label = stringResource(R.string.rdns_server_testing))
                        ServerTest.Ok -> StatusChip(RethinkStatus.Active, label = stringResource(R.string.rdns_server_test_ok))
                        is ServerTest.Failed -> StatusChip(RethinkStatus.Error, label = stringResource(R.string.rdns_server_test_failed, r.reason).take(60))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val base = normalized ?: return@TextButton
                    // a secret only applies to a custom server
                    onSave(base, dot && host.isNotEmpty(), if (base.isEmpty()) "" else secret)
                },
                enabled = !invalid && !secretInvalid,
                modifier = Modifier.testTag("rdns-server-save"),
            ) { Text(stringResource(R.string.lbl_save)) }
        },
        dismissButton = {
            Row {
                if (initial.isCustom) {
                    TextButton(onClick = { onSave("", false, "") }, modifier = Modifier.testTag("rdns-server-reset")) {
                        Text(stringResource(R.string.rdns_server_reset))
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.lbl_cancel)) }
            }
        },
    )
}
