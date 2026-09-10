package com.aengix.hopper.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.aengix.hopper.model.HopConstants
import com.aengix.hopper.model.HopNodeProfile
import com.aengix.hopper.vpn.VpnController
import com.aengix.hopper.vpn.VpnStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vpn: VpnController,
    onConfigureChains: () -> Unit,
    onChainDetail: (String) -> Unit,
    onRequestVpnConnect: (restartHopperd: Boolean) -> Unit,
    onRequestCameraPermission: (onGranted: () -> Unit) -> Unit,
) {
    val state by vpn.state.collectAsState()
    val vpnStatus by vpn.vpnStatus.collectAsState()
    val provisionStatus by vpn.provisionStatus.collectAsState()
    val errorMessage by vpn.errorMessage.collectAsState()
    val serverUpdatePrompt by vpn.serverUpdatePrompt.collectAsState()
    val pendingHopperConf by vpn.pendingHopperConfBytes.collectAsState()
    var showConnectOptions by remember { mutableStateOf(false) }
    var shareMode by remember { mutableStateOf<HopperExportMode?>(null) }
    var importMode by remember { mutableStateOf<HopperImportMode?>(null) }
    var showScanner by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val hops = state.activeHops

    shareMode?.let { mode ->
        ChainExportScreen(
            chainName = state.selectedChain?.name.orEmpty(),
            hops = hops,
            mode = mode,
            onBack = { shareMode = null },
        )
        return
    }

    if (importMode == HopperImportMode.Remote) {
        HopperLanReceiveScreen(
            vpn = vpn,
            onBack = { importMode = null },
        )
        return
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
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("${HopConstants.APP_DISPLAY_NAME} ${HopConstants.appVersion(context)}") })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("Chain", style = MaterialTheme.typography.titleMedium)
            val chain = state.selectedChain
            if (chain != null) {
                ChainPicker(
                    chains = state.chains,
                    selectedId = state.selectedChainID,
                    onSelect = vpn::selectChain,
                )
                Text(
                    text = chainRouteSummary(hops),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .dPadClickable { onChainDetail(chain.id) },
                )
            } else {
                Text(
                    "No chain selected",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedButton(
                onClick = onConfigureChains,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .dPadActivate(onClick = onConfigureChains),
            ) {
                Text("Configure chains")
            }

            Text("Connect", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            val entry = state.entryHop
            if (entry != null) {
                Text(entry.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${entry.trimmedUser}@${entry.trimmedHost}:${entry.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Add servers and build a chain (entry → exit).",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val connected = vpnStatus == VpnStatus.Connected
            val busy = vpnStatus == VpnStatus.Connecting || vpnStatus == VpnStatus.Disconnecting
            Text(
                statusLabel(vpnStatus),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            provisionStatus?.let {
                Text(it, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 4.dp))
            }
            errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
            }
            val onConnectClick = {
                if (connected || busy) {
                    vpn.disconnect()
                } else {
                    showConnectOptions = true
                }
            }
            Button(
                onClick = onConnectClick,
                enabled = !busy && entry != null && provisionStatus == null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .dPadActivate(
                        enabled = !busy && entry != null && provisionStatus == null,
                        onClick = onConnectClick,
                    ),
            ) {
                Text(if (connected) "Disconnect" else "Connect")
            }
            HopperExportButtonsRow(
                enabled = hops.isNotEmpty(),
                modifier = Modifier.padding(top = 8.dp),
                onSelect = { shareMode = it },
            )

            HopperImportButtons(
                modifier = Modifier.padding(top = 8.dp),
                onSelect = { importMode = it },
            )
            OutlinedButton(
                onClick = {
                    onRequestCameraPermission { showScanner = true }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .dPadActivate { onRequestCameraPermission { showScanner = true } },
            ) {
                Text("Scan QR")
            }

            if (hops.isNotEmpty()) {
                Text("Route", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                hops.forEachIndexed { index, hop ->
                    ListItem(
                        headlineContent = { Text(chainRole(index, hops.size, hop)) },
                        supportingContent = {
                            Text("${hop.trimmedUser}@${hop.trimmedHost}:${hop.port}")
                        },
                    )
                }
            }
        }
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

    if (showConnectOptions) {
        HopperOptionsDialog(
            title = "Connect to chain",
            text = "Restart hopperd on all nodes if you've changed the chain or are having connection issues on the servers. Leave off for faster reconnects.",
            onDismiss = { showConnectOptions = false },
            actions = listOf(
                HopperDialogAction("Connect") {
                    showConnectOptions = false
                    onRequestVpnConnect(false)
                },
                HopperDialogAction("Connect & restart hopperd") {
                    showConnectOptions = false
                    onRequestVpnConnect(true)
                },
                HopperDialogAction("Cancel") { showConnectOptions = false },
            ),
        )
    }

    serverUpdatePrompt?.let { prompt ->
        HopperConfirmDialog(
            title = "Update servers?",
            text = "Server software is older than app v${prompt.targetVersion}. Update ${prompt.hops.size} hop(s) before connecting?",
            confirmLabel = "Update",
            onConfirm = { vpn.confirmServerUpdate() },
            onDismiss = { vpn.cancelServerUpdate() },
        )
    }

    if (pendingHopperConf != null) {
        HopperConfPasswordDialog(
            onDismiss = { vpn.clearPendingHopperConf() },
            onImport = { password -> vpn.importPendingHopperConf(password) },
        )
    }
}

@Composable
private fun ChainPicker(
    chains: List<com.aengix.hopper.model.HopChain>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    chains.forEach { chain ->
        val isSelected = chain.id == selectedId
        ListItem(
            headlineContent = { Text(chain.displayName) },
            leadingContent = {
                RadioButton(
                    selected = isSelected,
                    onClick = { onSelect(chain.id) },
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .dPadClickable { onSelect(chain.id) },
        )
    }
}

private fun chainRouteSummary(hops: List<HopNodeProfile>): String = when {
    hops.isEmpty() -> "No servers in chain"
    hops.size == 1 -> hops[0].displayName
    else -> "${hops.first().displayName} → ${hops.last().displayName} (${hops.size} hops)"
}

private fun chainRole(index: Int, total: Int, hop: HopNodeProfile): String {
    val role = when {
        total == 1 -> "Exit"
        index == 0 -> "Entry"
        index == total - 1 -> "Exit"
        else -> "Relay"
    }
    return "${index + 1}. $role — ${hop.displayName}"
}

private fun statusLabel(status: VpnStatus): String = when (status) {
    VpnStatus.Connected -> "Connected"
    VpnStatus.Connecting -> "Connecting…"
    VpnStatus.Disconnecting -> "Disconnecting…"
    VpnStatus.Disconnected -> "Disconnected"
}
