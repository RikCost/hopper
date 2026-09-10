package com.aengix.hopper.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aengix.hopper.data.HopperConf

@Composable
fun ImportConfDialog(
    onDismiss: () -> Unit,
    onImport: (HopperConf.Payload) -> Unit,
    onError: (String) -> Unit,
    initialTab: Int = 0,
) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(initialTab) }
    var jsonText by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw HopperConf.ConfError.Empty
        }.onSuccess { bytes ->
            pendingBytes = bytes
            errorMessage = null
            if (!HopperConf.isHopperConfFile(bytes)) {
                // Plain JSON — import immediately.
                runCatching {
                    HopperConf.parsePayloadJson(String(bytes, Charsets.UTF_8))
                }.onSuccess(onImport).onFailure {
                    errorMessage = it.message
                    onError(it.message ?: "Import failed")
                }
            }
        }.onFailure {
            errorMessage = it.message
            onError(it.message ?: "Could not read file")
        }
    }

    HopperAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import") },
        text = {
            Column {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("File") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Paste") })
                }
                if (tab == 0) {
                    TextButton(
                        onClick = {
                            picker.launch(
                                arrayOf(
                                    HopperConf.MIME_TYPE,
                                    "application/octet-stream",
                                    "application/json",
                                    "text/plain",
                                    "*/*",
                                ),
                            )
                        },
                        modifier = Modifier.dPadActivate {
                            picker.launch(
                                arrayOf(
                                    HopperConf.MIME_TYPE,
                                    "application/octet-stream",
                                    "application/json",
                                    "text/plain",
                                    "*/*",
                                ),
                            )
                        },
                    ) {
                        Text(if (pendingBytes != null) "Choose another file…" else "Choose .hopperconf…")
                    }
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password (optional)") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                    Text(
                        "Tried automatically with the default password first; enter a custom password only if needed.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                } else {
                    OutlinedTextField(
                        value = jsonText,
                        onValueChange = { jsonText = it },
                        label = { Text("Hop / chain JSON") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .padding(top = 8.dp),
                    )
                }
                errorMessage?.let {
                    Text(
                        it,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            DialogActionButton(
                text = "Import",
                autoFocus = true,
                enabled = when (tab) {
                    0 -> pendingBytes != null
                    else -> jsonText.trim().isNotEmpty()
                },
                onClick = {
                    errorMessage = null
                    runCatching {
                        when (tab) {
                            0 -> {
                                val bytes = pendingBytes ?: throw HopperConf.ConfError.Empty
                                if (HopperConf.isHopperConfFile(bytes)) {
                                    HopperConf.decryptFile(bytes, password)
                                } else {
                                    HopperConf.parsePayloadJson(String(bytes, Charsets.UTF_8))
                                }
                            }
                            else -> HopperConf.parsePayloadJson(jsonText)
                        }
                    }.onSuccess(onImport).onFailure { error ->
                        errorMessage = error.message
                        onError(error.message ?: "Import failed")
                    }
                },
            )
        },
        dismissButton = {
            DialogActionButton(text = "Cancel", onClick = onDismiss)
        },
    )
}

@Composable
fun HopperConfPasswordDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    HopperAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import .hopperconf") },
        text = {
            Column {
                Text("Tried automatically with the default password first; enter a custom password only if that fails.")
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password (optional)") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            DialogActionButton(text = "Import", autoFocus = true, onClick = { onImport(password) })
        },
        dismissButton = {
            DialogActionButton(text = "Cancel", onClick = onDismiss)
        },
    )
}
