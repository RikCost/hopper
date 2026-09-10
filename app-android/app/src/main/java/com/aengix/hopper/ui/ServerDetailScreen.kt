package com.aengix.hopper.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aengix.hopper.vpn.VpnController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerDetailScreen(
    vpn: VpnController,
    serverId: String,
    onBack: () -> Unit,
) {
    val state by vpn.state.collectAsState()
    val server = state.server(serverId)
    var name by remember(server?.name) { mutableStateOf(server?.name.orEmpty()) }
    var exportMode by remember { mutableStateOf<HopperExportMode?>(null) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    exportMode?.let { mode ->
        if (server != null) {
            ServerExportScreen(
                server = server,
                mode = mode,
                onBack = { exportMode = null },
            )
            return
        }
    }

    if (showDeleteConfirm && server != null) {
        HopperConfirmDialog(
            title = "Delete server?",
            text = "Remove ${server.displayName} from the library. Chains that use this server will drop it.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                vpn.deleteServers(setOf(serverId))
                showDeleteConfirm = false
                onBack()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(server?.displayName ?: "Server") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (server == null) {
            Text("Server not found", modifier = Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }

        Column(modifier = Modifier.padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    vpn.renameServer(serverId, it)
                },
                label = { Text("Server name") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Host: ${server.trimmedHost}", modifier = Modifier.padding(top = 16.dp))
            Text("Port: ${server.port}")
            Text("User: ${server.trimmedUser}")
            if (server.installDir.trim().isNotEmpty()) {
                Text("Install path: ${server.installDir}")
            }
            HopperExportButtonsRow(
                modifier = Modifier.padding(top = 24.dp),
                onSelect = { exportMode = it },
            )
            OutlinedButton(
                onClick = { showDeleteConfirm = true },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .dPadActivate { showDeleteConfirm = true },
            ) {
                Text("Delete")
            }
        }
    }
}
