package dev.openlifespan.logger

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val lifespanDeviceName = "LifeSpan"
    private val logFileName = "openlifespan-log.txt"
    private val autoConnectIntervalMillis = 4_000L
    private val commandTimeoutMillis = 1_500L

    private lateinit var store: SessionStore
    private lateinit var statusView: TextView
    private lateinit var summaryView: TextView
    private lateinit var currentView: TextView
    private lateinit var historyList: LinearLayout
    private lateinit var logView: TextView

    private var activeGatt: BluetoothGatt? = null
    private var activeWriteCharacteristic: BluetoothGattCharacteristic? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingCommands = ArrayDeque<PendingCommand>()
    private var writeInFlight = false
    private var inFlightCommand: PendingCommand? = null
    private var commandQueueIdle: (() -> Unit)? = null
    private var autoConnectEnabled = true
    private var gattConnectInProgress = false
    private var activeGattAttemptId = 0
    private var gattCloseInProgress = false
    private var activeSync: SyncSnapshot? = null
    private var syncAfterConnect = false
    private var clearAfterSync = false
    private var restoreSpeedAfterSync: Int? = null

    private data class PendingCommand(
        val label: String,
        val bytes: ByteArray,
        val property: Int? = null,
        val onResponse: ((ByteArray?) -> Unit)? = null
    )

    private val autoConnectRunnable = object : Runnable {
        override fun run() {
            if (autoConnectEnabled) {
                if (activeGatt == null && !gattConnectInProgress && !gattCloseInProgress) {
                    updateStatus("Standby; press console BT")
                    connectGattToLifespan(manual = false)
                }
                mainHandler.postDelayed(this, autoConnectIntervalMillis)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt != activeGatt) {
                appendLog("ignored stale gatt state status=$status state=${newState.toBluetoothStateName()}")
                gatt.closeQuietly()
                return
            }

            appendLog("gatt state status=$status state=${newState.toBluetoothStateName()}")
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                gattConnectInProgress = false
                gattCloseInProgress = false
                updateStatus("Connected; discovering services")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                resetGattState("disconnected status=$status state=${newState.toBluetoothStateName()}")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt != activeGatt) {
                appendLog("ignored stale service discovery status=$status")
                return
            }

            appendLog("services discovered status=$status count=${gatt.services.size}")
            val service = gatt.getService(LifeSpanProtocol.serviceUuid)
            val notifyCharacteristic = service?.getCharacteristic(LifeSpanProtocol.notifyUuid)
            val writeCharacteristic = service?.getCharacteristic(LifeSpanProtocol.writeUuid)
            if (service == null || notifyCharacteristic == null || writeCharacteristic == null) {
                updateStatus("LifeSpan service unavailable")
                resetGattState("missing LifeSpan GATT service", closeDelayMillis = 500)
                return
            }

            activeWriteCharacteristic = writeCharacteristic
            val notificationSet = gatt.setCharacteristicNotification(notifyCharacteristic, true)
            appendLog("notification local set=$notificationSet")
            val descriptor = notifyCharacteristic.getDescriptor(LifeSpanProtocol.clientConfigUuid)
            if (descriptor == null) {
                appendLog("notification descriptor missing")
                onGattReady()
            } else {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                val started = gatt.writeDescriptor(descriptor)
                appendLog("notification descriptor writeStarted=$started")
                if (!started) onGattReady()
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (gatt != activeGatt) {
                appendLog("ignored stale descriptor write status=$status")
                return
            }

            appendLog("descriptor write ${descriptor.uuid} status=$status")
            onGattReady()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (gatt != activeGatt) {
                appendLog("ignored stale notify ${characteristic.uuid}")
                return
            }

            val value = characteristic.value
            val command = inFlightCommand
            appendLog("rx ${value?.toHex().orEmpty()} for ${command?.label ?: "unknown"}")
            command?.onResponse?.invoke(value)
            writeInFlight = false
            inFlightCommand = null
            sendNextPendingCommand()
            notifyQueueIdleIfReady()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (gatt != activeGatt) {
                appendLog("ignored stale write status=$status")
                return
            }

            appendLog("write ${characteristic.uuid} status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                inFlightCommand?.onResponse?.invoke(null)
                writeInFlight = false
                inFlightCommand = null
                sendNextPendingCommand()
                notifyQueueIdleIfReady()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SessionStore(this)
        buildUi()
        requestNeededPermissions()
        refreshHistory()
        appendLog("OpenLifeSpan ready")
        startAutoConnect()
    }

    override fun onDestroy() {
        autoConnectEnabled = false
        mainHandler.removeCallbacks(autoConnectRunnable)
        resetGattState("activity destroyed", closeDelayMillis = 0, resumeAutoConnect = false)
        super.onDestroy()
    }

    private fun buildUi() {
        statusView = TextView(this).apply {
            text = "Standby; press console BT"
            textSize = 18f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        }
        summaryView = TextView(this).apply { textSize = 15f }
        currentView = TextView(this).apply {
            text = "No session synced yet."
            textSize = 15f
        }
        historyList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logView = TextView(this).apply {
            textSize = 12f
            visibility = View.GONE
            setTextIsSelectable(true)
        }

        val syncButton = commandButton("Sync Session") { startSessionSync() }
        val syncClearRestoreButton = commandButton("Sync, Clear, Restore 2.5") {
            confirmCommand(
                title = "Sync and clear?",
                message = "This saves the console counters, clears stored console data, then restores speed to 2.5 MPH."
            ) {
                startSessionSync(clearConsole = true, restoreSpeedHundredths = 250)
            }
        }
        val clearButton = commandButton("Clear Console Data") {
            confirmCommand(
                title = "Clear console data?",
                message = "This sends AB 01 00 00 00 to clear stored activity data from the console."
            ) {
                enqueueCommand("clear console data", LifeSpanProtocol.clearStoredData())
            }
        }
        val resetButton = commandButton("Reset BLE Session") { resetBleSession() }
        val autoButton = commandButton("Auto Connect: On") {
            autoConnectEnabled = !autoConnectEnabled
            (it as Button).text = if (autoConnectEnabled) "Auto Connect: On" else "Auto Connect: Off"
            updateStatus(if (autoConnectEnabled) "Standby; press console BT" else "Auto connect off")
            appendLog("auto-connect enabled=$autoConnectEnabled")
            if (autoConnectEnabled) startAutoConnect()
        }
        val logButton = commandButton("Show Log") {
            logView.visibility = if (logView.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            (it as Button).text = if (logView.visibility == View.VISIBLE) "Hide Log" else "Show Log"
        }

        val speedControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(commandButton("2.0") { confirmSetSpeed(200) }, rowButtonParams())
            addView(commandButton("2.5") { confirmSetSpeed(250) }, rowButtonParams())
            addView(commandButton("3.0") { confirmSetSpeed(300) }, rowButtonParams())
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(statusView)
            addView(sectionTitle("Today"))
            addView(summaryView)
            addView(sectionTitle("Sync"))
            addView(syncButton)
            addView(syncClearRestoreButton)
            addView(clearButton)
            addView(sectionTitle("Speed"))
            addView(speedControls)
            addView(sectionTitle("History"))
            addView(currentView)
            addView(historyList)
            addView(sectionTitle("Connection"))
            addView(autoButton)
            addView(resetButton)
            addView(logButton)
            addView(logView)
        }

        val scrollView = ScrollView(this).apply { addView(content) }
        if (Build.VERSION.SDK_INT >= 23) {
            scrollView.setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val systemBars = insets.getInsets(WindowInsets.Type.systemBars())
                    view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(
                        insets.systemWindowInsetLeft,
                        insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight,
                        insets.systemWindowInsetBottom
                    )
                }
                insets
            }
        }
        setContentView(scrollView)
    }

    private fun sectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 17f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, 28, 0, 8)
        }
    }

    private fun commandButton(text: String, onClick: (View) -> Unit): Button {
        return Button(this).apply {
            this.text = text
            minHeight = 52
            setAllCaps(false)
            setOnClickListener(onClick)
        }
    }

    private fun rowButtonParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = 8
        }
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

        if (permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(), 100)
    }

    private fun hasConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun startAutoConnect() {
        mainHandler.removeCallbacks(autoConnectRunnable)
        if (autoConnectEnabled && !gattCloseInProgress) mainHandler.post(autoConnectRunnable)
    }

    private fun connectGattToLifespan(manual: Boolean = true) {
        if (!hasConnectPermission()) {
            if (manual) appendLog("missing Bluetooth connect permission")
            requestNeededPermissions()
            return
        }
        if (gattConnectInProgress || activeGatt != null || gattCloseInProgress) return

        val bluetoothAdapter = getSystemService(BluetoothManager::class.java)?.adapter
        val device = bluetoothAdapter?.bondedDevices.orEmpty()
            .firstOrNull { it.name.equals(lifespanDeviceName, ignoreCase = true) }
        if (device == null) {
            if (manual) appendLog("no paired $lifespanDeviceName device found")
            updateStatus("Pair LifeSpan in Android Bluetooth settings")
            return
        }

        gattConnectInProgress = true
        val attemptId = activeGattAttemptId + 1
        activeGattAttemptId = attemptId
        updateStatus("Connecting to console")
        appendLog("${if (manual) "manual" else "auto"} connect attempt=$attemptId ${device.address}")
        val gatt = if (Build.VERSION.SDK_INT >= 23) {
            device.connectGatt(this, false, gattCallback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(this, false, gattCallback)
        }
        activeGatt = gatt
        mainHandler.postDelayed({
            if (gattConnectInProgress && activeGatt == gatt && activeGattAttemptId == attemptId) {
                resetGattState("connect attempt=$attemptId timed out", closeDelayMillis = 500)
            }
        }, 6_000)
    }

    private fun onGattReady() {
        updateStatus("Connected; ready to sync")
        appendLog("GATT ready")
        if (syncAfterConnect) {
            syncAfterConnect = false
            startSessionSync(clearAfterSync, restoreSpeedAfterSync)
        }
    }

    private fun startSessionSync(clearConsole: Boolean = false, restoreSpeedHundredths: Int? = null) {
        clearAfterSync = clearConsole
        restoreSpeedAfterSync = restoreSpeedHundredths
        if (activeWriteCharacteristic == null || activeGatt == null) {
            syncAfterConnect = true
            updateStatus("Sync armed; press console BT")
            appendLog("sync armed while disconnected")
            return
        }

        val snapshot = SyncSnapshot()
        activeSync = snapshot
        updateStatus("Syncing session")
        currentView.text = "Reading console counters..."
        commandQueueIdle = { finishSessionSync(snapshot, clearConsole, restoreSpeedHundredths) }

        enqueueProperty("units", LifeSpanProtocol.PROPERTY_UNITS, snapshot)
        enqueueProperty("distance", LifeSpanProtocol.PROPERTY_DISTANCE, snapshot)
        enqueueProperty("calories", LifeSpanProtocol.PROPERTY_CALORIES, snapshot)
        enqueueProperty("steps", LifeSpanProtocol.PROPERTY_STEPS, snapshot)
        enqueueProperty("elapsed time", LifeSpanProtocol.PROPERTY_ELAPSED_TIME, snapshot)
        enqueueProperty("max speed", LifeSpanProtocol.PROPERTY_MAX_SPEED, snapshot)
        enqueueProperty("device state", LifeSpanProtocol.PROPERTY_DEVICE_STATE, snapshot)
        enqueueProperty("workout status", LifeSpanProtocol.PROPERTY_WORKOUT_STATUS, snapshot)
    }

    private fun enqueueProperty(label: String, property: Int, snapshot: SyncSnapshot) {
        enqueueCommand(label, LifeSpanProtocol.requestProperty(property), property) { value ->
            val decoded = value?.let { LifeSpanProtocol.parsePropertyResponse(property, it) }
            when (decoded) {
                is PropertyValue.Units -> snapshot.units = decoded.value
                is PropertyValue.Distance -> snapshot.distance = decoded.value
                is PropertyValue.Calories -> snapshot.calories = decoded.value
                is PropertyValue.Steps -> snapshot.steps = decoded.value
                is PropertyValue.ElapsedTime -> snapshot.durationSeconds = decoded.seconds
                is PropertyValue.MaxSpeed -> snapshot.maxSpeed = decoded.value
                is PropertyValue.DeviceState -> snapshot.deviceState = decoded.value
                is PropertyValue.WorkoutStatus -> snapshot.workoutStatus = decoded.value
                else -> appendLog("could not decode $label")
            }
            updateCurrentSnapshot(snapshot)
        }
    }

    private fun finishSessionSync(
        snapshot: SyncSnapshot,
        clearConsole: Boolean,
        restoreSpeedHundredths: Int?
    ) {
        commandQueueIdle = null
        activeSync = null
        val session = snapshot.toSessionOrNull()
        if (session == null) {
            updateStatus("Sync incomplete")
            currentView.text = "Sync incomplete. Press console BT and try again."
            appendLog("sync incomplete snapshot=$snapshot")
            return
        }

        store.add(session)
        updateStatus("Session saved")
        currentView.text = formatSession(session)
        appendLog("session saved id=${session.id}")
        refreshHistory()

        if (clearConsole) enqueueCommand("clear console data", LifeSpanProtocol.clearStoredData())
        if (restoreSpeedHundredths != null) {
            enqueueCommand("restore speed", LifeSpanProtocol.setSpeed(restoreSpeedHundredths))
        }
    }

    private fun enqueueCommand(
        label: String,
        bytes: ByteArray,
        property: Int? = null,
        onResponse: ((ByteArray?) -> Unit)? = null
    ) {
        if (!hasConnectPermission()) {
            requestNeededPermissions()
            return
        }
        if (activeGatt == null || activeWriteCharacteristic == null) {
            updateStatus("Disconnected; press console BT")
            appendLog("cannot send $label while disconnected")
            return
        }

        pendingCommands.add(PendingCommand(label, bytes, property, onResponse))
        appendLog("queued $label tx=${bytes.toHex()}")
        sendNextPendingCommand()
    }

    private fun sendNextPendingCommand() {
        if (writeInFlight || pendingCommands.isEmpty()) return

        val gatt = activeGatt
        val characteristic = activeWriteCharacteristic
        if (gatt == null || characteristic == null) {
            notifyQueueIdleIfReady()
            return
        }

        val command = pendingCommands.remove()
        val started = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(
                characteristic,
                command.bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = command.bytes
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
        appendLog("tx ${command.label} ${command.bytes.toHex()} started=$started")
        if (!started) {
            command.onResponse?.invoke(null)
            sendNextPendingCommand()
            notifyQueueIdleIfReady()
            return
        }

        writeInFlight = true
        inFlightCommand = command
        mainHandler.postDelayed({
            if (writeInFlight && inFlightCommand === command) {
                appendLog("timeout waiting for ${command.label}")
                command.onResponse?.invoke(null)
                writeInFlight = false
                inFlightCommand = null
                sendNextPendingCommand()
                notifyQueueIdleIfReady()
            }
        }, commandTimeoutMillis)
    }

    private fun notifyQueueIdleIfReady() {
        if (!writeInFlight && pendingCommands.isEmpty()) {
            commandQueueIdle?.let { mainHandler.post(it) }
        }
    }

    private fun confirmSetSpeed(speedHundredths: Int) {
        val speed = speedHundredths / 100.0
        val packet = LifeSpanProtocol.setSpeed(speedHundredths)
        confirmCommand(
            title = "Set speed to ${"%.1f".format(Locale.US, speed)}?",
            message = "This sends ${packet.toHex()} to the treadmill. Use only while supervising the treadmill."
        ) {
            enqueueCommand("set speed ${"%.2f".format(Locale.US, speed)}", packet)
            enqueueCommand("read speed", LifeSpanProtocol.requestProperty(LifeSpanProtocol.PROPERTY_SPEED)) { value ->
                val decoded = value?.let {
                    LifeSpanProtocol.parsePropertyResponse(LifeSpanProtocol.PROPERTY_SPEED, it)
                } as? PropertyValue.Speed
                appendLog("speed now ${decoded?.value ?: "unknown"}")
            }
        }
    }

    private fun resetBleSession() {
        appendLog("manual BLE reset")
        resetGattState("manual reset", closeDelayMillis = 500)
    }

    private fun resetGattState(
        reason: String,
        closeDelayMillis: Long = 250,
        resumeAutoConnect: Boolean = true
    ) {
        activeGattAttemptId += 1
        val gatt = activeGatt
        gattConnectInProgress = false
        activeGatt = null
        activeWriteCharacteristic = null
        pendingCommands.clear()
        writeInFlight = false
        inFlightCommand = null
        commandQueueIdle = null
        activeSync = null
        gattCloseInProgress = gatt != null
        updateStatus("Resetting BLE session")
        appendLog("reset GATT: $reason")

        if (gatt == null) {
            gattCloseInProgress = false
            if (resumeAutoConnect) startAutoConnect()
            return
        }

        gatt.disconnectQuietly()
        mainHandler.postDelayed({
            gatt.closeQuietly()
            gattCloseInProgress = false
            updateStatus("Standby; press console BT")
            if (resumeAutoConnect) startAutoConnect()
        }, closeDelayMillis)
    }

    private fun updateCurrentSnapshot(snapshot: SyncSnapshot) {
        currentView.text = listOf(
            "Distance: ${snapshot.distance?.let { "%.2f".format(Locale.US, it) } ?: "--"}",
            "Time: ${snapshot.durationSeconds?.let { LifeSpanProtocol.formatDuration(it) } ?: "--"}",
            "Steps: ${snapshot.steps ?: "--"}",
            "Calories: ${snapshot.calories ?: "--"}",
            "Max speed: ${snapshot.maxSpeed?.let { LifeSpanProtocol.formatSpeed(it) } ?: "--"}"
        ).joinToString("\n")
    }

    private fun refreshHistory() {
        val sessions = store.load()
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val dayFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val todaySessions = sessions.filter { dayFormatter.format(Date(it.capturedAtMillis)) == todayKey }
        val distance = todaySessions.sumOf { it.distance }
        val duration = todaySessions.sumOf { it.durationSeconds }
        val calories = todaySessions.sumOf { it.calories }
        val steps = todaySessions.sumOf { it.steps }
        summaryView.text = "${"%.2f".format(Locale.US, distance)} mi  |  " +
            "${LifeSpanProtocol.formatDuration(duration)}  |  $steps steps  |  $calories cal"

        historyList.removeAllViews()
        sessions.take(20).forEach { session ->
            historyList.addView(TextView(this).apply {
                text = formatSession(session)
                textSize = 14f
                setPadding(0, 10, 0, 10)
            })
        }
    }

    private fun formatSession(session: WorkoutSession): String {
        val maxSpeed = session.maxSpeed?.let { " max ${LifeSpanProtocol.formatSpeed(it)}" } ?: ""
        return "${session.displayTitle()}\n" +
            "${"%.2f".format(Locale.US, session.distance)} mi, " +
            "${LifeSpanProtocol.formatDuration(session.durationSeconds)}, " +
            "${session.steps} steps, ${session.calories} cal\n" +
            "avg ${LifeSpanProtocol.formatSpeed(session.averageSpeed)} mph$maxSpeed"
    }

    private fun confirmCommand(title: String, message: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Send") { _, _ -> onConfirm() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateStatus(status: String) {
        runOnUiThread {
            if (::statusView.isInitialized) statusView.text = status
        }
    }

    private fun appendLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "[$timestamp] $message"
        runOnUiThread {
            if (::logView.isInitialized) logView.append("$line\n")
        }
        try {
            openFileOutput(logFileName, MODE_APPEND).use { output ->
                output.write("$line\n".toByteArray(Charsets.UTF_8))
            }
        } catch (_: RuntimeException) {
        }
    }

    private fun BluetoothGatt.disconnectQuietly() {
        try {
            disconnect()
        } catch (exception: RuntimeException) {
            appendLog("gatt disconnect failed: ${exception.message}")
        }
    }

    private fun BluetoothGatt.closeQuietly() {
        try {
            close()
        } catch (exception: RuntimeException) {
            appendLog("gatt close failed: ${exception.message}")
        }
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
}
