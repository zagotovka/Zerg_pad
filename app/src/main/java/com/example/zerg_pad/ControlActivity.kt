package com.example.zerg_pad

import android.Manifest
import android.bluetooth.BluetoothDevice
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
import kotlin.collections.ArrayList

class ControlActivity : ComponentActivity() {
    private var btSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private val myuuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var logDisplay: TextView? = null
    private var angleTextView: TextView? = null
    private var powerTextView: TextView? = null
    private var directionTextView: TextView? = null
    private lateinit var joystick: ZergJoystickView

    private val xFilter = LowPassFilter(0.25f)
    private val yFilter = LowPassFilter(0.25f)
    private var lastSentX = 0
    private var lastSentY = 0
    private var lastSentTime = 0L
    private var calibratedCenterX = JOYSTICK_CENTER
    private var calibratedCenterY = JOYSTICK_CENTER

    companion object {
        const val ENABLE_LOG = true
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
        const val DEADZONE_PERCENT = 15
        const val MIN_UPDATE_INTERVAL = 50L
        const val PERMISSION_REQUEST_CODE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("BT_Zerg", "ControlActivity started")

        setFullscreenMode()
        setupSystemUIListener()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setContentView(R.layout.activity_control)

        initViews()
        setupControls()

        if (checkAndRequestPermissions()) {
            connectToBluetooth()
        }
    }

