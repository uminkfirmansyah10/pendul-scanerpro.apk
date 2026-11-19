package com.pendul.scanerpro

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Low-latency dual-mode Bluetooth controller (Classic SPP + BLE GATT).
 * Uses dedicated IO coroutines so incoming sensor streams never block the UI.
 */
@SuppressLint("MissingPermission")
class ResponsiveBluetoothController(private val context: Context) {

    companion object {
        // Standard SPP UUID for HC-05, HC-06, ESP32 Serial
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        // Nordic UART Service (NUS) UUID for BLE modules
        val NUS_SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_RX_CHAR_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_TX_CHAR_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        data class Connecting(val deviceName: String) : ConnectionState()
        data class Connected(val deviceName: String, val address: String, val mode: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState = _connectionState.asStateFlow()

    private val _incomingLines = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val incomingLines = _incomingLines.asSharedFlow()

    private val _discoveredDevices = MutableSharedFlow<BtDeviceItem>(extraBufferCapacity = 64)
    val discoveredDevices = _discoveredDevices.asSharedFlow()

    private var classicSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var readJob: Job? = null

    private var bleGatt: BluetoothGatt? = null
    private var bleWriteChar: BluetoothGattCharacteristic? = null

    private var lastConnectedItem: BtDeviceItem? = null
    var autoReconnectEnabled: Boolean = true
    private var manualDisconnect: Boolean = false
    private var isScanning = false

    private val classicReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == BluetoothDevice.ACTION_FOUND) {
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, 0).toInt()
                device?.let {
                    _discoveredDevices.tryEmit(
                        BtDeviceItem(
                            device = it,
                            name = it.name ?: "Perangkat Bluetooth",
                            address = it.address,
                            isPaired = it.bondState == BluetoothDevice.BOND_BONDED,
                            isBle = it.type == BluetoothDevice.DEVICE_TYPE_LE,
                            rssi = rssi
                        )
                    )
                }
            }
        }
    }

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            _discoveredDevices.tryEmit(
                BtDeviceItem(
                    device = device,
                    name = device.name ?: result.scanRecord?.deviceName ?: "Perangkat BLE",
                    address = device.address,
                    isPaired = device.bondState == BluetoothDevice.BOND_BONDED,
                    isBle = true,
                    rssi = result.rssi
                )
            )
        }
    }

    fun getPairedDevices(): List<BtDeviceItem> {
        val bonded = adapter?.bondedDevices ?: return emptyList()
        return bonded.map {
            BtDeviceItem(
                device = it,
                name = it.name ?: "Perangkat Terpasang",
                address = it.address,
                isPaired = true,
                isBle = it.type == BluetoothDevice.DEVICE_TYPE_LE
            )
        }
    }

    fun startDiscovery() {
        val btAdapter = adapter ?: return
        stopDiscovery()
        isScanning = true

        // 1. Classic Bluetooth Discovery
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(classicReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(classicReceiver, filter)
            }
        } catch (_: Exception) { }

        if (btAdapter.isDiscovering) btAdapter.cancelDiscovery()
        btAdapter.startDiscovery()

        // 2. Low-Latency BLE Scan
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            btAdapter.bluetoothLeScanner?.startScan(null, settings, bleScanCallback)
        } catch (_: Exception) { }

        // Auto-stop discovery after 12 seconds to save battery & improve link speed
        scope.launch {
            delay(12_000)
            stopDiscovery()
        }
    }

    fun stopDiscovery() {
        if (!isScanning) return
        isScanning = false
        try {
            context.unregisterReceiver(classicReceiver)
        } catch (_: Exception) { }
        try {
            if (adapter?.isDiscovering == true) adapter.cancelDiscovery()
            adapter?.bluetoothLeScanner?.stopScan(bleScanCallback)
        } catch (_: Exception) { }
    }

    fun connect(item: BtDeviceItem) {
        manualDisconnect = false
        lastConnectedItem = item
        stopDiscovery()
        closeSocketsOnly()

        _connectionState.value = ConnectionState.Connecting(item.name)

        if (item.isBle && item.device.type == BluetoothDevice.DEVICE_TYPE_LE) {
            connectBle(item)
        } else {
            connectClassicWithFallback(item)
        }
    }

    private fun connectClassicWithFallback(item: BtDeviceItem) {
        scope.launch {
            try {
                // Fast insecure RFCOMM socket works reliably with HC-05 / ESP32 without pairing PIN prompts
                val socket = try {
                    item.device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                } catch (e: Exception) {
                    item.device.createRfcommSocketToServiceRecord(SPP_UUID)
                }

                socket.connect()
                classicSocket = socket
                outputStream = socket.outputStream
                _connectionState.value = ConnectionState.Connected(item.name, item.address, "SPP")
                startReadingStream(socket.inputStream)
            } catch (e: IOException) {
                // Fallback to BLE if Classic SPP fails
                if (item.device.type != BluetoothDevice.DEVICE_TYPE_CLASSIC) {
                    withContext(Dispatchers.Main) { connectBle(item) }
                } else {
                    _connectionState.value = ConnectionState.Error("Gagal terhubung: ${e.message}")
                    scheduleReconnectIfNeeded()
                }
            }
        }
    }

    private fun connectBle(item: BtDeviceItem) {
        bleGatt = item.device.connectGatt(context, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    _connectionState.value = ConnectionState.Disconnected
                    if (!manualDisconnect) scheduleReconnectIfNeeded()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) return
                // Look for Nordic UART Service or any notify/write characteristic
                for (service in gatt.services) {
                    for (char in service.characteristics) {
                        val props = char.properties
                        if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                            (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                        ) {
                            bleWriteChar = char
                        }
                        if ((props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                            gatt.setCharacteristicNotification(char, true)
                            val desc = char.getDescriptor(CCCD_UUID)
                            if (desc != null) {
                                @Suppress("DEPRECATION")
                                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                @Suppress("DEPRECATION")
                                gatt.writeDescriptor(desc)
                            }
                        }
                    }
                }
                _connectionState.value = ConnectionState.Connected(item.name, item.address, "BLE")
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                @Suppress("DEPRECATION")
                val data = characteristic.value ?: return
                val text = data.toString(Charsets.UTF_8).trim()
                if (text.isNotEmpty()) {
                    _incomingLines.tryEmit(text)
                }
            }
        }, BluetoothDevice.TRANSPORT_LE)
    }

    private fun startReadingStream(inputStream: InputStream) {
        readJob?.cancel()
        readJob = scope.launch {
            val buffer = ByteArray(1024)
            val lineBuilder = StringBuilder()
            try {
                while (isActive) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break
                    if (bytesRead > 0) {
                        val chunk = String(buffer, 0, bytesRead, Charsets.UTF_8)
                        for (ch in chunk) {
                            if (ch == '\n' || ch == '\r') {
                                val line = lineBuilder.toString().trim()
                                if (line.isNotEmpty()) {
                                    _incomingLines.tryEmit(line)
                                }
                                lineBuilder.clear()
                            } else {
                                lineBuilder.append(ch)
                            }
                        }
                    }
                }
            } catch (_: IOException) {
                // Stream closed
            } finally {
                if (!manualDisconnect) {
                    _connectionState.value = ConnectionState.Disconnected
                    scheduleReconnectIfNeeded()
                }
            }
        }
    }

    fun sendCommand(command: String): Boolean {
        val payload = (command.trim() + "\n").toByteArray(Charsets.UTF_8)
        // Classic SPP write
        outputStream?.let { out ->
            scope.launch {
                try {
                    out.write(payload)
                    out.flush()
                } catch (_: IOException) { }
            }
            return true
        }
        // BLE write
        val gatt = bleGatt
        val char = bleWriteChar
        if (gatt != null && char != null) {
            @Suppress("DEPRECATION")
            char.value = payload
            @Suppress("DEPRECATION")
            return gatt.writeCharacteristic(char)
        }
        return false
    }

    private fun scheduleReconnectIfNeeded() {
        val target = lastConnectedItem ?: return
        if (!autoReconnectEnabled || manualDisconnect) return
        scope.launch {
            delay(2500)
            if (!manualDisconnect && _connectionState.value !is ConnectionState.Connected) {
                withContext(Dispatchers.Main) {
                    connect(target)
                }
            }
        }
    }

    private fun closeSocketsOnly() {
        readJob?.cancel()
        try { outputStream?.close() } catch (_: Exception) { }
        try { classicSocket?.close() } catch (_: Exception) { }
        try {
            bleGatt?.disconnect()
            bleGatt?.close()
        } catch (_: Exception) { }
        outputStream = null
        classicSocket = null
        bleGatt = null
        bleWriteChar = null
    }

    fun disconnect() {
        manualDisconnect = true
        stopDiscovery()
        closeSocketsOnly()
        _connectionState.value = ConnectionState.Disconnected
    }

    fun release() {
        disconnect()
        scope.cancel()
    }
}
