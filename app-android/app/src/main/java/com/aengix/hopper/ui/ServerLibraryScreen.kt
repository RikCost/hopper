package com.aengix.hopper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.unit.dp
import com.aengix.hopper.model.HopNodeProfile
import com.aengix.hopper.vpn.VpnController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerLibraryScreen(
    vpn: VpnController,
    onBack: () -> Unit,
    onServerDetail: (String) -> Unit,
    onRequestCameraPermission: (onGranted: () -> Unit) -> Unit,
    chainId: String? = null,
) {
    val state by vpn.state.collectAsState()
    val isPickMode = chainId != null
    val chain = chainId?.let { id -> state.chains.firstOrNull { it.id == id } }
    val displayedServers = when {
        chain != null -> state.servers.filter { server -> server.id !in chain.hopIDs }
        isPickMode -> emptyList()
        else -> state.servers
    }
    var importMode by remember { mutableStateOf<HopperImportMode?>(null) }
    var showScanner by remember { mutableStateOf(false) }
    var showDeploy by remember { mutableStateOf(false) }
    var showAddManual by remember { mutableStateOf(false) }
    var serverToDelete by remember { mutableStateOf<HopNodeProfile?>(null) }

    if (importMode == HopperImportMode.Remote) {
        HopperLanReceiveScreen(
            vpn = vpn,
            onBack = { importMode = null },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isPickMode) "Add server" else "Servers") },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.dPadActivate(onClick = onBack),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { showDeploy = true },
                        modifier = Modifier.dPadActivate { showDeploy = true },
                    ) {
                        Text("Deploy")
                    }
                    TextButton(
                        onClick = {
                            onRequestCameraPermission { showScanner = true }
                        },
                        modifier = Modifier.dPadActivate {
                            onRequestCameraPermission { showScanner = true }
                        },
                    ) {
                        Text("Scan QR")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            HopperImportButtons(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                onAddManually = { showAddManual = true },
                onSelect = { importMode = it },
            )
            if (displayedServers.isEmpty()) {
                Text(
                    if (isPickMode) {
                        "No servers — add one manually, deploy, scan a QR code, or import a .hopperconf file, then tap to add to this chain."
                    } else {
                        "No servers — add one manually, deploy, scan a QR code, or import a .hopperconf file."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(displayedServers, key = { it.id }) { server ->
                        ServerLibraryRow(
                            server = server,
                            onClick = {
                                val pickChainId = chainId
                                if (pickChainId != null) {
                                    vpn.addServerToChain(pickChainId, server.id)
                                    onBack()
                                } else {
                                    onServerDetail(server.id)
                                }
                            },
                            onDelete = if (isPickMode) null else {
                                { serverToDelete = server }
                            },
                        )
                    }
                }
            }
        }
    }

    serverToDelete?.let { server ->
        HopperConfirmDialog(
            title = "Delete server?",
            text = "Remove ${server.displayName} from the library. Chains that use this server will drop it.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                vpn.deleteServers(setOf(server.id))
                serverToDelete = null
            },
            onDismiss = { serverToDelete = null },
        )
    }

    if (showDeploy) {
        DeployServerDialog(
            vpn = vpn,
            onDismiss = { showDeploy = false },
            onError = vpn::setError,
        )
    }

    if (showAddManual) {
        AddServerDialog(
            vpn = vpn,
            onDismiss = { showAddManual = false },
        )
    }

    if (importMode == HopperImportMode.File || importMode == HopperImportMode.Paste) {
        ImportConfDialog(
            initialTab = if (importMode == HopperImportMode.Paste) 1 else 0,
            onDismiss = { importMode = null },
            onImport = { payload ->
                vpn.importPayload(payload)
                importMode = null
            },
            onError = vpn::setError,
        )
    }

    if (showScanner) {
        QRScannerScreen(
            onDismiss = { showScanner = false },
            onScan = { payload ->
                if (vpn.handleScannedQr(payload)) {
                    showScanner = false
                }
            },
        )
    }
}

@Composable
private fun ServerLibraryRow(
    server: HopNodeProfile,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(server.displayName) },
            supportingContent = {
                Text("${server.trimmedUser}@${server.trimmedHost}:${server.port}")
            },
            modifier = Modifier.dPadClickable(onClick = onClick),
            trailingContent = onDelete?.let { delete ->
                {
                    IconButton(
                        onClick = delete,
                        modifier = Modifier.dPadActivate(onClick = delete),
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete server",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
        )
    }
}
