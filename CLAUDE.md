# Bernos – Anweisungen für Claude

Bernos ist eine private Android-App (plus geplante Wear-OS-App), die Sonos-Lautsprecher im
lokalen WLAN steuert. Sie darf nicht „Sonos“ heißen und steht in keiner Verbindung zu Sonos, Inc.
Nur Android/Wear OS – **kein iOS**.

Der Nutzer spricht Deutsch. Antworten, Commit-Messages, Code-Kommentare und alle Texte in der
App sind auf Deutsch. Bezeichner im Code bleiben Englisch.

## Arbeitsweise

- **Direkt auf `main` committen und pushen**, keine Feature-Branches, keine Pull Requests
  (ausdrücklicher Wunsch des Nutzers).
- Vor jedem Push lokal prüfen:
  `./gradlew :sonos-core:test :wear-protocol:test :app:testDebugUnitTest :app:assembleDebug :wear:testDebugUnitTest :wear:assembleDebug`.
  Lokal liegt kein JDK im PATH: `JAVA_HOME=~/Android/jdk-21` setzen (kompiliert für Java 17).
- GitHub Actions (`.github/workflows/build.yml`) baut bei jedem Push, führt alle Tests aus und
  stellt die APKs als Artefakte `bernos-debug-apk` (Handy) und `bernos-wear-debug-apk` (Uhr) bereit. Ein roter Build ist sofort zu beheben.
- Am Ende einer Phase `README.md` und `docs/ROADMAP.md` aktualisieren.
- Ehrlich berichten, was getestet ist: automatische Tests ≠ Test an echten Lautsprechern.

## Versionsnummer

Handy und Uhr haben **eine gemeinsame** Version `<major>.<minor>.<patch>`, einzig gepflegt als
`bernos.version` in `gradle.properties`. Das Wurzel-`build.gradle.kts` prüft das Format und leitet
`versionCode` ab: `(major*10000 + minor*100 + patch) * 10 + Geräteziffer` (0 = Handy, 1 = Uhr).
Angezeigt wird sie am Ende der Raumliste (Handy und Uhr).

- Solange Bernos im Aufbau ist: `0.<Phase>.<Patch>`. Neue Phase → minor erhöhen, patch auf 0.
- **patch** erhöhen bei jedem Push, der das Verhalten einer App ändert (Fehlerbehebung, kleine
  Funktion). Reine Doku-, Test- oder CI-Änderungen erhöhen nichts.
- **1.0.0**, wenn Phase 3 abgeschlossen und im Alltag stabil ist; danach klassisch SemVer
  (major = inkompatible Änderung, z. B. neue Protokoll-Version zwischen Handy und Uhr).
- Nach dem Erhöhen beide Apps installieren, weil sie zusammenpassen müssen.

## Projektaufbau

| Modul | Inhalt |
|---|---|
| `sonos-core` | Reines Kotlin/JVM ohne Android-Abhängigkeiten. Gesamte Sonos-Logik, mit Unit-Tests. |
| `app` | Android-App (Jetpack Compose, Material 3), `PlaybackService`, mDNS-Suche, Brücke zur Uhr. |
| `wear-protocol` | Reines Kotlin: `WatchState` und `WatchCommand` samt Binärformat für die Data Layer, mit Tests. |
| `wear` | Wear-OS-App (Compose for Wear OS, Material 3), spricht nur mit dem Handy, nie direkt mit Sonos. |

Paket `de.bernos.sonos` (Kern), `de.bernos.app` (App), `de.bernos.wearprotocol`, `de.bernos.wear`.
applicationId `de.bernos.app` für Handy **und** Uhr (Pflicht für die Data Layer, ebenso gleiche
Signatur). minSdk 26 (Handy) bzw. 30 (Uhr), compile/targetSdk 36. Versionen in `gradle/libs.versions.toml`
(AGP 8.13, Kotlin 2.2.20, Gradle 8.14.3, JDK 17).

### sonos-core
- `SoapClient` – SOAP über HTTP an `http://<ip>:1400`; UPnP-Fehlercodes landen in `SonosException.upnpErrorCode`.
- `SonosPlayerClient` – einzelne Befehle (AVTransport, RenderingControl, GroupRenderingControl,
  ZoneGroupTopology): Play/Pause/Next/Previous, Position/Metadaten, Gruppen- und Raumlautstärke,
  `joinGroup` (`x-rincon:`), `leaveGroup`, `delegateCoordination`, `zoneGroups`.
- `Parsers` – DIDL-Lite (Titel, Künstler, Album, Cover; Radio über `streamContent`), Dauer,
  Quelle aus `CurrentURIMetaData` (Sendername und -logo; TuneIn liefert das Logo nur dort),
  `ZoneGroupState` (unsichtbare Mitglieder und Bridges werden ausgeblendet).
