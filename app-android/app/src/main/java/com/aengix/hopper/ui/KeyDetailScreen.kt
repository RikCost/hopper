package com.aengix.hopper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.aengix.hopper.ssh.SSHKeyGenerator
import com.aengix.hopper.vpn.VpnController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyDetailScreen(
    vpn: VpnController,
    keyId: String,
    onBack: () -> Unit,
) {
    val state by vpn.state.collectAsState()
    val key = state.deployKey(keyId)
    var name by remember(key?.name) { mutableStateOf(key?.name.orEmpty()) }
    var showPrivate by remember { mutableStateOf(false) }
    var exportMode by remember { mutableStateOf<HopperExportMode?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showDeleteBlocked by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf<String?>(null) }
    val canDelete = key?.canDelete(state.servers) == true
    val context = LocalContext.current
    val publicLine = key?.let {
        runCatching { SSHKeyGenerator.publicKeyLine(it.privateKey, it.trimmedName) }.getOrNull()
    }

    exportMode?.let { mode ->
        if (key != null) {
            KeyExportScreen(
                key = key,
                mode = mode,
                onBack = { exportMode = null },
            )
            return
        }
    }

    if (showDeleteBlocked && key != null) {
        val names = key.assignedServers(state.servers).joinToString(", ") { it.displayName }
        HopperConfirmDialog(
            title = "Can't delete key",
            text = "This key is assigned to $names. Remove those servers from the library before deleting the key.",
            confirmLabel = "OK",
            dismissLabel = null,
            onConfirm = { showDeleteBlocked = false },
            onDismiss = { showDeleteBlocked = false },
        )
    }

    if (showDeleteConfirm && key != null) {
        HopperConfirmDialog(
            title = "Delete key?",
            text = "Remove ${key.displayName} from the library. Servers keep their own hop keys.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                vpn.deleteDeployKeys(setOf(keyId))
                showDeleteConfirm = false
                onBack()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(key?.displayName ?: "Key") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (key == null) {
            Text("Key not found", modifier = Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    vpn.renameDeployKey(keyId, it)
                },
                label = { Text("Key name") },
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Public key", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Text(
                publicLine ?: "Could not derive public key.",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            if (publicLine != null) {
                TextButton(
                    onClick = {
                        copyToClipboard(context, publicLine)
                        copied = "Public key copied"
                    },
                    modifier = Modifier.dPadActivate {
                        copyToClipboard(context, publicLine)
                        copied = "Public key copied"
                    },
                ) { Text("Copy public key") }
            }

            Text("Private key", style = MaterialTheme.typography.titleSmall)
            Text(
                if (showPrivate) key.privateKey else "Hidden",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            TextButton(
                onClick = { showPrivate = !showPrivate },
                modifier = Modifier.dPadActivate { showPrivate = !showPrivate },
            ) {
                Text(if (showPrivate) "Hide private key" else "Show private key")
            }
            TextButton(
                onClick = {
                    copyToClipboard(context, key.privateKey)
                    copied = "Private key copied"
                },
                modifier = Modifier.dPadActivate {
                    copyToClipboard(context, key.privateKey)
                    copied = "Private key copied"
                },
            ) { Text("Copy private key") }

            Text("Assigned servers", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            val labels = key.assignments.map { it.label(state.servers) }.filter { it.isNotBlank() }
            if (labels.isEmpty()) {
                Text(
                    "No assigned servers yet. Deploy with this key to record user@host.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                labels.forEach { Text(it) }
            }

            copied?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            HopperExportButtonsRow(
                modifier = Modifier.padding(top = 16.dp),
                onSelect = { exportMode = it },
            )
            OutlinedButton(
                onClick = {
                    if (canDelete) showDeleteConfirm = true else showDeleteBlocked = true
                },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .dPadActivate {
                        if (canDelete) showDeleteConfirm = true else showDeleteBlocked = true
                    },
            ) {
                Text("Delete")
            }
            if (!canDelete) {
                Text(
                    "Remove assigned servers from the library before deleting this key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
