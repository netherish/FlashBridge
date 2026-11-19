package com.netherish.flashbridge.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.netherish.flashbridge.core.AdbState
import com.netherish.flashbridge.core.AdbUsb
import com.netherish.flashbridge.core.FastbootUsb
import com.netherish.flashbridge.core.FileUtils
import com.netherish.flashbridge.core.UsbInfo
import com.netherish.flashbridge.core.UsbState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val lastResultOk: Boolean? = null,
    val queue: List<FlashItem> = emptyList(),
    val defaultPartition: String = "boot",
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

    private var nextId = 0L

    init {
        if (!fastboot.hasUsbHost()) append("⚠ This device has no USB host (OTG) support.")

        viewModelScope.launch {
            fastboot.status.collect { st ->
                if (st is UsbState.Connected) {
                    _state.value = _state.value.copy(deviceMode = DeviceMode.FASTBOOT)
                    append("✓ Fastboot device connected")
                    refreshDeviceInfo()
                }
            }
        }
        viewModelScope.launch {
            adb.status.collect { st ->
                if (st is AdbState.Connected) {
                    append("✓ ADB device connected")
                    refreshAdbInfo()
                }
            }
        }
    }

    // ---- Discovery -------------------------------------------------------

    fun scan() {
        append("Scanning USB…")
        fastboot.close()
        adb.close()
        _state.value = _state.value.copy(
            deviceMode = DeviceMode.NONE,
            adbModel = "",
            deviceInfo = "",
        )

        fastboot.scan()
        when (fastboot.status.value) {
            is UsbState.NeedPermission, is UsbState.Connected -> return
            else -> adb.scan()
        }
    }

    private fun refreshAdbInfo() {
        viewModelScope.launch {
            try {
                val model = withContext(Dispatchers.IO) {
                    listOf("ro.product.model", "ro.product.device", "ro.build.version.release", "ro.serialno")
                        .joinToString(" | ") { p -> "$p=${adb.getprop(p)}" }
                }
                _state.value = _state.value.copy(
                    deviceMode = DeviceMode.ADB,
                    adbModel = model,
                )
                append("Device: $model")
                // Recovery detection: sideload service is only present in recovery
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
                val info = withContext(Dispatchers.IO) {
                    listOf("product", "variant", "serialno", "unlocked", "state", "slot-count", "current-slot", "version-bootloader")
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
        val added = uris.map { uri ->
            val name = FileUtils.name(ctx, uri)
            FlashItem(nextId++, uri, name, _state.value.defaultPartition, FileUtils.size(ctx, uri))
        }
        _state.value = _state.value.copy(
            queue = _state.value.queue + added,
            selectedFile = added.lastOrNull()?.name ?: _state.value.selectedFile,
            selectedUri = added.lastOrNull()?.uri,
        )
        added.forEach { append("Added: ${it.name} → ${it.partition}") }
        // Compute checksums in the background
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
        _state.value = _state.value.copy(
            queue = _state.value.queue.map { if (it.id == id) it.copy(partition = value.trim()) else it }
        )
    }

    fun setDefaultPartition(value: String) {
        _state.value = _state.value.copy(defaultPartition = value.trim())
    }

    fun clearQueue() {
        _state.value = _state.value.copy(queue = emptyList(), selectedFile = "", selectedUri = null)
    }

    // ---- Fastboot actions ------------------------------------------------

    fun flashQueue() = action("flash queue") {
        val items = _state.value.queue
        if (items.isEmpty()) throw IllegalStateException("No images in queue")
        val ctx = getApplication<Application>()
        items.forEachIndexed { index, item ->
            append("[${index + 1}/${items.size}] Flashing ${item.name} → ${item.partition}")
            ctx.contentResolver.openInputStream(item.uri)?.use { input ->
                fastboot.flash(item.partition, input) { sent, total ->
                    val pct = if (total > 0) (sent * 100 / total) else 0
                    _state.value = _state.value.copy(progress = "${item.name}: $pct%")
                }
            } ?: throw IllegalStateException("Cannot open ${item.name}")
            append("✓ ${item.partition} flashed")
        }
        _state.value = _state.value.copy(progress = "")
    }

    fun bootImage() = action("boot image") {
        val item = _state.value.queue.firstOrNull() ?: throw IllegalStateException("Add an image first")
        getApplication<Application>().contentResolver.openInputStream(item.uri)?.use { input ->
            fastboot.boot(input) { sent, total ->
                _state.value = _state.value.copy(progress = "${(sent * 100 / total)}%")
            }
        }
        _state.value = _state.value.copy(progress = "")
        append("✓ Boot image sent (temporary boot)")
    }

    fun getvarAll() = action("getvar all") {
        val info = fastboot.getvarAll()
        _state.value = _state.value.copy(deviceInfo = info)
        append(info)
    }

    fun erase(partition: String) = action("erase $partition") {
        fastboot.erase(partition)
        append("✓ Erase complete")
    }

    fun format(partition: String) = action("format $partition") {
        fastboot.format(partition)
        append("✓ Format complete")
    }

    fun wipe() = action("wipe") {
        fastboot.wipe()
        append("✓ Wipe complete")
    }

    fun setActive(slot: String) = action("set_active $slot") {
        fastboot.setActive(slot)
        append("✓ Active slot set to $slot")
    }

    fun flashingUnlock() = action("flashing unlock") {
        fastboot.flashingUnlock()
        append("✓ Unlock command sent")
    }

    fun flashingLock() = action("flashing lock") {
        fastboot.flashingLock()
        append("✓ Lock command sent")
    }

    fun unlockCritical() = action("flashing unlock_critical") {
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

    fun fastbootRaw(cmd: String) = action(cmd) {
        val out = fastboot.raw(cmd)
        append(if (out.isNotEmpty()) out else "✓ OKAY")
        _state.value = _state.value.copy(terminal = out)
    }

    fun oem(cmd: String) = action("oem $cmd") {
        val out = fastboot.oem(cmd)
        append(if (out.isNotEmpty()) out else "✓ OKAY")
        _state.value = _state.value.copy(terminal = out)
    }

    // ---- ADB actions -----------------------------------------------------

    fun adbShell(command: String) = action("adb shell $command") {
        val out = withContext(Dispatchers.IO) { adb.shell(command, 30000) }
        append(out.ifEmpty { "(no output)" })
        _state.value = _state.value.copy(terminal = out)
    }

    fun adbSideload() = action("adb sideload") {
        val item = _state.value.queue.firstOrNull() ?: throw IllegalStateException("Add a ZIP to sideload")
        val size = if (item.size > 0) item.size else
            FileUtils.size(getApplication(), item.uri)
        getApplication<Application>().contentResolver.openInputStream(item.uri)?.use { input ->
            withContext(Dispatchers.IO) {
                adb.sideload(input, size) { sent, total ->
                    _state.value = _state.value.copy(progress = "${item.name}: ${(sent * 100 / total)}%")
                }
            }
        } ?: throw IllegalStateException("Cannot open ${item.name}")
        _state.value = _state.value.copy(progress = "")
        append("✓ Sideload complete")
    }

    fun adbPush(remotePath: String) = action("adb push") {
        val item = _state.value.queue.firstOrNull() ?: throw IllegalStateException("Add a file to push")
        val size = if (item.size > 0) item.size else FileUtils.size(getApplication(), item.uri)
        val path = remotePath.ifBlank { "/sdcard/Download/${item.name}" }
        getApplication<Application>().contentResolver.openInputStream(item.uri)?.use { input ->
            withContext(Dispatchers.IO) {
                adb.push(input, size, path) { sent, total ->
                    _state.value = _state.value.copy(progress = "${(sent * 100 / total)}%")
                }
            }
        } ?: throw IllegalStateException("Cannot open ${item.name}")
        _state.value = _state.value.copy(progress = "")
        append("✓ Pushed to $path")
    }

    fun adbReboot(target: String) = action("adb reboot $target") {
        adb.reboot(target)
        append("✓ Reboot sent — device is going $target")
        adb.close()
        _state.value = _state.value.copy(deviceMode = DeviceMode.NONE)
    }

    // ---- Shared ----------------------------------------------------------

    private fun action(label: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = Busy.Running, lastResultOk = null)
            append("$ $label")
            try {
                withContext(Dispatchers.IO) { block() }
                _state.value = _state.value.copy(busy = Busy.None, lastResultOk = true)
            } catch (e: Throwable) {
                append("✗ ${e.message ?: "Failed"}")
                _state.value = _state.value.copy(busy = Busy.None, lastResultOk = false, progress = "")
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
