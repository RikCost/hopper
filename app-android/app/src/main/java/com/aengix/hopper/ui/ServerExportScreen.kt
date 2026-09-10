package com.aengix.hopper.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.aengix.hopper.data.HopperConf
import com.aengix.hopper.model.HopNodeProfile
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerExportScreen(
    server: HopNodeProfile,
    mode: HopperExportMode,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val payload = remember(server) { HopperConf.Payload.Server(server) }
    val qrJson = remember(payload) { HopperConf.qrPayloadJson(payload) }
    val qrBitmap = remember(qrJson) { QRCodeGenerator.encode(qrJson) }
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(mode.navigationTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (mode == HopperExportMode.Qr) {
                Text(
                    "Scan on another device to import this server. The QR is only for in-person transfer — it is not shared as a file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                )

                if (qrBitmap != null) {
                    FitQrImage(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "Server config QR code",
                    )
                } else {
                    Text(
                        "Could not generate QR code.",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                    )
                }
            } else {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Optional encryption password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 20.dp),
                )
                Text(
                    "Leave empty to use the default password. The file always encrypts private keys.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )

                Button(
                    onClick = {
                        errorMessage = null
                        runCatching { shareHopperConf(context, payload, password) }
                            .onFailure { errorMessage = it.message }
                    },
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 24.dp)
                        .fillMaxWidth()
                        .dPadActivate {
                            errorMessage = null
                            runCatching { shareHopperConf(context, payload, password) }
                                .onFailure { errorMessage = it.message }
                        },
                ) {
                    Text("Share .hopperconf…")
                }
            }

            errorMessage?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

internal fun shareHopperConf(context: Context, payload: HopperConf.Payload, password: String) {
    val bytes = HopperConf.encryptFile(payload, password)
    val dir = File(context.cacheDir, "share").apply { mkdirs() }
    val file = File(dir, HopperConf.suggestedFileName(payload))
    file.writeBytes(bytes)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = HopperConf.MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share Hopper config"))
}
