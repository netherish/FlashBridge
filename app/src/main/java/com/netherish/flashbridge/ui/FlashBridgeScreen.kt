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
import com.netherish.flashbridge.core.AdbState
import com.netherish.flashbridge.core.FileUtils
import com.netherish.flashbridge.core.UsbState
import com.netherish.flashbridge.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlashBridgeScreen(vm: MainViewModel, onPickFiles: () -> Unit) {
    val state by vm.state.collectAsState()
    val usbStatus by vm.usbStatus.collectAsState()
    val adbStatus by vm.adbStatus.collectAsState()

    var tab by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var shellCmd by remember { mutableStateOf("getprop ro.product.model") }
    var pushPath by remember { mutableStateOf("") }

    val busy = state.busy != Busy.None

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⚡", fontSize = 22.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("FlashBridge", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        Text("ADVANCED", color = Amber, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                StatusCard(
                    mode = state.deviceMode,
                    usbStatus = usbStatus,
                    adbStatus = adbStatus,
                    info = state.adbModel.ifEmpty { state.deviceInfo.lines().firstOrNull() ?: "" },
                )

                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF3A2A00)), shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                        Text("⚠", fontSize = 16.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Flashing firmware can permanently damage a device. Use the correct files for your exact model. You are responsible for what you flash.",
                            color = Warning, fontSize = 11.sp, lineHeight = 15.sp,
                        )
                    }
                }

                Button(
                    onClick = { vm.scan() },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Color(0xFF00363D)),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Filled.Usb, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Detect device", fontWeight = FontWeight.Bold)
                }

                TabRow(
                    selectedTabIndex = tab,
                    containerColor = Surface,
                    contentColor = Cyan,
                    divider = { HorizontalDivider(color = SurfaceHigh) },
                ) {
                    listOf("Flash", "Fastboot", "ADB", "Console").forEachIndexed { i, title ->
                        Tab(
                            selected = tab == i,
                            onClick = { tab = i },
                            text = { Text(title, fontSize = 12.sp) },
                            selectedContentColor = Cyan,
                            unselectedContentColor = TextMuted,
                        )
                    }
                }

                when (tab) {
                    0 -> FlashTab(state, vm, busy, onPickFiles)
                    1 -> FastbootTab(state, vm, busy) { title, action -> confirm = title to action }
                    2 -> AdbTab(state, vm, busy, shellCmd, { shellCmd = it },
                        pushPath = pushPath, onPushPath = { pushPath = it })
                    3 -> ConsoleTab(state)
                }

                Text(
                    "Developed by netherish · v1.3 · All rights reserved",
                    color = TextMuted, fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp),
                )
            }
        }
    }

    confirm?.let { (title, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = { Text("This is a destructive operation on the connected device. Continue?") },
            confirmButton = {
                TextButton(onClick = { confirm = null; action() }) { Text("CONFIRM", color = Error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

// ---- Flash tab -----------------------------------------------------------

@Composable
private fun FlashTab(state: UiState, vm: MainViewModel, busy: Boolean, onPickFiles: () -> Unit) {
    val fastbootReady = state.deviceMode == DeviceMode.FASTBOOT
    val adbReady = state.deviceMode == DeviceMode.ADB || state.deviceMode == DeviceMode.RECOVERY

    SectionTitle("Image queue")
    Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onPickFiles,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan),
            ) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add image / ZIP files")
            }

            if (state.queue.isEmpty()) {
                Text("No images added yet. Add .img, .zip or ROM files.", color = TextMuted, fontSize = 12.sp)
            } else {
                state.queue.forEach { item ->
                    Card(colors = CardDefaults.cardColors(containerColor = SurfaceHigh), shape = RoundedCornerShape(10.dp)) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(item.name, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f))
                                IconButton(onClick = { vm.removeItem(item.id) }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Filled.Close, contentDescription = "Remove", tint = Error, modifier = Modifier.size(16.dp))
                                }
                            }
                            Text(
                                (if (item.size > 0) FileUtils.readable(item.size) else "size unknown") +
                                    (if (item.sha256.isNotEmpty()) " · SHA256 ${item.sha256.take(12)}…" else " · hashing…"),
                                color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                            )
                            OutlinedTextField(
                                value = item.partition,
                                onValueChange = { vm.setItemPartition(item.id, it) },
                                label = { Text("Partition", fontSize = 11.sp) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Amber, unfocusedBorderColor = TextMuted,
                                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                                ),
                            )
                        }
                    }
                }
                TextButton(onClick = { vm.clearQueue() }) { Text("Clear queue", color = Error, fontSize = 11.sp) }
            }

            OutlinedTextField(
                value = state.defaultPartition,
                onValueChange = { vm.setDefaultPartition(it) },
                label = { Text("Default partition for new files") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Amber, unfocusedBorderColor = TextMuted,
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                ),
            )
        }
    }

    if (state.progress.isNotEmpty()) {
        Text(state.progress, color = Cyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        LinearProgressIndicator(
            progress = { (state.progress.substringAfterLast(' ').removeSuffix("%").toFloatOrNull() ?: 0f) / 100f },
            modifier = Modifier.fillMaxWidth(), color = Amber,
        )
    }

    Button(
        onClick = { vm.flashQueue() },
        modifier = Modifier.fillMaxWidth().height(52.dp),
        enabled = fastbootReady && state.queue.isNotEmpty() && !busy,
        colors = ButtonDefaults.buttonColors(
            containerColor = Amber, contentColor = Color(0xFF1A1200),
            disabledContainerColor = SurfaceHigh, disabledContentColor = TextMuted,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Icon(Icons.Filled.FlashOn, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("FLASH ${state.queue.size} IMAGE(S)", fontWeight = FontWeight.Bold)
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionButton("Boot image", Icons.Filled.PlayArrow, Modifier.weight(1f),
            fastbootReady && state.queue.isNotEmpty() && !busy) { vm.bootImage() }
        ActionButton("Sideload ZIP", Icons.Filled.SystemUpdate, Modifier.weight(1f),
            adbReady && state.queue.isNotEmpty() && !busy) { vm.adbSideload() }
    }
}

// ---- Fastboot tab --------------------------------------------------------

@Composable
private fun FastbootTab(
    state: UiState,
    vm: MainViewModel,
    busy: Boolean,
    confirm: (String, () -> Unit) -> Unit,
) {
    val ready = state.deviceMode == DeviceMode.FASTBOOT

    SectionTitle("Device info")
    Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                state.deviceInfo.ifEmpty { "Press Detect, then Getvar all" },
                color = if (state.deviceInfo.isEmpty()) TextMuted else Success,
                fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
            )
            Button(onClick = { vm.getvarAll() }, enabled = ready && !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Info, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Getvar all")
            }
        }
    }

    SectionTitle("Bootloader")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton("Unlock", Icons.Filled.LockOpen, Modifier.weight(1f), ready && !busy) {
            confirm("Unlock bootloader?") { vm.flashingUnlock() }
        }
        ActionButton("Lock", Icons.Filled.Lock, Modifier.weight(1f), ready && !busy) {
            confirm("Lock bootloader?") { vm.flashingLock() }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton("Unlock critical", Icons.Filled.EnhancedEncryption, Modifier.weight(1f), ready && !busy) {
            confirm("Unlock critical partitions?") { vm.unlockCritical() }
        }
        ActionButton("Wipe", Icons.Filled.DeleteForever, Modifier.weight(1f), ready && !busy) {
            confirm("Wipe device?") { vm.wipe() }
        }
    }

    SectionTitle("Partitions")
    var partition by remember { mutableStateOf("boot") }
    OutlinedTextField(
        value = partition,
        onValueChange = { partition = it },
        label = { Text("Partition name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Amber, unfocusedBorderColor = TextMuted,
            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
        ),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton("Erase", Icons.Filled.Delete, Modifier.weight(1f), ready && !busy) {
            confirm("Erase $partition?") { vm.erase(partition) }
        }
        ActionButton("Format", Icons.Filled.FormatSize, Modifier.weight(1f), ready && !busy) {
            confirm("Format $partition?") { vm.format(partition) }
        }
    }

    SectionTitle("Active slot")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton("Slot A", Icons.Filled.LooksOne, Modifier.weight(1f), ready && !busy) { vm.setActive("a") }
        ActionButton("Slot B", Icons.Filled.LooksTwo, Modifier.weight(1f), ready && !busy) { vm.setActive("b") }
    }

    SectionTitle("Reboot")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton("System", Icons.Filled.PowerSettingsNew, Modifier.weight(1f), ready && !busy) { vm.rebootFastboot() }
        ActionButton("Recovery", Icons.Filled.MedicalServices, Modifier.weight(1f), ready && !busy) { vm.rebootRecovery() }
        ActionButton("Continue", Icons.Filled.SkipNext, Modifier.weight(1f), ready && !busy) { vm.continueBoot() }
    }

    SectionTitle("Raw commands")
    var raw by remember { mutableStateOf("getvar:product") }
    OutlinedTextField(
        value = raw,
        onValueChange = { raw = it },
        label = { Text("Fastboot command") },
        placeholder = { Text("getvar:product, erase:cache, oem …") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Amber, unfocusedBorderColor = TextMuted,
            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
        ),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton("Send raw", Icons.Filled.Send, Modifier.weight(1f), ready && !busy) { vm.fastbootRaw(raw) }
        ActionButton("OEM cmd", Icons.Filled.Build, Modifier.weight(1f), ready && !busy) {
            vm.oem(raw.removePrefix("oem ").trim())
        }
    }
}

