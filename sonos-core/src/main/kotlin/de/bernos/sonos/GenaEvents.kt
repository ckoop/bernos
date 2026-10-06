package de.bernos.sonos

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** Eine UPnP-Ereignismeldung (NOTIFY) eines Lautsprechers. */
data class GenaEvent(val sid: String, val body: String)

/**
 * Kleiner HTTP-Server, der die NOTIFY-Meldungen der Lautsprecher entgegennimmt.
 * Lautsprecher melden sich damit von selbst, wenn sich Titel, Status oder Lautstärke ändern.
 */
class GenaEventServer(private val scope: CoroutineScope) {
    private val _events = MutableSharedFlow<GenaEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<GenaEvent> = _events.asSharedFlow()

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    val port: Int get() = serverSocket?.localPort ?: 0

    @Synchronized
    fun start() {
        if (serverSocket != null) return
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(0))
        }
        serverSocket = socket
        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                launch { handle(client) }
            }
        }
    }

    @Synchronized
    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 5000
            try {
                val request = readRequest(BufferedInputStream(socket.getInputStream()))
                val ok = request != null && request.method == "NOTIFY" && request.headers["SID"] != null
                val status = if (ok) "200 OK" else "400 Bad Request"
                socket.getOutputStream().apply {
                    write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    flush()
                }
                if (ok) _events.tryEmit(GenaEvent(request!!.headers.getValue("SID"), request.body))
            } catch (_: IOException) {
                // Verbindung abgebrochen – ignorieren.
            }
        }
    }

    internal data class HttpRequest(val method: String, val headers: Map<String, String>, val body: String)

    internal companion object {
        private const val MAX_BODY = 1 shl 20

        fun readRequest(input: InputStream): HttpRequest? {
            val requestLine = readLine(input) ?: return null
            val method = requestLine.substringBefore(' ')
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: return null
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().uppercase()] = line.substring(idx + 1).trim()
            }
            val length = headers["CONTENT-LENGTH"]?.toIntOrNull()?.coerceIn(0, MAX_BODY) ?: 0
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) break
                read += n
            }
            return HttpRequest(method, headers, String(body, 0, read, Charsets.UTF_8))
        }

        private fun readLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                if (c == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(c.toChar())
                if (sb.length > 8192) throw IOException("Header zu lang")
            }
        }
    }
}

/** An- und Abmelden von UPnP-Ereignissen (SUBSCRIBE/UNSUBSCRIBE) bei einem Lautsprecher. */
class GenaSubscriber(private val http: OkHttpClient) {

    data class Subscription(val device: SonosDevice, val service: SonosService, val sid: String, val timeoutSeconds: Int)

    suspend fun subscribe(device: SonosDevice, service: SonosService, callbackPort: Int): Subscription =
        withContext(Dispatchers.IO) {
            val callbackHost = localAddressFor(device.host)
            val request = Request.Builder()
                .url(device.baseUrl + service.eventPath)
                .method("SUBSCRIBE", null)
                .header("CALLBACK", "<http://$callbackHost:$callbackPort/bernos>")
                .header("NT", "upnp:event")
                .header("TIMEOUT", "Second-$DEFAULT_TIMEOUT_SECONDS")
                .build()
            execute(request, device, service)
        }

    suspend fun renew(subscription: Subscription): Subscription = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(subscription.device.baseUrl + subscription.service.eventPath)
            .method("SUBSCRIBE", null)
            .header("SID", subscription.sid)
            .header("TIMEOUT", "Second-$DEFAULT_TIMEOUT_SECONDS")
            .build()
        execute(request, subscription.device, subscription.service)
    }

    suspend fun unsubscribe(subscription: Subscription) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(subscription.device.baseUrl + subscription.service.eventPath)
                .method("UNSUBSCRIBE", null)
                .header("SID", subscription.sid)
                .build()
            runCatching { http.newCall(request).execute().close() }
        }
    }

    private fun execute(request: Request, device: SonosDevice, service: SonosService): Subscription {
        try {
            http.newCall(request).execute().use { response ->
                val sid = response.header("SID")
                if (!response.isSuccessful || sid == null) {
                    throw SonosException("Ereignis-Anmeldung bei ${device.roomName} fehlgeschlagen (HTTP ${response.code})")
                }
                val timeout = response.header("TIMEOUT")?.substringAfter("Second-")?.toIntOrNull() ?: DEFAULT_TIMEOUT_SECONDS
                return Subscription(device, service, sid, timeout)
            }
        } catch (e: IOException) {
            throw SonosException("${device.roomName} nicht erreichbar: ${e.message}", cause = e)
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 1800

        /** Die eigene IP-Adresse auf der Netzwerkschnittstelle, über die [remoteHost] erreichbar ist. */
        fun localAddressFor(remoteHost: String): String = DatagramSocket().use { socket ->
            // UDP-"connect" sendet nichts, legt aber die Route und damit die lokale Adresse fest.
            socket.connect(InetSocketAddress(remoteHost, SonosDevice.DEFAULT_PORT))
            socket.localAddress.hostAddress ?: throw SonosException("Keine lokale Netzwerkadresse gefunden")
        }
    }
}
