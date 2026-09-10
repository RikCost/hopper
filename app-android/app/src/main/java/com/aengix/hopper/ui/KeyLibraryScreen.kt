package com.aengix.hopper.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
import com.aengix.hopper.model.DeploySSHKey
import com.aengix.hopper.ssh.SSHKeyGenerator
import com.aengix.hopper.vpn.VpnController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyLibraryScreen(
    vpn: VpnController,
    onBack: () -> Unit,
    onKeyDetail: (String) -> Unit,
    onRequestCameraPermission: (onGranted: () -> Unit) -> Unit,
) {
    val state by vpn.state.collectAsState()
    var showGenerate by remember { mutableStateOf(false) }
    var showPaste by remember { mutableStateOf(false) }
    var importMode by remember { mutableStateOf<HopperImportMode?>(null) }
    var showScanner by remember { mutableStateOf(false) }
    var keyToDelete by remember { mutableStateOf<DeploySSHKey?>(null) }
    var blockedDeleteKey by remember { mutableStateOf<DeploySSHKey?>(null) }

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
                title = { Text("Keys") },
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
                        onClick = { showGenerate = true },
                        modifier = Modifier.dPadActivate { showGenerate = true },
                    ) { Text("Generate") }
                    TextButton(
                        onClick = { showPaste = true },
                        modifier = Modifier.dPadActivate { showPaste = true },
                    ) { Text("Paste") }
                    TextButton(
                        onClick = {
                            onRequestCameraPermission { showScanner = true }
                        },
                        modifier = Modifier.dPadActivate {
                            onRequestCameraPermission { showScanner = true }
                        },
                    ) { Text("Scan QR") }
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
                onSelect = { importMode = it },
            )
            if (state.deployKeys.isEmpty()) {
                Text(
                    "Generate an ED25519 key, paste a private key, or import a .hopperconf file.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                items(state.deployKeys, key = { it.id }) { key ->
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        ListItem(
                            headlineContent = { Text(key.displayName) },
                            supportingContent = { Text(assignmentSummary(key, state.servers)) },
                            modifier = Modifier.dPadClickable { onKeyDetail(key.id) },
                            trailingContent = {
                                val onDeleteClick = {
                                    if (key.canDelete(state.servers)) {
                                        keyToDelete = key
                                    } else {
                                        blockedDeleteKey = key
                                    }
                                }
                                IconButton(
                                    onClick = onDeleteClick,
                                    modifier = Modifier.dPadActivate(onClick = onDeleteClick),
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete key",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
        }
    }

    keyToDelete?.let { key ->
        HopperConfirmDialog(
            title = "Delete key?",
            text = "Remove ${key.displayName} from the library. Servers keep their own hop keys.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                vpn.deleteDeployKeys(setOf(key.id))
                keyToDelete = null
            },
            onDismiss = { keyToDelete = null },
        )
    }

    blockedDeleteKey?.let { key ->
        HopperConfirmDialog(
            title = "Can't delete key",
            text = deleteBlockedMessage(key, state.servers),
            confirmLabel = "OK",
            dismissLabel = null,
            onConfirm = { blockedDeleteKey = null },
            onDismiss = { blockedDeleteKey = null },
        )
    }

    if (showGenerate) {
        KeyGenerateDialog(
            vpn = vpn,
            onDismiss = { showGenerate = false },
        )
    }

    if (showPaste) {
        KeyPasteDialog(
            vpn = vpn,
            onDismiss = { showPaste = false },
            onError = vpn::setError,
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

private fun assignmentSummary(
    key: DeploySSHKey,
    servers: List<com.aengix.hopper.model.HopNodeProfile>,
): String {
    val labels = key.assignments.map { it.label(servers) }.filter { it.isNotBlank() }
    return when (labels.size) {
        0 -> "No assigned servers"
        1 -> labels[0]
        else -> "${labels.size} servers — ${labels[0]}"
    }
}

private fun deleteBlockedMessage(
    key: DeploySSHKey,
    servers: List<com.aengix.hopper.model.HopNodeProfile>,
): String {
    val names = key.assignedServers(servers).joinToString(", ") { it.displayName }
    return "This key is assigned to $names. Remove those servers from the library before deleting the key."
}

@Composable
private fun KeyGenerateDialog(
    vpn: VpnController,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var privatePem by remember { mutableStateOf("") }
    var publicLine by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf<String?>(null) }
    val hasKey = privatePem.isNotEmpty()

    HopperAlertDialog(
        onDismissRequest = { if (hasKey) onDismiss() },
        title = { Text("Generate key") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Key name") },
                    enabled = !hasKey,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (hasKey) {
                    Text("Public key", style = MaterialTheme.typography.labelMedium)
                    Text(publicLine, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
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
                    Text("Private key", style = MaterialTheme.typography.labelMedium)
                    Text(privatePem, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    TextButton(
                        onClick = {
                            copyToClipboard(context, privatePem)
                            copied = "Private key copied"
                        },
                        modifier = Modifier.dPadActivate {
                            copyToClipboard(context, privatePem)
                            copied = "Private key copied"
                        },
                    ) { Text("Copy private key") }
                    copied?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    Text(
                        "Creates an ED25519 key, saves it in the library, and shows both halves for copy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (hasKey) {
                DialogActionButton(text = "Done", onClick = onDismiss, autoFocus = true)
            } else {
                DialogActionButton(
                    text = "Create",
                    autoFocus = true,
                    onClick = {
                        errorMessage = null
                        runCatching {
                            val comment = name.trim().ifEmpty { "hopper" }
                            val generated = SSHKeyGenerator.generateEd25519(comment)
                            vpn.addDeployKey(
                                DeploySSHKey(
                                    name = comment,
                                    privateKey = generated.privateKeyPem,
                                ),
                            )
                            privatePem = generated.privateKeyPem
                            publicLine = generated.publicKeyLine
                        }.onFailure { errorMessage = it.message }
                    },
                )
            }
        },
        dismissButton = {
            if (!hasKey) {
                DialogActionButton(text = "Cancel", onClick = onDismiss)
            }
        },
    )
}

@Composable
private fun KeyPasteDialog(
    vpn: VpnController,
    onDismiss: () -> Unit,
    onError: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var pem by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val isValid = SSHKeyGenerator.isValidPrivateKey(pem)

    HopperAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Key name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pem,
                    onValueChange = { pem = it },
                    label = { Text("Private key") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                )
                val status = when {
                    pem.trim().isEmpty() -> "Paste an OpenSSH ED25519 private key."
                    isValid -> "Valid ED25519 private key."
                    else -> "Not a valid ED25519 private key."
                }
                Text(
                    status,
                    color = when {
                        pem.trim().isEmpty() -> MaterialTheme.colorScheme.onSurfaceVariant
                        isValid -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.error
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            DialogActionButton(
                text = "Import",
                enabled = isValid,
                autoFocus = true,
                onClick = {
                    errorMessage = null
                    if (!isValid) {
                        errorMessage = "Not a valid ED25519 private key."
                        onError(errorMessage!!)
                        return@DialogActionButton
                    }
                    vpn.importDeployKey(
                        DeploySSHKey(
                            name = name.trim().ifEmpty { "Imported key" },
                            privateKey = pem.trim(),
                        ),
                    )
                    onDismiss()
                },
            )
        },
        dismissButton = {
            DialogActionButton(text = "Cancel", onClick = onDismiss)
        },
    )
}

internal fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Hopper key", text))
}
