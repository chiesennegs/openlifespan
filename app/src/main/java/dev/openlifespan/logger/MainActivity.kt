package dev.openlifespan.logger

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
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
    private var scanning = false

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
            scanning = false
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
        appendLog("OpenLifeSpan BLE logger ready")
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
        if (!hasScanPermission()) {
            appendLog("missing Bluetooth scan permission")
            requestNeededPermissions()
            return
        }

        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            appendLog("Bluetooth LE scanner unavailable")
            return
        }

        if (!scanning) {
            scanner.startScan(scanCallback)
            scanning = true
            appendLog("scan started")
        }
    }

    private fun stopScan() {
        if (scanning && hasScanPermission()) {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
        scanning = false
        appendLog("scan stopped")
    }

    override fun onDestroy() {
        stopScan()
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

    private fun appendLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logView.append("[$timestamp] $message\n")
    }
}
