package com.netherish.flashbridge.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netherish.flashbridge.BuildConfig
import com.netherish.flashbridge.core.AdbState
import com.netherish.flashbridge.core.FileUtils
import com.netherish.flashbridge.core.UsbState
import com.netherish.flashbridge.ui.theme.*

/** Exact description of a pending destructive operation. */
private data class ConfirmRequest(
    val title: String,
    val details: String,
    val critical: Boolean = false,
    val token: String = "",
    val action: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlashBridgeScreen(
    vm: MainViewModel,
    onPickFiles: () -> Unit,
    onShareLog: (String) -> Unit,
) {
    val state by vm.state.collectAsState()
    val usbStatus by vm.usbStatus.collectAsState()
    val adbStatus by vm.adbStatus.collectAsState()

    var tab by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<ConfirmRequest?>(null) }
    var shellCmd by remember { mutableStateOf("getprop ro.product.model") }
    var pushPath by remember { mutableStateOf("") }

    val busy = state.busy != Busy.None || state.demoMode
    val advancedAvailable = BuildConfig.ADVANCED_FEATURES
    val requestConfirm: (ConfirmRequest) -> Unit = { confirm = it }

    // Motion blur (Compose has no true per-frame blur): a light smear while
    // scrolling, an animated blur on tab entry, and a heavier blurred backdrop
    // while a destructive-action dialog is on screen.
    val scroll = rememberScrollState()
    val dialogBlurAnim = remember { Animatable(0f) }
    LaunchedEffect(confirm != null) {
        dialogBlurAnim.animateTo(
            targetValue = if (confirm != null) 8f else 0f,
            animationSpec = tween<Float>(180),
        )
    }
    val scrollBlurAnim = remember { Animatable(0f) }
    LaunchedEffect(scroll.isScrollInProgress) {
        scrollBlurAnim.animateTo(
            targetValue = if (scroll.isScrollInProgress) 2f else 0f,
            animationSpec = tween<Float>(140),
        )
    }

    // Included in every destructive-operation dialog so the user can verify
    // that the operation is aimed at the device they actually connected.
    val deviceLabel = when (state.deviceMode) {
        DeviceMode.FASTBOOT -> {
            val product = state.deviceInfo.lines().firstOrNull { it.startsWith("product:") }
                ?.removePrefix("product:")?.trim().orEmpty()
            if (product.isEmpty()) "Fastboot device" else "Fastboot device ($product)"
        }
        DeviceMode.ADB -> state.adbModel.lines().firstOrNull().orEmpty()
            .ifEmpty { "ADB device" }.let { "ADB device ($it)" }
        DeviceMode.RECOVERY -> "Recovery device"
        DeviceMode.NONE -> "NO DEVICE DETECTED"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⚡", fontSize = 22.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("FlashBridge", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (advancedAvailable) "ADVANCED" else "STANDARD",
                            color = if (advancedAvailable) Amber else Success,
                            fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        )
                        if (state.demoMode) {
                            Spacer(Modifier.width(8.dp))
                            Text("DEMO", color = Error, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { vm.toggleDemo() }) {
                        Icon(Icons.Filled.Visibility, contentDescription = "Toggle demo mode")
                    }
                    IconButton(onClick = {
                        onShareLog(state.log + "\n\n--- TERMINAL ---\n" + state.terminal)
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share session log")
                    }
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
        Column(modifier = Modifier.padding(padding).fillMaxSize().blur(dialogBlurAnim.value.dp)) {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(scroll).padding(16.dp).blur(scrollBlurAnim.value.dp),
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
                            "Flashing firmware can turn your phone into a very expensive paperweight. Use files for your exact model. " +
                                "The app checks some things; it cannot save you from yourself.",
                            color = Warning, fontSize = 11.sp, lineHeight = 15.sp,
                        )
                    }
                }

                // Normal vs advanced separation: the standard build has no
                // destructive/experimental code compiled in at all.
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (state.advancedUnlocked) Color(0xFF4A1200) else Surface
                    ),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (advancedAvailable) "Advanced operations" else "Standard build",
                                color = if (advancedAvailable) TextPrimary else Success,
                                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            )
                            Text(
                                if (advancedAvailable)
                                    "Erase, unlock, wipe and raw commands are hidden until enabled."
                                else
                                    "This build omits destructive and experimental operations entirely.",
                                color = TextMuted, fontSize = 10.sp, lineHeight = 14.sp,
                            )
                        }
                        if (advancedAvailable) {
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = state.advancedUnlocked,
                                onCheckedChange = { vm.toggleAdvanced() },
                                enabled = !state.demoMode,
                            )
                        }
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
                    Text("Detect device — no mercy", fontWeight = FontWeight.Bold)
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

                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(200)) togetherWith fadeOut(animationSpec = tween(120))
                    },
                    label = "tabTransition",
                ) { targetTab ->
                    val enterBlur = remember { Animatable(6f) }
                    LaunchedEffect(Unit) {
                        enterBlur.animateTo(0f, animationSpec = tween<Float>(240, easing = FastOutSlowInEasing))
                    }
                    Column(Modifier.fillMaxWidth().blur(enterBlur.value.dp)) {
                        when (targetTab) {
                            0 -> FlashTab(state, vm, busy, advancedAvailable, onPickFiles, requestConfirm)
                            1 -> FastbootTab(state, vm, busy, advancedAvailable, requestConfirm)
                            2 -> AdbTab(state, vm, busy, advancedAvailable, shellCmd, { shellCmd = it },
                                pushPath = pushPath, onPushPath = { pushPath = it }, confirm = requestConfirm)
                            3 -> ConsoleTab(state)
                        }
                    }
                }

                Text(
                    "Developed by netherish · v1.8 · MIT licensed",
                    color = TextMuted, fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp),
                )
            }
        }
    }

    confirm?.let { req ->
        // No typing test — just a brutal, honest description of what is about
        // to happen. Cancelling clears the request without running the action.
        val lines = if (state.confirmDestructive) {
            if (req.critical) listOf(
                "Ah, bold of you to click this one.",
                "This is the exact moment 'it'll probably be fine' meets reality.",
                "No undo button. No take-backs. No support ticket for a brick.",
            ) else listOf(
                "You sure? The device is not going to ask twice.",
                "Bricks do not come with a return receipt.",
            )
        } else listOf("This action cannot be undone.")
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(req.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(req.details, fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text("Target device: $deviceLabel", fontSize = 12.sp,
                        color = if (state.deviceMode == DeviceMode.NONE) Error else Warning)
                    Spacer(Modifier.height(4.dp))
                    lines.forEach { Text("• $it", fontSize = 11.sp, color = Warning, lineHeight = 15.sp) }
                }
            },
            confirmButton = {
                TextButton(onClick = { confirm = null; req.action() }, enabled = !busy) {
                    Text(if (req.critical) "I KNOW WHAT I'M DOING" else "CONFIRM", color = Error)
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Nope — bail out") } },
        )
    }
}

