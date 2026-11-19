package com.netherish.flashbridge.core

data class UsbInfo(
    val vendorId: Int,
    val productId: Int,
    val name: String,
    val manufacturer: String?,
)
