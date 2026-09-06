package dev.openlifespan.logger

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var logView: TextView
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanning = false
    private var receiverRegistered = false

    private val classicReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                    if (device != null && hasConnectPermission()) {
                        appendLog("classic seen name=${device.name ?: "(unnamed)"} address=${device.address} rssi=$rssi")
                    } else {
                        appendLog("classic seen device; connect permission unavailable")
                    }
                }

                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> appendLog("classic discovery started")
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> appendLog("classic discovery finished")
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = if (hasConnectPermission()) device.name ?: "(unnamed)" else "(name unavailable)"
            appendLog(
                "seen name=$name address=${device.address} rssi=${result.rssi} " +
                    "services=${result.scanRecord?.serviceUuids?.joinToString().orEmpty()}"
            )
        }

        override fun onScanFailed(errorCode: Int) {
            appendLog("scan failed code=$errorCode")
            bleScanning = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bluetoothAdapter = getSystemService(BluetoothManager::class.java)?.adapter

        val startButton = Button(this).apply {
            text = "Start Scan"
            setOnClickListener { startScan() }
        }
        val stopButton = Button(this).apply {
            text = "Stop Scan"
            setOnClickListener { stopScan() }
        }
        logView = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(startButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(stopButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        val logScrollView = ScrollView(this).apply {
            addView(logView)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(controls)
            addView(logScrollView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ))
        }

        if (Build.VERSION.SDK_INT >= 23) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val systemBars = insets.getInsets(WindowInsets.Type.systemBars())
                    view.setPadding(
                        24 + systemBars.left,
                        24 + systemBars.top,
                        24 + systemBars.right,
                        24 + systemBars.bottom
                    )
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(
                        24 + insets.systemWindowInsetLeft,
                        24 + insets.systemWindowInsetTop,
                        24 + insets.systemWindowInsetRight,
                        24 + insets.systemWindowInsetBottom
                    )
                }
                insets
            }
        }

        setContentView(root)
        requestNeededPermissions()
        appendLog("OpenLifeSpan Bluetooth logger ready")
        appendBluetoothState()
        appendBondedDevices()
    }

    private fun requestNeededPermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), 100)
        }
    }

    private fun startScan() {
        appendBluetoothState()
        appendBondedDevices()

        if (!hasScanPermission()) {
            appendLog("missing Bluetooth scan permission")
            requestNeededPermissions()
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null) {
            appendLog("Bluetooth adapter unavailable")
            return
        }

        if (!adapter.isEnabled) {
            appendLog("Bluetooth is disabled")
            return
        }

        registerClassicReceiver()

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner != null && !bleScanning) {
            scanner.startScan(scanCallback)
            bleScanning = true
            appendLog("BLE scan started")
        } else if (scanner == null) {
            appendLog("Bluetooth LE scanner unavailable")
        }

        if (adapter.isDiscovering) {
            adapter.cancelDiscovery()
        }
        val discoveryStarted = adapter.startDiscovery()
        appendLog("classic discovery requested started=$discoveryStarted")
    }

    private fun stopScan() {
        if (bleScanning && hasScanPermission()) {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
        bleScanning = false
        if (hasScanPermission() && bluetoothAdapter?.isDiscovering == true) {
            bluetoothAdapter?.cancelDiscovery()
        }
        appendLog("scans stopped")
    }

    override fun onDestroy() {
        stopScan()
        if (receiverRegistered) {
            unregisterReceiver(classicReceiver)
            receiverRegistered = false
        }
        super.onDestroy()
    }

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun registerClassicReceiver() {
        if (receiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_FOUND)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(classicReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(classicReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun appendBluetoothState() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            appendLog("Bluetooth state: no adapter")
            return
        }

        val permission = "scanPermission=${hasScanPermission()} connectPermission=${hasConnectPermission()}"
        appendLog("Bluetooth state: enabled=${adapter.isEnabled} $permission")
    }

    private fun appendBondedDevices() {
        if (!hasConnectPermission()) {
            appendLog("bonded devices unavailable until connect permission is granted")
            return
        }

        val bondedDevices = bluetoothAdapter?.bondedDevices.orEmpty()
        if (bondedDevices.isEmpty()) {
            appendLog("no bonded Classic Bluetooth devices")
        } else {
            bondedDevices.forEach { device ->
                appendLog("bonded name=${device.name ?: "(unnamed)"} address=${device.address} type=${device.type}")
            }
        }
    }

    private fun appendLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logView.append("[$timestamp] $message\n")
    }
}