- `SsdpDiscovery` – M-SEARCH nach `ZonePlayer:1`; auf Android über `Hooks` mit MulticastLock.
- `GenaEvents` – eigener kleiner HTTP-Server für NOTIFY + SUBSCRIBE/Renew/UNSUBSCRIBE.
- `SonosController` – zentrale Steuerung, `StateFlow<SonosState>`. Ereignisse lösen
  Aktualisierungen aus, Polling (3 s ohne / 15 s mit Ereignissen) ist das Sicherheitsnetz.
  Gruppenänderungen laden die Topologie sofort und nach 1,5 s nochmals; „Musik verschieben“ =
  Zielraum tritt bei → `DelegateGroupCoordinationTo(RejoinGroup=0)` mit Wiederholungen → Auswahl folgt.
- Steuerbefehle gehen immer an den **Koordinator** der Gruppe.

### app
- `BernosApp` – hält den `SonosController` (prozessweit), startet SSDP + mDNS (`discover()`).
- `MainActivity` – Compose, verbindet `BernosActions` mit dem Controller.
- `ui/BernosScreen` – Raumliste (mit IP-Eingabe als Fallback) und Wiedergabeansicht
  (Cover, Titel, Fortschritt, Steuerung, Gruppenlautstärke, Raumbereich Dazu/Entfernen/Hierher,
  Lautstärke pro Raum). Icons sind eigene Vector-Drawables in `res/drawable`.
- `PlaybackService` – Vordergrunddienst mit `MediaSessionCompat` (androidx.media) und
  `VolumeProviderCompat`: Benachrichtigung, Sperrbildschirm, Wear-OS-Mediensteuerung,
  Lautstärketasten. „Beenden“ stoppt auch das Polling (`stopTracking`).
- `MdnsDiscovery` – `_sonos._tcp` über `NsdManager`; ein gefundener Lautsprecher genügt,
  die Topologie liefert alle anderen. Beide Suchwege protokollieren unter `BernosSuche`.
- Cleartext-HTTP ist per `network_security_config.xml` erlaubt (Sonos spricht nur HTTP).
- `wear/WearBridge` – veröffentlicht `WatchState` als DataItem `/bernos/state`, Cover als
  JPEG-Asset (320 px). `wear/WearCommandService` (WearableListenerService) führt Befehle von
  `/bernos/command` aus. Capability `bernos_phone` in `res/values/wear.xml`.

### wear
- `PhoneLink` – empfängt den Zustand per `DataClient`, sucht das Handy über die Capability und
  schickt Befehle per `MessageClient`; Play/Pause, Lautstärke und Raumwahl werden sofort lokal
  angezeigt. Lautstärke wird gedrosselt (letzter Wert gewinnt).
- `MainActivity` – schickt alle 10 s `Hello`, solange sichtbar, damit das Handy auch im
  Hintergrund aktualisiert (Android friert Hintergrund-Apps sonst ein).
- `ui/BernosWear` – Raumliste und Wiedergabe (Cover als Hintergrund, Steuerung,
  Lautstärke über Lünette/Drehkrone mit `LevelIndicator`). Tipp auf den Raumnamen → Raumliste.

## Tests
- `sonos-core`: Parser-, SOAP-, SSDP-, Ereignis-Tests und Ende-zu-Ende-Tests des Controllers gegen
  `FakeSonosSystem` (simuliert mehrere Räume mit MockWebServer). Neue Sonos-Funktionen dort
  zuerst nachbilden und testen.
- `app`: Compose-Oberflächentests mit Robolectric (`@Config(sdk = [35])`).
- `wear`: Oberflächentests mit Robolectric auf einer runden Uhr (Qualifier `...-round-watch-...`,
  `application = Application::class`, damit keine echte Data Layer startet).
- Testnamen in Backticks **nur ASCII** (keine Umlaute) – sonst bricht der Kotlin-Compiler auf
  Systemen ohne UTF-8-Locale ab.
- Keine Endlosschleifen (`while(true) { delay() }`) in Composables, die in Tests laufen – sonst wird
  Compose nie „idle“.

## Stand

- Phase 1 ✅ Suche, Steuerung, Titel/Cover, Live-Updates, Mediensitzung.
- Phase 2 ✅ Gruppieren, Musik verschieben, Lautstärke pro Raum, mDNS.
- ✅ Erster Test an echten Geräten (07.10.2026, Xiaomi/POCO mit Android 16 und Galaxy Watch):
  Raumsuche, Titel/Cover, Steuerung, Live-Updates bei Änderungen aus der Sonos-App,
  Benachrichtigung, Uhr-Mediensteuerung, Lautstärketasten sowie Dazu/Entfernen/Hierher
  funktionieren laut Nutzer. Radio (TuneIn) zeigt Titel und Senderlogo. Nicht gezielt geprüft:
  welcher Suchweg (SSDP/mDNS/IP) gegriffen hat.
- Phase 3, Teil 1 ✅ Uhr-App (07.10.2026 an Galaxy Watch7 bestätigt): Raumliste, Wiedergabe mit
  Cover, Play/Pause/Weiter/Zurück, Lautstärke über die Lünette, Raum wechseln, Musik verschieben.
  Lehren: Ziele von `SwipeDismissableNavHost` dürfen keinen Zustand einfangen (über
  `rememberUpdatedState` lesen); mehrfache `adb shell am start` erzeugten zwei Instanzen →
  `singleTask`. Galaxy Watch verliert WLAN-Debugging oft beim Ausschalten des Bildschirms.
