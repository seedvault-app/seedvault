package com.stevesoltys.seedvault.service.settings

import android.hardware.usb.UsbDevice

data class FlashDrive(
    val name: String,
    val serialNumber: String?,
    val vendorId: Int,
    val productId: Int,
) {
    companion object {
        fun from(device: UsbDevice) = FlashDrive(
            name = "${device.manufacturerName} ${device.productName}",
            serialNumber = device.serialNumber,
            vendorId = device.vendorId,
            productId = device.productId
        )
    }
}
