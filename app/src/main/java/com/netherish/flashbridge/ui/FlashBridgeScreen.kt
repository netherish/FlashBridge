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
import com.netherish.flashbridge.core.MtkState
import com.netherish.flashbridge.core.UsbState
import com.netherish.flashbridge.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlashBridgeScreen(
    vm: MainViewModel,
    onPickFiles: () -> Unit,
    onPickDa: () -> Unit,
    onPickAuth: () -> Unit,
) {
    val state by vm.state.collectAsState()
    val usbStatus by vm.usbStatus.collectAsState()
    val adbStatus by vm.adbStatus.collectAsState()
    val mtkStatus by vm.mtkStatus.collectAsState()

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
                    info = when (state.deviceMode) {
                        DeviceMode.MTK -> state.mtkInfo?.chipName ?: ""
                        else -> state.adbModel.ifEmpty { state.deviceInfo.lines().firstOrNull() ?: "" }
                    },
                    mtkStatus = mtkStatus,
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
                    listOf("Flash", "Fastboot", "ADB", "MTK", "Console").forEachIndexed { i, title ->
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
                    0 -> FlashTab(state, vm, busy, onPickFiles) { title, action -> confirm = title to action }
                    1 -> FastbootTab(state, vm, busy) { title, action -> confirm = title to action }
                    2 -> AdbTab(state, vm, busy, shellCmd, { shellCmd = it },
                        pushPath = pushPath, onPushPath = { pushPath = it })
                    3 -> MtkTab(state, mtkStatus, vm, busy, onPickDa, onPickAuth) { title, action ->
                        confirm = title to action
                    }
                    4 -> ConsoleTab(state)
                }

                Text(
                    "Developed by netherish · v1.6 · All rights reserved",
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
private fun FlashTab(
    state: UiState,
    vm: MainViewModel,
    busy: Boolean,
    onPickFiles: () -> Unit,
    confirm: (String, () -> Unit) -> Unit,
) {
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

    SectionTitle("After flashing — factory reset")
    Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Most custom ROMs need data wiped after flashing — otherwise you can bootloop or get stuck on encryption. Format data first.",
                color = Warning, fontSize = 11.sp, lineHeight = 15.sp,
            )
            Button(
                onClick = { confirm("Factory reset the device?") { vm.factoryReset() } },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                enabled = fastbootReady && !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Error,
                    contentColor = Color.White,
                    disabledContainerColor = SurfaceHigh,
                    disabledContentColor = TextMuted,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Filled.RestartAlt, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("FACTORY RESET (format data + cache)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Format data", Icons.Filled.FormatSize, Modifier.weight(1f),
                    fastbootReady && !busy) { confirm("Format userdata?") { vm.formatData() } }
                ActionButton("Wipe cache", Icons.Filled.DeleteSweep, Modifier.weight(1f),
                    fastbootReady && !busy) { vm.wipeCache() }
            }
            Button(
                onClick = { vm.rebootFastboot() },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                enabled = fastbootReady && !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Success, contentColor = Color(0xFF00220F),
                    disabledContainerColor = SurfaceHigh, disabledContentColor = TextMuted,
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Filled.PowerSettingsNew, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Reboot system", fontWeight = FontWeight.Bold)
            }
        }
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

// ---- MTK tab -------------------------------------------------------------

@Composable
private fun MtkTab(
    state: UiState,
    mtkStatus: MtkState,
    vm: MainViewModel,
    busy: Boolean,
    onPickDa: () -> Unit,
    onPickAuth: () -> Unit,
    confirm: (String, () -> Unit) -> Unit,
) {
    val info = state.mtkInfo
    val da = state.daFile
    var entryIndex by remember { mutableIntStateOf(0) }
    var regionIndex by remember { mutableIntStateOf(0) }
    var use64 by remember { mutableStateOf(true) }
    var address by remember { mutableStateOf("") }
    var count by remember { mutableStateOf("1") }
    var values by remember { mutableStateOf("") }

    SectionTitle("MediaTek device")
    Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (info != null) {
                val rows = listOf(
                    "Chipset" to info.chipName,
                    "HW code" to "0x%04X".format(info.hwCode),
                    "HW version" to "0x%04X".format(info.hwVer),
                    "HW subcode" to "0x%04X".format(info.hwSubCode),
                    "SW version" to "0x%04X".format(info.swVer),
                    "BL version" to if (info.blVer >= 0) "0x%02X".format(info.blVer) else "n/a",
                    "BROM version" to if (info.bromVer >= 0) "0x%02X".format(info.bromVer) else "n/a",
                    "Mode" to if (mtkStatus is MtkState.DaRunning) "DA running" else if (info.isBrom) "BROM" else "Preloader",
                    "USB VID/PID" to "0x%04X:0x%04X".format(info.usb.vendorId, info.usb.productId),
                )
                rows.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(k, color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text(v, color = TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            } else {
                val arming = mtkStatus is MtkState.Arming
                val msg = when (mtkStatus) {
                    is MtkState.Arming ->
                        "Watching USB… power the phone off, hold Volume Up (or Volume Down) + Power, then plug in the cable. It will be detected automatically the moment it enters BROM."
                    is MtkState.NoDevice -> mtkStatus.message
                    is MtkState.Error -> mtkStatus.message
                    is MtkState.NeedPermission -> "Approve the USB permission prompt now"
                    else -> "Press Detect, then power off the phone, hold Volume Up or Volume Down and connect USB."
                }
                if (arming) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Cyan, strokeWidth = 2.dp)
                    Spacer(Modifier.height(8.dp))
                }
                Text(msg, color = if (arming) Cyan else TextMuted, fontSize = 12.sp)
            }
            if (state.mtkProgress.isNotEmpty()) {
                Text(state.mtkProgress, color = Cyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }

    if (info != null) {
        SectionTitle("Security configuration")
        Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(14.dp)) {
            Text(info.security, color = Warning, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                modifier = Modifier.padding(14.dp))
        }
    }

    SectionTitle("1 · Download Agent (you supply it)")
    Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Pick your own chipset-matched DA file. FlashBridge reads its header and recognises the protocol, chipsets and stages — no DA is bundled.",
                color = TextMuted, fontSize = 11.sp, lineHeight = 15.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("Choose DA", Modifier.weight(1f), !busy) { onPickDa() }
                if (da != null) SmallButton("Clear", Modifier.weight(0.5f), !busy) { vm.clearDa() }
            }
            if (da != null) {
                val match = info?.let { da.supports(it.hwCode) }
                val rows = listOf(
                    "File" to da.name,
                    "Size" to FileUtils.readable(da.size),
                    "Protocol" to da.protocol.label,
                    "File ID" to da.fileId,
                    "Version" to da.version.toString(),
                    "SoC entries" to da.entries.size.toString(),
                    "SHA-256" to da.sha256.take(24) + "…",
                    "Chip match" to when (match) {
                        null -> "detect a device first"
                        true -> "✓ matches 0x%04X".format(info!!.hwCode)
                        false -> "⚠ DA does not list 0x%04X".format(info!!.hwCode)
                    },
                )
                rows.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(k, color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text(v, color = if (k == "Chip match" && match == false) Error else TextPrimary,
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
                if (da.entries.size > 1) {
                    Text("SoC entry: $entryIndex · HW 0x%04X".format(da.entries[entryIndex.coerceIn(da.entries.indices)].hwCode),
                        color = TextMuted, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SmallButton("◀", Modifier.weight(1f), entryIndex > 0) { entryIndex-- }
                        SmallButton("▶", Modifier.weight(1f), entryIndex < da.entries.size - 1) { entryIndex++ }
                    }
                }
                val entry = da.entries[entryIndex.coerceIn(da.entries.indices)]
                if (entry.regions.isNotEmpty()) {
                    val region = entry.regions[regionIndex.coerceIn(entry.regions.indices)]
                    Text(
                        "Stage ${regionIndex + 1}/${entry.regions.size} · offset 0x%X · load 0x%08X · %s".format(
                            region.offset, region.address, FileUtils.readable(region.length.toLong())),
                        color = TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SmallButton("◀ stage", Modifier.weight(1f), regionIndex > 0) { regionIndex-- }
                        SmallButton("stage ▶", Modifier.weight(1f), regionIndex < entry.regions.size - 1) { regionIndex++ }
                    }
                }
            }
        }
    }

    SectionTitle("2 · Authorization files (you supply them)")
    Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "If the chip requires SLA/DAA/certificate authorization, add your own valid auth files. They are sent with the standard BROM auth commands — nothing is bypassed or generated by this app.",
                color = TextMuted, fontSize = 11.sp, lineHeight = 15.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallButton("Add auth files", Modifier.weight(1f), !busy) { onPickAuth() }
                if (state.authFiles.isNotEmpty()) SmallButton("Clear", Modifier.weight(0.5f), !busy) { vm.clearAuth() }
            }
            state.authFiles.forEach { auth ->
                Row(Modifier.fillMaxWidth()) {
                    Text("${auth.kind}: ${auth.name}", color = TextPrimary, fontSize = 11.sp,
                        modifier = Modifier.weight(1f))
                    Text(auth.sha256.take(10), color = TextMuted, fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }
            if (state.authFiles.isEmpty()) Text("No auth files added", color = TextMuted, fontSize = 11.sp)
        }
    }

    SectionTitle("3 · Boot DA stage")
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceHigh), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = use64, onCheckedChange = { use64 = it }, enabled = !busy)
                Text("64-bit DA (JUMP_DA64)", color = TextPrimary, fontSize = 11.sp)
            }
            Button(
                onClick = {
                    confirm("Upload and execute this DA stage on the connected device?") {
                        vm.bootDa(entryIndex, regionIndex, use64)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(46.dp),
                enabled = info != null && da != null && da.entries.isNotEmpty() && !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Color(0xFF00363D),
                    disabledContainerColor = Surface, disabledContentColor = TextMuted),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Filled.Memory, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("UPLOAD + JUMP DA", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Text(
                "This runs the DA you chose on your device. Wrong DA or auth files can hard-brick it. Continue only with files for your exact chipset.",
                color = Warning, fontSize = 11.sp, lineHeight = 15.sp,
            )
        }
    }

    SectionTitle("4 · Raw memory access (advanced)")
    Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(address, { address = it }, label = { Text("Address (hex)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(count, { count = it }, label = { Text("Word count") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            SmallButton("Read 32-bit", Modifier.fillMaxWidth(), info != null && !busy) {
                vm.mtkRead32(address, count)
            }
            OutlinedTextField(values, { values = it }, label = { Text("Values (hex, space separated)") },
                modifier = Modifier.fillMaxWidth())
            SmallButton("Write 32-bit", Modifier.fillMaxWidth(), info != null && !busy) {
                vm.mtkWrite32(address, values)
            }
        }
    }

    SectionTitle("Enter MediaTek download mode")
    Card(colors = CardDefaults.cardColors(containerColor = SurfaceHigh), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("• BROM: power off, hold Volume Up + Power (or Volume Down + Power), connect USB, then release.",
                color = TextPrimary, fontSize = 11.sp)
            Text("• Preloader: power off, connect USB with no buttons held.",
                color = TextPrimary, fontSize = 11.sp)
            Text("• If the port disconnects after a few seconds, hold the button through the whole Detect step.",
                color = TextMuted, fontSize = 11.sp)
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF3A2A00)), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Text("⛔", fontSize = 16.sp)
            Spacer(Modifier.width(8.dp))
            Text(
                "Bring your own DA and authorization files. FlashBridge recognises them and drives the standard upload/jump path in pure Kotlin. " +
                    "It does not bundle MediaTek binaries and does not implement exploit-based security bypasses. " +
                    "Partition-level flashing (write/format) is only possible once a compatible DA is running; always verify the chip match and keep backups.",
                color = Warning, fontSize = 11.sp, lineHeight = 15.sp,
            )
        }
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
private fun StatusCard(
    mode: DeviceMode,
    usbStatus: UsbState,
    adbStatus: AdbState,
    mtkStatus: MtkState,
    info: String,
) {
    val (color, title) = when (mode) {
        DeviceMode.FASTBOOT -> Success to "Fastboot connected ✓"
        DeviceMode.ADB -> Cyan to "ADB connected ✓"
        DeviceMode.RECOVERY -> Amber to "Recovery / sideload ✓"
        DeviceMode.MTK -> Amber to "MediaTek device identified ✓"
        DeviceMode.NONE -> {
            val problem = when {
                mtkStatus is MtkState.Arming -> "Watching USB for a MediaTek device…"
                adbStatus is AdbState.NeedPermission || usbStatus is UsbState.NeedPermission -> "Approve the USB prompt"
                adbStatus is AdbState.Error -> adbStatus.message
                usbStatus is UsbState.Error -> usbStatus.message
                mtkStatus is MtkState.Error -> mtkStatus.message
                mtkStatus is MtkState.NeedPermission -> "Approve the MTK USB prompt"
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
