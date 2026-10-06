package de.bernos.sonos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Die UPnP-Dienste eines Sonos-Lautsprechers, die Bernos verwendet. */
enum class SonosService(val controlPath: String, val eventPath: String, val urn: String) {
    AV_TRANSPORT(
        "/MediaRenderer/AVTransport/Control",
        "/MediaRenderer/AVTransport/Event",
        "urn:schemas-upnp-org:service:AVTransport:1",
    ),
    RENDERING_CONTROL(
        "/MediaRenderer/RenderingControl/Control",
        "/MediaRenderer/RenderingControl/Event",
        "urn:schemas-upnp-org:service:RenderingControl:1",
    ),
    GROUP_RENDERING_CONTROL(
        "/MediaRenderer/GroupRenderingControl/Control",
        "/MediaRenderer/GroupRenderingControl/Event",
        "urn:schemas-upnp-org:service:GroupRenderingControl:1",
    ),
    ZONE_GROUP_TOPOLOGY(
        "/ZoneGroupTopology/Control",
        "/ZoneGroupTopology/Event",
        "urn:schemas-upnp-org:service:ZoneGroupTopology:1",
    ),
}

/** Minimaler SOAP-Client für die lokale Sonos-Schnittstelle (Port 1400). */
class SoapClient(private val http: OkHttpClient) {

    suspend fun call(
        device: SonosDevice,
        service: SonosService,
        action: String,
        arguments: List<Pair<String, String>> = emptyList(),
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(device.baseUrl + service.controlPath)
            .header("SOAPACTION", "\"${service.urn}#$action\"")
            .post(envelope(service, action, arguments).toRequestBody(XML))
            .build()

        val (code, body) = try {
            http.newCall(request).execute().use { response ->
                response.code to response.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            throw SonosException("${device.roomName} nicht erreichbar: ${e.message}", cause = e)
        }

        if (code !in 200..299) {
            val errorCode = runCatching { Xml.parse(body).firstText("errorCode")?.toInt() }.getOrNull()
            throw SonosException("$action fehlgeschlagen (HTTP $code, UPnP-Fehler $errorCode)", errorCode)
        }
        parseResponse(body, action)
    }

    internal companion object {
        private val XML = "text/xml; charset=\"utf-8\"".toMediaType()

        fun envelope(service: SonosService, action: String, arguments: List<Pair<String, String>>): String =
            buildString {
                append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
                append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
                append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
                append("<u:$action xmlns:u=\"${service.urn}\">")
                for ((name, value) in arguments) {
                    append("<$name>${Xml.escape(value)}</$name>")
                }
                append("</u:$action></s:Body></s:Envelope>")
            }

        fun parseResponse(body: String, action: String): Map<String, String> {
            val document = try {
                Xml.parse(body)
            } catch (e: Exception) {
                throw SonosException("Ungültige Antwort auf $action", cause = e)
            }
            val response = document.descendants("${action}Response").firstOrNull()
                ?: throw SonosException("Unerwartete Antwort auf $action")
            return response.childElements().associate { it.localName to it.textContent }
        }
    }
}
