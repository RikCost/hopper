package com.aengix.hopper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aengix.hopper.model.DeploySSHKey
import com.aengix.hopper.model.HopConstants
import com.aengix.hopper.provision.ServerDeployAuth
import com.aengix.hopper.vpn.VpnController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class DeployAuthMode { Password, SavedKey }

@Composable
fun DeployServerDialog(
    vpn: VpnController,
    onDismiss: () -> Unit,
    onError: (String) -> Unit,
) {
    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("root") }
    var portText by remember { mutableStateOf("22") }
    var password by remember { mutableStateOf("") }
    var authMode by remember { mutableStateOf(DeployAuthMode.Password) }
    var selectedKeyId by remember { mutableStateOf<String?>(null) }
    var isDeploying by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var deployLog by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val deployKeys = vpn.state.value.deployKeys
    val logScroll = rememberScrollState()

    LaunchedEffect(deployLog) {
        if (deployLog.isNotEmpty()) {
            logScroll.animateScrollTo(logScroll.maxValue)
        }
    }

    HopperAlertDialog(
        onDismissRequest = { if (!isDeploying) onDismiss() },
        title = { Text("Deploy Server") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!isDeploying) {
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
                            selected = authMode == DeployAuthMode.Password,
                            onClick = { authMode = DeployAuthMode.Password },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        ) { Text("Password") }
                        SegmentedButton(
                            selected = authMode == DeployAuthMode.SavedKey,
                            onClick = { authMode = DeployAuthMode.SavedKey },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        ) { Text("Saved key") }
                    }

                    when (authMode) {
                        DeployAuthMode.Password -> {
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("Password") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "A new deploy key is generated, saved in the Keys library, and installed on the server.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DeployAuthMode.SavedKey -> {
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
                            }
                        }
                    }
                }

                if (isDeploying || deployLog.isNotEmpty()) {
                    Text("Deploy log", style = MaterialTheme.typography.titleSmall)
                    Surface(
                        tonalElevation = 1.dp,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = deployLog.ifEmpty { "Starting…" },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = 240.dp)
                                .verticalScroll(logScroll)
                                .padding(8.dp),
                        )
                    }
                    if (isDeploying) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                            Text("Deploying…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            DialogActionButton(
                text = "Deploy",
                autoFocus = true,
                enabled = !isDeploying && canDeploy(host, authMode, password, deployKeys, selectedKeyId),
                onClick = {
                    errorMessage = null
                    deployLog = ""
                    isDeploying = true
                    val port = portText.trim().toIntOrNull() ?: HopConstants.DEFAULT_SSH_PORT
                    val auth = when (authMode) {
                        DeployAuthMode.Password -> ServerDeployAuth.Password(password)
                        DeployAuthMode.SavedKey -> {
                            val keyId = selectedKeyId ?: deployKeys.firstOrNull()?.id
                            val key = keyId?.let { id -> deployKeys.firstOrNull { it.id == id } }
                                ?: run {
                                    errorMessage = "Select a deploy key from the library."
                                    isDeploying = false
                                    return@DialogActionButton
                                }
                            ServerDeployAuth.DeployKey(key)
                        }
                    }
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                vpn.deployServer(host, port, user, auth) { line ->
                                    scope.launch(Dispatchers.Main.immediate) {
                                        deployLog = if (deployLog.isEmpty()) line else "$deployLog\n$line"
                                    }
                                }
                            }
                        }.onSuccess {
                            isDeploying = false
                            onDismiss()
                        }.onFailure { error ->
                            isDeploying = false
                            errorMessage = error.message
                            onError(error.message ?: "Deploy failed")
                        }
                    }
                },
            )
        },
        dismissButton = {
            DialogActionButton(text = "Cancel", onClick = onDismiss, enabled = !isDeploying)
        },
    )
}

private fun canDeploy(
    host: String,
    authMode: DeployAuthMode,
    password: String,
    deployKeys: List<DeploySSHKey>,
    selectedKeyId: String?,
): Boolean {
    if (host.trim().isEmpty()) return false
    return when (authMode) {
        DeployAuthMode.Password -> password.isNotEmpty()
        DeployAuthMode.SavedKey -> selectedKeyId != null || deployKeys.isNotEmpty()
    }
}
