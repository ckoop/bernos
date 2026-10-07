package de.bernos.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Zweiter Suchweg neben SSDP: Sonos-Lautsprecher kündigen sich per mDNS als `_sonos._tcp` an.
 * Manche Router lassen das eine durch, das andere nicht. Gefundene Adressen gehen an [onFound].
 */
class MdnsDiscovery(context: Context, private val onFound: (String) -> Unit) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val handler = Handler(Looper.getMainLooper())
    private var listener: NsdManager.DiscoveryListener? = null

    /** Sucht für [durationMs] Millisekunden und beendet die Suche dann selbst. */
    fun search(durationMs: Long = 5_000) {
        if (listener != null) return
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d(TAG, "Dienst gefunden: ${service.serviceName}")
                resolve(service)
            }
            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Log.d(TAG, "Suche gestartet").let { }
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Suche fehlgeschlagen, Fehler $errorCode")
                listener = null
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        listener = discoveryListener
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener) }
            .onFailure { listener = null; return }
        handler.postDelayed({ stop() }, durationMs)
    }

    fun stop() {
        val current = listener ?: return
        listener = null
        runCatching { nsd.stopServiceDiscovery(current) }
    }

    @Suppress("DEPRECATION") // Der Nachfolger registerServiceInfoCallback gibt es erst ab Android 14.
    private fun resolve(service: NsdServiceInfo) {
        runCatching {
            nsd.resolveService(
                service,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.d(TAG, "Auflösen fehlgeschlagen: ${serviceInfo.serviceName}, Fehler $errorCode")
                    }
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val host = serviceInfo.host?.hostAddress ?: return
                        Log.d(TAG, "Aufgelöst: ${serviceInfo.serviceName} -> $host")
                        // IPv6-Adressen lassen sich nicht ohne Weiteres als URL verwenden.
                        if (':' in host) return
                        handler.post { onFound(host) }
                    }
                },
            )
        }
    }

    private companion object {
        const val SERVICE_TYPE = "_sonos._tcp."
        const val TAG = "BernosSuche"
    }
}
