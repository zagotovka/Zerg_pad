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
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import java.io.IOException
import java.io.OutputStream
import java.util.*
import android.widget.Button
import android.widget.ImageButton
import kotlin.math.cos
import kotlin.math.sin

class ControlActivity2 : ComponentActivity() {
    private var btSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private val myuuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // Лог — делаем nullable
    private var logDisplay: TextView? = null

    private lateinit var angleTextView: TextView
    private lateinit var powerTextView: TextView
    private lateinit var directionTextView: TextView
    private lateinit var joystick: ZergJoystickView

    // Сохраняем адрес устройства для переподключения после получения разрешений
    private var deviceAddress: String? = null
    // Флаг, указывающий, что мы ожидаем результат запроса разрешений
    private var waitingForPermissions = false

    companion object {
        const val PREFIX_JOYSTICK = 0xFE.toByte()
        const val PREFIX_BUTTON = 0xFF.toByte()
        const val BUTTON_A = 0x01.toByte()
        const val BUTTON_B = 0x02.toByte()
        const val BUTTON_X = 0x03.toByte()
        const val BUTTON_Y = 0x04.toByte()
        const val BUTTON_SELECT = 0x05.toByte()
        const val BUTTON_START = 0x06.toByte()
        const val BUTTON_L = 0x07.toByte()
        const val BUTTON_R = 0x08.toByte()
        const val JOYSTICK_CENTER = 127
        const val PERMISSION_REQUEST_CODE = 101
        const val ENABLE_LOG = false  // Использовать существующую константу из исходного кода
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Установка полноэкранного режима и скрытие навигационных кнопок
        setFullscreenMode()

        // Настраиваем слушатель для автоматического возврата в полноэкранный режим
        setupSystemUIListener()

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        // Используем альтернативный макет
        setContentView(R.layout.activity_control2)

        // Если лог отключен — скрываем log_scroll_view
        if (!ENABLE_LOG) {
            findViewById<View>(R.id.log_scroll_view).visibility = View.GONE
        } else {
            // Инициализируем logDisplay
            logDisplay = findViewById(R.id.log_display)
        }

        // Инициализация остальных элементов
        angleTextView = findViewById(R.id.angleTextView)
        powerTextView = findViewById(R.id.powerTextView)
        directionTextView = findViewById(R.id.directionTextView)
        joystick = findViewById(R.id.joystickView)

        // Получаем адрес устройства из интента
        deviceAddress = intent.getStringExtra("device_address")?.trim()
        if (deviceAddress.isNullOrEmpty()) {
            showToast("Device address is missing")
            Log.e("BT_Zerg2", "device_address is null or empty")
            finish()
            return
        }

        // Настройка джойстика и кнопок (делаем независимо от подключения)
        setupControls()

        // Проверяем нужные разрешения в зависимости от версии Android
        if (!checkBluetoothPermissions()) {
            waitingForPermissions = true
            requestBluetoothPermissions()
            // Подключение будет произведено после получения разрешений в onRequestPermissionsResult
            return
        }

        // Если все разрешения уже есть, подключаемся к устройству
        connectToBluetooth()
    }