// ---- Flash tab -----------------------------------------------------------

@Composable
private fun FlashTab(
    state: UiState,
    vm: MainViewModel,
    busy: Boolean,
    advanced: Boolean,
    onPickFiles: () -> Unit,
    confirm: (ConfirmRequest) -> Unit,
) {
    val fastbootReady = state.deviceMode == DeviceMode.FASTBOOT
    val adbReady = state.deviceMode == DeviceMode.ADB || state.deviceMode == DeviceMode.RECOVERY

    SectionTitle("Image queue — choose your poison")
    Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onPickFiles,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan),
            ) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add image / ZIP files (the questionable ones)")
            }

            if (state.queue.isEmpty()) {
                Text("No images yet. The brick count remains at zero. Add .img, .zip or ROM files.", color = TextMuted, fontSize = 12.sp)
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = state.confirmDestructive,
                    onCheckedChange = { vm.setConfirmDestructive(it) },
                    enabled = !busy,
                )
                Spacer(Modifier.width(8.dp))
                Text("Extra sass in warnings", color = TextPrimary, fontSize = 11.sp)
            }
            Text(
                "Settings live on this phone and won't follow you to the next one. Confirmations always stay on.",
                color = TextMuted, fontSize = 10.sp,
            )
        }
    }

    if (state.progress.isNotEmpty()) {
        Text(state.progress, color = Cyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        if (state.progressPercent >= 0) {
            LinearProgressIndicator(
                progress = { state.progressPercent / 100f },
                modifier = Modifier.fillMaxWidth(), color = Amber,
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Amber)
        }
    }

    Button(
        onClick = {
            val summary = state.queue.joinToString(", ") { "${it.name} → ${it.partition} (${FileUtils.readable(it.size)})" }
            confirm(ConfirmRequest(
                title = "Flash ${state.queue.size} image(s)?",
                details = "flash: $summary",
                critical = true,
                token = "FLASH",
            ) { vm.flashQueue() })
        },
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
        ActionButton("Boot once", Icons.Filled.PlayArrow, Modifier.weight(1f),
            fastbootReady && state.queue.isNotEmpty() && !busy) { vm.bootImage() }
        ActionButton("Sideload ZIP", Icons.Filled.SystemUpdate, Modifier.weight(1f),
            adbReady && state.queue.isNotEmpty() && !busy) { confirm(ConfirmRequest(
                title = "Sideload ZIP?",
                details = "adb sideload ${state.queue.firstOrNull()?.name ?: ""} — recovery will overwrite system partitions with whatever is in that ZIP",
                critical = true, token = "SIDELOAD",
            ) { vm.adbSideload() }) }
    }

    SectionTitle("After flashing — factory reset (or enjoy the bootloop)")
    Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Most custom ROMs demand a data wipe after flashing. Skip it and enjoy the bootloop / encryption screen forever. " +
                    "The pretty recovery UI is not a guarantee.",
                color = Warning, fontSize = 11.sp, lineHeight = 15.sp,
            )
            Button(
                onClick = { confirm(ConfirmRequest(
                    title = "Factory reset the device?",
                    details = "format:userdata + format:metadata + erase:cache — all user data will be erased",
                    critical = true, token = "FACTORY RESET",
                ) { vm.factoryReset() }) },
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
                    fastbootReady && !busy) { confirm(ConfirmRequest(
                        title = "Format userdata?",
                        details = "fastboot format:userdata — all user data and encryption keys will be erased",
                        critical = true, token = "FORMAT DATA",
                    ) { vm.formatData() }) }
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
    advanced: Boolean,
    confirm: (ConfirmRequest) -> Unit,
) {
    val ready = state.deviceMode == DeviceMode.FASTBOOT

    SectionTitle("Device info")
    Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                state.deviceInfo.ifEmpty { "Press Detect first, then Getvar all. Devices are shy." },
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

    var partition by remember { mutableStateOf("boot") }
    var raw by remember { mutableStateOf("getvar:product") }

    SectionTitle("Reboot — the safe buttons")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton("System", Icons.Filled.PowerSettingsNew, Modifier.weight(1f), ready && !busy) { vm.rebootFastboot() }
        ActionButton("Recovery", Icons.Filled.MedicalServices, Modifier.weight(1f), ready && !busy) { vm.rebootRecovery() }
        ActionButton("Continue", Icons.Filled.SkipNext, Modifier.weight(1f), ready && !busy) { vm.continueBoot() }
    }

    SectionTitle("The fun part — advanced only")
    if (!advanced) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0E2A16)), shape = RoundedCornerShape(14.dp)) {
            Text(
                "Standard build: unlock, erase, format, slot-swap and raw commands are not in this binary. " +
                    "Your device thanks you. Grab the full build if you enjoy living dangerously.",
                color = Success, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(14.dp),
            )
        }
    } else if (!state.advancedUnlocked) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF3A2A00)), shape = RoundedCornerShape(14.dp)) {
            Text(
                "Locked on purpose. Flip the Advanced switch up top if you want to see the buttons that can ruin your day.",
                color = Warning, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(14.dp),
            )
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Unlock", Icons.Filled.LockOpen, Modifier.weight(1f), ready && !busy) {
                confirm(ConfirmRequest(
                    "Unlock bootloader?", "fastboot flashing unlock — wipes user data and breaks the chain of trust",
                    true, "UNLOCK") { vm.flashingUnlock() })
            }
            ActionButton("Lock", Icons.Filled.Lock, Modifier.weight(1f), ready && !busy) {
                confirm(ConfirmRequest(
                    "Lock bootloader?", "fastboot flashing lock — bricking is very much on the menu if the ROM is not stock",
                    true, "LOCK") { vm.flashingLock() })
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Unlock critical", Icons.Filled.EnhancedEncryption, Modifier.weight(1f), ready && !busy) {
                confirm(ConfirmRequest(
                    "Unlock critical partitions?", "fastboot flashing unlock_critical — crosses another safety line",
                    true, "UNLOCK CRITICAL") { vm.unlockCritical() })
            }
            ActionButton("Wipe", Icons.Filled.DeleteForever, Modifier.weight(1f), ready && !busy) {
                confirm(ConfirmRequest(
                    "Wipe device?", "fastboot wipe — everything goes bye-bye",
                    true, "WIPE") { vm.wipe() })
            }
        }

        SectionTitle("Partitions — touch at your own risk")
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
                confirm(ConfirmRequest(
                    "Erase $partition?", "fastboot erase:$partition — gone, not coming back",
                    true, "ERASE") { vm.erase(partition) })
            }
            ActionButton("Format", Icons.Filled.FormatSize, Modifier.weight(1f), ready && !busy) {
                confirm(ConfirmRequest(
                    "Format $partition?", "fastboot format:$partition — rebuilds the filesystem from scratch",
                    true, "FORMAT") { vm.format(partition) })
            }
        }

        SectionTitle("Active slot")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Slot A", Icons.Filled.LooksOne, Modifier.weight(1f), ready && !busy) {
                confirm(ConfirmRequest(
                    "Switch active slot to A?", "fastboot set_active:a — next boot may not be the one you expect",
                    false, "SLOT A") { vm.setActive("a") })
            }
            ActionButton("Slot B", Icons.Filled.LooksTwo, Modifier.weight(1f), ready && !busy) {
                confirm(ConfirmRequest(
                    "Switch active slot to B?", "fastboot set_active:b — next boot may not be the one you expect",
                    false, "SLOT B") { vm.setActive("b") })
            }
        }

        SectionTitle("Raw commands — for people who read the docs")
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
            ActionButton("Send raw", Icons.Filled.Send, Modifier.weight(1f), ready && !busy) {
                confirm(ConfirmRequest(
                    "Send raw fastboot command?", "fastboot $raw — yes, this really sends that exact string",
                    true, "RAW") { vm.fastbootRaw(raw) })
            }
            ActionButton("OEM cmd", Icons.Filled.Build, Modifier.weight(1f), ready && !busy) {
                val cmd = raw.removePrefix("oem ").trim()
                confirm(ConfirmRequest(
                    "Send OEM command?", "fastboot oem $cmd — vendor territory, no guardrails here",
                    true, "OEM") { vm.oem(cmd) })
            }
        }
    }
}

