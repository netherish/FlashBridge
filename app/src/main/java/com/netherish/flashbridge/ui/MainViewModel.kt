package com.netherish.flashbridge.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.netherish.flashbridge.core.FastbootUsb
import com.netherish.flashbridge.core.UsbInfo
import com.netherish.flashbridge.core.UsbState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Busy { None, Running }

data class UiState(
    val busy: Busy = Busy.None,
    val log: String = "FlashBridge ready — no root required.\nConnect a device in fastboot mode via OTG.\n",
    val selectedFile: String = "",
    val partition: String = "boot",
    val progress: String = "",
    val lastResultOk: Boolean? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val fastboot = FastbootUsb(app)

    val usbStatus: StateFlow<UsbState> = fastboot.status
    val usbDevice: StateFlow<UsbInfo?> = fastboot.deviceInfo

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var selectedUri: Uri? = null

    init {
        if (!fastboot.hasUsbHost()) {
            append("⚠ This device has no USB host (OTG) support.")
        }
    }

    fun scan() {
        append("Scanning for fastboot device…")
        fastboot.scan()
    }

    fun onFileSelected(uri: Uri?) {
        selectedUri = uri
        val name = uri?.let { resolveName(it) } ?: ""
        _state.value = _state.value.copy(selectedFile = name)
        if (name.isNotEmpty()) append("Selected: $name")
    }

    private fun resolveName(uri: Uri): String {
        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        getApplication<Application>().contentResolver.query(uri, projection, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return uri.lastPathSegment ?: "image.img"
    }

    fun setPartition(value: String) {
        _state.value = _state.value.copy(partition = value.trim())
    }

    fun getvar(name: String = "product") = action("getvar $name") {
        val value = fastboot.getvar(name)
        append("$name: $value")
    }

    fun erase() = action("erase ${_state.value.partition}") {
        fastboot.erase(_state.value.partition)
        append("✓ Erase complete")
    }

    fun reboot() = action("reboot") {
        fastboot.reboot()
        append("✓ Reboot command sent")
    }

    fun unlock() = action("flashing unlock") {
        fastboot.flashingUnlock()
        append("✓ Unlock command sent")
    }

    fun flash() {
        val uri = selectedUri
        if (uri == null) {
            append("✗ No image selected.")
            return
        }
        action("flash ${_state.value.partition}") {
            val ctx = getApplication<Application>()
            val partition = _state.value.partition
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                append("Flashing $partition…")
                fastboot.flash(partition, input) { sent, total ->
                    val pct = if (total > 0) (sent * 100 / total) else 0
                    _state.value = _state.value.copy(progress = "$pct%")
                }
            } ?: throw IllegalStateException("Cannot open image")
            _state.value = _state.value.copy(progress = "")
            append("✓ Flash complete")
        }
    }

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
        _state.value = _state.value.copy(log = "")
    }

    private fun append(line: String) {
        _state.value = _state.value.copy(log = (_state.value.log + line + "\n").takeLast(20000))
    }

    override fun onCleared() {
        super.onCleared()
        fastboot.dispose()
    }
}
