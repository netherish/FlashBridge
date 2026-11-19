package com.netherish.flashbridge.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.netherish.flashbridge.BuildConfig
import com.netherish.flashbridge.core.AdbState
import com.netherish.flashbridge.core.AdbUsb
import com.netherish.flashbridge.core.FastbootUsb
import com.netherish.flashbridge.core.FileUtils
import com.netherish.flashbridge.core.UsbInfo
import com.netherish.flashbridge.core.UsbState
import com.netherish.flashbridge.core.Validation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

enum class Busy { None, Running }
enum class DeviceMode { NONE, FASTBOOT, ADB, RECOVERY }

data class FlashItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val partition: String,
    val size: Long,
    val sha256: String = "",
)

data class UiState(
    val busy: Busy = Busy.None,
    val log: String = "FlashBridge ready — no root required.\nConnect a device in ADB, recovery or fastboot mode via OTG.\n",
    val deviceMode: DeviceMode = DeviceMode.NONE,
    val adbModel: String = "",
    val deviceInfo: String = "",
    val terminal: String = "",
    val progress: String = "",
    /** 0..100 while a transfer is running, -1 when there is no active transfer. */
    val progressPercent: Int = -1,
    val lastResultOk: Boolean? = null,
    val queue: List<FlashItem> = emptyList(),
    val defaultPartition: String = "boot",
    val confirmDestructive: Boolean = true,
    val advancedUnlocked: Boolean = false,
    val demoMode: Boolean = false,
    val selectedFile: String = "",
    val selectedUri: Uri? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val fastboot = FastbootUsb(app)
    private val adb = AdbUsb(app)

    val usbStatus: StateFlow<UsbState> = fastboot.status
    val adbStatus: StateFlow<AdbState> = adb.status
    val usbDevice: StateFlow<UsbInfo?> = fastboot.deviceInfo
    val adbDevice: StateFlow<UsbInfo?> = adb.deviceInfo

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val prefs: SharedPreferences =
        app.getSharedPreferences("flashbridge_settings", Context.MODE_PRIVATE)

    private var nextId = 0L
    private val scanning = AtomicBoolean(false)

    init {
        _state.value = _state.value.copy(
            defaultPartition = prefs.getString("default_partition", "boot") ?: "boot",
            confirmDestructive = prefs.getBoolean("confirm_destructive", true),
        )
        if (!fastboot.hasUsbHost()) append("⚠ This device has no USB host (OTG) support.")

        viewModelScope.launch {
            fastboot.status.collect { st ->
                if (st is UsbState.Connected) {
                    _state.value = _state.value.copy(deviceMode = DeviceMode.FASTBOOT)
                    append("✓ Fastboot device connected")
                    refreshDeviceInfo()
                } else if (st is UsbState.Error) {
                    append("⚠ USB: ${st.message}")
                    _state.value = _state.value.copy(deviceMode = DeviceMode.NONE)
                }
            }
        }
        viewModelScope.launch {
            adb.status.collect { st ->
                if (st is AdbState.Connected) {
                    append("✓ ADB device connected")
                    refreshAdbInfo()
                } else if (st is AdbState.Error) {
                    append("⚠ ADB: ${st.message}")
                    _state.value = _state.value.copy(deviceMode = DeviceMode.NONE)
                }
            }
        }
    }

    // ---- Discovery -------------------------------------------------------

    /** USB probing does blocking I/O, so it runs off the main thread. */
    fun scan() {
        if (!scanning.compareAndSet(false, true)) {
            append("Scan already in progress")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (_state.value.demoMode) {
                    _state.value = _state.value.copy(demoMode = false)
                    append("Demo mode closed")
                }
                append("Scanning USB…")
                fastboot.close()
                adb.close()
                _state.value = _state.value.copy(
                    deviceMode = DeviceMode.NONE,
                    adbModel = "",
                    deviceInfo = "",
                    progress = "",
                    progressPercent = -1,
                )

                fastboot.scan()
                when (fastboot.status.value) {
                    is UsbState.NeedPermission, is UsbState.Connected -> return@launch
                    else -> adb.scan()
                }
                when (adb.status.value) {
                    is AdbState.NeedPermission, is AdbState.Connected -> return@launch
                    else -> append(
                        (adb.status.value as? AdbState.NoDevice)?.message
                            ?: (fastboot.status.value as? UsbState.NoDevice)?.message
                            ?: "No fastboot or ADB device found — check the cable and device mode"
                    )
                }
            } finally {
                scanning.set(false)
            }
        }
    }

    private fun refreshAdbInfo() {
        viewModelScope.launch {
            try {
                // Deliberately do not collect serial numbers or other unique IDs.
                val model = withContext(Dispatchers.IO) {
                    listOf("ro.product.model", "ro.product.device", "ro.build.version.release")
                        .joinToString(" | ") { p -> "$p=${adb.getprop(p)}" }
                }
                _state.value = _state.value.copy(deviceMode = DeviceMode.ADB, adbModel = model)
                append("Device: $model")
                val isRecovery = withContext(Dispatchers.IO) {
                    try { adb.shell("getprop ro.build.type").contains("recovery", true) } catch (_: Throwable) { false }
                }
                if (isRecovery) {
                    _state.value = _state.value.copy(deviceMode = DeviceMode.RECOVERY)
                    append("Recovery mode detected — sideload available")
                }
            } catch (e: Throwable) {
                append("⚠ Could not read device info: ${e.message}")
                _state.value = _state.value.copy(deviceMode = DeviceMode.ADB)
            }
        }
    }

    private fun refreshDeviceInfo() {
        viewModelScope.launch {
            try {
                // No serial numbers: keep the log free of unique device identifiers.
                val info = withContext(Dispatchers.IO) {
                    listOf("product", "variant", "unlocked", "state", "slot-count", "current-slot")
                        .joinToString("\n") { v -> "$v: ${try { fastboot.getvar(v) } catch (_: Throwable) { "n/a" }}" }
                }
                _state.value = _state.value.copy(deviceInfo = info)
            } catch (e: Throwable) {
                append("⚠ getvar failed: ${e.message}")
            }
        }
    }

    // ---- File queue ------------------------------------------------------

    fun addFiles(uris: List<Uri>) {
        val ctx = getApplication<Application>()
        val defaultPart = if (Validation.isValidPartition(_state.value.defaultPartition)) {
            _state.value.defaultPartition
        } else "boot"
        val added = mutableListOf<FlashItem>()
        uris.forEach { uri ->
            val name = FileUtils.name(ctx, uri)
            val size = FileUtils.size(ctx, uri)
            if (!Validation.isValidFileSize(size)) {
                append("✗ Rejected ${Validation.logSafe(name)}: " +
                    if (size <= 0) "empty or unknown size" else "over ${FileUtils.readable(Validation.MAX_FILE_SIZE)}")
                return@forEach
            }
            added += FlashItem(nextId++, uri, name, defaultPart, size)
        }
        if (added.isEmpty()) return
        _state.value = _state.value.copy(
            queue = _state.value.queue + added,
            selectedFile = added.lastOrNull()?.name ?: _state.value.selectedFile,
            selectedUri = added.lastOrNull()?.uri,
        )
        added.forEach { append("Added: ${Validation.logSafe(it.name)}") }
        viewModelScope.launch {
            added.forEach { item ->
                try {
                    val hash = withContext(Dispatchers.IO) {
                        ctx.contentResolver.openInputStream(item.uri)?.use { FileUtils.hash(it, "SHA-256") } ?: ""
                    }
                    _state.value = _state.value.copy(
                        queue = _state.value.queue.map { if (it.id == item.id) it.copy(sha256 = hash) else it }
                    )
                } catch (_: Throwable) {}
            }
        }
    }

    fun onFileSelected(uri: Uri?) {
        if (uri != null) addFiles(listOf(uri))
    }

    fun removeItem(id: Long) {
        _state.value = _state.value.copy(queue = _state.value.queue.filterNot { it.id == id })
    }

    fun setItemPartition(id: Long, value: String) {
        val clean = value.trim()
        _state.value = _state.value.copy(
            queue = _state.value.queue.map { if (it.id == id) it.copy(partition = clean) else it }
        )
    }

    fun setDefaultPartition(value: String) {
        val clean = value.trim()
        _state.value = _state.value.copy(defaultPartition = clean)
        prefs.edit().putString("default_partition", clean).apply()
    }

    fun setConfirmDestructive(value: Boolean) {
        _state.value = _state.value.copy(confirmDestructive = value)
        prefs.edit().putBoolean("confirm_destructive", value).apply()
        append(if (value) "Safety confirmations enabled" else "Safety confirmations disabled")
    }

    /** Deliberate, session-only opt-in for destructive/experimental operations. */
    fun toggleAdvanced() {
        if (!BuildConfig.ADVANCED_FEATURES) {
            append("Advanced operations are disabled in this build")
            return
        }
        val next = !_state.value.advancedUnlocked
        _state.value = _state.value.copy(advancedUnlocked = next)
        append(if (next) {
            "⚠ ADVANCED MODE ENABLED — destructive and experimental operations are available. Disable when finished."
        } else "Advanced mode disabled")
    }

    private fun requireAdvanced(feature: String) {
        if (!BuildConfig.ADVANCED_FEATURES) {
            throw IllegalStateException("$feature is disabled in this build")
        }
        if (!_state.value.advancedUnlocked) {
            throw IllegalStateException("Enable Advanced mode to $feature")
        }
    }

    /** Sample device for documentation/screenshots. Never performs real actions. */
    fun toggleDemo() {
        if (_state.value.demoMode) {
            _state.value = _state.value.copy(
                demoMode = false, deviceMode = DeviceMode.NONE, deviceInfo = "", adbModel = ""
            )
            append("Demo mode closed")
            return
        }
        _state.value = _state.value.copy(
            demoMode = true,
            deviceMode = DeviceMode.FASTBOOT,
            deviceInfo = listOf(
                "product: demo_device", "variant: demo_a", "unlocked: yes", "state: unlocked",
                "slot-count: 2", "current-slot: a", "version-bootloader: 1.0-DEMO",
            ).joinToString("\n"),
            adbModel = "DEMO DEVICE (fastboot)",
        )
        append("DEMO MODE — sample device shown. All actions are disabled until demo mode is closed.")
    }

    fun clearQueue() {
        _state.value = _state.value.copy(queue = emptyList(), selectedFile = "", selectedUri = null)
    }

    // ---- Fastboot actions ------------------------------------------------

    fun flashQueue() = action("flash queue") {
        val items = _state.value.queue
        if (items.isEmpty()) throw IllegalStateException("No images in queue")

        val problems = mutableListOf<String>()
        items.forEach { item ->
            Validation.partitionError(item.partition)?.let { problems += "${item.name}: $it" }
            if (item.size <= 0) problems += "${item.name}: unknown or invalid file size"
            if (item.sha256.isBlank()) problems += "${item.name}: checksum not ready yet"
        }
        val duplicates = items.groupBy { it.partition }.filter { it.value.size > 1 }.keys
        duplicates.forEach { problems += "duplicate partition: $it" }
        if (problems.isNotEmpty()) throw IllegalStateException("Preflight failed: ${problems.joinToString("; ")}")

        val total = items.sumOf { it.size }
        append("Preflight OK — ${items.size} image(s), ${FileUtils.readable(total)}, partitions: " +
            items.joinToString(", ") { it.partition })
        val ctx = getApplication<Application>()
        items.forEachIndexed { index, item ->
            append("[${index + 1}/${items.size}] Flashing ${Validation.logSafe(item.name)} → ${item.partition}")
            ctx.contentResolver.openInputStream(item.uri)?.use { input ->
                fastboot.flash(item.partition, input) { sent, totalBytes ->
                    val pct = if (totalBytes > 0) (sent * 100L / totalBytes).toInt().coerceIn(0, 100) else 0
                    _state.value = _state.value.copy(progress = "${item.name}: $pct%", progressPercent = pct)
                }
            } ?: throw IllegalStateException("Cannot open ${item.name}")
            append("✓ ${item.partition} flashed")
        }
        _state.value = _state.value.copy(progress = "", progressPercent = -1)
    }

    fun bootImage() = action("boot image") {
        val item = _state.value.queue.firstOrNull() ?: throw IllegalStateException("Add an image first")
        if (item.size <= 0) throw IllegalStateException("Unknown image size")
        getApplication<Application>().contentResolver.openInputStream(item.uri)?.use { input ->
            fastboot.boot(input) { sent, total ->
                val pct = if (total > 0) (sent * 100L / total).toInt().coerceIn(0, 100) else 0
                _state.value = _state.value.copy(progress = "$pct%", progressPercent = pct)
            }
        } ?: throw IllegalStateException("Cannot open ${item.name}")
        _state.value = _state.value.copy(progress = "", progressPercent = -1)
        append("✓ Boot image sent (temporary boot)")
    }

    fun getvarAll() = action("getvar all") {
        val info = fastboot.getvarAll()
        _state.value = _state.value.copy(deviceInfo = info)
        append(info)
    }

    fun erase(partition: String) = action("erase $partition") {
        requireAdvanced("erase partitions")
        Validation.partitionError(partition)?.let { throw IllegalStateException(it) }
        fastboot.erase(partition)
        append("✓ Erase complete")
    }

    fun format(partition: String) = action("format $partition") {
        requireAdvanced("format partitions")
        Validation.partitionError(partition)?.let { throw IllegalStateException(it) }
        fastboot.format(partition)
        append("✓ Format complete")
    }

    fun wipe() = action("wipe") {
        requireAdvanced("wipe the device")
        fastboot.wipe()
        append("✓ Wipe complete")
    }

    fun formatData() = action("format:userdata") {
        fastboot.format("userdata")
        append("✓ Data formatted")
    }

    fun formatMetadata() = action("format:metadata") {
        requireAdvanced("format metadata")
        fastboot.format("metadata")
        append("✓ Metadata formatted")
    }

    fun wipeCache() = action("erase:cache") {
        fastboot.erase("cache")
        append("✓ Cache wiped")
    }

    fun factoryReset() = action("factory reset") {
        val results = fastboot.factoryReset()
        results.forEach { append("  $it") }
        append("✓ Factory reset complete")
    }

    fun setActive(slot: String) = action("set_active $slot") {
        requireAdvanced("change the active slot")
        if (!Validation.isValidSlot(slot)) throw IllegalStateException("Invalid slot: $slot")
        fastboot.setActive(slot)
        append("✓ Active slot set to $slot")
    }

    fun flashingUnlock() = action("flashing unlock") {
        requireAdvanced("unlock the bootloader")
        fastboot.flashingUnlock()
        append("✓ Unlock command sent")
    }

    fun flashingLock() = action("flashing lock") {
        requireAdvanced("lock the bootloader")
        fastboot.flashingLock()
        append("✓ Lock command sent")
    }

    fun unlockCritical() = action("flashing unlock_critical") {
        requireAdvanced("unlock critical partitions")
        fastboot.flashingUnlockCritical()
        append("✓ Critical unlock sent")
    }

    fun rebootFastboot() = action("reboot") {
        fastboot.reboot()
        append("✓ Reboot sent")
    }

    fun rebootRecovery() = action("reboot-recovery") {
        fastboot.rebootRecovery()
        append("✓ Reboot to recovery sent")
    }

    fun continueBoot() = action("continue") {
        fastboot.continueBoot()
        append("✓ Continue sent")
    }

    fun fastbootRaw(cmd: String) = action("raw fastboot command") {
        requireAdvanced("send raw fastboot commands")
        if (!Validation.isValidFastbootCommand(cmd)) throw IllegalStateException("Invalid command")
        val out = fastboot.raw(cmd)
        append(if (out.isNotEmpty()) out else "✓ OKAY")
        _state.value = _state.value.copy(terminal = out)
    }

    fun oem(cmd: String) = action("OEM command") {
        requireAdvanced("send OEM commands")
        if (!Validation.isValidFastbootCommand(cmd)) throw IllegalStateException("Invalid command")
        val out = fastboot.oem(cmd)
        append(if (out.isNotEmpty()) out else "✓ OKAY")
        _state.value = _state.value.copy(terminal = out)
    }

    // ---- ADB actions -----------------------------------------------------

    fun adbShell(command: String) = action("adb shell") {
        requireAdvanced("run arbitrary ADB shell commands")
        if (!Validation.isValidShellCommand(command)) throw IllegalStateException("Invalid shell command")
        val out = withContext(Dispatchers.IO) { adb.shell(command, 30000) }
        append(out.ifEmpty { "(no output)" })
        _state.value = _state.value.copy(terminal = out)
    }

    fun adbSideload() = action("adb sideload") {
        val item = _state.value.queue.firstOrNull() ?: throw IllegalStateException("Add a ZIP to sideload")
        val size = if (item.size > 0) item.size else FileUtils.size(getApplication(), item.uri)
        if (!Validation.isValidFileSize(size)) throw IllegalStateException("Unknown or invalid file size")
        getApplication<Application>().contentResolver.openInputStream(item.uri)?.use { input ->
            withContext(Dispatchers.IO) {
                adb.sideload(input, size) { sent, total ->
                    val pct = if (total > 0) (sent * 100L / total).toInt().coerceIn(0, 100) else 0
                    _state.value = _state.value.copy(progress = "${item.name}: $pct%", progressPercent = pct)
                }
            }
        } ?: throw IllegalStateException("Cannot open ${item.name}")
        _state.value = _state.value.copy(progress = "", progressPercent = -1)
        append("✓ Sideload complete")
    }

    fun adbPush(remotePath: String) = action("adb push") {
        val item = _state.value.queue.firstOrNull() ?: throw IllegalStateException("Add a file to push")
        val size = if (item.size > 0) item.size else FileUtils.size(getApplication(), item.uri)
        if (!Validation.isValidFileSize(size)) throw IllegalStateException("Unknown or invalid file size")
        val path = remotePath.ifBlank { "/sdcard/Download/${item.name}" }
        if (!Validation.isValidRemotePath(path)) throw IllegalStateException("Invalid remote path")
        getApplication<Application>().contentResolver.openInputStream(item.uri)?.use { input ->
            withContext(Dispatchers.IO) {
                adb.push(input, size, path) { sent, total ->
                    val pct = if (total > 0) (sent * 100L / total).toInt().coerceIn(0, 100) else 0
                    _state.value = _state.value.copy(progress = "$pct%", progressPercent = pct)
                }
            }
        } ?: throw IllegalStateException("Cannot open ${item.name}")
        _state.value = _state.value.copy(progress = "", progressPercent = -1)
        append("✓ Pushed to $path")
    }

    fun adbReboot(target: String) = action("adb reboot $target") {
        if (target.length > 64) throw IllegalStateException("Invalid reboot target")
        adb.reboot(target)
        append("✓ Reboot sent — device is going ${target.ifEmpty { "system" }}")
        adb.close()
        _state.value = _state.value.copy(deviceMode = DeviceMode.NONE)
    }

    // ---- Shared ----------------------------------------------------------

    private fun action(label: String, block: suspend () -> Unit) {
        if (_state.value.demoMode) {
            append("Demo mode — actions are disabled")
            return
        }
        // Reject duplicate taps before any coroutine is launched.
        if (_state.value.busy == Busy.Running) {
            append("An operation is already running — ignoring duplicate request")
            return
        }
        _state.value = _state.value.copy(busy = Busy.Running, lastResultOk = null)
        append("$ $label")
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                _state.value = _state.value.copy(
                    busy = Busy.None, lastResultOk = true, progress = "", progressPercent = -1
                )
            } catch (e: Throwable) {
                append("✗ ${e.message ?: "Failed"}")
                _state.value = _state.value.copy(
                    busy = Busy.None, lastResultOk = false, progress = "", progressPercent = -1
                )
            }
        }
    }

    fun clearLog() {
        _state.value = _state.value.copy(log = "", terminal = "")
    }

    private fun append(line: String) {
        _state.value = _state.value.copy(log = (_state.value.log + line + "\n").takeLast(30000))
    }

    override fun onCleared() {
        super.onCleared()
        fastboot.dispose()
        adb.dispose()
    }
}
