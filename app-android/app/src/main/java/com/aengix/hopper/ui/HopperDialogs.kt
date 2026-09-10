package com.aengix.hopper.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** TV D-pad OK is often treated as a click outside Compose dialogs. */
internal val HopperDialogProperties = DialogProperties(dismissOnClickOutside = false)

internal fun Modifier.dPadActivate(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        if (!enabled) return@onPreviewKeyEvent false
        val activate = event.key == Key.DirectionCenter ||
            event.key == Key.Enter ||
            event.key == Key.NumPadEnter
        if (!activate) return@onPreviewKeyEvent false
        if (event.type == KeyEventType.KeyUp) onClick()
        true
    }

internal fun Modifier.dPadClickable(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = clickable(enabled = enabled, onClick = onClick).dPadActivate(enabled, onClick)

@Composable
internal fun DialogActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    autoFocus: Boolean = false,
    destructive: Boolean = false,
) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            withFrameNanos { }
            runCatching { requester.requestFocus() }
        }
    }
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = if (destructive) {
            ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
        } else {
            ButtonDefaults.textButtonColors()
        },
        modifier = modifier
            .then(if (autoFocus) Modifier.focusRequester(requester) else Modifier)
            .dPadActivate(enabled, onClick),
    ) {
        Text(text)
    }
}

@Composable
internal fun HopperAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    text: @Composable (() -> Unit)? = null,
    dismissButton: @Composable (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = title,
        text = text,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        modifier = modifier,
        properties = HopperDialogProperties,
    )
}

@Composable
internal fun HopperConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String? = "Cancel",
    destructive: Boolean = false,
) {
    val confirmFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { confirmFocus.requestFocus() }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = HopperDialogProperties,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (dismissLabel != null) {
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.dPadActivate(onClick = onDismiss),
                        ) {
                            Text(dismissLabel)
                        }
                    }
                    Button(
                        onClick = onConfirm,
                        colors = if (destructive) {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            )
                        } else {
                            ButtonDefaults.buttonColors()
                        },
                        modifier = Modifier
                            .focusRequester(confirmFocus)
                            .dPadActivate(onClick = onConfirm),
                    ) {
                        Text(confirmLabel)
                    }
                }
            }
        }
    }
}

data class HopperDialogAction(
    val label: String,
    val onClick: () -> Unit,
)

@Composable
internal fun HopperOptionsDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
    actions: List<HopperDialogAction>,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = HopperDialogProperties,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    actions.forEachIndexed { index, action ->
                        TextButton(
                            onClick = action.onClick,
                            modifier = Modifier
                                .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                                .dPadActivate(onClick = action.onClick),
                        ) {
                            Text(action.label)
                        }
                    }
                }
            }
        }
    }
}
