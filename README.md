# Kaffee-Bestand

Installierbare, offline-fähige React-PWA für 96 nummerierte Tiefkühlgläser und den vollständigen Weg jeder Kaffeecharge. Die Website liegt öffentlich auf GitHub Pages (`https://baschek.github.io/kaffeapp/`); die Daten liegen auf Jonathans Pi in **TESS**, nicht im Repository.

## Betriebsarten

- **TESS (Standard):** Der Pi ist die Quelle der Wahrheit. Die Website hält eine lokale Kopie des Journals (IndexedDB) und einen Ausgang mit noch nicht bestätigten Buchungen; sie synchronisiert beim Start, nach jeder Buchung, wenn das Fenster wieder in den Vordergrund kommt, und über „Jetzt synchronisieren“ (Protokoll: [`docs/sync-protocol.md`](docs/sync-protocol.md)). Lehnt TESS eine Buchung ab, weil sie nicht zum Bestand passt, verschwindet sie lokal wieder und die Website zeigt „Buchung abgelehnt: …“ mit Grund. Offline gebuchte Einträge warten im Ausgang.
- **Notfall: Google Drive (wenn der Pi nicht erreichbar ist):** der bisherige Weg über die Datei `Kaffee-Bestand/kaffee-bestand.json` im eigenen Drive. Der Pi schreibt diese Datei jede Nacht neu und übernimmt dabei Buchungen, die nur dort stehen. Danach in „Daten“ wieder „Zurück zu TESS“ wählen. Beide Modi haben getrennte lokale Kopien.

Claude bucht über TESS direkt mit (Werkzeuge `tess_*coffee*`); die Website zeigt diese Buchungen nach der nächsten Synchronisierung.

## Anmeldung mit Passkey

1. Auf dem Pi einen Einmal-Code erzeugen: `./tessctl kaffee-passkey-code` (10 Minuten gültig).
2. Website öffnen, Server-Adresse (`TESS_PUBLIC_URL`, z. B. `https://tess.<name>.ts.net`), Code und einen Namen für den Passkey eingeben, „Passkey anlegen und anmelden“. Der Browser legt den Passkey mit Fingerabdruck oder Gesichtserkennung an.
3. Danach genügt „Mit Passkey anmelden“. Die Sitzung gilt 12 Stunden und liegt nur im `sessionStorage` dieses Tabs; die Server-Adresse merkt sich der Browser (`localStorage`), sie steht nirgends im Code.
4. Weitere Geräte: neuer Code, dann in „Daten → Verbindung → Passkey hinzufügen“. Passkeys anzeigen und sperren auf dem Pi mit `./tessctl kaffee-passkeys` und `./tessctl kaffee-passkey-revoke <id>`.

Der Passkey gehört zur Domain `baschek.github.io` (Einstellung `TESS_KAFFEE_WEB_RP_ID` auf dem Pi); TESS erlaubt Anfragen nur von den Ursprüngen in `TESS_KAFFEE_WEB_ORIGINS`.

## Lokal starten

```bash
npm install
copy .env.example .env.local
npm run dev
```

Für die Entwicklung gegen einen TESS-Testserver `http://localhost:5173` in `TESS_KAFFEE_WEB_ORIGINS` aufnehmen und als Server `http://localhost:<port>` eintragen (nur `localhost` darf ohne https).

## Google Drive für den Notfallmodus einrichten

1. In der Google Cloud Console ein Projekt erstellen und die Google Drive API aktivieren.
2. Einen OAuth-Client vom Typ „Webanwendung“ anlegen.
3. Die lokale URL und die GitHub-Pages-URL als autorisierte JavaScript-Ursprünge eintragen.
4. Die Client-ID als `VITE_GOOGLE_CLIENT_ID` in `.env.local` setzen.
5. Für GitHub Pages dieselbe ID als Repository-Variable `VITE_GOOGLE_CLIENT_ID` hinterlegen.

Die App fordert ausschließlich `drive.file` an und kann damit nur die von ihr erzeugten oder ausdrücklich freigegebenen Dateien verwalten. Sie erstellt den sichtbaren Ordner `Kaffee-Bestand` samt `Sicherungen` selbst.

## Prüfen und veröffentlichen

```bash
npm test
npm run build
```

Nach einem Push auf `main` oder `master` baut die enthaltene GitHub-Action die App und veröffentlicht `dist` auf GitHub Pages (Basis-Pfad aus dem Repository-Namen, `/kaffeapp/`). Der Service Worker cacht nur die App-Hülle, nie Aufrufe an TESS oder Google. In den Repository-Einstellungen muss Pages als Quelle „GitHub Actions“ verwenden.

## Datenprinzip

Das Journal enthält Einstellungen und unveränderliche Ereignisse; der aktuelle Bestand wird daraus abgeleitet (dieselben Regeln rechnet TESS in Python nach, geprüft mit Golden-Fällen aus `src/golden/golden.test.ts`). Fehler werden durch eine begründete Gegenbuchung korrigiert; es gibt keine harten Löschungen. Im Notfallmodus entstehen vor Änderungen höchstens einmal täglich datierte Drive-Sicherungen; im TESS-Modus sichert der Pi.

## Verteilungsregeln

- Kategorie A nutzt vorrangig M-Gläser, behält aber immer mindestens eine S-Portion derselben Charge.
- Daily Driver nutzt vorrangig L-Gläser, behält aber immer mindestens ein M-Glas derselben Charge.
- Kleine Restmengen werden als eigene Teilfüllung dokumentiert, statt unzugeordnet außerhalb der Gläser zu bleiben.
- `Daily · gemahlen · M → S` ist für häufig genutzten gemahlenen Kaffee gedacht, der zunächst in M gelagert und beim Öffnen portionsweise nach S umgefüllt wird.
