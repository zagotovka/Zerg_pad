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
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import java.io.IOException
import java.io.OutputStream
import java.util.*
import kotlin.math.*

class ControlActivity2 : ComponentActivity() {

    private var btSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private val myuuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var logDisplay: TextView? = null
    private lateinit var angleTextView: TextView
    private lateinit var powerTextView: TextView
    private lateinit var directionTextView: TextView
    private lateinit var joystick: ZergJoystickView

    private var deviceAddress: String? = null
    private var waitingForPermissions = false

    private var calibratedCenterX = JOYSTICK_CENTER
    private var calibratedCenterY = JOYSTICK_CENTER

    private val xFilter = LowPassFilter(0.25f)
    private val yFilter = LowPassFilter(0.25f)
    private var lastSentX = JOYSTICK_CENTER
    private var lastSentY = JOYSTICK_CENTER
    private var lastSentTime = 0L
    private var isInCenter = false
    private var calibrated = false

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
        const val ENABLE_LOG = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFullscreenMode()
        setupSystemUIListener()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setContentView(R.layout.activity_control2)

        if (!ENABLE_LOG) {
            findViewById<View>(R.id.log_scroll_view).visibility = View.GONE
        } else {
            logDisplay = findViewById(R.id.log_display)
        }

        angleTextView = findViewById(R.id.angleTextView)
        powerTextView = findViewById(R.id.powerTextView)
        directionTextView = findViewById(R.id.directionTextView)
        joystick = findViewById(R.id.joystickView)

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
    }

    private fun setFullscreenMode() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.let {
                    it.hide(WindowInsets.Type.systemBars())
                    it.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_FULLSCREEN
                        )
            }
        } catch (e: Exception) {
            Log.e("BT_Zerg2", "Error setting fullscreen mode: ${e.message}")
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

    override fun onResume() {
        super.onResume()
        setFullscreenMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setFullscreenMode()
    }

    private fun setupControls() {
        joystick.setOnJoystickMoveListener(object : ZergJoystickView.OnJoystickMoveListener {
            override fun onValueChanged(angle: Int, power: Int, direction: Int) {
                // ✅ Центр = 0°, иначе поворот на -90°
                val fixedAngle = if (power < DEADZONE_PERCENT) {
                    0
                } else {
                    (angle - 90 + 360) % 360
                }
                angleTextView.text = getString(R.string.angle_format, fixedAngle)
                powerTextView.text = getString(R.string.power_format, power)

                // Направление остаётся как есть, потому что оно уже фиксировано в ZergJoystickView
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

                // Автокалибровка
                if (!calibrated && power < 5) {
                    calibratedCenterX = JOYSTICK_CENTER
                    calibratedCenterY = JOYSTICK_CENTER
                    xFilter.reset(calibratedCenterX)
                    yFilter.reset(calibratedCenterY)
                    calibrated = true
                    logToConsole("Центр откалиброван")
                }

                processJoystickMovement(fixedAngle, power)
            }
        })

        setupTouchButton(R.id.btn_a, BUTTON_A, "Button A")
        setupTouchButton(R.id.btn_b, BUTTON_B, "Button B")
        setupTouchButton(R.id.btn_x, BUTTON_X, "Button X")
        setupTouchButton(R.id.btn_y, BUTTON_Y, "Button Y")
        setupTouchButton(R.id.btn_select, BUTTON_SELECT, "SELECT")
        setupTouchButton(R.id.btn_start, BUTTON_START, "START")
        setupTouchButton(R.id.btn_left, BUTTON_L, "Left")
        setupTouchButton(R.id.btn_right, BUTTON_R, "Right")
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

    private fun calculateRawXY(angle: Int, power: Int): Pair<Int, Int> {
        val radians = Math.toRadians(angle.toDouble())
        val normalizedPower = (power * (JOYSTICK_CENTER - 1)) / 100

        val x = (cos(radians) * normalizedPower).toInt() + calibratedCenterX
        val y = (-sin(radians) * normalizedPower).toInt() + calibratedCenterY

        return Pair(x.coerceIn(0, 255), y.coerceIn(0, 255))
    }

    private fun shouldSendNewValues(x: Int, y: Int): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastSentTime < MIN_UPDATE_INTERVAL) return false

        val xDiff = abs(x - lastSentX)
        val yDiff = abs(y - lastSentY)

        return xDiff > 5 || yDiff > 5
    }

    private fun sendCenterPosition() {
        if (lastSentX != calibratedCenterX || lastSentY != calibratedCenterY) {
            sendXYCoordinates(calibratedCenterX, calibratedCenterY, 0)
            lastSentX = calibratedCenterX
            lastSentY = calibratedCenterY
            lastSentTime = System.currentTimeMillis()
            isInCenter = true

            xFilter.reset(calibratedCenterX)
            yFilter.reset(calibratedCenterY)

            logToConsole("JOY X:  +0 Y:  +0 [CENTERED]")
        }
    }

    private fun sendPacketWithRetry(packet: ByteArray, maxRetries: Int = 2) {
        var attempts = 0
        while (attempts <= maxRetries) {
            try {
                synchronized(this) {
                    outputStream?.let {
                        it.write(packet)
                        it.flush()
                    }
                }
                return
            } catch (e: Exception) {
                attempts++
                if (attempts > maxRetries) {
                    Log.e("BT_Zerg2", "Send failed after $maxRetries attempts", e)
                    attemptReconnect()
                } else {
                    Thread.sleep(15L * attempts)
                }
            }
        }
    }
    private var lastSentPower = 0
    private fun sendXYCoordinates(x: Int, y: Int, power: Int) {
        // 🛡️ Защита: не отправлять, если ничего не изменилось
        if (x == lastSentX && y == lastSentY && power == lastSentPower) return

        val pwr = power.coerceIn(0, 255)
        val packet = byteArrayOf(PREFIX_JOYSTICK, x.toByte(), y.toByte(), pwr.toByte())
        sendPacketWithRetry(packet)

        val dx = x - calibratedCenterX
        val dy = y - calibratedCenterY
        logToConsole("JOY X: ${"%+4d".format(dx)} Y: ${"%+4d".format(dy)} Power: $power")

        lastSentX = x
        lastSentY = y
        lastSentPower = power
        lastSentTime = System.currentTimeMillis()
    }

    @Suppress("ClickableViewAccessibility")
    private fun setupTouchButton(buttonId: Int, buttonCode: Byte, buttonName: String) {
        val view = findViewById<View>(buttonId)
        view.isClickable = true
        view.isFocusable = true

        val buttonStates = mutableMapOf<Int, Boolean>()

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (buttonStates[buttonId] != true) {
                        buttonStates[buttonId] = true
                        sendButtonCommand(buttonCode, true)
                        logToConsole("$buttonName pressed")
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (buttonStates[buttonId] != false) {
                        buttonStates[buttonId] = false
                        sendButtonCommand(buttonCode, false)
                        logToConsole("$buttonName released")
                    }
                    v.performClick()
                }
            }
            false
        }
    }

    private var lastButtonStates = mutableMapOf<Byte, Byte>()

    private fun sendButtonCommand(buttonCode: Byte, pressed: Boolean) {
        val state = if (pressed) STATE_PRESSED else STATE_RELEASED

        // Предотвращаем повторную отправку того же состояния
        if (lastButtonStates[buttonCode] == state) return
        lastButtonStates[buttonCode] = state

        val packet = byteArrayOf(PREFIX_BUTTON, buttonCode, state)
        sendPacketWithRetry(packet)

        val pressState = if (pressed) "PRESSED" else "RELEASED"
        logToConsole("BTN $buttonCode: $pressState [STABLE EVENT] (t=${System.currentTimeMillis()})")
    }

    private fun sendByteCommands(commands: ByteArray) {
        if (outputStream == null) {
            logToConsole("Bluetooth not connected, can't send commands")
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    logToConsole("Bluetooth permission not granted")
                    requestBluetoothPermissions()
                    return
                }
            }

            if (commands.size >= 2) {
                when (commands[0]) {
                    PREFIX_JOYSTICK -> {
                        if (commands[1] == PREFIX_JOYSTICK || commands[1] == PREFIX_BUTTON ||
                            commands[2] == PREFIX_JOYSTICK || commands[2] == PREFIX_BUTTON) {
                            Log.e("BT_Zerg2", "Invalid joystick data contains prefix byte")
                            return
                        }
                    }
                    PREFIX_BUTTON -> {
                        if (commands[1] == PREFIX_JOYSTICK || commands[1] == PREFIX_BUTTON) {
                            Log.e("BT_Zerg2", "Invalid button code contains prefix byte")
                            return
                        }
                    }
                }
            }

            synchronized(outputStream!!) {
                try {
                    outputStream?.apply {
                        write(commands)
                        flush()
                    }
                    logHexData(commands)
                } catch (e: SecurityException) {
                    Log.e("BT_Zerg2", "Security exception: ${e.message}")
                    handleBluetoothPermissionError()
                    return
                }
            }

        } catch (e: IOException) {
            Log.e("BT_Zerg2", "Send error: ${e.message}")
            attemptReconnect()
        } catch (e: InterruptedException) {
            Log.w("BT_Zerg2", "Send interrupted", e)
        }
    }

    private fun attemptReconnect() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestBluetoothPermissions()
                    return
                }
            }

            btSocket?.close()
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val device = btManager.adapter.getRemoteDevice(deviceAddress)
            btSocket = device.createRfcommSocketToServiceRecord(myuuid)
            btSocket?.connect()
            outputStream = btSocket?.outputStream
            logToConsole("Reconnected successfully")
        } catch (e: Exception) {
            Log.e("BT_Zerg2", "Reconnect failed: ${e.message}")
        }
    }

    private fun handleBluetoothPermissionError() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestBluetoothPermissions()
        }
        showToast("Bluetooth permission required")
    }

    private fun logHexData(commands: ByteArray) {
        val hex = commands.joinToString(" ") {
            "0x${it.toInt().and(0xFF).toString(16).padStart(2, '0').uppercase()}"
        }
        Log.d("BT_Zerg2", "Sent [${commands.size} bytes]: $hex")
    }

    private fun checkBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(
                        this, Manifest.permission.BLUETOOTH_SCAN
                    ) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(
                        this, Manifest.permission.BLUETOOTH_ADMIN
                    ) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(
                        this, Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE)
        } else {
            val permissions = arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            waitingForPermissions = false
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                connectToBluetooth()
            } else {
                showToast("Bluetooth permissions are required")
                Log.e("BT_Zerg2", "Bluetooth permissions not granted")
            }
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
            logToConsole("Connected to device: ${device.name}")
        } catch (e: IOException) {
            Log.e("BT_Zerg2", "Connection failed", e)
            showToast("Failed to connect: ${e.message}")
            logToConsole("Connection failed: ${e.message}")
        } catch (e: SecurityException) {
            Log.e("BT_Zerg2", "Security exception: ${e.message}")
            showToast("Bluetooth permission error")
            requestBluetoothPermissions()
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun logToConsole(message: String) {
        Log.d("BT_Zerg2", message)
        if (ENABLE_LOG) {
            logDisplay?.let {
                if (it.text.isNotEmpty()) it.append("\n")
                it.append(message)
                findViewById<View>(R.id.log_scroll_view)?.post {
                    findViewById<View>(R.id.log_scroll_view)?.scrollTo(0, it.bottom)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            logToConsole("Closing connection")
            outputStream?.close()
            btSocket?.close()
        } catch (e: IOException) {
            Log.e("BT_Zerg2", "Error closing socket", e)
            logToConsole("Error closing connection: ${e.message}")
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