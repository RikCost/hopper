package com.aengix.hopper.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import com.aengix.hopper.model.HopNodeProfile
import com.aengix.hopper.vpn.VpnController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChainDetailScreen(
    vpn: VpnController,
    chainId: String,
    onBack: () -> Unit,
    onAddServer: () -> Unit,
) {
    val state by vpn.state.collectAsState()
    val chain = state.chains.firstOrNull { it.id == chainId }
    val hops = chain?.let { state.resolveHops(it) }.orEmpty()
    var name by remember(chain?.name) { mutableStateOf(chain?.name.orEmpty()) }
    var exportMode by remember { mutableStateOf<HopperExportMode?>(null) }

    exportMode?.let { mode ->
        ChainExportScreen(
            chainName = chain?.name.orEmpty(),
            hops = hops,
            mode = mode,
            onBack = { exportMode = null },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(chain?.displayName ?: "Chain") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (chain == null) {
            Text("Chain not found", modifier = Modifier.padding(padding).padding(16.dp))
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("Name", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    vpn.renameChain(chainId, it)
                },
                label = { Text("Chain name") },
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Status", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
            val statusReports by vpn.chainStatusReports.collectAsState()
            val reports = statusReports[chainId].orEmpty()
            Button(onClick = { vpn.fetchChainStatus(chainId) }, enabled = hops.isNotEmpty(), modifier = Modifier.dPadActivate(enabled = hops.isNotEmpty()) { vpn.fetchChainStatus(chainId) }) {
                Text("Refresh status")
            }
            reports.forEachIndexed { index, report ->
                val hop = hops.getOrNull(index)
                Text(
                    hop?.displayName ?: report.host.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                report.server_version?.let { Text("Server v$it", style = MaterialTheme.typography.bodySmall) }
                report.chains.forEach { entry ->
                    Text(
                        "${entry.role ?: "?"} · ${if (entry.running == true) "running" else "stopped"} · ${entry.sessions.size} session(s)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Text(
                "Route (entry → exit)",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )

            if (hops.isEmpty()) {
                Text("Add servers from your library.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                hops.forEachIndexed { index, hop ->
                    ListItem(
                        headlineContent = { Text(roleLabel(index, hops.size, hop)) },
                        supportingContent = {
                            Text("${hop.trimmedUser}@${hop.trimmedHost}:${hop.port}")
                        },
                    )
                }
            }

            Button(
                onClick = onAddServer,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .dPadActivate(onClick = onAddServer),
            ) {
                Text("Add server…")
            }

            if (hops.isNotEmpty()) {
                HopperExportButtonsRow(
                    modifier = Modifier.padding(top = 16.dp),
                    onSelect = { exportMode = it },
                )
            }
        }
    }
}

private fun roleLabel(index: Int, total: Int, hop: HopNodeProfile): String {
    val role = when {
        total == 1 -> "Exit"
        index == 0 -> "Entry"
        index == total - 1 -> "Exit"
        else -> "Relay"
    }
    return "${index + 1}. $role — ${hop.displayName}"
}
