package com.example.zerg_pad

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Switch
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class StartActivity : ComponentActivity() {
    private lateinit var btAdapter: BluetoothAdapter
    private lateinit var deviceList: Array<String>
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var listView: ListView
    private lateinit var layoutSwitch: Switch
    private lateinit var sharedPreferences: SharedPreferences

    private var listAdapter: ArrayAdapter<String>? = null

    // Константы для SharedPreferences
    private val prefsName = "BtControllerPrefs"
    private val layoutSwitchKey = "layout_switch_state"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start)

        // Инициализация SharedPreferences
        sharedPreferences = getSharedPreferences(prefsName, Context.MODE_PRIVATE)

        // Находим SwipeRefreshLayout, ListView и Switch
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout)
        listView = findViewById(R.id.device_list_view)
        layoutSwitch = findViewById(R.id.layout_switch)

        // Загрузка сохраненного состояния переключателя
        val switchState = sharedPreferences.getBoolean(layoutSwitchKey, false)
        layoutSwitch.isChecked = switchState

        // Обработчик изменения состояния переключателя
        layoutSwitch.setOnCheckedChangeListener { _, isChecked ->
            // Сохраняем состояние переключателя
            val editor = sharedPreferences.edit()
            editor.putBoolean(layoutSwitchKey, isChecked)
            editor.apply()
            Log.d("BT_Zerg", "Layout switch state changed to: $isChecked")
        }

        // Получаем список устройств из MainActivity
        deviceList = intent.getStringArrayExtra("device_list") ?: emptyArray()
        if (deviceList.isEmpty()) {
            showToast("Список устройств пуст. Попробуйте обновить.")
            Log.d("BT_Zerg", "Initial device list is empty")
        }

        // Инициализируем Bluetooth адаптер
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        btAdapter = btManager?.adapter ?: run {
            showToast("Не удалось получить Bluetooth адаптер")
            Log.e("BT_Zerg", "BluetoothManager or Adapter is null")
            finish() // Выходим, если адаптера нет
            return
        }

        // Создаем и устанавливаем адаптер один раз
        listAdapter = ArrayAdapter(
            this@StartActivity,
            android.R.layout.simple_list_item_1,
            deviceList.toMutableList() // Создаем изменяемую копию для адаптера
        )
        listView.adapter = listAdapter

        // Обработчик нажатия на элемент списка
        listView.onItemClickListener =
            AdapterView.OnItemClickListener { _, _, position, _ ->
                val adapter = listAdapter
                if (adapter != null && position >= 0 && position < adapter.count) {
                    val selectedDevice = adapter.getItem(position)
                    if (selectedDevice != null) {
                        connectToDevice(selectedDevice)
                    } else {
                        Log.e("BT_Zerg", "Clicked item is unexpectedly null at position $position")
                    }
                } else {
                    Log.e("BT_Zerg", "Adapter is null or invalid position $position. Adapter count: ${listAdapter?.count}")
                }
            }

        // Настройка SwipeRefreshLayout
        swipeRefreshLayout.setOnRefreshListener {
            Log.d("BT_Zerg", "Swipe refresh triggered")
            updateDeviceList()
        }

        // Устанавливаем цвета индикатора обновления
        swipeRefreshLayout.setColorSchemeResources(
            android.R.color.holo_blue_bright,
            android.R.color.holo_green_light,
            android.R.color.holo_orange_light,
            android.R.color.holo_red_light
        )
    }

    private fun connectToDevice(deviceInfo: String) {
        val parts = deviceInfo.split(" - ", limit = 2)
        if (parts.size < 2 || parts[1].isBlank()) {
            showToast("Неверный формат информации об устройстве")
            Log.e("BT_Zerg", "Invalid device info format: $deviceInfo")
            return
        }

        val deviceAddress = parts[1].trim()
        Log.d("BT_Zerg", "Connecting to address: $deviceAddress")

        val useAlternativeLayout = layoutSwitch.isChecked
        val targetActivity = if (useAlternativeLayout) {
            ControlActivity2::class.java
        } else {
            ControlActivity::class.java
        }

        val intent = Intent(this, targetActivity).apply {
            putExtra("device_address", deviceAddress)
        }

        Log.d("BT_Zerg", "Launching ${targetActivity.simpleName} with address: $deviceAddress")
        startActivity(intent)
    }

    private fun updateDeviceList() {
        Log.d("BT_Zerg", "Updating device list...")

        val pairedDevices: Set<BluetoothDevice>?

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    Log.e("BT_Zerg", "BLUETOOTH_CONNECT permission missing for bondedDevices")
                    showToast("Нет разрешения BLUETOOTH_CONNECT")
                    swipeRefreshLayout.isRefreshing = false
                    return
                }
            }
            pairedDevices = btAdapter.bondedDevices
            Log.d("BT_Zerg", "btAdapter.bondedDevices called. Result is null: ${pairedDevices == null}")

        } catch (se: SecurityException) {
            Log.e("BT_Zerg", "SecurityException getting bonded devices: ${se.message}")
            showToast("Ошибка безопасности при доступе к устройствам")
            swipeRefreshLayout.isRefreshing = false
            return
        }

        val newDeviceList: List<String>

        if (pairedDevices.isNullOrEmpty()) {
            Log.d("BT_Zerg", "No paired devices found during update (list is null or empty)")
            showToast("Сопряженные устройства не найдены")
            newDeviceList = emptyList()
        } else {
            Log.d("BT_Zerg", "Mapping ${pairedDevices.size} bonded devices...")
            newDeviceList = pairedDevices.mapNotNull { device ->
                var deviceName: String?
                val deviceAddress = device.address
                try {
                    // Попытка получить имя устройства
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                            Log.w("BT_Zerg", "Permission missing for device.name: $deviceAddress")
                            deviceName = "Имя недоступно (нет разрешения)"
                        } else {
                            deviceName = device.name
                            Log.d("BT_Zerg", "Device name obtained for $deviceAddress: '$deviceName'")
                        }
                    } else {
                        deviceName = device.name
                        Log.d("BT_Zerg", "Device name obtained for $deviceAddress (pre-S): '$deviceName'")
                    }

                    if (deviceName.isNullOrBlank()) {
                        Log.w("BT_Zerg", "Device name is null or blank for $deviceAddress")
                        deviceName = "Неизвестное устройство"
                    }

                    "$deviceName - $deviceAddress"

                } catch (se: SecurityException) {
                    Log.e("BT_Zerg", "SecurityException getting name for $deviceAddress: ${se.message}")
                    "Addr: $deviceAddress (Ошибка имени)"
                } catch (e: Exception) {
                    Log.e("BT_Zerg", "Exception processing device $deviceAddress: ${e.message}")
                    null
                }
            }

            if (newDeviceList.isEmpty()) {
                Log.d("BT_Zerg", "Device list is empty after filtering/mapping (e.g., all had errors or were filtered out)")
                showToast("Нет устройств для отображения (проверьте разрешения)")
            } else {
                Log.d("BT_Zerg", "Updated device list created with ${newDeviceList.size} items.")
            }
        }

        listAdapter?.let { adapter ->
            adapter.clear()
            adapter.addAll(newDeviceList)
            adapter.notifyDataSetChanged()
            Log.d("BT_Zerg", "ListView adapter updated with new data.")
        } ?: run {
            Log.e("BT_Zerg", "listAdapter is null, cannot update ListView!")
        }

        swipeRefreshLayout.isRefreshing = false
        Log.d("BT_Zerg", "Swipe refresh animation finished.")
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        Log.i("BT_Zerg", "Toast: $message")
    }
}