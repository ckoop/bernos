# Bernos

Bernos ist eine Android-App zur Steuerung von Sonos-Lautsprechern, mit Wear-OS-Uhr als
Fernbedienung. Bernos ist ein privates Projekt und steht in keiner Verbindung zu Sonos, Inc.

## Stand: Phase 2

An echten Sonos-Lautsprechern erfolgreich getestet (Android 16, Galaxy Watch als Uhr).

- Findet die Sonos-Lautsprecher im WLAN automatisch (SSDP und mDNS); falls der Router das blockiert,
  lässt sich ein Lautsprecher per IP-Adresse hinzufügen.
- Zeigt alle Räume bzw. Gruppen an.
- **Räume gruppieren:** weitere Räume dazunehmen oder herausnehmen, Lautstärke pro Raum.
- **Musik verschieben:** die laufende Musik mit einem Tipp in einen anderen Raum schicken.
- Zeigt für den gewählten Raum Titel, Künstler, Album und **Albumcover** an, inkl. Fortschritt.
- Abspielen/Pause, nächster/vorheriger Titel, Lautstärke der Gruppe.
- Live-Aktualisierung: Die Lautsprecher melden Änderungen von selbst (UPnP-Ereignisse);
  zusätzlich fragt die App regelmäßig nach, falls Ereignisse nicht ankommen.
- Mediensitzung: Titel, Cover und Steuerung erscheinen in der Benachrichtigung, auf dem
  Sperrbildschirm und **in der Mediensteuerung der Wear-OS-Uhr**. Die Lautstärketasten des
  Handys regeln Sonos.

Die Ace-Kopfhörer lassen sich nicht über die App umschalten: Sonos bietet für
„TV Audio Swap“ keine Schnittstelle. Siehe [docs/ROADMAP.md](docs/ROADMAP.md).

## Aufbau

| Modul | Inhalt |
|---|---|
| `sonos-core` | Reines Kotlin ohne Android-Abhängigkeiten: Suche, SOAP-Befehle, Auswertung von Titeln und Raumaufteilung, UPnP-Ereignisse, `SonosController` als zentrale Steuerung. Mit Unit-Tests. |
| `app` | Android-App (Jetpack Compose) und `PlaybackService` für die Mediensitzung. |

Die Kommunikation läuft ausschließlich lokal im WLAN über die UPnP-Schnittstelle der
Lautsprecher (HTTP, Port 1400). Es wird kein Sonos-Konto benötigt.

## Version

Handy- und Uhr-App tragen dieselbe Versionsnummer (`bernos.version` in `gradle.properties`),
zu sehen am Ende der Raumliste.

## Bauen

Voraussetzungen: JDK 17 und das Android SDK (z. B. über Android Studio).

```sh
./gradlew :sonos-core:test        # Tests des Sonos-Kerns
./gradlew :app:testDebugUnitTest  # Oberflächentests (Robolectric)
./gradlew :app:assembleDebug      # APK unter app/build/outputs/apk/debug/
```

Jeder Push baut die App außerdem auf GitHub Actions; die fertige APK liegt dort als
Artefakt `bernos-debug-apk` zum Herunterladen.

## Auf dem Handy installieren

1. APK aus dem GitHub-Actions-Lauf herunterladen (oder selbst bauen).
2. Auf dem Handy öffnen und die Installation aus unbekannten Quellen erlauben.
3. Handy muss im selben WLAN wie die Sonos-Lautsprecher sein.

Installation per `adb` auf Xiaomi/POCO: In den Entwickleroptionen „Über USB installieren“
einschalten und die Abfrage auf dem Handy bestätigen, sonst bricht die Installation mit
`INSTALL_FAILED_USER_RESTRICTED` ab.
