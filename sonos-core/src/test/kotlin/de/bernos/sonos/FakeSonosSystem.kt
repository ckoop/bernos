package de.bernos.sonos

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.Closeable

/**
 * Simuliert ein Sonos-System im Test: Jeder Raum ist ein eigener HTTP-Server, der die
 * wichtigsten SOAP-Aktionen beantwortet und Gruppen, Lautstärke und Wiedergabe nachbildet.
 */
class FakeSonosSystem(roomNames: List<String>) : Closeable {

    inner class Speaker(val uuid: String, val roomName: String) {
        val server = MockWebServer()
        @Volatile var coordinatorUuid: String = uuid
        @Volatile var volume: Int = 20
        @Volatile var transport: String = "STOPPED"
        @Volatile var title: String? = null

        val address: String get() = "${server.hostName}:${server.port}"
    }

    val speakers: List<Speaker> = roomNames.mapIndexed { i, name -> Speaker("RINCON_${i}00", name) }
    private var topologyVersion = 1
    private val lock = Any()

    /** Alle empfangenen Aktionen als "Raum:Aktion", z. B. "Bad:DelegateGroupCoordinationTo". */
    val calls: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    init {
        for (speaker in speakers) {
            speaker.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = handle(speaker, request)
            }
            speaker.server.start()
        }
    }

    fun speaker(roomName: String): Speaker = speakers.first { it.roomName == roomName }

    override fun close() = speakers.forEach { runCatching { it.server.shutdown() } }

    private fun handle(speaker: Speaker, request: RecordedRequest): MockResponse = synchronized(lock) {
        if (request.method != "POST") return MockResponse().setResponseCode(412) // keine Ereignisse
        val action = request.getHeader("SOAPACTION")!!.trim('"').substringAfter('#')
        val args = Xml.parse(request.body.readUtf8()).descendants(action).first().childElements()
            .associate { it.localName to it.textContent }
        calls += "${speaker.roomName}:$action"
        val urn = request.getHeader("SOAPACTION")!!.trim('"').substringBefore('#')
        val coordinator = speakers.first { it.uuid == speaker.coordinatorUuid }
        val members = speakers.filter { it.coordinatorUuid == coordinator.uuid }

        val result: List<Pair<String, String>> = when (action) {
            "GetZoneGroupState" -> listOf("ZoneGroupState" to zoneGroupState())
            "GetTransportInfo" -> listOf("CurrentTransportState" to coordinator.transport)
            "GetPositionInfo" -> listOf(
                "TrackDuration" to "0:03:00",
                "RelTime" to "0:01:00",
                "TrackMetaData" to (coordinator.title?.let { didl(it) } ?: ""),
            )
            "GetMediaInfo" -> listOf("CurrentURIMetaData" to "")
            "Play" -> emptyList<Pair<String, String>>().also { coordinator.transport = "PLAYING" }
            "Pause" -> emptyList<Pair<String, String>>().also { coordinator.transport = "PAUSED_PLAYBACK" }
            "Next", "Previous" -> emptyList()
            "GetVolume" -> listOf("CurrentVolume" to speaker.volume.toString())
            "SetVolume" -> emptyList<Pair<String, String>>().also { speaker.volume = args.getValue("DesiredVolume").toInt() }
            "GetGroupVolume" -> listOf("CurrentVolume" to (members.sumOf { it.volume } / members.size).toString())
            "GetGroupMute" -> listOf("CurrentMute" to "0")
            "SnapshotGroupVolume" -> emptyList()
            "SetGroupVolume" -> emptyList<Pair<String, String>>().also {
                members.forEach { m -> m.volume = args.getValue("DesiredVolume").toInt() }
            }
            "SetAVTransportURI" -> {
                val uri = args.getValue("CurrentURI")
                if (uri.startsWith("x-rincon:")) {
                    speaker.coordinatorUuid = uri.removePrefix("x-rincon:")
                    speaker.transport = "STOPPED"
                    speaker.title = null
                    topologyVersion++
                }
                emptyList()
            }
            "BecomeCoordinatorOfStandaloneGroup" -> {
                speaker.coordinatorUuid = speaker.uuid
                topologyVersion++
                emptyList()
            }
            "DelegateGroupCoordinationTo" -> {
                val newUuid = args.getValue("NewCoordinator")
                val target = speakers.first { it.uuid == newUuid }
                if (target.coordinatorUuid != speaker.uuid) return fault(800)
                members.forEach { it.coordinatorUuid = newUuid }
                target.transport = speaker.transport
                target.title = speaker.title
                if (args["RejoinGroup"] == "0") {
                    speaker.coordinatorUuid = speaker.uuid
                    speaker.transport = "STOPPED"
                    speaker.title = null
                }
                topologyVersion++
                emptyList()
            }
            else -> return fault(401)
        }
        MockResponse().setBody(envelope(urn, action, result))
    }

    private fun zoneGroupState(): String = buildString {
        append("<ZoneGroupState><ZoneGroups>")
        for (coordinator in speakers.filter { it.coordinatorUuid == it.uuid }) {
            append("<ZoneGroup Coordinator=\"${coordinator.uuid}\" ID=\"${coordinator.uuid}:$topologyVersion\">")
            for (member in speakers.filter { it.coordinatorUuid == coordinator.uuid }) {
                append(
                    "<ZoneGroupMember UUID=\"${member.uuid}\" " +
                        "Location=\"http://${member.address}/xml/device_description.xml\" ZoneName=\"${member.roomName}\"/>",
                )
            }
            append("</ZoneGroup>")
        }
        append("</ZoneGroups></ZoneGroupState>")
    }

    private fun didl(title: String) =
        "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">" +
            "<item><dc:title>${Xml.escape(title)}</dc:title><dc:creator>Testband</dc:creator></item></DIDL-Lite>"

    private fun envelope(urn: String, action: String, values: List<Pair<String, String>>) = buildString {
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>")
        append("<u:${action}Response xmlns:u=\"$urn\">")
        values.forEach { (k, v) -> append("<$k>${Xml.escape(v)}</$k>") }
        append("</u:${action}Response></s:Body></s:Envelope>")
    }

    private fun fault(code: Int) = MockResponse().setResponseCode(500).setBody(
        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault><detail>" +
            "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>$code</errorCode></UPnPError>" +
            "</detail></s:Fault></s:Body></s:Envelope>",
    )
}
