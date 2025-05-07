package com.example.zerg_pad

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice // Добавлен импорт
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View // Импорт для View.VISIBLE / View.GONE
import android.widget.TextView // Импорт для TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.zerg_pad.R

class MainActivity : ComponentActivity() {
    private var btAdapter: BluetoothAdapter? = null
    private val tag = "BT_Zerg"
    private lateinit var bluetoothStatusTextView: TextView // Объявляем переменную для TextView

    // Регистрируем launcher для запроса разрешений (остается без изменений)
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Проверяем, все ли необходимые разрешения предоставлены
        val granted = permissions.entries.all { it.value } // Простая проверка на все значения true
        if (granted) {
            Log.d(tag, "All required permissions granted.")
            initializeBluetooth()
        } else {
            Log.w(tag, "Not all permissions granted.")
            showToast("Bluetooth permissions are required for this app to work")
            // Показываем ошибку в TextView
            if (::bluetoothStatusTextView.isInitialized) { // Проверка на инициализацию
                bluetoothStatusTextView.text = "Необходимы разрешения Bluetooth для работы приложения."
                bluetoothStatusTextView.visibility = View.VISIBLE
            }
        }
    }

    // BroadcastReceiver для отслеживания изменений состояния Bluetooth
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        Log.d(tag, "BroadcastReceiver: Bluetooth STATE_ON")
                        // Bluetooth включен, скрываем сообщение и пытаемся инициализировать
                        if (::bluetoothStatusTextView.isInitialized) {
                            bluetoothStatusTextView.visibility = View.GONE
                        }
                        // Проверяем разрешения еще раз перед инициализацией
                        checkAndRequestPermissions()
                    }
                    BluetoothAdapter.STATE_OFF -> {
                        Log.d(tag, "BroadcastReceiver: Bluetooth STATE_OFF")
                        // Bluetooth выключен, показываем сообщение
                        if (::bluetoothStatusTextView.isInitialized) {
                            bluetoothStatusTextView.text = getString(R.string.enable_bluetooth_prompt) // Устанавливаем текст из ресурсов
                            bluetoothStatusTextView.visibility = View.VISIBLE
                        }
                        // Очищаем список устройств или останавливаем процессы, если нужно
                    }
                    BluetoothAdapter.STATE_TURNING_ON -> Log.d(tag, "BroadcastReceiver: Bluetooth STATE_TURNING_ON")
                    BluetoothAdapter.STATE_TURNING_OFF -> Log.d(tag, "BroadcastReceiver: Bluetooth STATE_TURNING_OFF")
                    BluetoothAdapter.ERROR -> Log.e(tag, "BroadcastReceiver: Bluetooth STATE_ERROR")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 1. Устанавливаем Layout В САМОМ НАЧАЛЕ
        setContentView(R.layout.activity_main) // Убедитесь, что R.layout.activity_main существует

        // 2. Находим TextView ПОСЛЕ setContentView
        bluetoothStatusTextView = findViewById(R.id.bluetooth_status_message) // Убедитесь, что ID правильный

        // 3. Проверяем разрешения и инициализируем Bluetooth
        checkAndRequestPermissions()

        // Регистрация BroadcastReceiver теперь в onResume
        Log.d(tag, "onCreate completed.")
    }

    override fun onResume() {
        super.onResume()
        // Регистрируем ресивер для отслеживания состояния Bluetooth
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        registerReceiver(bluetoothStateReceiver, filter)
        Log.d(tag, "Bluetooth state receiver registered.")
        // Проверяем текущее состояние при возобновлении активности
        // Это важно, если пользователь включил/выключил BT, пока приложение было в фоне
        if (btAdapter != null) {
            if (!btAdapter!!.isEnabled) {
                if (::bluetoothStatusTextView.isInitialized) {
                    bluetoothStatusTextView.text = getString(R.string.enable_bluetooth_prompt)
                    bluetoothStatusTextView.visibility = View.VISIBLE
                }
            } else {
                // Если BT включен, но список устройств еще не получен (например, после включения в фоне)
                // Можно инициировать проверку разрешений и получение списка заново
                checkAndRequestPermissions() // Перепроверит разрешения и запустит инициализацию, если нужно
            }
        } else {
            // Если адаптер еще не инициализирован (например, при первом запуске)
            checkAndRequestPermissions()
        }
    }

    override fun onPause() {
        super.onPause()
        // Отменяем регистрацию ресивера, чтобы избежать утечек
        try {
            unregisterReceiver(bluetoothStateReceiver)
            Log.d(tag, "Bluetooth state receiver unregistered.")
        } catch (e: IllegalArgumentException) {
            Log.w(tag, "Receiver not registered or already unregistered.")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Дополнительная очистка, если ресивер не отменился в onPause
        try {
            unregisterReceiver(bluetoothStateReceiver)
            Log.d(tag, "Bluetooth state receiver unregistered in onDestroy.")
        } catch (e: IllegalArgumentException) {
            // Игнорируем, если он уже отменен
        }
    }


    private fun checkAndRequestPermissions() {
        Log.d(tag, "Checking permissions...")
        val requiredPermissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            // ACCESS_FINE_LOCATION все еще может быть нужен для получения информации о сканировании,
            // но не всегда для подключения к сопряженным устройствам. Запросим на всякий случай.
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            // Android 6 - 11
            requiredPermissions.add(Manifest.permission.BLUETOOTH)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION) // Для сканирования
        }
        // Для старых версий (ниже M) разрешения даются при установке

        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isEmpty()) {
            Log.d(tag, "All necessary permissions already granted.")
            // Все разрешения есть, инициализируем Bluetooth
            initializeBluetooth()
        } else {
            Log.d(tag, "Requesting permissions: ${permissionsToRequest.joinToString()}")
            // Запрашиваем недостающие разрешения
            requestPermissionLauncher.launch(permissionsToRequest)
        }
    }

    private fun initializeBluetooth() {
        Log.d(tag, "Initializing Bluetooth...")
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        if (btManager == null) {
            Log.e(tag, "BluetoothManager not available.")
            bluetoothStatusTextView.text = "Bluetooth Manager недоступен"
            bluetoothStatusTextView.visibility = View.VISIBLE
            showToast("Bluetooth Manager недоступен на этом устройстве")
            return
        }

        btAdapter = btManager.adapter
        if (btAdapter == null) {
            Log.e(tag, "BluetoothAdapter not available.")
            bluetoothStatusTextView.text = "Bluetooth не поддерживается на этом устройстве"
            bluetoothStatusTextView.visibility = View.VISIBLE
            showToast("Это устройство не поддерживает Bluetooth")
            return
        }

        Log.d(tag, "Bluetooth adapter obtained.")

        // Проверяем состояние Bluetooth
        if (!btAdapter!!.isEnabled) {
            Log.w(tag, "Bluetooth is disabled.")
            // --- УДАЛЯЕМ СТАРЫЙ TOAST ---
            // showToast("Please enable Bluetooth")
            // --- ПОКАЗЫВАЕМ НАШ TEXTVIEW ---
            bluetoothStatusTextView.text = getString(R.string.enable_bluetooth_prompt)
            bluetoothStatusTextView.visibility = View.VISIBLE
            // НЕ ДЕЛАЕМ return - ждем, пока пользователь включит Bluetooth (BroadcastReceiver сработает)
        } else {
            Log.d(tag, "Bluetooth is enabled.")
            // Bluetooth включен, скрываем сообщение (если было) и ищем устройства
            bluetoothStatusTextView.visibility = View.GONE

            // Теперь проверяем разрешения ПЕРЕД поиском устройств
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    Log.e(tag, "BLUETOOTH_CONNECT permission missing before getting paired devices.")
                    bluetoothStatusTextView.text = "Отсутствует разрешение BLUETOOTH_CONNECT"
                    bluetoothStatusTextView.visibility = View.VISIBLE
                    // Можно снова запросить разрешения или просто ждать
                    // checkAndRequestPermissions() // Запросить снова?
                    return // Не продолжаем без разрешения
                }
                // Разрешение BLUETOOTH_SCAN тоже проверено в checkAndRequestPermissions
            }
            // Для старых версий BLUETOOTH_ADMIN проверено в checkAndRequestPermissions

            getPairedDevices() // Запускаем поиск, только если BT включен и разрешения (предположительно) есть
        }
    }

    private fun getPairedDevices() {
        Log.d(tag, "Getting paired devices...")
        if (btAdapter == null || !btAdapter!!.isEnabled) {
            Log.w(tag, "Cannot get paired devices, adapter null or disabled.")
            return // Не можем получить устройства, если адаптер не готов
        }

        var pairedDevices: Set<BluetoothDevice>? = null
        try {
            // Проверка разрешения ПЕРЕД вызовом bondedDevices (критично для Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    Log.e(tag, "BLUETOOTH_CONNECT permission check failed in getPairedDevices.")
                    bluetoothStatusTextView.text = "Нет разрешения для доступа к сопряженным устройствам"
                    bluetoothStatusTextView.visibility = View.VISIBLE
                    return // Выходим, если нет разрешения
                }
            }
            // Разрешения для старых версий (BLUETOOTH) проверены ранее

            pairedDevices = btAdapter?.bondedDevices // Безопасный вызов
            Log.d(tag, "Found ${pairedDevices?.size ?: 0} bonded devices raw.")

        } catch (se: SecurityException) {
            Log.e(tag, "SecurityException getting bonded devices: ${se.message}")
            bluetoothStatusTextView.text = "Ошибка безопасности при доступе к устройствам"
            bluetoothStatusTextView.visibility = View.VISIBLE
            return // Выходим при ошибке безопасности
        }


        if (pairedDevices.isNullOrEmpty()) {
            Log.w(tag, "No paired devices found.")
            bluetoothStatusTextView.text = "Нет сопряженных устройств. Пожалуйста, выполните сопряжение в настройках Bluetooth."
            bluetoothStatusTextView.visibility = View.VISIBLE
            // showToast("No paired devices found") // Можно заменить на TextView
            return
        }

        // Преобразуем список устройств в строки
        val deviceList = pairedDevices.mapNotNull { device ->
            var deviceName: String? = null
            try {
                // Проверка разрешения перед доступом к имени (Android 12+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        Log.w(tag, "BLUETOOTH_CONNECT permission missing for device.name for address: ${device.address}")
                        // Имя не можем получить, вернем хотя бы адрес
                        deviceName = "Name Unavailable (Permission Missing)"
                    } else {
                        deviceName = device.name // Получаем имя, если разрешение есть
                    }
                } else {
                    deviceName = device.name // На старых версиях разрешение проверяется иначе
                }

                // Если имя все еще null или пустое после проверок
                if (deviceName.isNullOrBlank()) {
                    deviceName = "Unknown Device"
                }

                val address = device.address
                Log.d(tag, "Mapping device: Name='$deviceName', Address='$address'")
                "$deviceName - $address" // Формируем строку

            } catch (se: SecurityException) {
                Log.e(tag, "SecurityException getting name for device ${device.address}: ${se.message}")
                // В случае ошибки безопасности, можем вернуть адрес или null
                "Addr: ${device.address} (Name Access Error)" // Возвращаем адрес с пометкой об ошибке
                // return@mapNotNull null // Или пропускаем устройство полностью
            } catch (e: Exception) {
                Log.e(tag, "Exception processing device ${device.address}: ${e.message}")
                return@mapNotNull null // Пропускаем устройство при других ошибках
            }
        }

        if (deviceList.isEmpty()) {
            Log.w(tag, "Device list is empty after filtering/mapping.")
            bluetoothStatusTextView.text = "Не удалось получить информацию об устройствах (возможно, из-за разрешений)."
            bluetoothStatusTextView.visibility = View.VISIBLE
            // showToast("No devices to display")
            return
        } else {
            Log.d(tag, "Device list created with ${deviceList.size} items.")
            deviceList.forEach { Log.d(tag, "Device in list: $it") }
            // Список готов, скрываем сообщение и запускаем следующую активность
            bluetoothStatusTextView.visibility = View.GONE
            val intent = Intent(this, StartActivity::class.java).apply {
                putExtra("device_list", deviceList.toTypedArray())
            }
            Log.i(tag, "Starting StartActivity...")
            startActivity(intent)
            // finish() // Раскомментируйте, если MainActivity больше не нужна после перехода
        }
    }

    // Функция showToast остается для других возможных уведомлений
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        Log.i(tag, "Toast shown: $message")
    }

    // Добавьте эту строку в res/values/strings.xml:
    // <string name="enable_bluetooth_prompt">Пожалуйста, включите Bluetooth для продолжения</string>
}