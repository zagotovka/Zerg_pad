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
import kotlin.math.cos
import kotlin.math.sin

class ControlActivity : ComponentActivity() {
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

    companion object {
        const val ENABLE_LOG = false

        const val PREFIX_JOYSTICK = 0xF1.toByte() // NEW
        const val PREFIX_BUTTON = 0xF0.toByte()   // NEW

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
        const val PERMISSION_REQUEST_CODE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("BT_Zerg", "ControlActivity started")

        setFullscreenMode()
        setupSystemUIListener()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setContentView(R.layout.activity_control)

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
            showToast("Адрес устройства не получен")
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
                window.insetsController?.let { controller ->
                    controller.hide(WindowInsets.Type.systemBars())
                    controller.systemBarsBehavior =
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
            Log.e("BT_Zerg", "Error setting fullscreen mode: ${e.message}")
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

    private fun setupControls() {
        joystick.setOnJoystickMoveListener({ angle, power, direction ->
            angleTextView.text = getString(R.string.angle_format, angle)
            powerTextView.text = getString(R.string.power_format, power)

            directionTextView.text = when (direction) {
                ZergJoystickView.FRONT -> getString(R.string.front_lab)
                ZergJoystickView.FRONT_RIGHT -> getString(R.string.front_right_lab)
                //ZergJoystickView.RIGHT -> getString(R.string.right_lab)
                ZergJoystickView.RIGHT -> getString(R.string.left_lab)  // Инверсия Right/Left
                ZergJoystickView.RIGHT_BOTTOM -> getString(R.string.right_bottom_lab)
                ZergJoystickView.BOTTOM -> getString(R.string.bottom_lab)
                ZergJoystickView.BOTTOM_LEFT -> getString(R.string.bottom_left_lab)
                //ZergJoystickView.LEFT -> getString(R.string.left_lab)
                ZergJoystickView.LEFT -> getString(R.string.right_lab)  // Инверсия Left/Right
                ZergJoystickView.LEFT_FRONT -> getString(R.string.left_front_lab)
                else -> getString(R.string.center_lab)
            }

            sendJoystickCommand(angle, power)
            logToConsole("Joystick: angle=$angle°, power=$power%, direction=${directionTextView.text}")
        }, ZergJoystickView.DEFAULT_LOOP_INTERVAL)

        // Кнопки — через onTouchListener
        setupTouchButton(R.id.btn_a, BUTTON_A, "Button A")
        setupTouchButton(R.id.btn_b, BUTTON_B, "Button B")
        setupTouchButton(R.id.btn_x, BUTTON_X, "Button X")
        setupTouchButton(R.id.btn_y, BUTTON_Y, "Button Y")
        setupTouchButton(R.id.btn_select, BUTTON_SELECT, "SELECT")
        setupTouchButton(R.id.btn_start, BUTTON_START, "START")
        setupTouchButton(R.id.btn_left, BUTTON_L, "Left")
        setupTouchButton(R.id.btn_right, BUTTON_R, "Right")
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

        try {
            sendByteCommands(packet)

            val pressState = if (pressed) "pressed" else "released"
            Log.d("BT_Zerg2", "Button command sent: Button=$buttonCode, State=$pressState, " +
                    "Raw bytes: 0x${PREFIX_BUTTON.toInt().and(0xFF).toString(16).uppercase()}, " +
                    "0x${buttonCode.toInt().and(0xFF).toString(16).uppercase()}, " +
                    "0x${state.toInt().and(0xFF).toString(16).uppercase()}")
        } catch (e: Exception) {
            Log.e("BT_Zerg2", "Error sending button command", e)
        }
    }

    private fun joystickToXY(angle: Int, power: Int): Pair<Int, Int> {
        val radians = Math.toRadians(angle.toDouble())
        val normalizedPower = (power * 127) / 100
        val x = (cos(radians) * normalizedPower + JOYSTICK_CENTER).toInt().coerceIn(0, 255)
        val y = (-sin(radians) * normalizedPower + JOYSTICK_CENTER).toInt().coerceIn(0, 255)
        return Pair(x, y)
    }

    private fun sendJoystickCommand(angle: Int, power: Int) {
        if (power < 5) { // Не отправляем при малой мощности
            return
        }
        val (x, y) = joystickToXY(angle, power)
        val commands = byteArrayOf(PREFIX_JOYSTICK, x.toByte(), y.toByte())
        sendByteCommands(commands)
        Log.d("BT_Zerg2", "Joystick XY: x=$x, y=$y")
    }

    private fun sendByteCommands(commands: ByteArray) {
        if (outputStream == null) {
            logToConsole("Bluetooth not connected, can't send commands")
            return
        }

        try {
            // Явная проверка разрешений для Android 12+
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

            // Проверка данных
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

            // Отправка с синхронизацией
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
            // Проверка разрешений перед повторным подключением
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
                Log.d("BT_Zerg", "Запрашиваем Bluetooth разрешения для Android 12+")
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            waitingForPermissions = false

            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Log.d("BT_Zerg", "Bluetooth разрешения получены")
                connectToBluetooth()
            } else {
                showToast("Необходимы разрешения для работы Bluetooth")
                Log.e("BT_Zerg", "Bluetooth разрешения не предоставлены")
            }
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

            val btDevice = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    !checkBluetoothPermissions()
                ) {
                    showToast("Требуется разрешение BLUETOOTH_CONNECT")
                    Log.e("BT_Zerg", "BLUETOOTH_CONNECT permission required")
                    waitingForPermissions = true
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

            if (btDevice == null) {
                showToast("Не удалось получить Bluetooth-устройство")
                Log.e("BT_Zerg", "btDevice is null")
                finish()
                return
            }

            connectToDevice(btDevice)
        } catch (e: Exception) {
            Log.e("BT_Zerg", "Ошибка при подключении к Bluetooth: ${e.message}")
            showToast("Ошибка при подключении: ${e.message}")
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e("BT_Zerg2", "BLUETOOTH_CONNECT permission not granted")
                showToast("Требуется разрешение BLUETOOTH_CONNECT")
                waitingForPermissions = true
                requestBluetoothPermissions()
                return
            }

            try {
                btSocket?.close()
            } catch (e: Exception) {
                Log.e("BT_Zerg2", "Error closing existing socket", e)
            }

            logToConsole("Connecting to device: ${device.name}")
            btSocket = device.createRfcommSocketToServiceRecord(myuuid)
            btSocket?.connect()
            outputStream = btSocket?.outputStream

            if (outputStream == null) {
                throw IOException("Failed to get output stream")
            }

            showToast("Connected to device")
            logToConsole("Connected to device: ${device.name}")
            sendTestPacket()
        } catch (e: IOException) {
            Log.e("BT_Zerg2", "Error connecting to device", e)
            showToast("Failed to connect to device: ${e.message}")
            logToConsole("Connection failed: ${e.message}")

            try {
                btSocket?.close()
                btSocket = null
                outputStream = null
            } catch (closeException: Exception) {
                Log.e("BT_Zerg2", "Error closing socket after failed connection", closeException)
            }
        }
    }
    private fun sendTestPacket() {
        try {
            for (i in 1..3) {
                val testCommand = byteArrayOf(0xFE.toByte(), 0x00.toByte(), 0x00.toByte())
                sendByteCommands(testCommand)
                logToConsole("Test packet $i sent")
                Thread.sleep(100)
            }
        } catch (e: Exception) {
            Log.e("BT_Zerg2", "Error sending test packet", e)
        }
    }
    private fun verifyCommandsSent(commands: ByteArray) {
        val expectedBytes = commands.size
        var sentBytes = 0

        try {
            if (outputStream == null) {
                logToConsole("VerifyCommand: Output stream is null!")
                return
            }

            for (i in commands.indices) {
                outputStream?.write(byteArrayOf(commands[i]))
                outputStream?.flush()
                sentBytes++
                Thread.sleep(5)
            }

            logToConsole("VerifyCommand: Sent $sentBytes of $expectedBytes bytes")

            if (sentBytes != expectedBytes) {
                logToConsole("WARNING: Not all bytes were sent! Expected: $expectedBytes, Sent: $sentBytes")
            }
        } catch (e: Exception) {
            Log.e("BT_Zerg2", "Error in verifyCommandsSent", e)
            logToConsole("VerifyCommand error: ${e.message}")
        }
    }

