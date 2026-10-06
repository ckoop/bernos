# Roadmap

## Phase 1 – Grundlage ✅
Lautsprecher finden, Wiedergabe steuern, Titel und Cover anzeigen, Live-Updates,
Mediensitzung (damit funktioniert bereits die eingebaute Mediensteuerung von Wear OS).

## Phase 2 – Räume und Gruppen
- Gruppen bilden und auflösen (`SetAVTransportURI` mit `x-rincon:<Koordinator-UUID>`,
  `BecomeCoordinatorOfStandaloneGroup`).
- Wiedergabe in einen anderen Raum verschieben.
- Lautstärke pro Raum (RenderingControl je Mitglied) zusätzlich zur Gruppenlautstärke.
- Suche zusätzlich per mDNS (`_sonos._tcp`) als zweiter Weg neben SSDP.

## Phase 3 – Eigene Wear-OS-App
- Modul `wear` mit Compose for Wear OS.
- Handy bleibt die Zentrale; Uhr spricht über die Wearable Data Layer API
  (`MessageClient` für Befehle, `DataClient` für Status und Cover).
- Raumauswahl, Cover, Lautstärke über Drehkrone/Lünette, Kachel (Tile) und Komplikation.

## Phase 4 – Sonos Ace (offen)
Sonos bietet für „TV Audio Swap“ keine öffentliche Schnittstelle; die Ace ist kein
WLAN-Lautsprecher, sondern per Bluetooth mit dem Handy verbunden. Mögliche Wege:
1. Content-Taste an der Ace gedrückt halten (funktioniert heute schon ohne App).
2. Sonos-App per Bedienungshilfe/Tasker fernsteuern lassen – fragil.
3. Bluetooth-Protokoll der Ace analysieren – aufwendig, kann jederzeit brechen.

## Hinweise
- Ab Android 17 (API 37) kann für den Zugriff aufs lokale Netz eine eigene Berechtigung
  nötig werden; beim Anheben von `targetSdk` prüfen.
