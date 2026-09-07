package dev.openlifespan.logger

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.pm.PackageManager
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
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
import android.widget.TextView
import android.widget.PopupMenu
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Switch
import android.widget.Space
import android.widget.ScrollView
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPOutputStream

class MainActivity : Activity() {
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private val lightMode: Boolean get() = getSharedPreferences("settings", 0).getBoolean("lightMode", false)
    private var renderedLightMode = false
    companion object { const val ACTION_SET_SPEED = "dev.openlifespan.SET_SPEED"; const val ACTION_RESET_BLE = "dev.openlifespan.RESET_BLE"; const val ACTION_TOGGLE_THEME = "dev.openlifespan.TOGGLE_THEME"; const val ACTION_TOGGLE_UNITS = "dev.openlifespan.TOGGLE_UNITS"; const val EXTRA_SPEED = "speedHundredths" }
    private var currentSpeedHundredths = 250
    private val createExportRequest = 401
    private val importRequest = 402
    private var pendingExport: String? = null
    private val lifespanDeviceName = "LifeSpan"
    private val logFileName = "openlifespan-log.txt"
    private val compressedLogFileName = "openlifespan-log-previous.gz"
    private val maxLogBytes = 100 * 1024L
    private val autoConnectIntervalMillis = 4_000L
    private val commandTimeoutMillis = 1_500L

