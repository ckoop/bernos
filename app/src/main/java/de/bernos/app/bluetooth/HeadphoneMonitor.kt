package de.bernos.app.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Akkustand eines per Bluetooth verbundenen Sonos-Kopfhörers in Prozent. */
data class HeadphoneBattery(val name: String, val level: Int)

/**
 * Liest den Akkustand der Sonos Ace so, wie ihn auch die Bluetooth-Einstellungen zeigen.
 *
 * Die Ace hängt nicht im WLAN, sondern per Bluetooth am Handy; Android erfährt den Akkustand
 * über das Freisprechprofil bzw. den BLE-Akkudienst – aber nur, solange sie verbunden ist.
 * Die passende Schnittstelle (`getBatteryLevel`, `BATTERY_LEVEL_CHANGED`) ist in Android
 * vorhanden, aber nicht offiziell für Apps freigegeben; schlägt sie fehl, bleibt die Anzeige leer.
 */
class HeadphoneMonitor(context: Context) {
    private val context = context.applicationContext
    private val _battery = MutableStateFlow<HeadphoneBattery?>(null)
    val battery: StateFlow<HeadphoneBattery?> = _battery.asStateFlow()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.bluetoothDevice() ?: return
            val name = device.safeName()?.takeIf { isSonosHeadphone(it) } ?: return
            when (intent.action) {
                ACTION_BATTERY_LEVEL_CHANGED -> {
                    val level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                    Log.i(TAG, "$name meldet Akku $level")
                    _battery.value = if (level in 0..100) HeadphoneBattery(name, level) else null
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> refresh()
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    Log.i(TAG, "$name getrennt")
                    if (_battery.value?.name == name) _battery.value = null
                }
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(ACTION_BATTERY_LEVEL_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        // Die Meldungen kommen von der Bluetooth-App, nicht vom System; daher „exported“.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        refresh()
    }

    /** Liest den aktuellen Stand, z. B. beim Start oder nachdem die Berechtigung erteilt wurde. */
    @SuppressLint("MissingPermission")
    fun refresh() {
        if (!hasPermission()) {
            _battery.value = null
            return
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val devices = runCatching { adapter?.bondedDevices.orEmpty() }.getOrDefault(emptySet())
        _battery.value = devices.firstNotNullOfOrNull { device ->
            val name = device.safeName()?.takeIf { isSonosHeadphone(it) } ?: return@firstNotNullOfOrNull null
            // -1 = unbekannt bzw. nicht verbunden, -100 = Bluetooth aus.
            device.batteryLevel()?.takeIf { it in 0..100 }?.let { HeadphoneBattery(name, it) }
        }
        Log.i(TAG, "Akkustand gelesen: ${_battery.value}")
    }

    fun hasPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.safeName(): String? = runCatching { name }.getOrNull()

    private fun BluetoothDevice.batteryLevel(): Int? = runCatching {
        BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(this) as Int
    }.onFailure { Log.w(TAG, "getBatteryLevel nicht verfügbar", it) }.getOrNull()

    @Suppress("DEPRECATION")
    private fun Intent.bluetoothDevice(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private companion object {
        /** Protokoll: `adb logcat -s BernosAce`. */
        const val TAG = "BernosAce"
        const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
    }
}

/** Erkennt die Ace am Bluetooth-Namen (Standard „Sonos Ace“); andere Kopfhörer bleiben außen vor. */
internal fun isSonosHeadphone(name: String): Boolean =
    name.contains("Sonos", ignoreCase = true) || Regex("""\bAce\b""", RegexOption.IGNORE_CASE).containsMatchIn(name)
