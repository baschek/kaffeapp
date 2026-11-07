# Kaffee-Bestand · Android-App

Native Android-App (Kotlin, Compose, Room) für den Kaffee-Bestand. Sie hält das Buchungsjournal lokal (offline nutzbar) und synchronisiert es mit TESS auf dem Pi, der die Quelle der Wahrheit ist. Protokoll: [`../docs/sync-protocol.md`](../docs/sync-protocol.md) (Gerät = Zugang A).

- `core/`: reines Kotlin (JVM). Zeilengetreuer Nachbau der Regeln aus `src/domain` (Reducer, Aufteilungsplaner, Glaswahl, Umfüllvorschlag), Buchungen der Dialoge, CSV-Export, Sync-Engine, signierter API-Client, Update-Prüfung. `CoffeeGoldenTest` prüft gegen dieselben Golden-Fälle wie TESS (`core/src/test/resources/coffee_golden.json`).
- `app/`: Room (Journal + Ausgang), WorkManager-Sync (alle 15 Minuten, nach jeder Buchung, beim Öffnen, per Knopf), Kopplung mit Geräteschlüssel im Android Keystore (StrongBox, wenn vorhanden), Selbst-Update aus den GitHub-Releases, Compose-Oberfläche wie die Web-App (Übersicht, Gläser, Kaffees, Verlauf, Daten).
- Alle Farben, Schriften und Abstände stehen in **einer** Datei: `app/src/main/kotlin/de/baschek/kaffee/ui/theme/KaffeeTheme.kt`. Ein Design-Durchgang ändert nur diese Datei (und bei Bedarf Bausteine in `ui/Components.kt`).

## Bauen und prüfen

JDK 17 und das Android-SDK, dann:

```bash
cd android
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Die Golden-Fälle neu erzeugen (im Wurzelverzeichnis des Repos), wenn sich Regeln in `src/domain` ändern; danach die Datei auch nach TESS (`tests/fixtures/coffee_golden.json`) kopieren:

```bash
GOLDEN_OUT=android/core/src/test/resources/coffee_golden.json npx vitest run src/golden
```

Die Room-Schemas werden nach `app/schemas` exportiert und gehören ins Repo; jede Schemaänderung braucht eine Migration und einen Test.

## Koppeln

1. Auf dem Pi: `./tessctl kaffee-pair` (ein neues Handy: `--replace`). Der Code gilt 10 Minuten.
2. In der App: Daten → „Mit TESS koppeln“, Server-Adresse (`TESS_PUBLIC_URL`) und Code eingeben.
3. Prüfen: `./tessctl kaffee-devices`. Handy verloren: `./tessctl kaffee-revoke <id>`.

Meldet die App „Verbindung getrennt – neu koppeln“, kennt der Pi das Gerät nicht mehr (widerrufen, ersetzt oder 120 Tage ungenutzt).

## Neuer Signaturschlüssel (einmalig)

Die App bekommt einen **eigenen** Schlüssel (nicht den der GymApp). Auf deinem Rechner (der Schlüssel kommt nie ins Repo):

```bash
keytool -genkeypair -v -keystore kaffee-release.jks -alias kaffee -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 kaffee-release.jks > kaffee-release.jks.b64     # macOS: base64 -i kaffee-release.jks -o kaffee-release.jks.b64
```

Dann in GitHub unter *baschek/kaffeapp → Settings → Secrets and variables → Actions* vier Repository-Secrets anlegen:

| Secret | Inhalt |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Inhalt von `kaffee-release.jks.b64` |
| `ANDROID_KEYSTORE_PASSWORD` | Passwort des Keystores |
| `ANDROID_KEY_ALIAS` | `kaffee` |
| `ANDROID_KEY_PASSWORD` | Passwort des Schlüssels (bei PKCS12 dasselbe wie oben) |

`kaffee-release.jks` und die Passwörter sicher aufbewahren (z. B. Passwortmanager): ohne denselben Schlüssel lässt sich die App später nicht mehr über die installierte Version aktualisieren.

## Release

Auf `main`: Tag `android-v<version>` pushen (`git tag android-v1.0.0 && git push origin android-v1.0.0`) oder den Workflow *Android* manuell mit `release_version` (z. B. `1.0.0`) starten. CI baut, signiert und prüft die APK und legt ein GitHub-Release mit `kaffee-<version>.apk` an. Die App findet neuere Releases selbst (Daten → „Nach Update suchen“) und prüft vor der Installation die SHA-256-Prüfsumme.
