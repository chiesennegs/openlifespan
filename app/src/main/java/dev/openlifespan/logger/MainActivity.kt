package dev.openlifespan.logger

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
import android.os.Handler
import android.os.ParcelUuid
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.ArrayDeque
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
    private val pendingCommands = ArrayDeque<PendingCommand>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var writeInFlight = false
    private var inFlightCommand: PendingCommand? = null
    private var activeSocket: BluetoothSocket? = null

    private data class PendingCommand(val label: String, val bytes: ByteArray)

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
                activeWriteCharacteristic = null
                pendingCommands.clear()
                writeInFlight = false
                inFlightCommand = null
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
            val value = characteristic.value
            appendLog("gatt notify ${characteristic.uuid} value=${value?.toHex().orEmpty()}")
            if (value != null) {
                appendDecodedLifespanResponse(value)
            }
            if (characteristic.uuid == lifespanNotifyUuid) {
                writeInFlight = false
                inFlightCommand = null
                sendNextPendingCommand()
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            appendLog("gatt write ${characteristic.uuid} status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                writeInFlight = false
                inFlightCommand = null
                sendNextPendingCommand()
            }
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
        val syncHandshakeButton = Button(this).apply {
            text = "Sync Handshake"
            setOnClickListener {
                sendLifespanCommand("stopped state", byteArrayOf(0xA1.toByte(), 0x82.toByte(), 0x00, 0x00, 0x00))
                sendLifespanCommand("multi-user", byteArrayOf(0xAC.toByte(), 0x00, 0x00, 0x00, 0x00))
                sendLifespanCommand("record count", byteArrayOf(0xAA.toByte(), 0x00, 0x00, 0x00, 0x00))
            }
        }
        val recordStreamButton = Button(this).apply {
            text = "Begin Record Stream"
            setOnClickListener { sendLifespanCommand("record stream", byteArrayOf(0xAB.toByte(), 0x00, 0x00, 0x00, 0x00)) }
        }
        val firstRecordButton = Button(this).apply {
            text = "Query First Record"
            setOnClickListener { sendLifespanCommand("record 1", byteArrayOf(0xAB.toByte(), 0x00, 0x00, 0x01, 0x00)) }
        }
        val dateTimeButton = Button(this).apply {
            text = "Query Date/Time"
            setOnClickListener {
                sendLifespanCommand("date", byteArrayOf(0xA1.toByte(), 0x8D.toByte(), 0x00, 0x00, 0x00))
                sendLifespanCommand("time", byteArrayOf(0xA1.toByte(), 0x8E.toByte(), 0x00, 0x00, 0x00))
            }
        }
        val liveSnapshotButton = Button(this).apply {
            text = "Live Snapshot"
            setOnClickListener { sendLiveSnapshotCommands() }
        }
        val clearStoredDataButton = Button(this).apply {
            text = "Clear Stored Data"
            setOnClickListener {
                confirmCommand(
                    title = "Clear stored data?",
                    message = "This sends the legacy clear command AB 01 00 00 00 to the treadmill console."
                ) {
                    sendLifespanCommand("clear stored data", byteArrayOf(0xAB.toByte(), 0x01, 0x00, 0x00, 0x00))
                }
            }
        }
        val setSpeedButton = Button(this).apply {
            text = "Set Speed 2.5 TEST"
            setOnClickListener {
                confirmCommand(
                    title = "Set speed to 2.5?",
                    message = "This sends the experimental speed command D0 02 32 00 00. Use only while supervising the treadmill."
                ) {
                    sendLifespanCommand("set speed 2.5 test", byteArrayOf(0xD0.toByte(), 0x02, 0x32, 0x00, 0x00))
                    sendLifespanCommand("speed property 82", byteArrayOf(0xA1.toByte(), 0x82.toByte(), 0x00, 0x00, 0x00))
                }
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
            addView(syncHandshakeButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(recordStreamButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(firstRecordButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(dateTimeButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(liveSnapshotButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(clearStoredDataButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(setSpeedButton, LinearLayout.LayoutParams(
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

    private fun confirmCommand(title: String, message: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Send") { _, _ -> onConfirm() }
            .setNegativeButton("Cancel", null)
            .show()
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
        pendingCommands.clear()
        writeInFlight = false
        inFlightCommand = null
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
        pendingCommands.clear()
        writeInFlight = false
        inFlightCommand = null
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

        pendingCommands.add(PendingCommand(label, command))
        appendLog("queued command $label tx=${command.toHex()} queueSize=${pendingCommands.size}")
        sendNextPendingCommand()
    }

    private fun sendLiveSnapshotCommands() {
        val propertyQueries = listOf(
            0x81 to "units",
            0x82 to "speed",
            0x83 to "incline",
            0x84 to "resistance",
            0x85 to "distance",
            0x87 to "calories",
            0x88 to "steps",
            0x89 to "elapsed time",
            0x8A to "pace-or-rpm",
            0x91 to "device state",
            0x94 to "workout status",
            0x71 to "max speed",
            0x73 to "max resistance"
        )

        propertyQueries.forEach { (property, label) ->
            sendLifespanCommand(
                "$label property ${property.toHexByte()}",
                byteArrayOf(0xA1.toByte(), property.toByte(), 0x00, 0x00, 0x00)
            )
        }
    }

    private fun sendNextPendingCommand() {
        if (writeInFlight || pendingCommands.isEmpty()) return

        val gatt = activeGatt
        val characteristic = activeWriteCharacteristic
        if (gatt == null || characteristic == null) {
            appendLog("cannot send queued command; GATT is not ready")
            return
        }

        val command = pendingCommands.peek() ?: return
        val started = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(characteristic, command.bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = command.bytes
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
        appendLog("command ${command.label} tx=${command.bytes.toHex()} started=$started")

        if (started) {
            writeInFlight = true
            inFlightCommand = command
            pendingCommands.remove()
            mainHandler.postDelayed({
                if (writeInFlight && inFlightCommand === command) {
                    appendLog("command ${command.label} timed out waiting for notify; continuing")
                    writeInFlight = false
                    inFlightCommand = null
                    sendNextPendingCommand()
                }
            }, 1_500)
        } else {
            pendingCommands.remove()
            appendLog("dropped command ${command.label}; GATT write did not start")
        }
    }

    private fun appendDecodedLifespanResponse(value: ByteArray) {
        if (value.size < 2) return

        when (value[0].toUnsignedInt()) {
            0xAA -> appendRecordCountResponse(value)
            0xAB -> appendRecordDataResponse(value)
            0xAC -> appendStatusResponse("multi-user", value)
            0xA1 -> appendStatusResponse("property", value)
        }
    }

    private fun appendRecordCountResponse(value: ByteArray) {
        when (value.getOrNull(1)?.toUnsignedInt()) {
            0xAA -> {
                if (value.size >= 4) {
                    val count = (value[2].toUnsignedInt() shl 8) or value[3].toUnsignedInt()
                    appendLog("decoded record count=$count")
                } else {
                    appendLog("decoded record count response too short")
                }
            }
            0xFF -> appendLog("decoded record count status=FF (not a valid count)")
            else -> appendLog("decoded record count unexpected status=${value[1].toHexByte()}")
        }
    }

    private fun appendRecordDataResponse(value: ByteArray) {
        if (value.getOrNull(1)?.toUnsignedInt() == 0xAA) {
            appendLog("decoded record data frame marker")
        } else {
            appendLog("decoded record data status=${value.getOrNull(1)?.toHexByte() ?: "missing"}")
        }
    }

    private fun appendStatusResponse(label: String, value: ByteArray) {
        val activeLabel = inFlightCommand?.label
        val responseLabel = if (label == "property" && activeLabel != null) "$label for $activeLabel" else label
        val status = value.getOrNull(1)?.toUnsignedInt()
        when (status) {
            0xAA -> appendLog("decoded $responseLabel status=AA payload=${value.drop(2).toByteArray().toHex()} ${decodePropertyPayload(activeLabel, value)}")
            0xFF -> appendLog("decoded $responseLabel status=FF")
            null -> appendLog("decoded $responseLabel response too short")
            else -> appendLog("decoded $responseLabel status=${value[1].toHexByte()} payload=${value.drop(2).toByteArray().toHex()} ${decodePropertyPayload(activeLabel, value)}")
        }
    }

    private fun decodePropertyPayload(commandLabel: String?, value: ByteArray): String {
        if (commandLabel == null || value.size < 6 || value[0].toUnsignedInt() != 0xA1) return ""

        val payload = value.drop(2).toByteArray()
        val first = payload[0].toUnsignedInt()
        val second = payload[1].toUnsignedInt()
        val third = payload[2].toUnsignedInt()
        val twoByteValue = (first shl 8) or second
        val decimalValue = (first * 100 + second) / 100.0
        val hmsSeconds = first * 3600 + second * 60 + third

        return when {
            commandLabel.startsWith("speed ") -> "decodedValue=${"%.2f".format(Locale.US, decimalValue)}"
            commandLabel.startsWith("max speed ") -> "decodedValue=${"%.2f".format(Locale.US, decimalValue)}"
            commandLabel.startsWith("distance ") -> "decodedValue=${"%.2f".format(Locale.US, decimalValue)}"
            commandLabel.startsWith("incline ") -> "decodedValue=${decodeIncline(first)}"
            commandLabel.startsWith("elapsed time ") -> "decodedValue=${formatSeconds(hmsSeconds)}"
            commandLabel.startsWith("pace-or-rpm ") -> "decodedInt=$twoByteValue decodedTime=${formatSeconds(hmsSeconds)}"
            commandLabel.startsWith("units ") -> "decodedByte=$first"
            commandLabel.startsWith("device state ") -> "decodedByte=$first"
            commandLabel.startsWith("workout status ") -> "decodedByte=$first"
            else -> "decodedInt=$twoByteValue"
        }
    }

    private fun decodeIncline(value: Int): Int {
        val magnitude = value and 0x7F
        return if (magnitude <= 50) value else 50 - value
    }

    private fun formatSeconds(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remainingSeconds = seconds % 60
        return "%02d:%02d:%02d".format(Locale.US, hours, minutes, remainingSeconds)
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

    private fun Byte.toHexByte(): String = "%02X".format(toUnsignedInt())

    private fun Byte.toUnsignedInt(): Int = toInt() and 0xFF

    private fun Int.toHexByte(): String = "%02X".format(this and 0xFF)

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