    private fun testSendModes() {
        val buttonCode = BUTTON_A

        try {
            val packet1 = byteArrayOf(PREFIX_BUTTON, buttonCode, STATE_PRESSED)
            outputStream?.write(packet1)
            outputStream?.flush()
            logToConsole("Test 1: Standard sending completed")
            Thread.sleep(500)
        } catch (e: Exception) {
            Log.e("BT_Zerg2", "Error in test 1", e)
        }

        try {
            outputStream?.write(byteArrayOf(PREFIX_BUTTON))
            outputStream?.flush()
            Thread.sleep(10)

            outputStream?.write(byteArrayOf(buttonCode))
            outputStream?.flush()
            Thread.sleep(10)

            outputStream?.write(byteArrayOf(STATE_RELEASED))
            outputStream?.flush()
            logToConsole("Test 2: Byte-by-byte sending completed")
        } catch (e: Exception) {
            Log.e("BT_Zerg2", "Error in test 2", e)
        }
    }

    private fun testConnectionSpeed() {
        Thread {
            try {
                logToConsole("Starting speed test...")
                val startTime = System.currentTimeMillis()
                val testData = ByteArray(100) { 0x55 }

                for (i in 0 until 10) {
                    outputStream?.write(testData)
                    outputStream?.flush()
                    Thread.sleep(10)
                }

                val endTime = System.currentTimeMillis()
                val totalTime = endTime - startTime
                val bytesPerSecond = (1000 * 1000) / totalTime

                logToConsole("Speed test completed: $bytesPerSecond bytes/sec")
            } catch (e: Exception) {
                Log.e("BT_Zerg2", "Error in speed test", e)
                logToConsole("Speed test error: ${e.message}")
            }
        }.start()
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

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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
}