// ---- ADB tab -------------------------------------------------------------

@Composable
private fun AdbTab(
    state: UiState,
    vm: MainViewModel,
    busy: Boolean,
    shellCmd: String,
    onShellCmd: (String) -> Unit,
    pushPath: String,
    onPushPath: (String) -> Unit,
) {
    val adbReady = state.deviceMode == DeviceMode.ADB || state.deviceMode == DeviceMode.RECOVERY

    SectionTitle("Device")
    Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(14.dp)) {
        Text(
            state.adbModel.ifEmpty { "Connect a device with USB debugging enabled, then press Detect." },
            color = if (state.adbModel.isEmpty()) TextMuted else Success,
            fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
            modifier = Modifier.padding(14.dp),
        )
    }

    SectionTitle("Shell")
    OutlinedTextField(
        value = shellCmd,
        onValueChange = onShellCmd,
        label = { Text("Shell command") },
        placeholder = { Text("getprop ro.product.model") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Amber, unfocusedBorderColor = TextMuted,
            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
        ),
    )
    Button(onClick = { vm.adbShell(shellCmd) }, enabled = adbReady && !busy, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.Terminal, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Run shell command")
    }

    SectionTitle("Sideload / Push")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton("Sideload ZIP", Icons.Filled.SystemUpdate, Modifier.weight(1f),
            adbReady && state.queue.isNotEmpty() && !busy) { vm.adbSideload() }
        ActionButton("Boot → Fastboot", Icons.Filled.RestartAlt, Modifier.weight(1f),
            adbReady && !busy) { vm.adbReboot("bootloader") }
    }
    OutlinedTextField(
        value = pushPath,
        onValueChange = onPushPath,
        label = { Text("Push destination (optional)") },
        placeholder = { Text("/sdcard/Download/file.zip") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Amber, unfocusedBorderColor = TextMuted,
            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
        ),
    )
    Button(onClick = { vm.adbPush(pushPath) }, enabled = adbReady && state.queue.isNotEmpty() && !busy,
        modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.FileUpload, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Push first queued file")
    }

    SectionTitle("Reboot")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SmallButton("Recovery", Modifier.weight(1f), adbReady && !busy) { vm.adbReboot("recovery") }
        SmallButton("Fastboot", Modifier.weight(1f), adbReady && !busy) { vm.adbReboot("fastboot") }
        SmallButton("Download", Modifier.weight(1f), adbReady && !busy) { vm.adbReboot("download") }
        SmallButton("System", Modifier.weight(1f), adbReady && !busy) { vm.adbReboot("") }
    }
}

