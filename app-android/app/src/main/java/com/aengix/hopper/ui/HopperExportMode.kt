package com.aengix.hopper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class HopperExportMode {
    Qr, File
}

val HopperExportMode.navigationTitle: String
    get() = when (this) {
        HopperExportMode.Qr -> "QR code"
        HopperExportMode.File -> "Export as file"
    }

enum class HopperImportMode {
    File, Paste, Remote
}

@Composable
fun HopperImportButtons(
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onSelect: (HopperImportMode) -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { onSelect(HopperImportMode.File) },
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .dPadActivate(enabled) { onSelect(HopperImportMode.File) },
            ) {
                Text(
                    "Import from file",
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedButton(
                onClick = { onSelect(HopperImportMode.Paste) },
                enabled = enabled,
                modifier = Modifier
                    .weight(1f)
                    .dPadActivate(enabled) { onSelect(HopperImportMode.Paste) },
            ) {
                Text(
                    "Import from copy&paste",
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        OutlinedButton(
            onClick = { onSelect(HopperImportMode.Remote) },
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .dPadActivate(enabled) { onSelect(HopperImportMode.Remote) },
        ) {
            Text("Import remotely")
        }
    }
}

@Composable
fun HopperExportButtonsRow(
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onSelect: (HopperExportMode) -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = { onSelect(HopperExportMode.File) },
            enabled = enabled,
            modifier = Modifier
                .weight(1f)
                .dPadActivate(enabled) { onSelect(HopperExportMode.File) },
        ) {
            Text(
                "Export as file…",
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OutlinedButton(
            onClick = { onSelect(HopperExportMode.Qr) },
            enabled = enabled,
            modifier = Modifier
                .weight(1f)
                .dPadActivate(enabled) { onSelect(HopperExportMode.Qr) },
        ) {
            Text(
                "Show QR code…",
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
