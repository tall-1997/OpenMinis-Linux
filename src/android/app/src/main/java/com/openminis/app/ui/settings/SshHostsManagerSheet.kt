package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ssh.SshAuthType
import com.openminis.app.ssh.SshConfigStore
import com.openminis.app.ssh.SshCredentials
import com.openminis.app.ssh.SshEngine
import com.openminis.app.ssh.SshFailure
import com.openminis.app.ssh.SshHostConfig
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [T-ssh-workspace] (#32) SSH host management sheet, hosted inline by
 * SettingsScreen (no navigation route — AppNavigation is pinned at its
 * architecture ceiling).
 *
 * Covers the host lifecycle end to end:
 *  - list configured hosts (metadata only — secrets never render here);
 *  - add a host: name / hostname / port / username + password or pasted PEM
 *    key (+ optional passphrase). Credentials go straight into the
 *    keystore-backed [SshConfigStore]; the form state clears on save and the
 *    password fields are masked;
 *  - connection test: a REAL connect through [SshEngine] (TOFU pins the host
 *    key on first success; the result surfaces the pinned fingerprint so the
 *    user can verify it out-of-band) — success and failure both render the
 *    backend's own message, which names the exact cause (auth / network /
 *    changed host key) without ever echoing a secret;
 *  - remove host (drops stored credentials + pin) and forget-host-key
 *    (re-pin on next connect).
 *
 * Per-session binding (which chat uses which host) lives in the chat-side
 * SshWorkspaceBindingSheet; this sheet is the host registry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SshHostsManagerSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SshEngine.backend(context).store }
    var hosts by remember { mutableStateOf(store.list()) }
    var showAddForm by rememberSaveable { mutableStateOf(false) }

    // Per-host one-line test result (rendered under the host row).
    var testingHostId by remember { mutableStateOf<String?>(null) }
    var testResultHostId by remember { mutableStateOf<String?>(null) }
    var testResultOk by remember { mutableStateOf(false) }
    var testResultText by remember { mutableStateOf("") }

    fun refresh() {
        hosts = store.list()
    }

    fun runTest(host: SshHostConfig) {
        testingHostId = host.id
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { SshEngine.backend(context).test(host.id) }
                    .fold(onSuccess = { true to it }, onFailure = { false to (it.message ?: it.javaClass.simpleName) })
            }
            testingHostId = null
            testResultHostId = host.id
            testResultOk = outcome.first
            testResultText = outcome.second
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = stringResource(R.string.ssh_hosts_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.ssh_hosts_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = ChatColors.secondaryText,
            )
            Spacer(Modifier.height(12.dp))

            hosts.forEach { host ->
                HostRow(
                    host = host,
                    testing = testingHostId == host.id,
                    resultShown = testResultHostId == host.id,
                    resultOk = testResultOk,
                    resultText = testResultText,
                    onTest = { runTest(host) },
                    onRemove = {
                        SshEngine.backend(context).removeHost(host.id)
                        refresh()
                    },
                    onForgetKey = {
                        SshEngine.backend(context).forgetHostKey(host.id)
                        refresh()
                    },
                )
            }
            if (hosts.isEmpty()) {
                Text(
                    text = stringResource(R.string.ssh_hosts_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = ChatColors.secondaryText,
                )
                Spacer(Modifier.height(8.dp))
            }

            TextButton(onClick = { showAddForm = !showAddForm }) {
                Text(
                    stringResource(
                        if (showAddForm) R.string.ssh_hosts_add_cancel
                        else R.string.ssh_hosts_add,
                    ),
                )
            }

            if (showAddForm) {
                AddHostForm(
                    onSaved = {
                        showAddForm = false
                        refresh()
                    },
                )
            }
        }
    }
}