    // Новый метод для установки полноэкранного режима
    private fun setFullscreenMode() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Для Android 11 (API 30) и выше
                window.setDecorFitsSystemWindows(false)
                window.insetsController?.let { controller ->
                    // Скрываем и системную панель и панель навигации
                    controller.hide(WindowInsets.Type.systemBars())
                    // Установка поведения при свайпе - показать системные панели временно
                    controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                // Для Android 10 (API 29) и ниже используем старый способ
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN)
            }
        } catch (e: Exception) {
            Log.e("BT_Zerg2", "Error setting fullscreen mode: ${e.message}")
        }
    }

    // Новый метод для отслеживания появления системных UI элементов
    private fun setupSystemUIListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Для Android 11 (API 30) и выше используем новый WindowInsetsCallback
            val rootView = window.decorView

            rootView.setOnApplyWindowInsetsListener { view, windowInsets ->
                // Если системные панели становятся видимыми, скрываем их снова с задержкой
                if (windowInsets.isVisible(WindowInsets.Type.systemBars())) {
                    // Даем пользователю немного времени для взаимодействия, затем скрываем снова
                    rootView.postDelayed({
                        setFullscreenMode()
                    }, 3000) // 3 секунды задержки
                }

                // Обрабатываем insets и возвращаем результат
                view.onApplyWindowInsets(windowInsets)
            }
        } else {
            // Для Android 10 (API 29) и ниже используем старый способ через onSystemUiVisibilityChange
            @Suppress("DEPRECATION")
            window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
                if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                    // Системные панели стали видимыми, скрываем их снова через 3 секунды
                    window.decorView.postDelayed({
                        setFullscreenMode()
                    }, 3000)
                }
            }
        }

        // Добавляем слушатель касаний к корневому view
        window.decorView.setOnTouchListener { _, _ ->
            // Всегда восстанавливаем полноэкранный режим при касании
            setFullscreenMode()
            false // Не потребляем событие касания
        }
    }

    // Восстанавливаем полноэкранный режим при возвращении к активности
    override fun onResume() {
        super.onResume()
        setFullscreenMode()
    }

    // Восстанавливаем полноэкранный режим при получении фокуса окном
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
                ZergJoystickView.RIGHT -> getString(R.string.right_lab)
                ZergJoystickView.RIGHT_BOTTOM -> getString(R.string.right_bottom_lab)
                ZergJoystickView.BOTTOM -> getString(R.string.bottom_lab)
                ZergJoystickView.BOTTOM_LEFT -> getString(R.string.bottom_left_lab)
                ZergJoystickView.LEFT -> getString(R.string.left_lab)
                ZergJoystickView.LEFT_FRONT -> getString(R.string.left_front_lab)
                else -> getString(R.string.center_lab)
            }

            sendJoystickCommand(angle, power)
            logToConsole("Joystick: angle=$angle°, power=$power%, direction=${directionTextView.text}")
        }, ZergJoystickView.DEFAULT_LOOP_INTERVAL)

        setupButton(R.id.btn_a, BUTTON_A, "Button A")
        setupButton(R.id.btn_b, BUTTON_B, "Button B")
        setupButton(R.id.btn_x, BUTTON_X, "Button X")
        setupButton(R.id.btn_y, BUTTON_Y, "Button Y")
        setupButtonWithView(R.id.btn_select, BUTTON_SELECT, "SELECT")
        setupButtonWithView(R.id.btn_start, BUTTON_START, "START")
        setupButtonWithView(R.id.btn_left, BUTTON_L, "Left")
        setupButtonWithView(R.id.btn_right, BUTTON_R, "Right")
    }

    private fun connectToBluetooth() {
        try {
            // Получаем BluetoothDevice
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            if (btManager == null) {
                showToast("Bluetooth Manager недоступен")
                Log.e("BT_Zerg2", "BluetoothManager is null")
                finish()
                return
            }

            val btAdapter = btManager.adapter
            if (btAdapter == null) {
                showToast("Адаптер Bluetooth не найден")
                Log.e("BT_Zerg2", "BluetoothAdapter is null")
                finish()
                return
            }

            val btDevice = try {
                // Повторная проверка разрешений перед получением устройства
                if (!checkBluetoothPermissions()) {
                    showToast("Требуются разрешения Bluetooth")
                    Log.e("BT_Zerg2", "Bluetooth permissions required")
                    waitingForPermissions = true
                    requestBluetoothPermissions()
                    return
                }
                btAdapter.getRemoteDevice(deviceAddress)
            } catch (e: IllegalArgumentException) {
                Log.e("BT_Zerg2", "Ошибка getRemoteDevice: ${e.message}")
                showToast("Адрес Bluetooth некорректный")
                finish()
                return
            } catch (e: SecurityException) {
                Log.e("BT_Zerg2", "Ошибка безопасности: ${e.message}")
                showToast("Требуются разрешения Bluetooth")
                waitingForPermissions = true
                requestBluetoothPermissions()
                return
            }

            // Подключение к устройству
            connectToDevice(btDevice)
        } catch (e: Exception) {
            Log.e("BT_Zerg2", "Ошибка при подключении к Bluetooth: ${e.message}")
            showToast("Ошибка при подключении: ${e.message}")
        }
    }

    // Проверка наличия всех необходимых разрешений Bluetooth
    private fun checkBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Для Android 12+ (API 31+)
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            // Для Android 10-11 (API 29-30)
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    // Метод для запроса Bluetooth разрешений в зависимости от версии Android
    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Для Android 12+
            val permissions = arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE)
            Log.d("BT_Zerg2", "Запрашиваем Bluetooth разрешения для Android 12+")
        } else {
            // Для Android 10-11
            val permissions = arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE)
            Log.d("BT_Zerg2", "Запрашиваем Bluetooth разрешения для Android 10-11")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            waitingForPermissions = false

            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Log.d("BT_Zerg2", "Bluetooth разрешения получены")
                // Теперь подключаемся к устройству
                connectToBluetooth()
            } else {
                // Без разрешений приложение не сможет работать
                showToast("Необходимы разрешения для работы Bluetooth")
                Log.e("BT_Zerg2", "Bluetooth разрешения не предоставлены")
            }
        }
    }

    private fun setupButton(buttonId: Int, buttonCode: Byte, buttonName: String) {
        findViewById<ImageButton>(buttonId).setOnClickListener {
            sendButtonCommand(buttonCode)
            logToConsole("$buttonName pressed (0x${buttonCode.toInt().and(0xFF).toString(16).uppercase()})")
        }
    }

    private fun setupButtonWithView(buttonId: Int, buttonCode: Byte, buttonName: String) {
        findViewById<Button>(buttonId).setOnClickListener {
            sendButtonCommand(buttonCode)
            logToConsole("$buttonName pressed (0x${buttonCode.toInt().and(0xFF).toString(16).uppercase()})")
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
        val (x, y) = joystickToXY(angle, power)
        val commands = byteArrayOf(PREFIX_JOYSTICK, x.toByte(), y.toByte())
        sendByteCommands(commands)
        Log.d("BT_Zerg2", "Joystick XY: x=$x, y=$y")
    }

    private fun sendButtonCommand(buttonCode: Byte) {
        val commands = byteArrayOf(PREFIX_BUTTON, buttonCode)
        sendByteCommands(commands)
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

    private fun connectToDevice(device: BluetoothDevice) {
        try {
            // Проверяем разрешения перед подключением
            if (!checkBluetoothPermissions()) {
                Log.e("BT_Zerg2", "Bluetooth permissions not granted")
                showToast("Требуются разрешения Bluetooth")
                waitingForPermissions = true
                requestBluetoothPermissions()
                return
            }

            // Получаем имя устройства с учетом разных версий Android
            val deviceName = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    device.name ?: "Неизвестное устройство"
                } else {
                    device.name ?: "Неизвестное устройство"
                }
            } catch (e: SecurityException) {
                "Неизвестное устройство"
            }

            logToConsole("Connecting to device: $deviceName")
            btSocket = device.createRfcommSocketToServiceRecord(myuuid)
            btSocket?.connect()
            outputStream = btSocket?.outputStream
            showToast("Connected to device")
            logToConsole("Connected to device: $deviceName")
        } catch (e: IOException) {
            Log.e("BT_Zerg2", "Error connecting to device", e)
            showToast("Failed to connect to device: ${e.message}")
            logToConsole("Connection failed: ${e.message}")

            if (!waitingForPermissions) {
                finish()
            }
        } catch (e: SecurityException) {
            Log.e("BT_Zerg2", "Security error connecting to device", e)
            showToast("Требуются разрешения Bluetooth")
            requestBluetoothPermissions()
        }
    }

    private fun sendByteCommands(commands: ByteArray) {
        try {
            if (outputStream == null) {
                logToConsole("Bluetooth not connected, can't send commands")
                return
            }

            outputStream?.write(commands)
            val hexValues = commands.joinToString(" ") { "0x${it.toInt().and(0xFF).toString(16).uppercase()}" }
            Log.d("BT_Zerg2", "Commands sent: $hexValues")
        } catch (e: IOException) {
            Log.e("BT_Zerg2", "Error sending commands", e)
            logToConsole("Failed to send commands: ${e.message}")
            showToast("Failed to send commands")

            try {
                logToConsole("Trying to reconnect...")
                btSocket?.close()

                if (!checkBluetoothPermissions()) {
                    waitingForPermissions = true
                    requestBluetoothPermissions()
                    return
                }

                if (deviceAddress != null) {
                    val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                    val btAdapter = btManager.adapter
                    val device = btAdapter.getRemoteDevice(deviceAddress)
                    connectToDevice(device)
                }
            } catch (e: Exception) {
                Log.e("BT_Zerg2", "Error reconnecting", e)
                logToConsole("Reconnection failed: ${e.message}")
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