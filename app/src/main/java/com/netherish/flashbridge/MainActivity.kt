package com.netherish.flashbridge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.netherish.flashbridge.ui.FlashBridgeScreen
import com.netherish.flashbridge.ui.MainViewModel
import com.netherish.flashbridge.ui.theme.FlashBridgeTheme

class MainActivity : ComponentActivity() {

    private lateinit var vm: MainViewModel

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (!uris.isNullOrEmpty()) vm.addFiles(uris)
    }

    private val daPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.loadDa(uri)
    }

    private val authPicker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (!uris.isNullOrEmpty()) vm.loadAuth(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vm = ViewModelProvider(this)[MainViewModel::class.java]

        setContent {
            FlashBridgeTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    FlashBridgeScreen(
                        vm = vm,
                        onPickFiles = { filePicker.launch("*/*") },
                        onPickDa = { daPicker.launch("*/*") },
                        onPickAuth = { authPicker.launch("*/*") },
                    )
                }
            }
        }
    }
}