- Phase 3, Teil 2 ✅ Kachel und Komplikation (07.10.2026 an Galaxy Watch7 bestätigt). Lehren:
  `protolayout-material`-Text ist ohne `setColor` dunkelgrau (ON_PRIMARY); `lastClickableId`
  kommt nur in der Anfrage direkt nach dem Tipp, spätere Aktualisierungen haben sie leer.
  **Phase 3 abgeschlossen** (Version 0.3.3). 1.0.0 erst, wenn es sich im Alltag bewährt hat.
- Medien-Symbol auf dem Zifferblatt (0.4.2 probiert, 0.4.3 zurückgenommen): Es gehört zur
  System-Mediensteuerung und öffnet deren Player. Wear OS übernimmt die Mediensitzung des Handys
  auch mit `setLocalOnly(true)`; ein eigenes Ongoing-Activity-Symbol führte nur zu zwei Symbolen
  und einer Auswahl. Nutzer-Entscheidung: nur System-Player, Einschränkung dokumentiert.
  `REMOTE_MEDIA_ACTIVITY` ist in der Uhr-App angemeldet (wirkt ab Wear OS 7 / API 37).
- Suchwege geprüft (07.10.2026, 0.4.4): SSDP und mDNS finden beim Nutzer beide alle 4
  Lautsprecher (im allerersten Lauf nach der Installation nur 1 per SSDP, 0 per mDNS – die
  doppelte Suche fängt das ab). Protokoll: `adb logcat -s BernosSuche`.
- Stummschalten ✅ (0.4.4): Lautsprecher-Symbol neben der Gruppenlautstärke auf dem Handy.
- Phase 4 ✅ Sonos-Favoriten (07.10.2026, Version 0.4.3): Browse `FV:2`, Sender direkt,
  Playlists/Alben über die Warteschlange – beides an echten Geräten bestätigt (TuneIn-Sender,
  Spotify-Playlist). Handy: Reihe "Favoriten"; Uhr: Stern-Knopf neben dem Raum (unten war auf
  dem runden Bildschirm kein Platz – Sichtbarkeit in Uhr-Tests immer mit `assertIsDisplayed`
  und langen Texten prüfen). Verknüpfungen (`shortcut`) gehen nur in der Sonos-App.

## Todos

1. **Optional – Spotify-Login**: Spotify-Inhalte (eigene Playlists, gespeicherte Alben, Suche)
   in Bernos auswählbar machen, auf Handy und unter dem Stern auf der Uhr. Lokal über Sonos lässt
   sich Spotify nicht durchsuchen (SMAPI-Zugangsdaten bleiben in der Sonos-Cloud,
   `/status/accounts` ist auf aktueller Firmware leer). Weg: offizielle Spotify Web API mit
   OAuth (PKCE) zum Auflisten; abspielen lokal über die Sonos-Kennung
   `x-rincon-cpcontainer:…spotify%3Aplaylist%3A<id>?sid=12…` mit passenden Metadaten
   (wie SoCo-ShareLink), Spotify muss in der Sonos-App verknüpft sein. Nutzer muss eine App im
   Spotify-Entwicklerportal anlegen. Bis dahin: Spotify-Playlists als Sonos-Favoriten speichern
   (funktioniert über die Warteschlange). Einfachere Zwischenstufe wäre "Teilen → Bernos".
2. **Optional – Sonos Ace (ggf. nicht machbar)**: „TV Audio Swap“ hat keine öffentliche
   Schnittstelle; die Ace hängt per Bluetooth am Handy, nicht im WLAN. Optionen: Content-Taste
   (geht heute), Sonos-App per Bedienungshilfe fernsteuern (fragil), Bluetooth-Protokoll
   analysieren (aufwendig). Vor jedem Aufwand mit dem Nutzer abstimmen.
3. Kleinere offene Punkte:
   - Release-Signatur einrichten (Release nutzt derzeit den Debug-Schlüssel).
   - Ab Android 17 (API 37) prüfen, ob eine Berechtigung für das lokale Netz nötig ist.
   - Raumliste zeigt noch nicht, was in den einzelnen Räumen läuft.
   - Raumlautstärke wird nur per Polling aktualisiert (keine RenderingControl-Abos pro Mitglied).
   - Lint ist nicht Teil der CI; bei Gelegenheit `:app:lintDebug` aufnehmen und Befunde beheben.
   - Uhr: ungeprüft, ob sich Kachel/Komplikation bei geschlossener Uhr-App aktualisieren, wenn
     sich in der Sonos-App etwas ändert (StateListenerService). Die Handy-App fragt ohne
     Herzschlag der Uhr nur, solange ihr Prozess lebt bzw. der PlaybackService läuft.
   - Uhr: weitere Komplikationsformen (nur Symbol, kleines Cover) – vom Nutzer zurückgestellt.
1.0.0 vergeben, wenn der Nutzer bestätigt, dass Handy und Uhr im Alltag stabil laufen.
