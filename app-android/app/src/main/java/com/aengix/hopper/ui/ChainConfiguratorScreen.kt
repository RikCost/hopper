package com.aengix.hopper.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aengix.hopper.model.AppState
import com.aengix.hopper.model.HopChain
import com.aengix.hopper.vpn.VpnController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChainConfiguratorScreen(
    vpn: VpnController,
    onBack: () -> Unit,
    onChainDetail: (String) -> Unit,
    onOpenServers: () -> Unit,
    onOpenKeys: () -> Unit,
    onRequestCameraPermission: (onGranted: () -> Unit) -> Unit,
) {
    val state by vpn.state.collectAsState()
    var chainToDelete by remember { mutableStateOf<HopChain?>(null) }
    var importMode by remember { mutableStateOf<HopperImportMode?>(null) }
    var showScanner by remember { mutableStateOf(false) }

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
                title = { Text("Chains") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Button(
                    onClick = onOpenServers,
                    modifier = Modifier
                        .fillMaxWidth()
                        .dPadActivate(onClick = onOpenServers),
                ) {
                    Text("Server library")
                }
            }
            item {
                Button(
                    onClick = onOpenKeys,
                    modifier = Modifier
                        .fillMaxWidth()
                        .dPadActivate(onClick = onOpenKeys),
                ) {
                    Text("Keys library")
                }
            }
            item {
                Text(
                    "Manage servers and deploy keys, or scan / import a shared chain, server, or key below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Text("Chains", style = MaterialTheme.typography.titleMedium)
            }
            if (state.chains.isEmpty()) {
                item {
                    Text(
                        "Create a chain and add servers in entry → exit order.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(state.chains, key = { it.id }) { chain ->
                    ChainLibraryRow(
                        chain = chain,
                        summary = chainSummary(state, chain),
                        selected = state.selectedChainID == chain.id,
                        onClick = { onChainDetail(chain.id) },
                        onUse = { vpn.selectChain(chain.id) },
                        onDelete = { chainToDelete = chain },
                    )
                }
            }
            item {
                Button(
                    onClick = {
                        val id = vpn.addChain()
                        vpn.selectChain(id)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .dPadActivate {
                            val id = vpn.addChain()
                            vpn.selectChain(id)
                        },
                ) {
                    Text("New chain")
                }
            }
            item {
                OutlinedButton(
                    onClick = {
                        onRequestCameraPermission { showScanner = true }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .dPadActivate { onRequestCameraPermission { showScanner = true } },
                ) {
                    Text("Scan QR")
                }
            }
            item {
                HopperImportButtons(onSelect = { importMode = it })
            }
        }
    }

    chainToDelete?.let { chain ->
        HopperConfirmDialog(
            title = "Delete chain?",
            text = "Remove ${chain.displayName} from the library. Servers in your library are kept.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                vpn.deleteChains(setOf(chain.id))
                chainToDelete = null
            },
            onDismiss = { chainToDelete = null },
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
private fun ChainLibraryRow(
    chain: HopChain,
    summary: String,
    selected: Boolean,
    onClick: () -> Unit,
    onUse: () -> Unit,
    onDelete: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            CardDefaults.outlinedCardBorder()
        },
        colors = if (selected) {
            CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
            )
        } else {
            CardDefaults.outlinedCardColors()
        },
    ) {
        ListItem(
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selected) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Active chain",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(20.dp)
                                .padding(end = 8.dp),
                        )
                    }
                    Text(chain.displayName)
                }
            },
            supportingContent = { Text(summary) },
            modifier = Modifier.dPadClickable(onClick = onClick),
            trailingContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (!selected) {
                        TextButton(
                            onClick = onUse,
                            modifier = Modifier.dPadActivate(onClick = onUse),
                        ) {
                            Text("Use")
                        }
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.dPadActivate(onClick = onDelete),
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete chain",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
        )
    }
}

private fun chainSummary(state: AppState, chain: HopChain): String {
    val hops = state.resolveHops(chain)
    return when {
        hops.isEmpty() -> "No servers"
        hops.size == 1 -> "1 hop — ${hops[0].displayName}"
        else -> "${hops.size} hops — ${hops.first().displayName} → ${hops.last().displayName}"
    }
}
