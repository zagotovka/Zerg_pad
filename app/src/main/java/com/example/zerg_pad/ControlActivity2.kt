package com.example.zerg_pad

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import java.io.IOException
import java.io.OutputStream
import java.util.*
import kotlin.math.*

class ControlActivity2 : ComponentActivity() {

    private var btSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private val myuuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private lateinit var angleTextView: TextView
    private lateinit var powerTextView: TextView
    private lateinit var directionTextView: TextView
    private lateinit var joystick: ZergJoystickView
    private lateinit var btStatusText: TextView
    private lateinit var cmdTextView: TextView  // Добавлено: TextView для команд

    private var calibratedCenterX = JOYSTICK_CENTER
    private var calibratedCenterY = JOYSTICK_CENTER

    private val xFilter = LowPassFilter(0.25f)
    private val yFilter = LowPassFilter(0.25f)
    private var lastSentX = JOYSTICK_CENTER
    private var lastSentY = JOYSTICK_CENTER
    private var lastSentPower = 0
    private var lastSentTime = 0L
    private var isInCenter = false
    private var calibrated = false

    private var deviceAddress: String? = null
    private var waitingForPermissions = false

    private var btWarningVisible = false
    private var btWarningTimer: Timer? = null
    private var btMonitorTimer: Timer? = null
    private var showRussian = true

    private val activityScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val PREFIX_JOYSTICK = 0xF1.toByte()
        const val PREFIX_BUTTON = 0xF0.toByte()

        const val BUTTON_A = 0x01.toByte()
        const val BUTTON_B = 0x02.toByte()
        const val BUTTON_X = 0x03.toByte()
        const val BUTTON_Y = 0x04.toByte()
        const val BUTTON_SELECT = 0x05.toByte()
        const val BUTTON_START = 0x06.toByte()
        const val BUTTON_L = 0x07.toByte()
        const val BUTTON_R = 0x08.toByte()

        const val STATE_PRESSED = 0x7F.toByte()
        const val STATE_RELEASED = 0x00.toByte()

        const val JOYSTICK_CENTER = 127
        const val DEADZONE_PERCENT = 3
        const val MIN_UPDATE_INTERVAL = 50L
        const val PERMISSION_REQUEST_CODE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFullscreenMode()
        setupSystemUIListener()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setContentView(R.layout.activity_control2)

        angleTextView = findViewById(R.id.angleTextView)
        powerTextView = findViewById(R.id.powerTextView)
        directionTextView = findViewById(R.id.directionTextView)
        joystick = findViewById(R.id.joystickView)
        btStatusText = findViewById(R.id.bt_status_text)
        cmdTextView = findViewById(R.id.cmdTextView)  // Добавлено: инициализация TextView для команд

        deviceAddress = intent.getStringExtra("device_address")?.trim()
        if (deviceAddress.isNullOrEmpty()) {
            showToast("Device address is missing")
            finish()
            return
        }