@Composable
private fun HostRow(
    host: SshHostConfig,
    testing: Boolean,
    resultShown: Boolean,
    resultOk: Boolean,
    resultText: String,
    onTest: () -> Unit,
    onRemove: () -> Unit,
    onForgetKey: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(host.name, style = MaterialTheme.typography.bodyMedium)
        Text(
            host.label + " · " + host.authType.wireName,
            style = MaterialTheme.typography.bodySmall,
            color = ChatColors.secondaryText,
        )
        if (!host.hostKeyFingerprint.isNullOrBlank()) {
            Text(
                stringResource(R.string.ssh_hosts_pinned, host.hostKeyFingerprint.take(40)),
                style = MaterialTheme.typography.bodySmall,
                color = ChatColors.secondaryText,
            )
        }
        Row {
            TextButton(onClick = onTest, enabled = !testing) {
                Text(stringResource(if (testing) R.string.ssh_hosts_testing else R.string.ssh_hosts_test))
            }
            TextButton(onClick = onForgetKey) {
                Text(stringResource(R.string.ssh_hosts_forget_key))
            }
            TextButton(onClick = onRemove) {
                Text(
                    stringResource(R.string.ssh_hosts_remove),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (resultShown) {
            Text(
                text = stringResource(
                    if (resultOk) R.string.ssh_hosts_test_ok else R.string.ssh_hosts_test_failed,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (resultOk) ChatColors.link else MaterialTheme.colorScheme.error,
            )
            Text(
                text = resultText,
                style = MaterialTheme.typography.bodySmall,
                color = ChatColors.secondaryText,
            )
        }
    }
}

@Composable
private fun AddHostForm(onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SshEngine.backend(context).store }
    var name by rememberSaveable { mutableStateOf("") }
    var hostname by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("22") }
    var username by rememberSaveable { mutableStateOf("") }
    var authPassword by rememberSaveable { mutableStateOf(true) }
    var password by rememberSaveable { mutableStateOf("") }
    var privateKey by rememberSaveable { mutableStateOf("") }
    var passphrase by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.ssh_field_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = hostname,
            onValueChange = { hostname = it },
            label = { Text(stringResource(R.string.ssh_field_hostname)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                label = { Text(stringResource(R.string.ssh_field_port)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(0.35f),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.ssh_field_username)) },
                singleLine = true,
                modifier = Modifier.weight(0.65f),
            )
        }
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = authPassword,
                onClick = { authPassword = true },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) {
                Text(stringResource(R.string.ssh_auth_password))
            }
            SegmentedButton(
                selected = !authPassword,
                onClick = { authPassword = false },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) {
                Text(stringResource(R.string.ssh_auth_key))
            }
        }
        if (authPassword) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.ssh_field_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            OutlinedTextField(
                value = privateKey,
                onValueChange = { privateKey = it },
                label = { Text(stringResource(R.string.ssh_field_private_key)) },
                minLines = 3,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text(stringResource(R.string.ssh_field_passphrase)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row {
            MinisButton(
                onClick = {
                    // The id must exist BEFORE validation (validate() rejects a
                    // blank id), so the slug is generated here from the name.
                    // store.put() re-runs the full validation and throws
                    // SshFailure.Config on bad input — surfaced by onFailure.
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) {
                            runCatching {
                                val taken = store.list().map { h -> h.id }.toSet()
                                val config = SshHostConfig(
                                    id = SshConfigStore.newId(name.trim(), taken),
                                    name = name.trim(),
                                    host = hostname.trim(),
                                    port = port.toIntOrNull()?.coerceIn(1, 65535) ?: 22,
                                    username = username.trim(),
                                    authType = if (authPassword) SshAuthType.PASSWORD else SshAuthType.PRIVATE_KEY,
                                    createdAt = System.currentTimeMillis(),
                                )
                                val creds = when {
                                    authPassword && password.isNotEmpty() ->
                                        SshCredentials(password = password)
                                    !authPassword && privateKey.contains("PRIVATE KEY") ->
                                        SshCredentials(
                                            privateKeyPem = privateKey,
                                            passphrase = passphrase.takeIf { it.isNotEmpty() },
                                        )
                                    else -> null
                                }
                                store.put(config, creds)
                            }
                        }
                        saved.onSuccess {
                            // Form state clears on success; secrets never persist
                            // in the composition beyond this point.
                            name = ""; hostname = ""; port = "22"; username = ""
                            password = ""; privateKey = ""; passphrase = ""
                            error = null
                            onSaved()
                        }.onFailure { e ->
                            error = (e as? SshFailure)?.message ?: e.message ?: e.javaClass.simpleName
                        }
                    }
                },
                enabled = name.isNotBlank() && hostname.isNotBlank() && username.isNotBlank(),
            ) {
                Text(stringResource(R.string.ssh_save))
            }
        }
    }
}
