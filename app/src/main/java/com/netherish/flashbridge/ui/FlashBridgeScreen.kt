package com.netherish.flashbridge.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netherish.flashbridge.core.UsbState
import com.netherish.flashbridge.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlashBridgeScreen(vm: MainViewModel, onPickFile: () -> Unit) {
    val state by vm.state.collectAsState()
    val usbStatus by vm.usbStatus.collectAsState()
    val scroll = rememberScrollState()

    var confirmUnlock by remember { mutableStateOf(false) }
    var confirmErase by remember { mutableStateOf(false) }
    val connected = usbStatus is UsbState.Connected

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⚡", fontSize = 22.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("FlashBridge", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = { vm.clearLog() }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear log")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Surface,
                    titleContentColor = TextPrimary,
                    actionIconContentColor = TextPrimary,
                ),
            )
        },
        containerColor = DarkBg,
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(scroll).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusCard(status = usbStatus)

            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF3A2A00)), shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                    Text("⚠", fontSize = 18.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Flashing firmware can permanently damage a device. Use the correct files for your exact model. " +
                            "You are responsible for what you flash.",
                        color = Warning, fontSize = 12.sp, lineHeight = 17.sp,
                    )
                }
            }

            SectionTitle("Connection")
            Button(
                onClick = { vm.scan() },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Color(0xFF00363D)),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Filled.Usb, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Detect device", fontWeight = FontWeight.Bold)
            }

            SectionTitle("Device actions")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("Getvar", Icons.Filled.Info, Modifier.weight(1f), !connected || state.busy != Busy.None) { vm.getvar("product") }
                ActionButton("Reboot", Icons.Filled.PowerSettingsNew, Modifier.weight(1f), !connected || state.busy != Busy.None) { vm.reboot() }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("Erase", Icons.Filled.DeleteForever, Modifier.weight(1f), !connected || state.busy != Busy.None) { confirmErase = true }
                ActionButton("Unlock", Icons.Filled.LockOpen, Modifier.weight(1f), !connected || state.busy != Busy.None) { confirmUnlock = true }
            }

            SectionTitle("Flash image")
            Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = onPickFile,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan),
                    ) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.selectedFile.isEmpty()) "Choose image / ROM file" else state.selectedFile)
                    }

                    OutlinedTextField(
                        value = state.partition,
                        onValueChange = { vm.setPartition(it) },
                        label = { Text("Partition") },
                        placeholder = { Text("boot, system, recovery, vbmeta…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Amber,
                            unfocusedBorderColor = TextMuted,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                        ),
                    )

                    if (state.progress.isNotEmpty()) {
                        Text("Progress: ${state.progress}", color = Cyan, fontWeight = FontWeight.Bold)
                        LinearProgressIndicator(
                            progress = { (state.progress.removeSuffix("%").toFloatOrNull() ?: 0f) / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = Amber,
                        )
                    }

                    Button(
                        onClick = { vm.flash() },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        enabled = connected && state.selectedFile.isNotEmpty() && state.busy == Busy.None,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Amber,
                            contentColor = Color(0xFF1A1200),
                            disabledContainerColor = SurfaceHigh,
                            disabledContentColor = TextMuted,
                        ),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        if (state.busy == Busy.Running) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = Color(0xFF1A1200))
                        } else {
                            Icon(Icons.Filled.FlashOn, contentDescription = null)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("FLASH ${state.partition.uppercase()}", fontWeight = FontWeight.Bold)
                    }
                }
            }

            SectionTitle("Output log")
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF05070E)), shape = RoundedCornerShape(16.dp)) {
                Text(
                    text = state.log.ifEmpty { "(empty)" },
                    color = Success, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
                    modifier = Modifier.padding(14.dp).fillMaxWidth(),
                )
            }

            Text("Developed by netherish · v1.0", color = TextMuted, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp))
        }
    }

    if (confirmUnlock) {
        ConfirmDialog(
            title = "Unlock bootloader?",
            text = "This will erase all data on the connected device. Continue?",
            onConfirm = { confirmUnlock = false; vm.unlock() },
            onDismiss = { confirmUnlock = false },
        )
    }
    if (confirmErase) {
        ConfirmDialog(
            title = "Erase ${state.partition}?",
            text = "This will erase the ${state.partition} partition on the connected device. Continue?",
            onConfirm = { confirmErase = false; vm.erase() },
            onDismiss = { confirmErase = false },
        )
    }
}

@Composable
private fun ConfirmDialog(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("CONFIRM", color = Error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Cyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
}

@Composable
private fun StatusCard(status: UsbState) {
    val (color, title, detail) = when (status) {
        is UsbState.Connected -> Triple(Success, "Device connected ✓", "VID 0x%04X · PID 0x%04X".format(status.info.vendorId, status.info.productId))
        is UsbState.NeedPermission -> Triple(Warning, "Permission required", "Approve the USB access prompt")
        is UsbState.NoDevice -> Triple(Warning, "No fastboot device", status.message)
        is UsbState.Error -> Triple(Error, "Error", status.message)
        UsbState.Idle -> Triple(TextMuted, "Not connected", "Press Detect to scan for a device")
    }
    val animated by animateColorAsState(color)
    Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(RoundedCornerShape(50)).background(animated))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(detail, color = TextMuted, fontSize = 12.sp)
                Text("No root required", color = Cyan, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ActionButton(text: String, icon: ImageVector, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = SurfaceHigh,
            contentColor = TextPrimary,
            disabledContentColor = TextMuted,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 12.sp)
    }
}
