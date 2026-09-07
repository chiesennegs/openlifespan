package dev.openlifespan.logger

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : Activity() {
    private val lifespanDeviceName = "LifeSpan"
    private val logFileName = "openlifespan-log.txt"
    private val lifespanServiceUuid: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    private val lifespanNotifyUuid: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
    private val lifespanWriteUuid: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
    private val clientCharacteristicConfigUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val serialPortProfileUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    private val rfcommProbeChannels = (1..30).toList()
    private lateinit var logView: TextView
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleScanning = false
    private var receiverRegistered = false
    private var activeGatt: BluetoothGatt? = null
    private var activeWriteCharacteristic: BluetoothGattCharacteristic? = null
    private var activeSocket: BluetoothSocket? = null

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            appendLog("gatt state status=$status newState=${newState.toBluetoothStateName()}")
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                activeGatt = gatt
                appendLog("gatt connected; discovering services")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                appendLog("gatt disconnected")
                gatt.close()
                if (activeGatt == gatt) activeGatt = null
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            appendLog("gatt services discovered status=$status count=${gatt.services.size}")
            gatt.services.forEach { service ->
                appendLog("gatt service ${service.uuid}")
                service.characteristics.forEach { characteristic ->
                    appendLog("gatt characteristic ${characteristic.uuid} props=${characteristic.properties.toCharacteristicProperties()}")
                    characteristic.descriptors.forEach { descriptor ->
                        appendLog("gatt descriptor ${descriptor.uuid} for ${characteristic.uuid}")
                    }
                }
            }

            val service = gatt.getService(lifespanServiceUuid)
            if (service == null) {
                appendLog("LifeSpan service not found: $lifespanServiceUuid")
                return
            }

            val notifyCharacteristic = service.getCharacteristic(lifespanNotifyUuid)
            if (notifyCharacteristic == null) {
                appendLog("LifeSpan notify characteristic not found: $lifespanNotifyUuid")
                return
            }

            val notificationSet = gatt.setCharacteristicNotification(notifyCharacteristic, true)
            appendLog("LifeSpan notification local set=$notificationSet")
            val descriptor = notifyCharacteristic.getDescriptor(clientCharacteristicConfigUuid)
            if (descriptor == null) {
                appendLog("LifeSpan notification descriptor not found: $clientCharacteristicConfigUuid")
            } else {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                val writeStarted = gatt.writeDescriptor(descriptor)
                appendLog("LifeSpan notification descriptor writeStarted=$writeStarted")
            }

            val readStarted = gatt.readCharacteristic(notifyCharacteristic)
            appendLog("LifeSpan notify readStarted=$readStarted")

            val writeCharacteristic = service.getCharacteristic(lifespanWriteUuid)
            activeWriteCharacteristic = writeCharacteristic
            appendLog("LifeSpan write characteristic present=${writeCharacteristic != null}")
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            appendLog("gatt read ${characteristic.uuid} status=$status value=${characteristic.value?.toHex().orEmpty()}")
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            appendLog("gatt notify ${characteristic.uuid} value=${characteristic.value?.toHex().orEmpty()}")
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            appendLog("gatt write ${characteristic.uuid} status=$status")
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            appendLog("gatt descriptor write ${descriptor.uuid} status=$status")
        }
    }

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
                BluetoothDevice.ACTION_UUID -> {
                    val device = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val uuids = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID, ParcelUuid::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
                            ?.filterIsInstance<ParcelUuid>()
                            ?.toTypedArray()
                    }
                    appendDeviceUuids("sdp", device, uuids)
                }
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
            text = "Scan / Probe"
            setOnClickListener { startScan() }
        }
        val stopButton = Button(this).apply {
            text = "Stop Scan"
            setOnClickListener { stopScan() }
        }
        val clearButton = Button(this).apply {
            text = "Clear Log"
            setOnClickListener { clearLog() }
        }
        val connectButton = Button(this).apply {
            text = "GATT Connect"
            setOnClickListener { connectGattToLifespan() }
        }
        val recordCountButton = Button(this).apply {
            text = "Query Record Count"
            setOnClickListener { sendLifespanCommand("record count", byteArrayOf(0xAA.toByte(), 0x00, 0x00, 0x00, 0x00)) }
        }
        val dateTimeButton = Button(this).apply {
            text = "Query Date/Time"
            setOnClickListener {
                sendLifespanCommand("date", byteArrayOf(0xA1.toByte(), 0x8D.toByte(), 0x00, 0x00, 0x00))
                sendLifespanCommand("time", byteArrayOf(0xA1.toByte(), 0x8E.toByte(), 0x00, 0x00, 0x00))
            }
        }
        val sppButton = Button(this).apply {
            text = "SPP Probe"
            setOnClickListener { connectToLifespan() }
        }
        logView = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(connectButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(recordCountButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(dateTimeButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(sppButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        val scanControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(startButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(stopButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(clearButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        val logScrollView = ScrollView(this).apply {
            addView(logView)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(controls)
            addView(scanControls)
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
        probeBondedDevices()
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
        probeBondedDevices()

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
        activeSocket?.closeQuietly()
        activeGatt?.close()
        activeWriteCharacteristic = null
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
            addAction(BluetoothDevice.ACTION_UUID)
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
                appendLog("bonded name=${device.name ?: "(unnamed)"} address=${device.address} type=${device.type} bondState=${device.bondState}")
                appendDeviceUuids("cached", device, device.uuids)
            }
        }
    }

    private fun probeBondedDevices() {
        if (!hasConnectPermission()) return

        registerClassicReceiver()
        bluetoothAdapter?.bondedDevices.orEmpty().forEach { device ->
            val started = device.fetchUuidsWithSdp()
            appendLog("sdp requested name=${device.name ?: "(unnamed)"} address=${device.address} started=$started")
        }
    }

    private fun appendDeviceUuids(prefix: String, device: BluetoothDevice?, uuids: Array<out ParcelUuid>?) {
        if (device == null) {
            appendLog("$prefix UUID result without device")
            return
        }

        val name = if (hasConnectPermission()) device.name ?: "(unnamed)" else "(name unavailable)"
        val values = uuids?.joinToString { it.uuid.toString() } ?: "(none)"
        appendLog("$prefix UUIDs name=$name address=${device.address} uuids=$values")
    }

    private fun connectToLifespan() {
        if (!hasConnectPermission()) {
            appendLog("missing Bluetooth connect permission")
            requestNeededPermissions()
            return
        }

        val device = bluetoothAdapter?.bondedDevices.orEmpty()
            .firstOrNull { it.name.equals(lifespanDeviceName, ignoreCase = true) }
        if (device == null) {
            appendLog("no bonded $lifespanDeviceName device found")
            return
        }

        Thread {
            appendLog("connect probe started name=${device.name} address=${device.address}")
            bluetoothAdapter?.cancelDiscovery()
            activeSocket?.closeQuietly()

            val attempts = listOf(
                "secure SPP" to { device.createRfcommSocketToServiceRecord(serialPortProfileUuid) },
                "insecure SPP" to { device.createInsecureRfcommSocketToServiceRecord(serialPortProfileUuid) }
            )

            for ((label, factory) in attempts) {
                val socket = try {
                    factory()
                } catch (exception: IOException) {
                    appendLog("$label socket creation failed: ${exception.message}")
                    continue
                }

                try {
                    appendLog("$label connecting")
                    socket.connect()
                    activeSocket = socket
                    appendLog("$label connected; listening for 20 seconds")
                    listenForBytes(socket, label)
                    appendLog("$label listen complete")
                    return@Thread
                } catch (exception: IOException) {
                    appendLog("$label failed: ${exception.message}")
                    socket.closeQuietly()
                }
            }

            appendLog("SPP UUID attempts failed; probing RFCOMM channels")
            for (channel in rfcommProbeChannels) {
                val socket = try {
                    device.createRfcommSocketOnChannel(channel)
                } catch (exception: ReflectiveOperationException) {
                    appendLog("channel $channel socket creation failed: ${exception.message}")
                    break
                } catch (exception: ClassCastException) {
                    appendLog("channel $channel socket creation returned unexpected type: ${exception.message}")
                    break
                }

                try {
                    appendLog("channel $channel connecting")
                    socket.connect()
                    activeSocket = socket
                    appendLog("channel $channel connected; listening for 20 seconds")
                    listenForBytes(socket, "channel $channel")
                    appendLog("channel $channel listen complete")
                    return@Thread
                } catch (exception: IOException) {
                    appendLog("channel $channel failed: ${exception.message}")
                    socket.closeQuietly()
                }
            }

            appendLog("connect probe finished without a usable SPP connection")
        }.start()
    }

    private fun connectGattToLifespan() {
        if (!hasConnectPermission()) {
            appendLog("missing Bluetooth connect permission")
            requestNeededPermissions()
            return
        }

        val device = findLifespanDevice()
        if (device == null) {
            appendLog("no bonded $lifespanDeviceName device found")
            return
        }

        bluetoothAdapter?.cancelDiscovery()
        activeGatt?.close()
        activeWriteCharacteristic = null
        appendLog("gatt connect probe started name=${device.name} address=${device.address} type=${device.type}")
        activeGatt = if (Build.VERSION.SDK_INT >= 23) {
            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(this, false, gattCallback)
        }
        appendLog("gatt connect requested")
    }

    private fun findLifespanDevice(): BluetoothDevice? {
        if (!hasConnectPermission()) return null
        return bluetoothAdapter?.bondedDevices.orEmpty()
            .firstOrNull { it.name.equals(lifespanDeviceName, ignoreCase = true) }
    }

    private fun sendLifespanCommand(label: String, command: ByteArray) {
        if (!hasConnectPermission()) {
            appendLog("missing Bluetooth connect permission")
            requestNeededPermissions()
            return
        }

        val gatt = activeGatt
        val characteristic = activeWriteCharacteristic
        if (gatt == null || characteristic == null) {
            appendLog("cannot send $label; connect GATT first and wait for service discovery")
            return
        }

        val started = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(characteristic, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = command
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
        appendLog("command $label tx=${command.toHex()} started=$started")
    }

    private fun BluetoothDevice.createRfcommSocketOnChannel(channel: Int): BluetoothSocket {
        val method = javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
        return method.invoke(this, channel) as BluetoothSocket
    }

    private fun listenForBytes(socket: BluetoothSocket, label: String) {
        val input = try {
            socket.inputStream
        } catch (exception: IOException) {
            appendLog("$label input stream failed: ${exception.message}")
            return
        }

        val deadline = System.currentTimeMillis() + 20_000
        val buffer = ByteArray(256)
        var totalBytes = 0

        while (System.currentTimeMillis() < deadline) {
            try {
                val available = input.available()
                if (available > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size, available))
                    if (count > 0) {
                        totalBytes += count
                        appendLog("$label rx ${buffer.toHex(count)}")
                    }
                } else {
                    Thread.sleep(100)
                }
            } catch (exception: IOException) {
                appendLog("$label read failed: ${exception.message}")
                break
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                appendLog("$label interrupted")
                break
            }
        }

        appendLog("$label received totalBytes=$totalBytes")
        socket.closeQuietly()
    }

    private fun appendLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "[$timestamp] $message"
        runOnUiThread {
            logView.append("$line\n")
        }
        Log.d("OpenLifeSpanLogger", line)
        try {
            openFileOutput(logFileName, MODE_APPEND).use { output ->
                output.write("$line\n".toByteArray(Charsets.UTF_8))
            }
        } catch (exception: IOException) {
            Log.e("OpenLifeSpanLogger", "Failed to write app log", exception)
        }
    }

    private fun clearLog() {
        logView.text = ""
        deleteFile(logFileName)
        appendLog("log cleared")
    }

    private fun BluetoothSocket.closeQuietly() {
        try {
            close()
        } catch (_: IOException) {
        }
    }

    private fun ByteArray.toHex(length: Int = size): String {
        return take(length).joinToString(" ") { byte -> "%02X".format(byte) }
    }

    private fun Int.toBluetoothStateName(): String {
        return when (this) {
            BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
            BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
            BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
            BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
            else -> "UNKNOWN($this)"
        }
    }

    private fun Int.toCharacteristicProperties(): String {
        val properties = buildList {
            if (this@toCharacteristicProperties and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) add("broadcast")
            if (this@toCharacteristicProperties and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("read")
            if (this@toCharacteristicProperties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("writeNoResponse")
            if (this@toCharacteristicProperties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("write")
            if (this@toCharacteristicProperties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
            if (this@toCharacteristicProperties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
            if (this@toCharacteristicProperties and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) add("signedWrite")
            if (this@toCharacteristicProperties and BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS != 0) add("extended")
        }
        return if (properties.isEmpty()) "none($this)" else properties.joinToString("|")
    }
}
