package com.netherish.flashbridge.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.netherish.flashbridge.core.AdbState
import com.netherish.flashbridge.core.AdbUsb
import com.netherish.flashbridge.core.FastbootUsb
import com.netherish.flashbridge.core.FileUtils
import com.netherish.flashbridge.core.MtkDa
import com.netherish.flashbridge.core.MtkInfo
import com.netherish.flashbridge.core.MtkState
import com.netherish.flashbridge.core.MtkUsb
import com.netherish.flashbridge.core.UsbInfo
import com.netherish.flashbridge.core.UsbState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Busy { None, Running }
enum class DeviceMode { NONE, FASTBOOT, ADB, RECOVERY, MTK }

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
    val mtkInfo: MtkInfo? = null,
    val mtkProgress: String = "",
    val daFile: MtkDa.DaFile? = null,
    val authFiles: List<MtkDa.AuthFile> = emptyList(),
    val queue: List<FlashItem> = emptyList(),
    val defaultPartition: String = "boot",
    val selectedFile: String = "",
    val selectedUri: Uri? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val fastboot = FastbootUsb(app)
    private val adb = AdbUsb(app)
    private val mtk = MtkUsb(app)

    val usbStatus: StateFlow<UsbState> = fastboot.status
    val adbStatus: StateFlow<AdbState> = adb.status
    val mtkStatus: StateFlow<MtkState> = mtk.status
    val usbDevice: StateFlow<UsbInfo?> = fastboot.deviceInfo
    val adbDevice: StateFlow<UsbInfo?> = adb.deviceInfo
    val mtkInfo: StateFlow<MtkInfo?> = mtk.info
    val mtkProgress: StateFlow<String> = mtk.progress

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
        viewModelScope.launch {
            mtk.status.collect { st ->
                when (st) {
                    is MtkState.Connected -> {
                        val i = st.info
                        _state.value = _state.value.copy(
                            deviceMode = DeviceMode.MTK,
                            mtkInfo = i,
                        )
                        append("✓ MediaTek device: ${i.chipName}")
                        append("HW code 0x%04X · HW ver 0x%04X · BROM ${i.bromVer}".format(i.hwCode, i.hwVer))
                    }
                    is MtkState.Arming -> append("Watching USB — plug in the MTK device / hold the volume key now")
                    is MtkState.NeedPermission -> append("Approve the USB permission prompt for the MTK device")
                    is MtkState.DaRunning -> append("✓ DA stage running on device")
                    is MtkState.Error -> append("⚠ MTK: ${st.message}")
                    else -> {}
                }
            }
        }
        viewModelScope.launch {
            mtk.progress.collect { p ->
                _state.value = _state.value.copy(mtkProgress = p)
                if (p.isEmpty()) append("✓ MTK transfer complete")
            }
        }
    }

    // ---- Discovery -------------------------------------------------------

    fun scan() {
        append("Scanning USB…")
        fastboot.close()
        adb.close()
        mtk.close()
        _state.value = _state.value.copy(
            deviceMode = DeviceMode.NONE,
            adbModel = "",
            deviceInfo = "",
            mtkInfo = null,
        )

        fastboot.scan()
        when (fastboot.status.value) {
            is UsbState.NeedPermission, is UsbState.Connected -> return
            else -> adb.scan()
        }
        when (adb.status.value) {
            is AdbState.NeedPermission, is AdbState.Connected -> return
            else -> mtk.scan()
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

    /** Format userdata — clears encryption state after a custom ROM flash. */
    fun formatData() = action("format:userdata") {
        fastboot.format("userdata")
        append("✓ Data formatted")
    }

    fun formatMetadata() = action("format:metadata") {
        fastboot.format("metadata")
        append("✓ Metadata formatted")
    }

    fun wipeCache() = action("erase:cache") {
        fastboot.erase("cache")
        append("✓ Cache wiped")
    }

    /** The full post-flash reset most custom ROMs require. */
    fun factoryReset() = action("factory reset") {
        val results = fastboot.factoryReset()
        results.forEach { append("  $it") }
        append("✓ Factory reset complete")
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

    // ---- MediaTek DA / authorization -------------------------------------

    /** Parse and recognise a user-supplied Download Agent file. */
    fun loadDa(uri: Uri) = action("read DA file") {
        val da = withContext(Dispatchers.IO) { MtkDa.parseDa(getApplication(), uri) }
        _state.value = _state.value.copy(daFile = da)
        append("✓ DA: ${da.name}")
        append("  ${da.protocol.label} · ${da.fileId} · v${da.version}")
        append("  Chipsets: ${da.entries.joinToString(", ") { "0x%04X".format(it.hwCode) }}")
        val info = _state.value.mtkInfo
        if (info != null) {
            val match = da.supports(info.hwCode)
            append(if (match) "  ✓ DA matches connected chip 0x%04X".format(info.hwCode)
                   else "  ⚠ DA does NOT list connected chip 0x%04X".format(info.hwCode))
        }
        append("  SHA-256: ${da.sha256}")
    }

    fun clearDa() {
        _state.value = _state.value.copy(daFile = null)
        append("DA file cleared")
    }

    /** Add user-supplied BROM/CERT authorization files. */
    fun loadAuth(uris: List<Uri>) = action("read auth files") {
        val ctx = getApplication<Application>()
        val files = withContext(Dispatchers.IO) {
            uris.map { MtkDa.parseAuth(ctx, it) }
        }
        _state.value = _state.value.copy(authFiles = _state.value.authFiles + files)
        files.forEach { append("✓ Auth: ${it.kind} — ${it.name} (${it.sha256.take(16)}…)") }
    }

    fun clearAuth() {
        _state.value = _state.value.copy(authFiles = emptyList())
        append("Authorization files cleared")
    }

    /**
     * Upload one DA stage and jump to it. Everything outside the app — the
     * DA, the auth files and the chipset match — is supplied or confirmed by
     * the user.
     */
    fun bootDa(entryIndex: Int, regionIndex: Int, use64: Boolean) = action("boot DA stage") {
        val da = _state.value.daFile ?: throw IllegalStateException("Add a DA file first")
        val info = _state.value.mtkInfo ?: throw IllegalStateException("Detect an MTK device first")
        val entry = da.entries.getOrNull(entryIndex)
            ?: throw IllegalStateException("DA chipset entry not found")
        if (entry.hwCode != 0 && entry.hwCode != info.hwCode) {
            throw IllegalStateException(
                "DA is for 0x%04X, device is 0x%04X".format(entry.hwCode, info.hwCode)
            )
        }
        val region = entry.regions.getOrNull(regionIndex)
            ?: throw IllegalStateException("DA region not found")
        val ctx = getApplication<Application>()

        _state.value.authFiles.forEach { auth ->
            val data = withContext(Dispatchers.IO) {
                ctx.contentResolver.openInputStream(auth.uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Cannot read ${auth.name}")
            }
            append("→ Sending ${auth.kind}: ${auth.name}")
            withContext(Dispatchers.IO) {
                if (auth.name.contains("cert", true) || auth.kind.contains("cert", true)) {
                    mtk.sendCert(data)
                } else {
                    mtk.sendAuth(data)
                }
            }
            append("✓ ${auth.name} accepted")
        }

        val stage = withContext(Dispatchers.IO) { MtkDa.readRegion(ctx, da.uri, region) }
        append("→ Uploading DA stage to 0x%08X (%s bytes)".format(region.address, stage.size))
        withContext(Dispatchers.IO) {
            mtk.sendDa(stage, region.address, region.signatureLength)
        }
        append("✓ DA stage uploaded, jumping…")
        withContext(Dispatchers.IO) {
            if (use64) mtk.jumpDa64(region.address) else mtk.jumpDa(region.address)
        }
        append("✓ DA is running — the device is now under DA control")
    }

    /** Read raw 32-bit words from the device (hex address, decimal count). */
    fun mtkRead32(addressHex: String, countText: String) = action("read32") {
        val address = parseHex(addressHex)
        val count = countText.trim().toIntOrNull()?.coerceIn(1, 1024)
            ?: throw IllegalStateException("Count must be 1–1024")
        val values = withContext(Dispatchers.IO) { mtk.read32(address, count) }
        val out = values.mapIndexed { i, v -> "0x%08X: 0x%08X".format(address + i * 4, v) }
            .joinToString("\n")
        append(out)
        _state.value = _state.value.copy(terminal = out)
    }

    fun mtkWrite32(addressHex: String, valuesHex: String) = action("write32") {
        val address = parseHex(addressHex)
        val values = valuesHex.split(Regex("[\\s,]+"))
            .filter { it.isNotBlank() }
            .map { parseHex(it) }
        if (values.isEmpty()) throw IllegalStateException("Enter at least one hex value")
        withContext(Dispatchers.IO) { mtk.write32(address, values) }
        append("✓ Wrote ${values.size} word(s) at 0x%08X".format(address))
    }

    private fun parseHex(text: String): Int {
        val clean = text.trim().removePrefix("0x").removePrefix("0X")
        return clean.toIntOrNull(16) ?: throw IllegalStateException("Invalid hex value: $text")
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
        mtk.dispose()
    }
}
