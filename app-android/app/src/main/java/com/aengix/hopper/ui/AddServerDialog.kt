package com.aengix.hopper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aengix.hopper.model.DeploySSHKey
import com.aengix.hopper.model.HopConstants
import com.aengix.hopper.model.HopNodeProfile
import com.aengix.hopper.ssh.DeploySSH
import com.aengix.hopper.ssh.SSHKeyGenerator
import com.aengix.hopper.vpn.VpnController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AddServerAuthMode { Password, SavedKey }

@Composable
fun AddServerDialog(
    vpn: VpnController,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("root") }
    var portText by remember { mutableStateOf("22") }
    var password by remember { mutableStateOf("") }
    var authMode by remember {
        mutableStateOf(
            if (vpn.state.value.deployKeys.isEmpty()) AddServerAuthMode.Password
            else AddServerAuthMode.SavedKey,
        )
    }
    var selectedKeyId by remember { mutableStateOf<String?>(null) }
    var isAdding by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val deployKeys = vpn.state.value.deployKeys

    val canAdd = host.trim().isNotEmpty() && !isAdding && when (authMode) {
        AddServerAuthMode.Password -> password.isNotEmpty()
        AddServerAuthMode.SavedKey -> selectedKeyId != null || deployKeys.isNotEmpty()
    }

    HopperAlertDialog(
        onDismissRequest = { if (!isAdding) onDismiss() },
        title = { Text("Add Server") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!isAdding) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Host or IP") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = user,
                        onValueChange = { user = it },
                        label = { Text("User") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { portText = it },
                        label = { Text("SSH port") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = authMode == AddServerAuthMode.Password,
                            onClick = { authMode = AddServerAuthMode.Password },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        ) { Text("Password") }
                        SegmentedButton(
                            selected = authMode == AddServerAuthMode.SavedKey,
                            onClick = { authMode = AddServerAuthMode.SavedKey },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        ) { Text("Saved key") }
                    }

                    when (authMode) {
                        AddServerAuthMode.Password -> {
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("Password") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "A new key is generated, saved in the Keys library, and authorized on the server. Hopper is not installed.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AddServerAuthMode.SavedKey -> {
                            if (deployKeys.isEmpty()) {
                                Text(
                                    "No keys in the library yet. Generate or paste one in Keys library, or use password once.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                deployKeys.forEach { key ->
                                    val selected = (selectedKeyId ?: deployKeys.firstOrNull()?.id) == key.id
                                    TextButton(
                                        onClick = { selectedKeyId = key.id },
                                        modifier = Modifier.dPadActivate { selectedKeyId = key.id },
                                    ) {
                                        Text(
                                            if (selected) "● ${key.name}" else key.name,
                                            color = if (selected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                                Text(
                                    "Adds this server to the library using the selected key. No remote install is run.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                        Text("Adding…", style = MaterialTheme.typography.bodySmall)
                    }
                }

                errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            DialogActionButton(
                text = "Add",
                autoFocus = true,
                enabled = canAdd,
                onClick = {
                    errorMessage = null
                    val trimmedHost = host.trim()
                    if (trimmedHost.isEmpty()) {
                        errorMessage = "Host is required."
                        return@DialogActionButton
                    }
                    val port = portText.trim().toIntOrNull()?.takeIf { it > 0 }
                        ?: HopConstants.DEFAULT_SSH_PORT
                    val effectiveUser = user.trim().ifEmpty { "root" }
                    val trimmedName = name.trim()

                    when (authMode) {
                        AddServerAuthMode.SavedKey -> {
                            val keyId = selectedKeyId ?: deployKeys.firstOrNull()?.id
                            val key = keyId?.let { id -> deployKeys.firstOrNull { it.id == id } }
                            if (key == null || key.privateKey.trim().isEmpty()) {
                                errorMessage = "Select an SSH key from the library."
                                return@DialogActionButton
                            }
                            val profile = HopNodeProfile(
                                name = trimmedName,
                                host = trimmedHost,
                                port = port,
                                user = effectiveUser,
                                privateKey = key.privateKey,
                                installDir = HopConstants.DEFAULT_INSTALL_DIR,
                            )
                            vpn.addServer(profile)
                            vpn.recordDeployKeyUse(key.id, profile)
                            onDismiss()
                        }
                        AddServerAuthMode.Password -> {
                            if (password.isEmpty()) {
                                errorMessage = "Password is required."
                                return@DialogActionButton
                            }
                            isAdding = true
                            scope.launch {
                                try {
                                    val (key, profile) = withContext(Dispatchers.IO) {
                                        val generated = SSHKeyGenerator.generateEd25519("hopper-deploy@$trimmedHost")
                                        val publicLine = SSHKeyGenerator.publicKeyLine(
                                            generated.privateKeyPem,
                                            "deploy-$trimmedHost",
                                        )
                                        DeploySSH.authorizeKey(
                                            host = trimmedHost,
                                            port = port,
                                            user = effectiveUser,
                                            password = password,
                                            privateKeyPem = generated.privateKeyPem,
                                            publicKeyLine = publicLine,
                                        )
                                        val profile = HopNodeProfile(
                                            name = trimmedName,
                                            host = trimmedHost,
                                            port = port,
                                            user = effectiveUser,
                                            privateKey = generated.privateKeyPem,
                                            installDir = HopConstants.DEFAULT_INSTALL_DIR,
                                        )
                                        val key = DeploySSHKey(
                                            name = "Deploy $effectiveUser@$trimmedHost",
                                            privateKey = generated.privateKeyPem,
                                        ).recordAssignment(profile)
                                        key to profile
                                    }
                                    vpn.addDeployKey(key)
                                    vpn.addServer(profile)
                                    onDismiss()
                                } catch (error: Throwable) {
                                    errorMessage = error.message ?: error.toString()
                                    isAdding = false
                                }
                            }
                        }
                    }
                },
            )
        },
        dismissButton = {
            DialogActionButton(text = "Cancel", onClick = onDismiss, enabled = !isAdding)
        },
    )
}