// ---- ADB tab -------------------------------------------------------------

@Composable
private fun AdbTab(
    state: UiState,
    vm: MainViewModel,
    busy: Boolean,
    advanced: Boolean,
    shellCmd: String,
    onShellCmd: (String) -> Unit,
    pushPath: String,
    onPushPath: (String) -> Unit,
    confirm: (ConfirmRequest) -> Unit,
) {
    val adbReady = state.deviceMode == DeviceMode.ADB || state.deviceMode == DeviceMode.RECOVERY

    SectionTitle("Device")
    Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(14.dp)) {
        Text(
            state.adbModel.ifEmpty { "Connect a device with USB debugging enabled, then press Detect. Yes, the toggle is in Developer options." },
            color = if (state.adbModel.isEmpty()) TextMuted else Success,
            fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
            modifier = Modifier.padding(14.dp),
        )
    }

    SectionTitle("Shell — advanced only")
    if (!advanced || !state.advancedUnlocked) {
        Card(colors = CardDefaults.cardColors(containerColor = Surface), shape = RoundedCornerShape(14.dp)) {
            Text(
                if (!advanced) "Standard build: arbitrary shell is not compiled in. Your device's files stay where they are."
                else "Arbitrary shell is locked. Flip Advanced up top if you want a rootless command prompt with absolutely no safety net.",
                color = TextMuted, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(14.dp),
            )
        }
    } else {
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
            Text("Run shell command — no hand-holding")
        }
    }

    SectionTitle("Sideload / Push")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton("Sideload ZIP", Icons.Filled.SystemUpdate, Modifier.weight(1f),
            adbReady && state.queue.isNotEmpty() && !busy) { confirm(ConfirmRequest(
                title = "Sideload ZIP?",
                details = "adb sideload ${state.queue.firstOrNull()?.name ?: ""} — recovery will overwrite system partitions with whatever is in that ZIP",
                critical = true, token = "SIDELOAD",
            ) { vm.adbSideload() }) }
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
    Button(onClick = {
        val path = pushPath.ifBlank { "/sdcard/Download/${state.queue.firstOrNull()?.name ?: "file"}" }
        confirm(ConfirmRequest(
            "Push file to device?", "adb push → $path — existing files get overwritten without a polite warning",
            false, "PUSH",
        ) { vm.adbPush(pushPath) })
    }, enabled = adbReady && state.queue.isNotEmpty() && !busy,
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
            state.terminal.ifEmpty { "(silent — unlike the mistakes in the session log)" },
            color = Success, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
        )
    }
    SectionTitle("Session log")
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF05070E)), shape = RoundedCornerShape(14.dp)) {
        Text(
            state.log.ifEmpty { "(empty — suspiciously well-behaved so far)" },
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
    info: String,
) {
    val (color, title) = when (mode) {
        DeviceMode.FASTBOOT -> Success to "Fastboot connected ✓ — proceed at your own risk"
        DeviceMode.ADB -> Cyan to "ADB connected ✓ — the device trusts you now"
        DeviceMode.RECOVERY -> Amber to "Recovery / sideload ✓ — danger zone, nicely lit"
        DeviceMode.NONE -> {
            val problem = when {
                adbStatus is AdbState.NeedPermission || usbStatus is UsbState.NeedPermission -> "Approve the USB prompt (yes, the scary one)"
                adbStatus is AdbState.Error -> adbStatus.message
                usbStatus is UsbState.Error -> usbStatus.message
                adbStatus is AdbState.NoDevice && usbStatus is UsbState.NoDevice ->
                    "No ADB/fastboot device — check the OTG cable and the mode before blaming the app"
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
