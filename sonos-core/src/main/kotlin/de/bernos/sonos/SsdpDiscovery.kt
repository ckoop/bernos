package de.bernos.sonos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI

/**
 * Findet Sonos-Lautsprecher im lokalen Netz per SSDP (UPnP-Suche über Multicast).
 *
 * Auf Android muss während der Suche ein `WifiManager.MulticastLock` gehalten werden,
 * sonst verwirft das WLAN-Modul die Antworten. Dafür gibt es [hooks].
 */
class SsdpDiscovery(private val hooks: Hooks = Hooks.NONE) {

    interface Hooks {
        fun beforeSearch()
        fun afterSearch()

        companion object {
            val NONE = object : Hooks {
                override fun beforeSearch() = Unit
                override fun afterSearch() = Unit
            }
        }
    }

    /** Liefert die Hosts (IP-Adressen) aller Lautsprecher, die innerhalb von [timeoutMs] antworten. */
    suspend fun search(timeoutMs: Int = 3000): Set<String> = withContext(Dispatchers.IO) {
        hooks.beforeSearch()
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 250
                val payload = M_SEARCH.toByteArray(Charsets.US_ASCII)
                val targets = listOf(InetAddress.getByName(MULTICAST_ADDRESS), InetAddress.getByName("255.255.255.255"))

                val hosts = linkedSetOf<String>()
                val buffer = ByteArray(2048)
                val deadline = System.currentTimeMillis() + timeoutMs
                var sends = 0
                while (System.currentTimeMillis() < deadline) {
                    // UDP kann Pakete verlieren, daher mehrfach senden.
                    if (sends < 3) {
                        for (target in targets) {
                            runCatching { socket.send(DatagramPacket(payload, payload.size, target, SSDP_PORT)) }
                        }
                        sends++
                    }
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        parseResponse(String(packet.data, 0, packet.length, Charsets.UTF_8))?.let(hosts::add)
                    } catch (_: SocketTimeoutException) {
                        // weiter warten
                    }
                }
                hosts
            }
        } finally {
            hooks.afterSearch()
        }
    }

    internal companion object {
        const val MULTICAST_ADDRESS = "239.255.255.250"
        const val SSDP_PORT = 1900
        private const val SEARCH_TARGET = "urn:schemas-upnp-org:device:ZonePlayer:1"

        val M_SEARCH = "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $MULTICAST_ADDRESS:$SSDP_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 1\r\n" +
            "ST: $SEARCH_TARGET\r\n" +
            "\r\n"

        /** Gibt den Host aus dem LOCATION-Header zurück, falls die Antwort von einem Sonos-Gerät stammt. */
        fun parseResponse(response: String): String? {
            val headers = response.split("\r\n", "\n")
                .drop(1)
                .mapNotNull { line ->
                    val idx = line.indexOf(':')
                    if (idx <= 0) null else line.substring(0, idx).trim().uppercase() to line.substring(idx + 1).trim()
                }
                .toMap()
            val isSonos = headers["ST"] == SEARCH_TARGET || headers["SERVER"]?.contains("Sonos", ignoreCase = true) == true
            if (!isSonos) return null
            val location = headers["LOCATION"] ?: return null
            return runCatching { URI(location).host }.getOrNull()
        }
    }
}