    private lateinit var store: SessionStore
    private lateinit var statusView: TextView
    private lateinit var summaryView: TextView
    private lateinit var trendView: TextView
    private lateinit var trendChart: TrendChartView
    private lateinit var dashboardView: DashboardView
    private var selectedTrendPeriod = TrendPeriod.DAY
    private var settingsReceiverRegistered = false
    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == ACTION_RESET_BLE) { resetBleSession(); return }
            if (intent?.action == ACTION_TOGGLE_THEME) { recreate(); return }
            if (intent?.action == ACTION_TOGGLE_UNITS) { dashboardView.invalidate(); return }
            val speed = intent?.getIntExtra(EXTRA_SPEED, 250) ?: return
            confirmSetSpeed(speed)
        }
    }
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
        val light = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("lightMode", false)
        renderedLightMode = light
        if (light) setTheme(R.style.AppThemeLight)
        super.onCreate(savedInstanceState)
        window.navigationBarColor = if (light) 0xfff4f7fb.toInt() else 0xff090d16.toInt()
        if (Build.VERSION.SDK_INT >= 26 && light) window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        store = SessionStore(this)
        buildUi()
        requestNeededPermissions()
        refreshHistory()
        appendLog("OpenLifeSpan ready")
        startAutoConnect()
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(settingsReceiver, IntentFilter().apply { addAction(ACTION_SET_SPEED); addAction(ACTION_RESET_BLE); addAction(ACTION_TOGGLE_THEME) }, RECEIVER_NOT_EXPORTED)
            else registerReceiver(settingsReceiver, IntentFilter().apply { addAction(ACTION_SET_SPEED); addAction(ACTION_RESET_BLE); addAction(ACTION_TOGGLE_THEME) })
            settingsReceiverRegistered = true
        } catch (exception: RuntimeException) {
            appendLog("settings receiver unavailable: ${exception.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        if (::dashboardView.isInitialized) {
            if (renderedLightMode != lightMode) recreate() else refreshHistory()
        }
    }

    override fun onDestroy() {
        autoConnectEnabled = false
        mainHandler.removeCallbacks(autoConnectRunnable)
        if (settingsReceiverRegistered) {
            try { unregisterReceiver(settingsReceiver) } catch (_: IllegalArgumentException) { }
            settingsReceiverRegistered = false
        }
        resetGattState("activity destroyed", closeDelayMillis = 0, resumeAutoConnect = false)
        super.onDestroy()
    }

    private fun buildUi() {
        statusView = TextView(this).apply {
            text = "Standby; press console BT"
            textSize = 18f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(0xff94a3b8.toInt())
        }
        summaryView = TextView(this).apply { textSize = 18f; setTextColor(if (lightMode) 0xff0f172a.toInt() else 0xfff8fafc.toInt()); setPadding(dp(18), dp(18), dp(18), dp(18)); background = surface(if (lightMode) 0xffffffff.toInt() else 0xff131b2e.toInt(), dp(18)) }
        trendView = TextView(this).apply { textSize = 14f }
        trendChart = TrendChartView(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 260) }
        dashboardView = DashboardView(this).apply {
            // DashboardView owns its aspect ratio in onMeasure.  A wrap-content height is
            // essential here: an exact height quietly truncates the canvas on narrow windows.
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            onZoneInfo = { AlertDialog.Builder(this@MainActivity).setTitle("Speed zones").setMessage("Focus  0.4–1.8 mph\nLight walking for typing and detailed desk work.\n\nPace  1.8–2.8 mph\nSteady cadence for meetings, reading, and casual browsing.\n\nBrisk  2.8–4.0 mph\nActive walking for cardio, podcasts, or exercise bouts.").setPositiveButton("Close", null).show() }
            onDatePickerRequested = { showDatePicker() }
        }
        currentView = TextView(this).apply {
            text = "No session synced yet."
            textSize = 15f
            setTextColor(if (lightMode) 0xff334155.toInt() else 0xffcbd5e1.toInt()); setPadding(dp(18), dp(18), dp(18), dp(18)); background = surface(if (lightMode) 0xffffffff.toInt() else 0xff131b2e.toInt(), dp(18))
        }
        historyList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logView = TextView(this).apply {
            textSize = 12f
            visibility = View.GONE
            setTextIsSelectable(true)
        }

        val syncButton = commandButton("Sync Only") { startSessionSync() }
        val syncClearRestoreButton = commandButton("Sync & Clear") {
            confirmCommand(
                title = "Sync and clear?",
                message = "This saves the console counters, clears stored console data, then restores speed to 2.5 MPH.",
                onConfirm = {
                startSessionSync(clearConsole = true, restoreSpeedHundredths = 250)
                }
            )
        }
        syncButton.background = surface(0xff155e75.toInt(), dp(16))
        syncButton.setTextColor(0xfff8fafc.toInt())
        syncButton.textSize = 16f
        syncClearRestoreButton.background = surface(0xff065f46.toInt(), dp(16))
        syncClearRestoreButton.setTextColor(0xfff8fafc.toInt())
        syncClearRestoreButton.textSize = 16f
        syncClearRestoreButton.setPadding(dp(14), 0, dp(14), 0)
        syncButton.setPadding(dp(14), 0, dp(14), 0)
        val clearButton = commandButton("Clear Console Data") {
            confirmCommand(
                title = "Clear console data?",
                message = "This resets the console counters after confirmation. Use only while supervising the treadmill.",
                onConfirm = {
                    clearConsoleCounters(restoreSpeedHundredths = null)
                }
            )
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
        val hamburger = Button(this).apply { text = "⋮"; textSize = 30f; setAllCaps(false); minWidth = dp(48); background = null; setTextColor(0xff94a3b8.toInt()); setOnClickListener { showNavigationMenu(this) } }
        val logo = ImageView(this).apply { setImageResource(dev.openlifespan.logger.R.drawable.openlifespan_mascot); scaleType = ImageView.ScaleType.CENTER_CROP; layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(8) } }
        val appTitle = TextView(this).apply { text = "OpenLifeSpan"; textSize = 23f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(if (lightMode) 0xff0f172a.toInt() else 0xfff8fafc.toInt()) }
        val titleGroup = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER; addView(logo); addView(appTitle) }
        statusView.text = "●"
        statusView.textSize = 16f
        statusView.setTextColor(0xff64748b.toInt())
        val topBar = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)); addView(hamburger, FrameLayout.LayoutParams(dp(58), dp(58)).apply { gravity = android.view.Gravity.START }); addView(statusView, FrameLayout.LayoutParams(dp(42), dp(58)).apply { gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL }); addView(titleGroup, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(58)).apply { gravity = android.view.Gravity.CENTER }) }
        val speedSlider = SpeedControlView(this).apply { progress = ((currentSpeedHundredths - 40) / 10).coerceIn(0, 36) }
        val speedButton = commandButton("Speed") { showSpeedPopup(speedSlider, it as Button) }
        val syncRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)); addView(syncClearRestoreButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { marginEnd = dp(6) }); addView(syncButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { marginEnd = dp(6) }); addView(speedButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38))) }
        // The sheet owns confirmation; dragging is only a preview, never an accidental command.
        speedSlider.onCommit = null

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = surface(if (lightMode) 0xfff4f7fb.toInt() else 0xff090d16.toInt(), 0)
            addView(topBar)
            addView(syncRow)
            addView(dashboardView)
            addView(logView)
        }

        if (Build.VERSION.SDK_INT >= 23) {
            content.setOnApplyWindowInsetsListener { view, insets ->
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
        setContentView(content)
    }

    private fun showDatePicker() {
        val today = java.util.Calendar.getInstance()
        val picker = android.app.DatePickerDialog(this, { _, year, month, day ->
            dashboardView.selectDate(java.util.Calendar.getInstance().apply { set(year, month, day, 12, 0, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis)
        }, today.get(java.util.Calendar.YEAR), today.get(java.util.Calendar.MONTH), today.get(java.util.Calendar.DAY_OF_MONTH))
        picker.setButton(android.app.DatePickerDialog.BUTTON_NEUTRAL, "Today") { _, _ -> dashboardView.selectDate(java.util.Calendar.getInstance().timeInMillis) }
        picker.show()
    }

    private fun showNavigationMenu(anchor: View) {
        val dialog = Dialog(this)
        val sheet = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(18)); background = surface(if (lightMode) 0xffffffff.toInt() else 0xff131b2e.toInt(), dp(24)) }
        sheet.addView(TextView(this).apply { text = "OpenLifeSpan"; textSize = 18f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(if (lightMode) 0xff0f172a.toInt() else 0xfff8fafc.toInt()); setPadding(dp(8), 0, 0, dp(10)) })
        listOf("Settings", "History", "Data", "System", "Help").forEach { section -> sheet.addView(commandButton(section) { dialog.dismiss(); startActivity(Intent(this, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SECTION, section)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { bottomMargin = dp(5) }) }
        dialog.setContentView(sheet); dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT)); dialog.show(); dialog.window?.setLayout((resources.displayMetrics.widthPixels * .82f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun launchExport(mimeType: String, fileName: String, content: String) {
        pendingExport = content
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { type = mimeType; putExtra(Intent.EXTRA_TITLE, fileName) }, createExportRequest)
    }

    private fun launchImport() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "application/json"; addCategory(Intent.CATEGORY_OPENABLE) }, importRequest)
    }

    @Deprecated("Android activity result compatibility for minSdk 26")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            if (requestCode == createExportRequest) {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(pendingExport.orEmpty()) }
                updateStatus("Export complete")
            } else if (requestCode == importRequest) {
                val imported = contentResolver.openInputStream(uri)?.bufferedReader()?.use { SessionTransfer.fromJson(it.readText()) }.orEmpty()
                val before = store.load().size
                store.replaceAll(store.load().plus(imported).distinctBy { it.contentKey() })
                refreshHistory()
                updateStatus("Imported ${store.load().size - before} new sessions")
            }
        } catch (exception: Exception) {
            updateStatus("Data transfer failed")
            appendLog("data transfer failed: ${exception.message}")
        }
    }

    private fun sectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 17f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(0xfff8fafc.toInt())
            setPadding(0, dp(28), 0, dp(8))
        }
    }

    private fun showSpeedPopup(slider: SpeedControlView, button: Button) {
        var preview = ((currentSpeedHundredths - 40) / 10).coerceIn(0, 36)
        // A dialog owns this control instance.  Never move the previously displayed view
        // between windows: Android may detach it while a gesture callback is still running.
        val control = SpeedControlView(this)
        val sheet = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(22), dp(24), dp(20)); background = surface(if (lightMode) 0xffffffff.toInt() else 0xff131b2e.toInt(), dp(28)) }
        val value = TextView(this).apply { textSize = 34f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); gravity = android.view.Gravity.CENTER; setTextColor(if (lightMode) 0xff0f172a.toInt() else 0xfff8fafc.toInt()) }
        fun showValue() { value.text = "%.1f mph".format(Locale.US, 0.4 + preview / 10.0); control.progress = preview }
        sheet.addView(TextView(this).apply { text = "TREADMILL SPEED"; textSize = 12f; letterSpacing = .12f; gravity = android.view.Gravity.CENTER; setTextColor(0xff06b6d4.toInt()) })
        sheet.addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))
        val endpoints = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        endpoints.addView(TextView(this).apply { text = "0.4 mph"; textSize = 12f; setTextColor(0xff94a3b8.toInt()) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        endpoints.addView(TextView(this).apply { text = "4.0 mph"; textSize = 12f; gravity = android.view.Gravity.END; setTextColor(0xff94a3b8.toInt()) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sheet.addView(endpoints)
        sheet.addView(control, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        val stepRow = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER; orientation = LinearLayout.HORIZONTAL }
        fun step(text: String, delta: Int) = commandButton(text) { preview = (preview + delta).coerceIn(0, 36); showValue() }.apply { minHeight = dp(42); textSize = 18f }
        stepRow.addView(step("−", -1), LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(8) })
        stepRow.addView(step("+", 1), LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(8) })
        sheet.addView(stepRow)
        sheet.addView(TextView(this).apply { text = "QUICK PICKS"; textSize = 11f; setPadding(0, dp(18), 0, dp(7)); setTextColor(0xff94a3b8.toInt()) })
        val picks = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(1.0, 2.0, 2.5, 3.0).forEach { mph -> picks.addView(commandButton("%.1f".format(Locale.US, mph)) { preview = ((mph - .4) * 10).toInt(); showValue() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(5) }) }
        sheet.addView(picks)
        val actions = LinearLayout(this).apply { gravity = android.view.Gravity.END; setPadding(0, dp(18), 0, 0) }
        val dialog = Dialog(this)
        actions.addView(commandButton("Cancel") { dialog.dismiss() }, LinearLayout.LayoutParams(dp(104), dp(44)).apply { marginEnd = dp(8) })
        actions.addView(commandButton("Set speed") { val requested = 40 + preview * 10; dialog.dismiss(); if (requested != currentSpeedHundredths) confirmSetSpeed(requested) { slider.progress = (currentSpeedHundredths - 40) / 10; button.text = "Speed ${"%.1f".format(Locale.US, currentSpeedHundredths / 100.0)}" } }, LinearLayout.LayoutParams(dp(128), dp(44)))
        sheet.addView(actions)
        control.onChanged = { preview = it; value.text = "%.1f mph".format(Locale.US, .4 + it / 10.0) }
        showValue(); dialog.setContentView(sheet); dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT)); dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); dialog.show(); dialog.window?.setLayout((resources.displayMetrics.widthPixels * .92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun commandButton(text: String, onClick: (View) -> Unit): Button {
        return Button(this).apply {
            this.text = text
            minHeight = dp(52)
            setAllCaps(false)
            setTextColor(if (lightMode) 0xff0f172a.toInt() else 0xfff8fafc.toInt())
            background = surface(if (lightMode) 0xffffffff.toInt() else 0xff1e293b.toInt(), dp(16))
            setOnClickListener(onClick)
        }
    }

    private fun surface(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() }

    private fun rowButtonParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(8)
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
            updateStatus("Standby")
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

        val alreadySaved = store.containsEquivalent(session)
        store.add(session)
        updateStatus(if (alreadySaved) "Session already saved" else "Session saved")
        currentView.text = formatSession(session)
        appendLog("${if (alreadySaved) "duplicate session ignored" else "session saved"} id=${session.id}")
        refreshHistory()

        if (clearConsole) {
            clearConsoleCounters(restoreSpeedHundredths)
        } else if (restoreSpeedHundredths != null) {
            enqueueCommand("restore speed", LifeSpanProtocol.setSpeed(restoreSpeedHundredths))
        }
    }

    private fun clearConsoleCounters(restoreSpeedHundredths: Int?) {
        updateStatus("Clearing console counters")
        enqueueCommand("engage external control", LifeSpanProtocol.engageExternalControl()) { value ->
            appendLog("engage external control status=${value?.toHex().orEmpty()}")
        }
        enqueueCommand("reset console counters", LifeSpanProtocol.resetCounters()) { value ->
            appendLog("reset counters status=${value?.toHex().orEmpty()}")
        }
        enqueueCommand("verify distance", LifeSpanProtocol.requestProperty(LifeSpanProtocol.PROPERTY_DISTANCE)) { value ->
            appendLog("post-clear distance=${value?.toHex().orEmpty()}")
        }
        enqueueCommand("verify elapsed time", LifeSpanProtocol.requestProperty(LifeSpanProtocol.PROPERTY_ELAPSED_TIME)) { value ->
            appendLog("post-clear elapsed time=${value?.toHex().orEmpty()}")
        }
        enqueueCommand("verify steps", LifeSpanProtocol.requestProperty(LifeSpanProtocol.PROPERTY_STEPS)) { value ->
            appendLog("post-clear steps=${value?.toHex().orEmpty()}")
        }
        if (restoreSpeedHundredths != null) {
            enqueueCommand("restore speed", LifeSpanProtocol.setSpeed(restoreSpeedHundredths))
        }
        enqueueCommand("escape to idle", LifeSpanProtocol.escapeToIdle()) { value ->
            appendLog("escape to idle status=${value?.toHex().orEmpty()}")
            updateStatus("Console clear sent")
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

    private fun confirmSetSpeed(speedHundredths: Int, onCancelled: (() -> Unit)? = null) {
        val speed = speedHundredths / 100.0
        val packet = LifeSpanProtocol.setSpeed(speedHundredths)
        confirmCommand(
            title = "Set speed to ${"%.1f".format(Locale.US, speed)}?",
            message = "Use only while supervising the treadmill.",
            onCancel = onCancelled,
            onConfirm = {
            currentSpeedHundredths = speedHundredths
            enqueueCommand("set speed ${"%.2f".format(Locale.US, speed)}", packet)
            enqueueCommand("read speed", LifeSpanProtocol.requestProperty(LifeSpanProtocol.PROPERTY_SPEED)) { value ->
                val decoded = value?.let {
                    LifeSpanProtocol.parsePropertyResponse(LifeSpanProtocol.PROPERTY_SPEED, it)
                } as? PropertyValue.Speed
                appendLog("speed now ${decoded?.value ?: "unknown"}")
            }
            }
        )
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
        dashboardView.sessions = sessions
        dashboardView.period = selectedTrendPeriod
        dashboardView.invalidate()
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val dayFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val todaySessions = sessions.filter { dayFormatter.format(Date(it.capturedAtMillis)) == todayKey }
        val distance = todaySessions.sumOf { it.distance }
        val duration = todaySessions.sumOf { it.durationSeconds }
        val calories = todaySessions.sumOf { it.calories }
        val steps = todaySessions.sumOf { it.steps }
        summaryView.text = "${"%.2f".format(Locale.US, distance)} mi  |  " +
            "${LifeSpanProtocol.formatDuration(duration)}  |  $steps steps  |  $calories cal"
        val now = System.currentTimeMillis()
        fun trend(days: Int): String {
            val recent = sessions.filter { now - it.capturedAtMillis < days * 86_400_000L }
            if (recent.isEmpty()) return "No sessions in the last $days days"
            val totalDistance = recent.sumOf { it.distance }
            val totalDuration = recent.sumOf { it.durationSeconds }
            val avgDistance = totalDistance / recent.size
            return "Last $days days: ${recent.size} sessions, ${"%.2f".format(Locale.US, totalDistance)} mi, " +
                "${LifeSpanProtocol.formatDuration(totalDuration)}\n" +
                "Average: ${"%.2f".format(Locale.US, avgDistance)} mi/session"
        }
        trendView.text = trend(7) + "\n" + trend(30)
        val points = TrendCalculator.points(sessions, selectedTrendPeriod)
        trendChart.points = points
        trendChart.title = "${selectedTrendPeriod.label} distance"
        trendChart.invalidate()
        val selectedDistance = points.sumOf { it.distance }
        val selectedCalories = points.sumOf { it.calories }
        val selectedSessions = points.sumOf { it.sessions }
        trendView.text = "${selectedTrendPeriod.label}: ${"%.2f".format(Locale.US, selectedDistance)} mi  •  $selectedSessions sessions  •  $selectedCalories cal\n" + trendView.text

        historyList.removeAllViews()
        sessions.take(20).forEach { session ->
            historyList.addView(TextView(this).apply {
                text = formatSession(session)
                textSize = 14f
                setPadding(0, dp(10), 0, dp(10))
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

    private fun confirmCommand(title: String, message: String, onConfirm: () -> Unit, onCancel: (() -> Unit)? = null) {
        val dialog = Dialog(this)
        val sheet = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(22), dp(24), dp(20)); background = surface(if (lightMode) 0xffffffff.toInt() else 0xff131b2e.toInt(), dp(28)) }
        sheet.addView(TextView(this).apply { text = title; textSize = 22f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); setTextColor(if (lightMode) 0xff0f172a.toInt() else 0xfff8fafc.toInt()) })
        sheet.addView(TextView(this).apply { text = message; textSize = 15f; setTextColor(if (lightMode) 0xff475569.toInt() else 0xffcbd5e1.toInt()); setPadding(0, dp(10), 0, dp(20)) })
        val actions = LinearLayout(this).apply { gravity = android.view.Gravity.END }
        actions.addView(commandButton("Cancel") { dialog.dismiss(); onCancel?.invoke() }, LinearLayout.LayoutParams(dp(104), dp(44)).apply { marginEnd = dp(8) })
        actions.addView(commandButton("Confirm") { dialog.dismiss(); onConfirm() }, LinearLayout.LayoutParams(dp(112), dp(44)))
        sheet.addView(actions); dialog.setContentView(sheet); dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT)); dialog.show(); dialog.window?.setLayout((resources.displayMetrics.widthPixels * .88f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun updateStatus(status: String) {
        runOnUiThread {
            if (::statusView.isInitialized) {
                statusView.text = "●"
                statusView.setTextColor(if (status.contains("Connected") || status.contains("ready") || status.contains("saved")) 0xff10b981.toInt() else 0xff64748b.toInt())
                statusView.contentDescription = status
            }
        }
    }

    private fun appendLog(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "[$timestamp] $message"
        runOnUiThread {
            if (::logView.isInitialized) logView.append("$line\n")
        }
        try {
            val bytes = "$line\n".toByteArray(Charsets.UTF_8)
            val activeLog = getFileStreamPath(logFileName)
            if (activeLog.length() + bytes.size > maxLogBytes && activeLog.exists()) {
                // Keep exactly one compact rollover: the most recent full diagnostic window.
                openFileInput(logFileName).use { input ->
                    openFileOutput(compressedLogFileName, MODE_PRIVATE).use { output ->
                        GZIPOutputStream(output).use { gzip -> input.copyTo(gzip) }
                    }
                }
                openFileOutput(logFileName, MODE_PRIVATE).close()
            }
            openFileOutput(logFileName, MODE_APPEND).use { output ->
                output.write(bytes)
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
