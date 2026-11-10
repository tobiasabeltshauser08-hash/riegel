# Riegel

Android-App gegen zu viel Social Media: Jede Ablenkungs-App bekommt ein **Tageslimit**. Ist die Zeit für heute aufgebraucht,
schiebt Riegel den Riegel vor – die App ist bis Mitternacht gesperrt. Alles andere auf dem Handy bleibt frei
(Telefon, Nachrichten, Wecker, Musik, Karten …).

- **Tageslimits pro App:** 5 bis 120 Minuten. Beim Einrichten sind die installierten bekannten Social-Media-Apps
  (Instagram, TikTok, YouTube, Snapchat, Facebook, X, Reddit, Threads, Pinterest, Twitch, BeReal, Tumblr, 9GAG) mit 20 min vorgewählt;
  jede andere App lässt sich dazunehmen.
- **Sperrbildschirm:** „Zeit für heute aufgebraucht – Instagram: 20 von 20 min genutzt“ mit „Zum Startbildschirm“.
  Die Zurück-Taste wirkt dort nicht. 2 Minuten vor dem Limit kommt eine Warnung.
- **Lockern kostet Mühe:** Ein Limit erhöhen oder entfernen und „Mehr Zeit für heute“ (+10 min) gehen nur über eine Hürde:
  entweder einen **zufälligen Code aus 32 Zeichen abtippen** (Einfügen gesperrt) oder **5 Minuten warten**, während Riegel offen bleibt
  (wer die App wechselt, fängt von vorn an). Strenger stellen geht immer sofort.
- **Statistik:** Social-Media-Zeit heute (gesamt und je App mit Balken), Diagramm der letzten 7 Tage mit Durchschnitt,
  Ø pro Tag je App, Tabelle, Serie „X Tage in Folge alle Limits eingehalten“.
- **Läuft immer:** Riegel wacht im Hintergrund (dauerhafte Benachrichtigung „Social Media heute: 34 min · 4 Apps begrenzt“),
  auch wenn die App weggewischt wird, und startet nach einem Neustart des Handys und nach Updates von selbst wieder.

Alle Daten bleiben auf dem Handy. Riegel hat keinen Server und schickt nichts weg.

## Installieren (Android)

1. Am Handy die APK herunterladen: **https://github.com/tobiasabeltshauser08-hash/riegel/releases/latest/download/riegel-debug.apk**
   (oder `dist/riegel-debug.apk` per USB, Cloud oder Mail aufs Handy kopieren) und antippen.
2. Android fragt einmal, ob die App (z. B. „Dateien“ oder Chrome) **unbekannte Apps installieren** darf → erlauben.
3. Riegel öffnen. Beim ersten Start führt ein Assistent **einmalig** durch die Berechtigungen, danach wählst du die Limits:

| Berechtigung | Wofür | Pflicht |
|---|---|---|
| Nutzungszugriff | messen, wie lange welche App offen ist, und erkennen, welche gerade vorne ist | ja |
| Über anderen Apps anzeigen | Sperrbildschirm über eine App legen | ja |
| Benachrichtigungen | Anzeige im Hintergrund und Warnung 2 min vor dem Limit | nein |
| Akku-Optimierung ausnehmen | damit Hersteller Riegel nicht beenden | nein, empfohlen |

Danach fragt Riegel nicht mehr von selbst. Fehlt später eine Berechtigung, zeigt die Startseite
„Berechtigung fehlt – tippen zum Einrichten“.

**Android 13+:** Bei einer per Datei installierten App sind „Nutzungszugriff“ und „Über anderen Apps anzeigen“ zuerst
ausgegraut („Eingeschränkte Einstellung“). Dann: Einstellungen → Apps → Riegel → Menü ⋮ oben rechts →
**„Eingeschränkte Einstellungen zulassen“**, danach die Schalter einschalten.

## Grenzen

- Riegel ist eine Selbstverpflichtung, kein Kinderschutz: Wer Riegel deinstalliert oder die Berechtigungen entzieht, ist frei.
- Gesperrt wird auf App-Ebene. Social Media im Browser (z. B. instagram.com in Chrome) zählt nicht – bei Bedarf Chrome selbst
  begrenzen.
- Die Zeit wird aus Androids Nutzungsstatistik gerechnet und kann um einige Sekunden von „Digital Wellbeing“ abweichen.
  Eine gesperrte App ist kurz sichtbar (bis ca. 0,5 s), bevor der Sperrbildschirm kommt.
- Android hebt die genauen Nutzungsereignisse nur einige Tage auf. Riegel sichert deshalb jeden abgeschlossenen Tag selbst
  (bis 40 Tage), sobald du die App öffnest oder der Tag wechselt.
- Manche Hersteller (Xiaomi, Huawei, Samsung mit Energiesparmodus) beenden Hintergrund-Dienste aggressiv; dafür die
  Akku-Optimierung ausnehmen.
- Die Debug-APK ist mit einem Entwicklungsschlüssel signiert. Updates installieren nur, wenn sie auf demselben Rechner gebaut wurden.

## Vorschau im Browser

```bash
cd riegel
python3 -m http.server 8000
```

Dann **http://localhost:8000** öffnen, oder online: **https://tobiasabeltshauser08-hash.github.io/riegel/**. Im Browser läuft Riegel als **Vorschau mit Beispieldaten** (sperren kann nur die Android-App).
Wenn du später Dateien änderst, erhöhe in `sw.js` die Versionsnummer (z. B. `riegel-v1` → `riegel-v2`).

## Selbst bauen

Voraussetzungen (benutzerlokal, ohne Root): JDK 21 und Android-SDK (Commandline-Tools) mit `platforms;android-36`,
`build-tools;36.0.0` und `platform-tools`. Die SDK-Lizenzen einmal selbst annehmen: `sdkmanager --licenses`.

```bash
cd riegel
npm install                 # Capacitor (nur lokal, devDependencies)
export JAVA_HOME=~/jdk ANDROID_HOME=~/android-sdk
npm run android:build       # kopiert die Web-App nach www/, synchronisiert, baut dist/riegel-debug.apk
```

## Test

```bash
npm test                                    # Logik: Limits, Lockern, Zeittexte, Code, Statistik
cd android && ./gradlew testDebugUnitTest   # Android: Zeitmessung aus Nutzungsereignissen, Texte (JUnit)
```

## Aufbau

| Datei | Inhalt |
|---|---|
| `js/app.js` | Oberfläche: Einrichtung, Startseite mit Statistik, Limits, Mehr Zeit, Hürde |
| `js/logic.js` | Limits, Lockern erkennen, Zeittexte, Code, Statistik, Berechtigungs-Schritte (ohne DOM, getestet) |
| `js/native.js` | Brücke zur Android-App; im Browser Vorschau mit Beispieldaten |
| `android/app/src/main/java/app/riegel/guard/GuardPlugin.java` | Schnittstelle für die Web-App |
| `…/guard/GuardService.java` | Wächter im Vordergrund: misst, warnt, zeigt den Sperrbildschirm |
| `…/guard/Usage.java` | rechnet aus Androids Nutzungsereignissen die Zeit je App (getestet) |
| `…/guard/GuardState.java` | gespeicherte Limits, Extra-Minuten, Tageswerte |
| `…/guard/BootReceiver.java` | startet den Wächter nach Neustart und Update |
| `scripts/build-web.mjs` | kopiert die Web-App nach `www/` für Capacitor |
| `sw.js` | Service Worker für die Browser-Vorschau |