// ---- Console tab ---------------------------------------------------------

@Composable
private fun ConsoleTab(state: UiState) {
    SectionTitle("Command output")
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF05070E)), shape = RoundedCornerShape(14.dp)) {
        Text(
            state.terminal.ifEmpty { "(no command output)" },
            color = Success, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
        )
    }
    SectionTitle("Session log")
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF05070E)), shape = RoundedCornerShape(14.dp)) {
        Text(
            state.log.ifEmpty { "(empty)" },
            color = Cyan, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
        )
    }
}

// ---- Shared composables --------------------------------------------------

@Composable
private fun StatusCard(mode: DeviceMode, usbStatus: UsbState, adbStatus: AdbState, info: String) {
    val (color, title) = when (mode) {
        DeviceMode.FASTBOOT -> Success to "Fastboot connected ✓"
        DeviceMode.ADB -> Cyan to "ADB connected ✓"
        DeviceMode.RECOVERY -> Amber to "Recovery / sideload ✓"
        DeviceMode.NONE -> {
            val problem = when {
                adbStatus is AdbState.NeedPermission || usbStatus is UsbState.NeedPermission -> "Approve the USB prompt"
                adbStatus is AdbState.Error -> adbStatus.message
                usbStatus is UsbState.Error -> usbStatus.message
                adbStatus is AdbState.NoDevice && usbStatus is UsbState.NoDevice ->
                    "No ADB/fastboot device — check OTG cable and mode"
                else -> "Press Detect to scan for devices"
            }
            Warning to problem
        }
    }
    val animated by animateColorAsState(color)
    Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(RoundedCornerShape(50)).background(animated))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                if (info.isNotEmpty()) Text(info, color = TextMuted, fontSize = 11.sp, maxLines = 2)
                Text("No root required · pure Kotlin USB", color = Cyan, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = Cyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
}

@Composable
private fun ActionButton(text: String, icon: ImageVector, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = SurfaceHigh, contentColor = TextPrimary,
            disabledContentColor = TextMuted,
        ),
        shape = RoundedCornerShape(10.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, fontSize = 11.sp)
    }
}

@Composable
private fun SmallButton(text: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(38.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = SurfaceHigh, contentColor = TextPrimary),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(4.dp),
    ) {
        Text(text, fontSize = 10.sp)
    }
}
