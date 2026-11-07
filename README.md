# Kaffee-Bestand

Installierbare, offline-fähige React-PWA für 96 nummerierte Tiefkühlgläser und den vollständigen Weg jeder Kaffeecharge. Der App-Code kann öffentlich auf GitHub Pages liegen; persönliche Daten bleiben als JSON-Datei im eigenen Google Drive.

## Lokal starten

```bash
npm install
copy .env.example .env.local
npm run dev
```

Für den reinen lokalen Betrieb ist keine Google-Konfiguration nötig. Änderungen werden dann in IndexedDB gespeichert und können als JSON oder CSV heruntergeladen werden.

## Google Drive einrichten

1. In der Google Cloud Console ein Projekt erstellen und die Google Drive API aktivieren.
2. Einen OAuth-Client vom Typ „Webanwendung“ anlegen.
3. Die lokale URL und die spätere GitHub-Pages-URL als autorisierte JavaScript-Ursprünge eintragen.
4. Die Client-ID als `VITE_GOOGLE_CLIENT_ID` in `.env.local` setzen.
5. Für GitHub Pages dieselbe ID als Repository-Variable `VITE_GOOGLE_CLIENT_ID` hinterlegen.

Die App fordert ausschließlich `drive.file` an und kann damit nur die von ihr erzeugten oder ausdrücklich freigegebenen Dateien verwalten. Sie erstellt den sichtbaren Ordner `Kaffee-Bestand` samt `Sicherungen` selbst.

## Prüfen und veröffentlichen

```bash
npm test
npm run build
```

Nach einem Push auf `main` oder `master` baut die enthaltene GitHub-Action die App und veröffentlicht `dist` auf GitHub Pages. In den Repository-Einstellungen muss Pages als Quelle „GitHub Actions“ verwenden.

## Datenprinzip

`kaffee-bestand.json` enthält Einstellungen und ein unveränderliches Ereignisjournal. Der aktuelle Bestand wird daraus abgeleitet. Fehler werden durch eine begründete Gegenbuchung korrigiert; es gibt keine harten Löschungen. Zusätzlich entstehen vor Änderungen höchstens einmal täglich datierte Drive-Sicherungen.

## Verteilungsregeln

- Kategorie A nutzt vorrangig M-Gläser, behält aber immer mindestens eine S-Portion derselben Charge.
- Daily Driver nutzt vorrangig L-Gläser, behält aber immer mindestens ein M-Glas derselben Charge.
- Kleine Restmengen werden als eigene Teilfüllung dokumentiert, statt unzugeordnet außerhalb der Gläser zu bleiben.
- `Daily · gemahlen · M → S` ist für häufig genutzten gemahlenen Kaffee gedacht, der zunächst in M gelagert und beim Öffnen portionsweise nach S umgefüllt wird.

## Native Android-App

Im Ordner [`android/`](android/) liegt die native App (Kotlin, Compose, Room). Sie synchronisiert das Journal mit TESS auf dem Pi statt mit Google Drive; Kopplung, Signaturschlüssel und Releases beschreibt [`android/README.md`](android/README.md). Die Regeln werden über gemeinsame Golden-Fälle (`src/golden/golden.test.ts`) mit dieser Web-App und TESS gleich gehalten.
