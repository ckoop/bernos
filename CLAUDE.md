# Bernos – Anweisungen für Claude

Bernos ist eine private Android-App (plus geplante Wear-OS-App), die Sonos-Lautsprecher im
lokalen WLAN steuert. Sie darf nicht „Sonos“ heißen und steht in keiner Verbindung zu Sonos, Inc.
Nur Android/Wear OS – **kein iOS**.

Der Nutzer spricht Deutsch. Antworten, Commit-Messages, Code-Kommentare und alle Texte in der
App sind auf Deutsch. Bezeichner im Code bleiben Englisch.

## Arbeitsweise

- **Direkt auf `main` committen und pushen**, keine Feature-Branches, keine Pull Requests
  (ausdrücklicher Wunsch des Nutzers).
- Vor jedem Push lokal prüfen: `./gradlew :sonos-core:test :app:testDebugUnitTest :app:assembleDebug`.
- GitHub Actions (`.github/workflows/build.yml`) baut bei jedem Push, führt alle Tests aus und
  stellt die APK als Artefakt `bernos-debug-apk` bereit. Ein roter Build ist sofort zu beheben.
- Am Ende einer Phase `README.md` und `docs/ROADMAP.md` aktualisieren.
- Ehrlich berichten, was getestet ist: automatische Tests ≠ Test an echten Lautsprechern.

## Projektaufbau

| Modul | Inhalt |
|---|---|
| `sonos-core` | Reines Kotlin/JVM ohne Android-Abhängigkeiten. Gesamte Sonos-Logik, mit Unit-Tests. |
| `app` | Android-App (Jetpack Compose, Material 3), `PlaybackService`, mDNS-Suche. |

Paket `de.bernos.sonos` (Kern) bzw. `de.bernos.app` (App), applicationId `de.bernos.app`,
minSdk 26, compile/targetSdk 36. Versionen in `gradle/libs.versions.toml`
(AGP 8.13, Kotlin 2.2.20, Gradle 8.14.3, JDK 17).

### sonos-core
- `SoapClient` – SOAP über HTTP an `http://<ip>:1400`; UPnP-Fehlercodes landen in `SonosException.upnpErrorCode`.
- `SonosPlayerClient` – einzelne Befehle (AVTransport, RenderingControl, GroupRenderingControl,
  ZoneGroupTopology): Play/Pause/Next/Previous, Position/Metadaten, Gruppen- und Raumlautstärke,
  `joinGroup` (`x-rincon:`), `leaveGroup`, `delegateCoordination`, `zoneGroups`.
- `Parsers` – DIDL-Lite (Titel, Künstler, Album, Cover; Radio über `streamContent`), Dauer,
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
  die Topologie liefert alle anderen.
- Cleartext-HTTP ist per `network_security_config.xml` erlaubt (Sonos spricht nur HTTP).

## Tests
- `sonos-core`: Parser-, SOAP-, SSDP-, Ereignis-Tests und Ende-zu-Ende-Tests des Controllers gegen
  `FakeSonosSystem` (simuliert mehrere Räume mit MockWebServer). Neue Sonos-Funktionen dort
  zuerst nachbilden und testen.
- `app`: Compose-Oberflächentests mit Robolectric (`@Config(sdk = [35])`).
- Testnamen in Backticks **nur ASCII** (keine Umlaute) – sonst bricht der Kotlin-Compiler auf
  Systemen ohne UTF-8-Locale ab.
- Keine Endlosschleifen (`while(true) { delay() }`) in Composables, die in Tests laufen – sonst wird
  Compose nie „idle“.

## Stand

- Phase 1 ✅ Suche, Steuerung, Titel/Cover, Live-Updates, Mediensitzung.
- Phase 2 ✅ Gruppieren, Musik verschieben, Lautstärke pro Raum, mDNS.
- **Noch nie an echten Sonos-Lautsprechern getestet.** Das ist der wichtigste nächste Schritt.

## Todos

1. **Test an echten Geräten** (Handy im selben WLAN, `./gradlew :app:installDebug` oder
   `adb install`): Suche (SSDP/mDNS/IP), Titel und Cover inkl. Radio, Live-Updates bei Änderungen
   aus der Sonos-App, Benachrichtigung + Uhr-Mediensteuerung, Lautstärketasten, Gruppieren,
   Verschieben, Entfernen. Gefundene Abweichungen in `FakeSonosSystem` nachbilden und als Test festhalten.
2. **Phase 3 – Wear-OS-App**: neues Modul `wear` (Compose for Wear OS). Handy bleibt die Zentrale;
   Kommunikation über die Wearable Data Layer API (`MessageClient` für Befehle, `DataClient` für
   Status/Cover). Raumauswahl, Cover, Lautstärke über Drehkrone/Lünette, Tile, Komplikation.
3. **Phase 4 – Sonos Ace (offen, ggf. nicht machbar)**: „TV Audio Swap“ hat keine öffentliche
   Schnittstelle; die Ace hängt per Bluetooth am Handy, nicht im WLAN. Optionen: Content-Taste
   (geht heute), Sonos-App per Bedienungshilfe fernsteuern (fragil), Bluetooth-Protokoll
   analysieren (aufwendig). Vor jedem Aufwand mit dem Nutzer abstimmen.
4. Kleinere offene Punkte:
   - Release-Signatur einrichten (Release nutzt derzeit den Debug-Schlüssel).
   - Ab Android 17 (API 37) prüfen, ob eine Berechtigung für das lokale Netz nötig ist.
   - Raumliste zeigt noch nicht, was in den einzelnen Räumen läuft.
   - Raumlautstärke wird nur per Polling aktualisiert (keine RenderingControl-Abos pro Mitglied).
   - Stummschalten (`setMuted`) ist im Controller vorhanden, aber nicht in der Oberfläche.
   - Lint ist nicht Teil der CI; bei Gelegenheit `:app:lintDebug` aufnehmen und Befunde beheben.
