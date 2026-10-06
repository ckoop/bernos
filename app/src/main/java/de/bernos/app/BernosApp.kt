package de.bernos.app

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import de.bernos.sonos.SonosController
import de.bernos.sonos.SsdpDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class BernosApp : Application() {

    /** Lebt so lange wie der App-Prozess; App-Oberfläche und Wiedergabedienst teilen ihn sich. */
    lateinit var controller: SonosController
        private set

    private lateinit var mdns: MdnsDiscovery

    override fun onCreate() {
        super.onCreate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        controller = SonosController(scope, SsdpDiscovery(MulticastLockHooks(this)))
        mdns = MdnsDiscovery(this) { host -> controller.addHost(host) }
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
    }
}

val Context.sonos: SonosController get() = (applicationContext as BernosApp).controller
