package com.pendul.scanerpro

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.pendul.scanerpro.databinding.ActivityMainBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var btController: ResponsiveBluetoothController
    private lateinit var deviceAdapter: BtDeviceAdapter

    private var peakValue: Double = 0.0
    private var packetCounter: Int = 0
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val numberRegex = Regex("[-+]?[0-9]*\\.?[0-9]+")

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            ensureBluetoothEnabledAndScan()
        } else {
            Toast.makeText(this, "Izin Bluetooth diperlukan agar aplikasi dapat memindai perangkat.", Toast.LENGTH_LONG).show()
        }
    }

    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (btController.adapter?.isEnabled == true) {
            loadPairedAndScan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        btController = ResponsiveBluetoothController(this)

        setupRecyclerView()
        setupListeners()
        observeBluetoothFlows()
        startPacketRateTimer()

        checkPermissionsAndStart()
    }

    private fun setupRecyclerView() {
        deviceAdapter = BtDeviceAdapter { deviceItem ->
            appendLog("[BT] Menghubungkan ke ${deviceItem.name} (${deviceItem.address})...")
            btController.connect(deviceItem)
        }
        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDevices.adapter = deviceAdapter
    }

    private fun setupListeners() {
        binding.btnScanDevices.setOnClickListener {
            checkPermissionsAndStart()
        }

        binding.btnDisconnect.setOnClickListener {
            btController.disconnect()
            appendLog("[BT] Koneksi diputus secara manual.")
        }

        binding.swAutoReconnect.setOnCheckedChangeListener { _, isChecked ->
            btController.autoReconnectEnabled = isChecked
        }

        binding.btnCmdScan.setOnClickListener { sendQuickCommand("SCAN") }
        binding.btnCmdCalibrate.setOnClickListener { sendQuickCommand("CALIBRATE") }
        binding.btnCmdReset.setOnClickListener {
            peakValue = 0.0
            binding.tvPeakValue.text = "Puncak: 0.00"
            binding.tvLiveReading.text = "0.00"
            binding.pbSignalStrength.progress = 0
            sendQuickCommand("RESET")
        }

        binding.btnSendCommand.setOnClickListener {
            val cmd = binding.etCommand.text.toString()
            if (cmd.isNotBlank()) {
                sendQuickCommand(cmd)
                binding.etCommand.text.clear()
            }
        }

        binding.etCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                binding.btnSendCommand.performClick()
                true
            } else {
                false
            }
        }

        binding.btnClearLog.setOnClickListener {
            binding.tvSerialLog.text = ""
        }
    }

    private fun sendQuickCommand(cmd: String) {
        val sent = btController.sendCommand(cmd)
        if (sent) {
            appendLog("[TX] > $cmd")
        } else {
            Toast.makeText(this, "Belum terhubung ke perangkat Bluetooth", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeBluetoothFlows() {
        lifecycleScope.launch {
            btController.connectionState.collect { state ->
                when (state) {
                    is ResponsiveBluetoothController.ConnectionState.Disconnected -> {
                        binding.tvConnectionStatus.text = "● Terputus • Siap Memindai"
                        binding.tvConnectionStatus.setTextColor(getColor(R.color.accent_amber))
                        binding.btnDisconnect.visibility = View.GONE
                        binding.btnScanDevices.visibility = View.VISIBLE
                    }
                    is ResponsiveBluetoothController.ConnectionState.Connecting -> {
                        binding.tvConnectionStatus.text = "◌ Menghubungkan ke ${state.deviceName}..."
                        binding.tvConnectionStatus.setTextColor(getColor(R.color.accent_cyan))
                    }
                    is ResponsiveBluetoothController.ConnectionState.Connected -> {
                        binding.tvConnectionStatus.text = "● Terhubung: ${state.deviceName} [${state.mode}]"
                        binding.tvConnectionStatus.setTextColor(getColor(R.color.accent_green))
                        binding.btnDisconnect.visibility = View.VISIBLE
                        appendLog("[OK] Terhubung ke ${state.deviceName} (${state.mode})")
                    }
                    is ResponsiveBluetoothController.ConnectionState.Error -> {
                        binding.tvConnectionStatus.text = "● Gagal Koneksi"
                        binding.tvConnectionStatus.setTextColor(getColor(R.color.accent_red))
                        appendLog("[ERR] ${state.message}")
                    }
                }
            }
        }

        lifecycleScope.launch {
            btController.discoveredDevices.collect { item ->
                deviceAdapter.upsertDevice(item)
            }
        }

        lifecycleScope.launch {
            btController.incomingLines.collect { line ->
                packetCounter++
                parsePendulumTelemetry(line)
                appendLog("[RX] $line")
            }
        }
    }

    private fun parsePendulumTelemetry(rawLine: String) {
        val match = numberRegex.find(rawLine)
        val numeric = match?.value?.toDoubleOrNull()
        if (numeric != null) {
            binding.tvLiveReading.text = String.format(Locale.US, "%.2f", numeric)
            if (kotlin.math.abs(numeric) > peakValue) {
                peakValue = kotlin.math.abs(numeric)
                binding.tvPeakValue.text = String.format(Locale.US, "Puncak: %.2f", peakValue)
            }
            val barPercent = kotlin.math.abs(numeric).coerceIn(0.0, 100.0).toInt()
            binding.pbSignalStrength.progress = barPercent
        } else {
            binding.tvLiveReading.text = rawLine.take(18)
        }
    }

    private fun startPacketRateTimer() {
        lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                binding.tvPacketRate.text = "$packetCounter pkt/s"
                packetCounter = 0
            }
        }
    }

    private fun appendLog(text: String) {
        val stamp = timeFormat.format(Date())
        val current = binding.tvSerialLog.text.toString()
        val trimmed = if (current.length > 6000) current.takeLast(4000) else current
        binding.tvSerialLog.text = "$trimmed[$stamp] $text\n"
        binding.svLog.post {
            binding.svLog.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun checkPermissionsAndStart() {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            ensureBluetoothEnabledAndScan()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun ensureBluetoothEnabledAndScan() {
        val adapter = btController.adapter
        if (adapter == null) {
            Toast.makeText(this, "Perangkat ini tidak mendukung Bluetooth", Toast.LENGTH_LONG).show()
            return
        }
        if (!adapter.isEnabled) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            loadPairedAndScan()
        }
    }

    private fun loadPairedAndScan() {
        val paired = btController.getPairedDevices()
        deviceAdapter.submitList(paired)
        appendLog("[BT] Memindai perangkat sekitar (${paired.size} perangkat tersimpan)...")
        btController.startDiscovery()
    }

    override fun onDestroy() {
        super.onDestroy()
        btController.release()
    }
}
