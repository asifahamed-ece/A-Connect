package com.aplusconnect.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aplusconnect.companion.ui.components.DangerConfirmButton
import com.aplusconnect.companion.ui.components.GhostButton
import com.aplusconnect.companion.ui.components.PrimaryButton
import com.aplusconnect.companion.ui.theme.AplusTheme
import com.aplusconnect.companion.ui.theme.AplusType
import com.aplusconnect.companion.ui.theme.Radius
import com.aplusconnect.companion.ui.theme.Space

@Composable
internal fun fieldColors() = AplusTheme.colors.let { c ->
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.accent,
        unfocusedBorderColor = c.outline,
        focusedLabelColor = c.accent,
        unfocusedLabelColor = c.textSecondary,
        cursorColor = c.accent,
        focusedTextColor = c.textPrimary,
        unfocusedTextColor = c.textPrimary,
        focusedContainerColor = c.surface,
        unfocusedContainerColor = c.surface,
    )
}

/**
 * Dialog chrome shared by every dialog: r-xl, Cancel then primary (§8.8).
 * Solid elevated surface: a dialog is its own window, so Haze cannot blur
 * what is behind it, and a translucent tint without blur would show the
 * page through the text.
 */
@Composable
private fun AplusDialog(
    title: String,
    onDismiss: () -> Unit,
    confirm: @Composable () -> Unit,
    body: @Composable () -> Unit,
) {
    val c = AplusTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = AplusType.title, color = c.textPrimary) },
        text = body,
        confirmButton = confirm,
        dismissButton = { GhostButton("Cancel", onDismiss) },
        containerColor = c.surfaceElevated,
        shape = RoundedCornerShape(Radius.xl),
        tonalElevation = androidx.compose.ui.unit.Dp(0f),
    )
}


@Composable
internal fun RenameDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(current) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AplusDialog(
        title = "Device name",
        onDismiss = onDismiss,
        confirm = { PrimaryButton("Save", { onSave(value.trim()) }, enabled = value.isNotBlank()) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
            Text("How this phone appears on your computer.", style = AplusType.bodySm, color = AplusTheme.colors.textSecondary)
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (value.isNotBlank()) onSave(value.trim()) }),
                colors = fieldColors(),
                shape = RoundedCornerShape(Radius.sm),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
    }
}

@Composable
internal fun SendTextDialog(onSend: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AplusDialog(
        title = "Send text",
        onDismiss = onDismiss,
        confirm = { PrimaryButton("Send", { onSend(value.trim()) }, enabled = value.isNotBlank()) },
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text("Text for the computer's clipboard") },
            colors = fieldColors(),
            shape = RoundedCornerShape(Radius.sm),
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).focusRequester(focus),
        )
    }
}

@Composable
internal fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AplusDialog(
        title = title,
        onDismiss = onDismiss,
        confirm = { DangerConfirmButton(confirmLabel, onConfirm) },
    ) {
        Text(body, style = AplusType.bodySm, color = AplusTheme.colors.textSecondary)
    }
}

/** "Enter code instead": the typed fallback for the QR scan, as a sheet (§8.8). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ManualPairingSheet(
    initialHost: String,
    initialPort: Int,
    initialCode: String,
    onConnect: (host: String, port: Int, code: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = AplusTheme.colors
    var host by remember { mutableStateOf(initialHost) }
    var port by remember { mutableStateOf(initialPort.toString()) }
    var code by remember { mutableStateOf(initialCode) }
    val portValue = port.toIntOrNull()
    val valid = host.isNotBlank() && portValue != null && portValue in 1..65535
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surfaceElevated,
        scrimColor = c.scrim,
        shape = RoundedCornerShape(topStart = Radius.xl, topEnd = Radius.xl),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.screen).navigationBarsPadding().imePadding(),
            verticalArrangement = Arrangement.spacedBy(Space.s3),
        ) {
            Text("Enter code", style = AplusType.title, color = c.textPrimary)
            Text(
                "On your computer, open A+ Connect → Connect → Pair. Type what it shows.",
                style = AplusType.bodySm,
                color = c.textSecondary,
            )
            OutlinedTextField(
                value = host,
                onValueChange = { host = it.trim() },
                label = { Text("Computer IP address") },
                singleLine = true,
                textStyle = AplusType.mono,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                colors = fieldColors(),
                shape = RoundedCornerShape(Radius.sm),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    textStyle = AplusType.mono,
                    isError = port.isNotEmpty() && !valid && host.isNotBlank(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    colors = fieldColors(),
                    shape = RoundedCornerShape(Radius.sm),
                    modifier = Modifier.weight(0.4f),
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    label = { Text("Pairing code") },
                    singleLine = true,
                    textStyle = AplusType.mono,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    colors = fieldColors(),
                    shape = RoundedCornerShape(Radius.sm),
                    modifier = Modifier.weight(0.6f),
                )
            }
            Box(Modifier.height(Space.s1))
            PrimaryButton(
                "Connect",
                { if (portValue != null) onConnect(host, portValue, code) },
                enabled = valid,
                fullWidth = true,
            )
            Box(Modifier.height(Space.s4))
        }
    }
}
