package com.zagotovka.zergpad

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
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Spinner
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
    private lateinit var skinSpinner: Spinner
    private lateinit var sharedPreferences: SharedPreferences

    private var listAdapter: ArrayAdapter<String>? = null

    // Константы для SharedPreferences
    private val prefsName = "BtControllerPrefs"
    private val layoutSwitchKey = "layout_switch_state"
    private val skinKey = "selected_skin_index"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start)

        // Инициализация SharedPreferences
        sharedPreferences = getSharedPreferences(prefsName, Context.MODE_PRIVATE)

        // Находим элементы управления
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout)
        listView = findViewById(R.id.device_list_view)
        layoutSwitch = findViewById(R.id.layout_switch)
        skinSpinner = findViewById(R.id.skin_spinner)

        // Загрузка сохраненного состояния переключателя
        layoutSwitch.isChecked = sharedPreferences.getBoolean(layoutSwitchKey, false)

        // Настройка Spinner для скинов
        val skins = arrayOf("Classic", "Neon")
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, skins)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        skinSpinner.adapter = spinnerAdapter
        
        // Загрузка сохраненного скина
        val savedSkin = sharedPreferences.getInt(skinKey, 0)
        skinSpinner.setSelection(savedSkin)

        // Обработчик изменения состояния переключателя
        layoutSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean(layoutSwitchKey, isChecked).apply()
            Log.d("BT_Zerg", "Layout switch state changed to: $isChecked")
        }

        // Обработчик выбора скина
        skinSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                sharedPreferences.edit().putInt(skinKey, position).apply()
                Log.d("BT_Zerg", "Skin changed to: ${skins[position]}")
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Получаем список устройств из MainActivity
        deviceList = intent.getStringArrayExtra("device_list") ?: emptyArray()
        
        // Инициализируем Bluetooth адаптер
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
        btAdapter = btManager?.adapter ?: run {
            showToast("Не удалось получить Bluetooth адаптер")
            finish()
            return
        }

        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, deviceList.toMutableList())
        listView.adapter = listAdapter

        listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            listAdapter?.getItem(position)?.let { connectToDevice(it) }
        }

        swipeRefreshLayout.setOnRefreshListener { updateDeviceList() }
    }

    private fun connectToDevice(deviceInfo: String) {
        val parts = deviceInfo.split(" - ", limit = 2)
        if (parts.size < 2 || parts[1].isBlank()) return

        val deviceAddress = parts[1].trim()
        val useAlternativeLayout = layoutSwitch.isChecked
        val selectedSkin = skinSpinner.selectedItemPosition

        val targetActivity = if (useAlternativeLayout) ControlActivity2::class.java else ControlActivity::class.java

        val intent = Intent(this, targetActivity).apply {
            putExtra("device_address", deviceAddress)
            putExtra("skin_index", selectedSkin)
        }
        startActivity(intent)
    }

    private fun updateDeviceList() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                swipeRefreshLayout.isRefreshing = false
                return
            }
        }
        val pairedDevices = btAdapter.bondedDevices
        val newDeviceList = pairedDevices?.map { "${it.name ?: "Unknown"} - ${it.address}" } ?: emptyList()

        listAdapter?.apply {
            clear()
            addAll(newDeviceList)
            notifyDataSetChanged()
        }
        swipeRefreshLayout.isRefreshing = false
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
