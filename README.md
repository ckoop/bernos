# Bernos

Bernos ist eine Android-App zur Steuerung von Sonos-Lautsprechern, mit Wear-OS-Uhr als
Fernbedienung. Bernos ist ein privates Projekt und steht in keiner Verbindung zu Sonos, Inc.

## Stand: Phase 4 abgeschlossen (Version 0.4.3)

An echten Sonos-Lautsprechern erfolgreich getestet (Handy mit Android 16, Galaxy Watch7).

### Handy

- Findet die Sonos-Lautsprecher im WLAN automatisch (SSDP und mDNS); falls der Router das blockiert,
  lässt sich ein Lautsprecher per IP-Adresse hinzufügen.
- Zeigt alle Räume bzw. Gruppen an.
- **Räume gruppieren:** weitere Räume dazunehmen oder herausnehmen, Lautstärke pro Raum.
- **Musik verschieben:** die laufende Musik mit einem Tipp in einen anderen Raum schicken.
- Zeigt für den gewählten Raum Titel, Künstler, Album und **Albumcover** an, inkl. Fortschritt.
- Abspielen/Pause, nächster/vorheriger Titel, Lautstärke der Gruppe.
- **Sonos-Favoriten** ("Meine Sonos") mit Cover direkt abspielen: Radiosender sowie
  Playlists und Alben, z. B. von Spotify. Verknüpfungen (etwa Bereiche von Sonos Radio)
  gehen nur in der Sonos-App; Bernos weist darauf hin.
- Live-Aktualisierung: Die Lautsprecher melden Änderungen von selbst (UPnP-Ereignisse);
  zusätzlich fragt die App regelmäßig nach, falls Ereignisse nicht ankommen.
- Mediensitzung: Titel, Cover und Steuerung erscheinen in der Benachrichtigung, auf dem
  Sperrbildschirm und **in der Mediensteuerung der Wear-OS-Uhr**. Die Lautstärketasten des
  Handys regeln Sonos.

### Uhr (Wear OS)

Eigene Uhr-App, die über das Handy steuert (das Handy muss Bernos installiert haben und in der
Nähe sein; es spricht mit Sonos, die Uhr nur mit dem Handy):

- **Raumliste:** Raum zum Steuern wählen; laufende Räume sind markiert.
- **Musik hierher verschieben:** laufende Musik in einen anderen Raum schicken.
- **Wiedergabe:** Cover bzw. Senderlogo als Hintergrund, Titel, Künstler,
  Zurück/Abspielen/Weiter, **Lautstärke über die Lünette** bzw. Drehkrone.
- **Kachel** neben dem Zifferblatt: Bernos, Raum, Titel mit Cover im Hintergrund und
  Steuerknöpfe – ohne die App zu öffnen.
- **Komplikation** fürs Zifferblatt (kurzer oder langer Text) mit dem laufenden Titel.
- **Favoriten** über den Stern neben dem Raum abspielen.

**Einschränkung:** Das Medien-Symbol unten auf dem Zifferblatt gehört zur System-Mediensteuerung
von Wear OS und öffnet deren Player, nicht Bernos. Wear OS übernimmt die Wiedergabe des Handys
automatisch; bis Wear OS 6 lässt sich das für eine einzelne App nicht abschalten (Samsung bietet
nur den globalen Schalter "Medienelemente anzeigen", der das Symbol nicht entfernt). Bernos
öffnest du über die App-Liste, die Kachel oder die Komplikation. Ab Wear OS 7 kann das System
Bernos statt des Players öffnen; die Uhr-App ist dafür bereits angemeldet.

Die Ace-Kopfhörer lassen sich nicht über die App umschalten: Sonos bietet für
„TV Audio Swap“ keine Schnittstelle. Siehe [docs/ROADMAP.md](docs/ROADMAP.md).

## Aufbau

| Modul | Inhalt |
|---|---|
| `sonos-core` | Reines Kotlin ohne Android-Abhängigkeiten: Suche, SOAP-Befehle, Auswertung von Titeln und Raumaufteilung, UPnP-Ereignisse, `SonosController` als zentrale Steuerung. Mit Unit-Tests. |
| `app` | Android-App (Jetpack Compose), `PlaybackService` für die Mediensitzung, Brücke zur Uhr. |
| `wear-protocol` | Reines Kotlin: Zustand und Befehle zwischen Handy und Uhr samt Binärformat. Mit Tests. |
| `wear` | Wear-OS-App (Compose for Wear OS), Kachel und Komplikation. |

Die Kommunikation läuft ausschließlich lokal im WLAN über die UPnP-Schnittstelle der
Lautsprecher (HTTP, Port 1400). Es wird kein Sonos-Konto benötigt. Handy und Uhr tauschen
sich über die Wearable Data Layer API aus.

## Version

Handy- und Uhr-App tragen dieselbe Versionsnummer (`bernos.version` in `gradle.properties`),
zu sehen am Ende der Raumliste.

## Bauen

Voraussetzungen: JDK 17 und das Android SDK (z. B. über Android Studio).

```sh
./gradlew :sonos-core:test :wear-protocol:test   # Tests von Sonos-Kern und Uhr-Protokoll
./gradlew :app:testDebugUnitTest                 # Oberflächentests Handy (Robolectric)
./gradlew :wear:testDebugUnitTest                # Oberflächen- und Kacheltests Uhr
./gradlew :app:assembleDebug                     # Handy-APK unter app/build/outputs/apk/debug/
./gradlew :wear:assembleDebug                    # Uhr-APK unter wear/build/outputs/apk/debug/
```

Jeder Push baut beide Apps außerdem auf GitHub Actions; die fertigen APKs liegen dort als
Artefakte `bernos-debug-apk` (Handy) und `bernos-wear-debug-apk` (Uhr).

## Auf dem Handy installieren

1. APK aus dem GitHub-Actions-Lauf herunterladen (oder selbst bauen).
2. Auf dem Handy öffnen und die Installation aus unbekannten Quellen erlauben.
3. Handy muss im selben WLAN wie die Sonos-Lautsprecher sein.

## Auf der Uhr installieren

Auf der Uhr unter Entwickleroptionen „Kabelloses Debugging“ einschalten, koppeln
(`adb pair <IP:Port>`) und `adb install -r wear-debug.apk`. Handy- und Uhr-App müssen mit
demselben Schlüssel signiert sein (beide aus demselben Build bzw. CI-Lauf) und sollten dieselbe
Version haben. Danach: Kachel über „+ Kachel hinzufügen“, Komplikation über „Zifferblatt
anpassen“ einrichten.

Installation per `adb` auf Xiaomi/POCO: In den Entwickleroptionen „Über USB installieren“
einschalten und die Abfrage auf dem Handy bestätigen, sonst bricht die Installation mit
`INSTALL_FAILED_USER_RESTRICTED` ab.
