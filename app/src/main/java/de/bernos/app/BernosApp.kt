package de.bernos.app

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import de.bernos.app.bluetooth.HeadphoneMonitor
import de.bernos.app.wear.WearBridge
import de.bernos.sonos.SonosController
import de.bernos.sonos.SsdpDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class BernosApp : Application() {

    /** Lebt so lange wie der App-Prozess; App-Oberfläche und Wiedergabedienst teilen ihn sich. */
    lateinit var controller: SonosController
        private set

    /** Akkustand der Sonos Ace, solange sie per Bluetooth mit dem Handy verbunden ist. */
    lateinit var headphones: HeadphoneMonitor
        private set

    private lateinit var mdns: MdnsDiscovery

    override fun onCreate() {
        super.onCreate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        controller = SonosController(scope, SsdpDiscovery(MulticastLockHooks(this)))
        mdns = MdnsDiscovery(this) { host ->
            Log.i(DISCOVERY_TAG, "mDNS: Lautsprecher gefunden: $host")
            controller.addHost(host)
        }
        headphones = HeadphoneMonitor(this).apply { start() }
        WearBridge(this, controller, headphones.battery, scope).start()
    }

    /** Sucht parallel per SSDP und mDNS; je nach Router funktioniert nur einer der beiden Wege. */
    fun discover() {
        controller.discover()
        mdns.search()
    }

    /** Ohne Multicast-Lock verwirft Android die SSDP-Antworten der Lautsprecher. */
    private class MulticastLockHooks(context: Context) : SsdpDiscovery.Hooks {
        private val lock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createMulticastLock("bernos-discovery")
            .apply { setReferenceCounted(true) }

        override fun beforeSearch() = lock.acquire()

        override fun afterSearch() {
            if (lock.isHeld) lock.release()
        }

        override fun onFound(hosts: Set<String>) {
            Log.i(DISCOVERY_TAG, "SSDP: ${hosts.size} Lautsprecher gefunden: ${hosts.joinToString()}")
        }
    }

    private companion object {
        /** Protokoll der Suchwege: `adb logcat -s BernosSuche`. */
        const val DISCOVERY_TAG = "BernosSuche"
    }
}

val Context.sonos: SonosController get() = (applicationContext as BernosApp).controller