    private fun setFullscreenMode() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.let { controller ->
                    controller.hide(WindowInsets.Type.systemBars())
                    controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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
            Log.e("BT_Zerg", "Ошибка установки полноэкранного режима: ${e.message}")
        }
    }

    private fun setupSystemUIListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val rootView = window.decorView
            rootView.setOnApplyWindowInsetsListener { view, windowInsets ->
                if (windowInsets.isVisible(WindowInsets.Type.systemBars())) {
                    rootView.postDelayed({ setFullscreenMode() }, 3000)
                }
                view.onApplyWindowInsets(windowInsets)
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
        if (hasFocus) {
            setFullscreenMode()
        }
    }

    private fun initViews() {
        if (!ENABLE_LOG) {
            findViewById<View>(R.id.log_scroll_view).visibility = View.GONE
        } else {
            logDisplay = findViewById(R.id.log_display)
        }

        angleTextView = findViewById(R.id.angleTextView)
        powerTextView = findViewById(R.id.powerTextView)
        directionTextView = findViewById(R.id.directionTextView)
        joystick = findViewById(R.id.joystickView)
    }

    private fun setupControls() {
        // Заменить старый вызов на новый интерфейс ZergJoystickView
        joystick.setOnJoystickMoveListener(object : ZergJoystickView.OnJoystickMoveListener {
            override fun onValueChanged(angle: Int, power: Int, direction: Int) {
                val fixedAngle = if (power < DEADZONE_PERCENT) {
                    0 // В центре показываем 0°
                } else {
                    ((angle - 90 + 360) % 360) // Остальная логика сохраняется
                }

                updateDisplay(fixedAngle, power, direction)
                processJoystickMovement(fixedAngle, power)
            }
        })

        setupButton(R.id.btn_a, BUTTON_A, "Button A")
        setupButton(R.id.btn_b, BUTTON_B, "Button B")
        setupButton(R.id.btn_x, BUTTON_X, "Button X")
        setupButton(R.id.btn_y, BUTTON_Y, "Button Y")
        setupButton(R.id.btn_select, BUTTON_SELECT, "SELECT")
        setupButton(R.id.btn_start, BUTTON_START, "START")
        setupButton(R.id.btn_left, BUTTON_L, "Left")
        setupButton(R.id.btn_right, BUTTON_R, "Right")
    }

    private fun updateDisplay(angle: Int, power: Int, direction: Int) {
        angleTextView?.text = getString(R.string.angle_format, angle)
        powerTextView?.text = getString(R.string.power_format, power)

        directionTextView?.text = when (direction) {
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
                    Log.e("BT_Zerg", "Send failed after $maxRetries attempts", e)
                    attemptReconnect()
                } else {
                    Thread.sleep(15L * attempts)
                }
            }
        }
    }

    @Suppress("ClickableViewAccessibility")
    private fun setupButton(buttonId: Int, buttonCode: Byte, buttonName: String) {
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
                    buttonStates[buttonId] = false
                    sendButtonCommand(buttonCode, false)
                    logToConsole("$buttonName released")
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
    }

    private fun checkAndRequestPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val hasPermissions =
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                        ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

            if (!hasPermissions) {
                requestBluetoothPermissions()
            }

            hasPermissions
        } else {
            true
        }
    }

    private fun connectToBluetooth() {
        try {
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            if (btManager == null) {
                showToast("Bluetooth Manager недоступен")
                Log.e("BT_Zerg", "BluetoothManager is null")
                finish()
                return
            }

            val btAdapter = btManager.adapter
            if (btAdapter == null) {
                showToast("Адаптер Bluetooth не найден")
                Log.e("BT_Zerg", "BluetoothAdapter is null")
                finish()
                return
            }

            val deviceAddress = intent.getStringExtra("device_address") ?: run {
                showToast("Адрес устройства не получен")
                finish()
                return
            }

            val btDevice = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !checkBluetoothPermissions()) {
                    showToast("Требуется разрешение BLUETOOTH_CONNECT")
                    Log.e("BT_Zerg", "BLUETOOTH_CONNECT permission required")
                    requestBluetoothPermissions()
                    return
                }
                btAdapter.getRemoteDevice(deviceAddress)
            } catch (e: IllegalArgumentException) {
                Log.e("BT_Zerg", "Ошибка getRemoteDevice: ${e.message}")
                showToast("Адрес Bluetooth некорректный")
                finish()
                return
            }

            connectToDevice(btDevice)
        } catch (e: Exception) {
            Log.e("BT_Zerg", "Ошибка при подключении к Bluetooth: ${e.message}")
            showToast("Ошибка подключения: ${e.message}")
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e("BT_Zerg", "BLUETOOTH_CONNECT permission not granted")
                showToast("Нет разрешения на Bluetooth")
                requestBluetoothPermissions()
                return
            }

            btSocket?.close()

            logToConsole("Подключение к устройству: ${device.name}")
            btSocket = device.createRfcommSocketToServiceRecord(myuuid)
            btSocket?.connect()
            outputStream = btSocket?.outputStream

            if (outputStream == null) throw IOException("Не удалось получить OutputStream")

            showToast("Соединение установлено: ${device.name}")
            logToConsole("Соединение установлено: ${device.name}")
        } catch (e: IOException) {
            Log.e("BT_Zerg", "Ошибка подключения: ${e.message}")
            showToast("Ошибка подключения: ${e.message}")
            logToConsole("Ошибка подключения: ${e.message}")

            try {
                btSocket?.close()
                btSocket = null
                outputStream = null
            } catch (closeException: Exception) {
                Log.e("BT_Zerg", "Ошибка закрытия сокета", closeException)
            }
        }
    }

    private fun attemptReconnect() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                requestBluetoothPermissions()
                return
            }

            btSocket?.close()
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val deviceAddress = intent.getStringExtra("device_address") ?: return
            val device = btManager.adapter.getRemoteDevice(deviceAddress)
            btSocket = device.createRfcommSocketToServiceRecord(myuuid)
            btSocket?.connect()
            outputStream = btSocket?.outputStream
            logToConsole("Повторное подключение успешно")
        } catch (e: Exception) {
            Log.e("BT_Zerg", "Ошибка повторного подключения: ${e.message}")
            showToast("Ошибка переподключения: ${e.message}")
        }
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

            val missingPermissions = permissions.filter {
                ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }.toTypedArray()

            if (missingPermissions.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, missingPermissions, PERMISSION_REQUEST_CODE)
                Log.d("BT_Zerg", "Запрос Bluetooth разрешений")
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Log.d("BT_Zerg", "Разрешения Bluetooth получены")
                connectToBluetooth()
            } else {
                showToast("Разрешения не получены. Bluetooth не работает.")
                Log.e("BT_Zerg", "Разрешения Bluetooth отклонены")
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun logToConsole(message: String) {
        Log.d("BT_Zerg", message)

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
            logToConsole("Закрытие соединения")
            outputStream?.close()
            btSocket?.close()
        } catch (e: IOException) {
            Log.e("BT_Zerg", "Ошибка при закрытии соединения", e)
            logToConsole("Ошибка при закрытии: ${e.message}")
        }
    }

    private class LowPassFilter(private val alpha: Float) {
        private var lastValue = JOYSTICK_CENTER.toFloat()

        fun filter(newValue: Int): Int {
            lastValue = alpha * newValue + (1 - alpha) * lastValue
            return lastValue.roundToInt()
        }
    }
}