        setupControls()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!checkBluetoothPermissions()) {
                waitingForPermissions = true
                requestBluetoothPermissions()
                return
            }
        }

        connectToBluetooth()
        startBtMonitor()
    }

    private fun setupControls() {
        joystick.setOnJoystickMoveListener(object : ZergJoystickView.OnJoystickMoveListener {
            override fun onValueChanged(angle: Int, power: Int, direction: Int) {
                val fixedAngle = if (power < DEADZONE_PERCENT) 0 else (angle - 90 + 360) % 360

                angleTextView.text = getString(R.string.angle_format, fixedAngle)
                powerTextView.text = getString(R.string.power_format, power)
                directionTextView.text = when (direction) {
                    ZergJoystickView.FRONT -> getString(R.string.front_lab)
                    ZergJoystickView.FRONT_RIGHT -> getString(R.string.front_right_lab)
                    ZergJoystickView.RIGHT -> getString(R.string.right_lab)
                    ZergJoystickView.RIGHT_BOTTOM -> getString(R.string.right_bottom_lab)
                    ZergJoystickView.BOTTOM -> getString(R.string.bottom_lab)
                    ZergJoystickView.BOTTOM_LEFT -> getString(R.string.bottom_left_lab)
                    ZergJoystickView.LEFT -> getString(R.string.left_lab)
                    ZergJoystickView.LEFT_FRONT -> getString(R.string.left_front_lab)
                    else -> getString(R.string.center_lab)
                }

                if (!calibrated && power < 5) {
                    calibratedCenterX = JOYSTICK_CENTER
                    calibratedCenterY = JOYSTICK_CENTER
                    xFilter.reset(calibratedCenterX)
                    yFilter.reset(calibratedCenterY)
                    calibrated = true
                }

                processJoystickMovement(fixedAngle, power)
            }
        })

        setupButton(R.id.btn_a, BUTTON_A)
        setupButton(R.id.btn_b, BUTTON_B)
        setupButton(R.id.btn_x, BUTTON_X)
        setupButton(R.id.btn_y, BUTTON_Y)
        setupButton(R.id.btn_select, BUTTON_SELECT)
        setupButton(R.id.btn_start, BUTTON_START)
        setupButton(R.id.btn_left, BUTTON_L)
        setupButton(R.id.btn_right, BUTTON_R)
    }

    private fun processJoystickMovement(angle: Int, power: Int) {
        if (power < DEADZONE_PERCENT) {
            sendCenterPosition()
            return
        }

        val (rawX, rawY) = calculateRawXY(angle, power)
        val filteredX = xFilter.filter(rawX)
        val filteredY = yFilter.filter(rawY)

        if (shouldSendNewValues(filteredX, filteredY)) {
            sendXYCoordinates(filteredX, filteredY, power)
            lastSentX = filteredX
            lastSentY = filteredY
            lastSentTime = System.currentTimeMillis()
            isInCenter = false
        }
    }

    private fun sendCenterPosition() {
        sendXYCoordinates(calibratedCenterX, calibratedCenterY, 0)
        lastSentX = calibratedCenterX
        lastSentY = calibratedCenterY
        lastSentPower = 0
        lastSentTime = System.currentTimeMillis()
        isInCenter = true
        xFilter.reset(calibratedCenterX)
        yFilter.reset(calibratedCenterY)
    }

    private fun sendXYCoordinates(x: Int, y: Int, power: Int) {
        if (x == lastSentX && y == lastSentY && power == lastSentPower) return
        val packet = byteArrayOf(PREFIX_JOYSTICK, x.toByte(), y.toByte(), power.toByte())
        sendPacketWithRetry(packet)
        updateCommandDisplay(packet)  // Добавлено: отображение команды
        lastSentX = x
        lastSentY = y
        lastSentPower = power
        lastSentTime = System.currentTimeMillis()
    }

    // Добавлено: метод для отображения команды
    private fun updateCommandDisplay(packet: ByteArray) {
        runOnUiThread {
            val cmdText = packet.joinToString(" ") { byte ->
                byte.toInt().and(0xFF).toString(16).uppercase().padStart(2, '0')
            }
            cmdTextView.text = cmdText
        }
    }

    private fun shouldSendNewValues(x: Int, y: Int): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastSentTime < MIN_UPDATE_INTERVAL) return false
        return abs(x - lastSentX) > 5 || abs(y - lastSentY) > 5
    }

    private fun calculateRawXY(angle: Int, power: Int): Pair<Int, Int> {
        val radians = Math.toRadians(angle.toDouble())
        val normalizedPower = (power * (JOYSTICK_CENTER - 1)) / 100
        val x = (cos(radians) * normalizedPower).toInt() + calibratedCenterX
        val y = (-sin(radians) * normalizedPower).toInt() + calibratedCenterY
        return Pair(x.coerceIn(0, 255), y.coerceIn(0, 255))
    }

    private fun sendPacketWithRetry(packet: ByteArray, maxRetries: Int = 2) {
        activityScope.launch {
            var attempts = 0
            while (attempts <= maxRetries) {
                try {
                    outputStream?.write(packet)
                    outputStream?.flush()
                    return@launch
                } catch (e: IOException) {
                    attempts++
                    if (attempts > maxRetries) {
                        withContext(Dispatchers.Main) { showBtWarning() }
                        return@launch
                    } else {
                        delay(15L * attempts)
                    }
                }
            }
        }
    }

    @Suppress("ClickableViewAccessibility")
    private fun setupButton(buttonId: Int, buttonCode: Byte) {
        val view = findViewById<View>(buttonId)
        val buttonStates = mutableMapOf<Int, Boolean>()

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (buttonStates[buttonId] != true) {
                        buttonStates[buttonId] = true
                        sendButtonCommand(buttonCode, true)
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (buttonStates[buttonId] != false) {
                        buttonStates[buttonId] = false
                        sendButtonCommand(buttonCode, false)
                    }
                    v.performClick()
                }
            }
            false
        }
    }

    private fun sendButtonCommand(buttonCode: Byte, pressed: Boolean) {
        val state = if (pressed) STATE_PRESSED else STATE_RELEASED
        val packet = byteArrayOf(PREFIX_BUTTON, buttonCode, state)
        sendPacketWithRetry(packet)
        updateCommandDisplay(packet)  // Добавлено: отображение команды
    }

    private fun showBtWarning() {
        if (btWarningVisible) return
        btWarningVisible = true

        runOnUiThread {
            btStatusText.visibility = View.VISIBLE
        }

        btWarningTimer = Timer()
        btWarningTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                runOnUiThread {
                    btStatusText.text = if (showRussian)
                        "Потерянная связь с BT!"
                    else
                        "Lost connection with BT!"
                    showRussian = !showRussian
                }
            }
        }, 0, 1000)
    }

    private fun hideBtWarning() {
        if (!btWarningVisible) return
        btWarningVisible = false

        runOnUiThread {
            btStatusText.visibility = View.GONE
        }

        btWarningTimer?.cancel()
        btWarningTimer = null
    }

    private fun scheduleNextBtCheck() {
        btMonitorTimer?.schedule(object : TimerTask() {
            override fun run() {
                val isConnected = try {
                    btSocket?.isConnected == true
                } catch (e: Exception) {
                    false
                }

                if (!isConnected) {
                    showBtWarning()
                } else {
                    hideBtWarning()
                }

                scheduleNextBtCheck()
            }
        }, 2000)
    }

    private fun startBtMonitor() {
        btMonitorTimer = Timer()
        scheduleNextBtCheck()
    }

    private fun checkBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE)
        }
    }

    private fun connectToBluetooth() {
        try {
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val btAdapter = btManager.adapter

            if (!checkBluetoothPermissions()) {
                waitingForPermissions = true
                requestBluetoothPermissions()
                return
            }

            val device = btAdapter.getRemoteDevice(deviceAddress)
            btSocket = device.createRfcommSocketToServiceRecord(myuuid)
            btSocket?.connect()
            outputStream = btSocket?.outputStream
            showToast("Connected to device")
        } catch (e: IOException) {
            Log.e("BT_Zerg2", "Connection failed", e)
            showToast("Failed to connect: ${e.message}")
            showBtWarning()
        } catch (e: SecurityException) {
            Log.e("BT_Zerg2", "Security exception: ${e.message}")
            showToast("Bluetooth permission error")
            requestBluetoothPermissions()
        }
    }

    private fun showToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            outputStream?.close()
            btSocket?.close()
        } catch (e: IOException) {
            Log.e("BT_Zerg", "Ошибка при закрытии соединения", e)
        }

        hideBtWarning()
        btWarningTimer?.cancel()
        btMonitorTimer?.cancel()
        btWarningTimer = null
        btMonitorTimer = null

        activityScope.cancel()
    }

    // Добавьте метод здесь
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            setFullscreenMode()
        }
    }

    private fun setFullscreenMode() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.let {
                    it.hide(WindowInsets.Type.systemBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_FULLSCREEN)
            }
        } catch (e: Exception) {
            Log.e("BT_Zerg2", "Ошибка установки полноэкранного режима: ${e.message}")
        }
    }

    private fun setupSystemUIListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val rootView = window.decorView
            rootView.setOnApplyWindowInsetsListener { view, insets ->
                if (insets.isVisible(WindowInsets.Type.systemBars())) {
                    rootView.postDelayed({ setFullscreenMode() }, 3000)
                }
                view.onApplyWindowInsets(insets)
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
                if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                    window.decorView.postDelayed({ setFullscreenMode() }, 3000)
                }
            }
        }

        window.decorView.setOnTouchListener { _, _ ->
            setFullscreenMode()
            false
        }
    }

    // === Класс фильтра для сглаживания движения джойстика ===
    private class LowPassFilter(private val alpha: Float) {
        private var lastValue = JOYSTICK_CENTER.toFloat()

        fun filter(newValue: Int): Int {
            lastValue = alpha * newValue + (1 - alpha) * lastValue
            return lastValue.roundToInt().coerceIn(0, 255)
        }

        fun reset(value: Int) {
            lastValue = value.toFloat()
        }
    }
}