package com.aengix.hopper.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aengix.hopper.data.HopperConf
import com.aengix.hopper.data.HopperLanInvite
import com.aengix.hopper.lan.HopperLanReceivePhase
import com.aengix.hopper.lan.HopperLanReceiveSession
import com.aengix.hopper.lan.HopperLanSendSession
import com.aengix.hopper.vpn.VpnController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HopperLanReceiveScreen(
    vpn: VpnController,
    onBack: () -> Unit,
) {
    val session = remember {
        HopperLanReceiveSession { payload -> vpn.importPayload(payload, announceChain = false) }
    }
    DisposableEffect(session) {
        session.start()
        onDispose { session.close() }
    }
    val phase by session.phase.collectAsState()
    val invite by session.invite.collectAsState()
    val received by session.received.collectAsState()
    val qrBitmap = remember(invite?.url) { invite?.url?.let { QRCodeGenerator.encode(it) } }
    val snackbarHostState = remember { SnackbarHostState() }
    val connected = phase is HopperLanReceivePhase.Connected

    LaunchedEffect(phase) {
        if (phase is HopperLanReceivePhase.Connected) {
            snackbarHostState.showSnackbar("Somebody connected. You can keep receiving until you disconnect.")
        }
    }
    LaunchedEffect(received.size) {
        received.lastOrNull()?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import remotely") },
                navigationIcon = {
                    IconButton(onClick = {
                        session.close()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (connected) {
                Button(
                    onClick = {
                        session.close()
                        onBack()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .dPadActivate {
                            session.close()
                            onBack()
                        },
                ) {
                    Text("Disconnect")
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val current = phase) {
                is HopperLanReceivePhase.Starting -> Text(
                    "Starting a local receive session…",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is HopperLanReceivePhase.Failed -> Text(
                    current.message,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error,
                )
                else -> {
                    Text(
                        "Open Camera on the other Hopper device and scan this code. Then choose what to share.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    if (qrBitmap != null) {
                        FitQrImage(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Remote import QR code",
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    invite?.let {
                        Text(
                            "${it.ip}:${it.port}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HopperLanSendScreen(
    vpn: VpnController,
    invite: HopperLanInvite,
    onBack: () -> Unit,
) {
    val session = remember(invite) { HopperLanSendSession(invite) }
    DisposableEffect(session) {
        session.start()
        onDispose { session.close() }
    }
    val status by session.status.collectAsState()
    val ready by session.ready.collectAsState()
    val log by session.log.collectAsState()
    val state by vpn.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val latestLog = rememberUpdatedState(log)

    LaunchedEffect(log.size) {
        latestLog.value.lastOrNull()?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Share") },
                navigationIcon = {
                    IconButton(onClick = {
                        session.close()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(status, modifier = Modifier.padding(16.dp))
            if (ready) {
                if (state.chains.isNotEmpty()) {
                    Text("Chains", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp))
                    state.chains.forEach { chain ->
                        val hops = state.resolveHops(chain)
                        ListItem(
                            headlineContent = { Text(chain.displayName) },
                            supportingContent = { Text("${hops.size} server(s)") },
                            trailingContent = {
                                TextButton(
                                    onClick = { session.share(HopperConf.Payload.Chain(chain.name, hops)) },
                                    enabled = hops.isNotEmpty(),
                                    modifier = Modifier.dPadActivate(enabled = hops.isNotEmpty()) {
                                        session.share(HopperConf.Payload.Chain(chain.name, hops))
                                    },
                                ) { Text("Share") }
                            },
                        )
                    }
                }
                if (state.servers.isNotEmpty()) {
                    Text("Servers", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp))
                    state.servers.forEach { server ->
                        ListItem(
                            headlineContent = { Text(server.displayName) },
                            trailingContent = {
                                TextButton(
                                    onClick = { session.share(HopperConf.Payload.Server(server)) },
                                    modifier = Modifier.dPadActivate {
                                        session.share(HopperConf.Payload.Server(server))
                                    },
                                ) {
                                    Text("Share")
                                }
                            },
                        )
                    }
                }
                if (state.deployKeys.isNotEmpty()) {
                    Text("Keys", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp))
                    state.deployKeys.forEach { key ->
                        ListItem(
                            headlineContent = { Text(key.displayName) },
                            trailingContent = {
                                TextButton(
                                    onClick = { session.share(HopperConf.Payload.Key(key)) },
                                    modifier = Modifier.dPadActivate {
                                        session.share(HopperConf.Payload.Key(key))
                                    },
                                ) {
                                    Text("Share")
                                }
                            },
                        )
                    }
                }
                if (state.chains.isEmpty() && state.servers.isEmpty() && state.deployKeys.isEmpty()) {
                    Text(
                        "Nothing to share yet. Add a server, chain, or key first.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
