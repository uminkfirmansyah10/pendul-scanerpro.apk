package com.pendul.scanerpro

import android.bluetooth.BluetoothDevice

data class BtDeviceItem(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val isPaired: Boolean,
    val isBle: Boolean = false,
    val rssi: Int = 0
)
