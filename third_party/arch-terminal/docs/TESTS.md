# Testkonzept und Stand

Stand: Version 0.2.0, 8. November 2026. Die Host-Prüfungen verwenden Linux x86_64 mit JDK 17, Android SDK 36 und NDK r28c. Ein Android-11-Emulator (API 30, AOSP x86_64) dient den Start-/JNI-, Tastatur-, Sprach- und Mehrsitzungsregressionen. Diese Prüfungen ersetzen keine ARM64-/PRoot-Geräteabnahme.

## Wiederholbare Suite

`scripts/test.sh` führt diese Prüfungen aus:

| Bereich | Prüfung |
| --- | --- |
| Native PTY, CTest | stdin/stdout/stderr sind TTYs; controlling terminal; Anfangsgröße; UTF-8-Eingabe; Resize; gleichzeitige PTYs mit getrennter Ein-/Ausgabe und Geometrie sowie Weiterlaufen nach dem Schließen der anderen Sitzung; stille, weiterhin bedienbare Shell; Ctrl-C; 32 MiB Ausgabe; SIGKILL/Exitcodes; fehlgeschlagenes exec/chdir und anschließender erfolgreicher Neustart |
| Nativer Entpacker, CTest | Gzip/Tar; ausführbare Dateien; Verzeichnismodi bei umask 0077 einschließlich privater/schreibgeschützter Verzeichnisse, Sticky-/Setgid-Bits und später Elterneinträge; sichere/idempotente mtree-Bestandsreparatur samt Konflikten und maskierten Pfadnamen; relative und absolute Guest-Symlinks; Hardlink-Dateikopien samt Vorwärtsreferenzen/Ketten; Geräte überspringen; `..`/absolute Dateipfade ablehnen; Symlink-Ausbruch; fehlende/zyklische Hardlink-Ziele; doppelte Dateien; Abbruchsignal |
| Android-Hardlink-Sperre, CTest | gleiche Entpacktests mit `link` und `linkat` durch seccomp auf EACCES gesperrt; normaler Probe-Hardlink muss tatsächlich scheitern |
| App-Java, JUnit | Argumente mit Leerzeichen; separate Host-/Guest-Umgebung; expliziter seccomp-Kompatibilitätsmodus; pacman-Signaturpflicht und Konfiguration; unvollständiges Rootfs ablehnen; einmaliger Mirror-/Rechte-Upgrade mit Erhalt eigener Mirrorlisten/Shellprofile und Wiederaufnahme nach Fehlern; schreibgeschütztes Staging ohne Symlink-Folgen aufräumen |
| Download-Java, JUnit | echte lokale HTTP-Verbindungen: Range-Fortsetzung, ignorierte Range mit sauberem Neustart, Verbindungsabbruch und Wiederaufnahme, falsche Content-Range, Abbruch mit erhaltenem Teilarchiv |
| Sitzungsverwaltung, JUnit | unabhängiger Shellzustand und Auswahl; Start-/Neustartfehler; stabile Terminalnummern; gezieltes Schließen und benachbarte Auswahl; Hintergrundende mit erhaltenem Transcript; Neustart nur des beendeten Tabs; verspätete Callbacks; unveränderliche Listensnapshots; gemeinsame Bereinigung; Prozessende vor vollständigem Ausgabedrain beim Schließen, Neustart und gemeinsamen Beenden |
| Eingabequeue, JUnit | kopierte Eingabedaten und FIFO; voller Puffer blockiert/beendet die Sitzung nicht; Writer wird beim Schließen geweckt |
| Upstream-Emulator, JUnit | vorhandene VT-/Unicode-/Cursor-/Farb-/Scroll-/Escape-Sequenz-Regressionssuite |
| Python | gepinnte native Inputs, Rootfs-Architektur/Schlüssel/Hash und Gradle-Prüfsumme; vollständige deutsche/englische Ressourcen und identische Formatargumente |
| Android Lint | API-Verwendung, Ressourcen und App-Code |

Das Test-APK ist eine weitere, separat auf einem Android-Gerät auszuführende Suite. Die dokumentierten Zählungen und Ergebnisse früherer Versionen stehen in den jeweiligen Abschnitten.

## Mirror und Sprache 0.2.0

Die Java-Regressionen decken die Migration der früheren RWTH-/dotsrc- und Princeton-Standards bei bereits vorhandenem Verzeichnis-Erfolgsmarker ab. Eigene Server, zusätzliche Include-Anweisungen, Symlinks, unveränderte Paket-Signaturkonfiguration und nachträgliche manuelle Einstellungen bleiben erhalten. Die Shell-Migration erhält eigene Profilinhalte, ist idempotent und folgt keinen Profilsymlinks. Die erzeugten Profile werden mit `bash -n` geprüft; die Begrüßung wird tatsächlich in beiden Sprachen ausgeführt und muss `pacman -Syyu` nennen. Weitere Fälle prüfen gespeicherte Sprachcodes, ungültige Präferenzen, englische UTF-8-Guest-Variablen, laufzeitabhängige Download-Fehlersprache und sprachunabhängige Fortschrittswerte.

`python3 scripts/test-mirrors.py` prüft die in der App definierten HTTPS-Mirrors nacheinander. Für beide lädt es `core`, `extra`, `alarm` und `aur` für aarch64, liest die tatsächlichen Dateinamen von `bash`, `pacman`, `git`, `mc`, `nodejs`, `npm` und `tree` aus den Datenbanken und erwartet HTTP 200 für jedes Paket und seine Signatur. Es führt keinen Git-Befehl und keine Pakettransaktion auf dem Host aus.

Die Android-Instrumentation prüft über das tatsächliche Menü den Wechsel Deutsch → English → Deutsch. Nach dem Activity-Neuaufbau müssen die gespeicherte Präferenz, Oberfläche und Dienstbenachrichtigung übereinstimmen; derselbe PTY-Prozess mit identischer PID, Shell-Variable und Scrollback muss weiterarbeiten. Anschließend wird der englische Prozessende-Hinweis geprüft. Der Fall lässt sich mit `adb shell am instrument -w -r -e languageRegression true de.archterminal.test/de.archterminal.TerminalInstrumentation` einzeln ausführen.

Die vollständige Host-Suite besteht mit 192 Java-Tests (44 App-Tests und 148 Emulator-/Eingabequeue-Tests), 4 Python-Tests und 3 nativen CTest-Läufen. Die abschließenden App-Tests und Lint sind in `output/tests/0.2.0/app-java-lint-final.txt` protokolliert: keine Fehler, drei bekannte Lint-Warnungen. Der Live-Mirrortest bestätigt beide Server einschließlich aller geprüften Paketdateien und Signaturen (`output/tests/0.2.0/mirrors.txt`). Die vollständige Android-Instrumentation besteht einschließlich des Sprachwechsels (`output/tests/0.2.0/final-stable/android.txt`). Der Test-Runner setzt für seine bisherigen deutschen Menüprüfungen vorübergehend Deutsch und stellt anschließend die ursprüngliche Präferenz wieder her. Der abschließende ARM64-Build samt APK-Prüfung ist erfolgreich (`output/tests/0.2.0/build-final.txt`).

Die Prüfungen, Gradle-Builds und Emulatorläufe wurden nacheinander auf CPU 0 ausgeführt: `ARCH_BUILD_JOBS=1`, `CMAKE_BUILD_PARALLEL_LEVEL=1`, `CTEST_PARALLEL_LEVEL=1`, ein Gradle-Worker und `-XX:ActiveProcessorCount=1 -XX:+UseSerialGC`. Der Emulator verwendet einen virtuellen Kern; tatsächliche Host-Affinität, Gastkerne und Speicherverbrauch stehen unter `output/tests/0.2.0/final-stable/`. Ein Zwischenlauf wurde durch einen schon vor Testbeginn sichtbaren Android-System-ANR-Dialog am Fensterfokus gehindert (`final/preflight.xml`, `final/failure-logcat.txt`). Die Wiederholung verwendet unveränderte APKs und unveränderte Prüfkriterien; die Emulatorvorbereitung bestätigt ausschließlich die Systemdialoge „System UI isn't responding“ und „Process system isn't responding“ mit „Wait“. Der erste erfolgreiche Gesamtlauf bleibt unter `after/` erhalten. Die Smartphone-Abnahme von 0.2.0 bleibt offen.

## Sitzungslebenszyklus 0.1.9

Beide Fehler wurden vor ihrer jeweiligen Korrektur reproduziert. Die Android-Nachweise verwenden die gesicherte, unveränderte 0.1.8-App; App- und Test-APK sind mit SHA-256 unter `output/tests/0.1.9/reproduction-apks.sha256` festgehalten.

- **Prozessende vor vollständiger Ausgabe:** Ein Android-Shell-Kind hält die PTY offen, während der Elternprozess bereits von `waitpid` eingesammelt ist. Der Test prüft ausdrücklich `processEnded=true`, `isRunning()=false`, einen noch offenen Reader und den ausstehenden Exit-Callback. Schließen und Neustart verwerfen beziehungsweise ersetzen den Eintrag in 0.1.8 vorzeitig. Die gezielt fehlgeschlagenen Nachweise stehen in `output/tests/0.1.9/before/drain-close.txt` und `drain-restart.txt`. Nach Freigabe des Kindes muss `DEFERRED_TAIL` vollständig ankommen; erst der abschließende Callback darf das Schließen abschließen oder einen Neustart ermöglichen. Drei zusätzliche JUnit-Fälle für Schließen, Neustart und gemeinsames Beenden scheitern ebenfalls am ursprünglichen Manager (`session-manager-before.txt`). Bestehende Tests für bereits vollständig beendete Shells liefern nun auch ausdrücklich ihren Exit-Callback.
- **Alle Tabs schließen, bevor ein Exit-Callback eintrifft:** Zwei echte PRoot-Sitzungen samt abgekoppelten Kindern werden in demselben Hauptthread-Durchlauf einzeln geschlossen. Der unveränderte Dienst bleibt dabei fälschlich außerhalb seines Stop-Zustands, weil beide Einträge zunächst in der Liste bleiben. Der Test scheitert mit `Closing every tab before exit callbacks must stop the service` (`output/tests/0.1.9/before/overlapping-close.txt`). Die Regression prüft den gebundenen Dienst direkt und erwartet nach beiden tatsächlichen Exits eine leere Sitzungsliste ohne Benachrichtigung und WakeLock; die bisherigen Menütests prüfen weiterhin den Abschluss der Activity.

Die neuen Android-Fälle lassen sich nach Installation von App und Test-APK einzeln ausführen:

```bash
adb shell am instrument -w -r -e sessionRegression drain-close de.archterminal.test/de.archterminal.TerminalInstrumentation
adb shell am instrument -w -r -e sessionRegression drain-restart de.archterminal.test/de.archterminal.TerminalInstrumentation
adb shell am instrument -w -r -e sessionRegression overlapping-close de.archterminal.test/de.archterminal.TerminalInstrumentation
```

Ohne `sessionRegression` bleiben sie Bestandteil der vollständigen Instrumentation. Für den Vorher-/Nachher-Vergleich wird dasselbe gesicherte Test-APK gegen die alte beziehungsweise korrigierte App verwendet. Es wird keine Arch-Installation verändert.

Mit den Korrekturen bestehen alle 180 Java-Tests, darunter die 14 Sitzungsmanager-Tests, sowie 3 native CTest-Läufe, 3 Python-Tests und Android Lint. Es gibt keine Testfehler oder übersprungenen Java-Tests; Lint meldet ausschließlich die vier unten beschriebenen bestehenden Hinweise. Protokoll: `output/tests/0.1.9/host-java-lint.txt`, Zählung: `test-summary.json`.

Auch die vollständige Android-Instrumentation besteht mit `archterminal.success=true`, einschließlich aller neuen Regressionen, Menü-/Benachrichtigungsstopp, Logo, Tastatur und Mehrsitzungsnavigation (`output/tests/0.1.9/after/android.txt`). Der erste Gesamtlauf scheiterte nach erfolgreichen neuen Regressionen beim vorhandenen Tastaturtest mit `Menu did not reopen keyboard` (`after-window-animations/android.txt`). Die Wiederholung verwendet dieselben unveränderten APKs und dieselben Prüfkriterien; lediglich `window_animation_scale` und `transition_animation_scale` des Testemulators stehen auf 0. `animator_duration_scale=1` erhält die geprüfte Logoanimation. Der erste fehlgeschlagene Lauf samt Logcat bleibt erhalten.

Hosttests, Builds und Emulatorläufe erfolgten nacheinander auf CPU 0 unter `nice -n 10`: `ARCH_BUILD_JOBS=1`, `CMAKE_BUILD_PARALLEL_LEVEL=1`, `CTEST_PARALLEL_LEVEL=1`, ein Gradle-Worker, keine parallelen Test-Forks und Java mit `-XX:ActiveProcessorCount=1 -XX:+UseSerialGC`. Der Emulator verwendet einen virtuellen Kern, 1 GiB Gast-RAM und Softwaregrafik ohne Vulkan; Host-/Gast-Kern und Speicherverbrauch sind unter `output/tests/0.1.9/after/` protokolliert. Die ARM64-APK ist auf Version 0.1.9 / versionCode 10, Signatur, ZIP-/ELF-Ausrichtung und Laufzeitdateien geprüft (`output/tests/0.1.9/build.txt`). Die erneute Geräteprüfung dieser Version bleibt offen.

## Sitzungen beenden 0.1.8

Die Android-Instrumentation verwendet für die Stop-Regressionen den gepinnten PRoot samt Loader aus der APK und das produktive Stop-Backend des Dienstes. Innerhalb von PRoot läuft eine Android-Shell, die SIGTERM ignoriert und zusätzlich einen per setsid abgekoppelten Kindprozess startet. Eine Arch-Installation wird dafür weder benötigt noch verändert. Die vorherigen einfachen Android-Shell-Fixtures bleiben für Tastatur und Tabnavigation erhalten.

- Menü: Zwei PRoot-Sitzungen öffnen, die ausgewählte Sitzung über „Sitzung schließen“ und die Bestätigung beenden, das Ende ihres abgekoppelten Kindes prüfen und in der anderen Shell weiterarbeiten. Danach die letzte Sitzung genauso schließen; Activity, Benachrichtigung und WakeLock müssen enden.
- Benachrichtigung: Mit zwei PRoot-Sitzungen die Activity in den Hintergrund schicken und den echten „Alle beenden“-PendingIntent aus der publizierten Benachrichtigung auslösen. Beide Tracer und ihre abgekoppelten Kinder müssen beendet sein. Ein zusätzlicher Testclient hält die Dienstbindung, während das Verschwinden von Benachrichtigung und WakeLock geprüft wird. Ein wiederholter Stop darf keine leere Benachrichtigung zurückbringen.

Die Ursache in 0.1.7 ist im gepinnten PRoot-Signalhandler nachvollziehbar: SIGTERM wird ignoriert; SIGQUIT ruft kill_all_tracees auf und lässt die Ereignisschleife aufräumen. Der Dienst wählt jetzt SIGQUIT. Es wird keine Befehls-, Leerlauf- oder Ausgabefrist eingeführt.

Die vollständige Android-Instrumentation besteht mit archterminal.success=true, einschließlich beider neuen Stop-Regressionen sowie aller bisherigen Start-, Tastatur- und Mehrsitzungstests. Protokoll: output/tests/0.1.8/android.txt. Auch 177 Java-Tests, 3 native CTest-Läufe, 3 Python-Tests und Android Lint sind erfolgreich. Die ARM64-APK ist auf Version 0.1.8 / versionCode 9, Signatur, ZIP-/ELF-Ausrichtung und Laufzeitdateien geprüft; Protokolle: output/tests/0.1.8/host-java-lint.txt und build.txt.

Tests und Builds laufen weiterhin nacheinander mit einem CPU-Kern und einem Worker. Der erfolgreiche Android-11-Emulatorlauf verwendet einen virtuellen Kern, auf Host und Gast CPU 0, Softwaregrafik und deaktiviertes Vulkan. Wegen belegtem Speicherdruck mit 768 MiB und Systemserver-Watchdogs erhält der Testgast 1 GiB RAM; der Host-Spitzen-RSS beträgt 1.568.184 KiB, bei mindestens 763.852 KiB verfügbarem Host-Speicher. Die Startprüfung verwendet die erreichbare Oberfläche anstelle der globalen Broadcast-Leerlaufabfrage. Messungen und Startdiagnosen stehen unter output/tests/0.1.8/.

## Mehrere Sitzungen 0.1.7

Die Android-unabhängige JUnit-Suite prüft den Lebenszyklus über ein austauschbares Backend. Insbesondere dürfen ein fehlgeschlagener neuer PTY-Start, ein fehlgeschlagener Neustart und verspätete Exit-Callbacks weder die Auswahl noch andere laufende Sitzungen verändern. Schließende Prozesse bleiben bis zum Exit-Callback verwaltet; das gemeinsame Schließen wartet auf alle Prozesse. Natürlich beendete Tabs behalten ihre Ausgabe und werden beim Neustart an derselben Position unter derselben Nummer ersetzt.

Die Android-Instrumentation verwendet bis zu drei parallele temporäre JNI-PTYs mit Android-Shells sowie einen anschließenden Tab-Neustart, ohne ein Arch-Dateisystem zu installieren oder zu verändern. Sie prüft die tatsächlichen Plus- und Menü-Aktionen, getrennte Shellvariablen und Ausgabe, IME-Eingabe, Tabwechsel und Schließen, Fokus und zurückgesetzte CTRL-Zustände, Navigation bei ausgeblendeten Zusatztasten, Hintergrundausgabe sowie Dienst-Neuanbindung mit erhaltener Auswahl. Das Schließen eines Terminals und der natürliche Exit eines Hintergrundterminals müssen die anderen Shells und deren WakeLock erhalten. Enter startet ausschließlich den beendeten Tab unter derselben Nummer neu. Nach dem letzten Testprozess dürfen weder verwaltete Terminals noch ein WakeLock zurückbleiben. Bestehende Start-, Logo- und Tastaturregressionen bleiben enthalten.

Für 0.1.7 werden Tests und Builds nacheinander unter `taskset -c 0 nice -n 10` ausgeführt: ein CPU-Kern, `ARCH_BUILD_JOBS=1`, `CMAKE_BUILD_PARALLEL_LEVEL=1`, `CTEST_PARALLEL_LEVEL=1`, ein Gradle-Worker, keine parallelen Test-Forks und Java mit `-XX:ActiveProcessorCount=1 -XX:+UseSerialGC`. Der Emulator wird anschließend mit einem virtuellen Kern, 768 MiB Gast-RAM und Softwaregrafik bei deaktiviertem Vulkan gestartet. Die Mehrsitzungsabnahme mit Arch/PRoot auf ARM64-Geräten bleibt separat erforderlich.

Die vollständige Android-Instrumentation besteht mit `archterminal.success=true`, einschließlich Plus-/Menü-Erzeugung, IME, Tabwechsel, Hintergrund/Rückkehr, isoliertem Schließen, natürlichem Hintergrundende, Enter-Neustart und WakeLock-Freigabe. Protokoll: `output/tests/0.1.7/android.txt`. Der erfolgreiche Emulatorlauf verwendet nachweislich nur CPU 0 auf Host und Gast; sein Spitzen-RSS beträgt 1.289.692 KiB. Nach einem anfänglichen System-UI-ANR wird vor der Instrumentation auf das Ende der Android-Boot-Broadcasts gewartet. Der IME-Test wartet auf das tatsächlich angehängte und vollständig aufgebaute Terminal, bevor er Eingabe sendet. Messungen und Vorprüfung liegen ebenfalls unter `output/tests/0.1.7/`.

Auch die abschließenden 177 Java-Tests, 3 nativen CTest-Läufe einschließlich gleichzeitiger PTYs, 3 Python-Tests und Android Lint bestehen. Der abschließende ARM64-Build und die Prüfung der APK auf Version 0.1.7 / versionCode 8, Signatur, ZIP-/ELF-Ausrichtung und Laufzeitdateien sind erfolgreich. Protokolle: `output/tests/0.1.7/host-java-lint.txt` und `build.txt`. Es verbleiben ausschließlich die unten dokumentierten bestehenden Lint-Hinweise.

## Installationsanzeige 0.1.6

Die Android-Instrumentation prüft an der tatsächlichen Activity, dass ohne Dienstverbindung kein Installationsbutton und kein Downloadhinweis erscheint. Anschließend werden die UI-Zustände für vorhandenes Arch vor/nach dem Verbinden, fehlgeschlagenen Konsolenstart, fehlendes Arch und laufende Installation geprüft. Vorhandenes Arch muss „Konsole wird gestartet …“ anzeigen; ein Startfehler muss seinen Fehlertext und „Konsole erneut starten“ erhalten. Nur bestätigtes fehlendes Arch bietet „Arch installieren / fortsetzen“ an. Die Zustandsprüfung verändert keine Arch-Dateien und benötigt keinen Rootfs-Download.

Mit der unveränderten 0.1.5-App scheitert derselbe Test reproduzierbar mit `Unconnected Activity offers Arch installation before checking the existing rootfs` (`output/tests/0.1.6/before-fix.txt`). Der Vergleich verwendet ein identisches Test-APK und dieselbe x86_64-JNI-Bibliothek. Der Installations-Click-Listener bleibt die ausdrückliche Freigabe des Downloads; die neue Anzeige startet keinen Download selbstständig.

Mit 0.1.6 besteht dasselbe Test-APK einschließlich der Installationszustände, Logo-, Hintergrund- und Tastaturprüfungen mit aktivierten und deaktivierten Systemanimationen (`archterminal.success=true` in beiden Läufen). Protokolle: `output/tests/0.1.6/android-animation-1.txt` und `android-animation-0.txt`. Der Emulator lief mit Vulkan aus und 768 MiB Gast-RAM; sein Spitzen-RSS lag bei 1297788 KiB. Gradle und Emulator liefen nacheinander.

## Startlogo 0.1.5

Der Android-Test zeigt die tatsächliche Startansicht und prüft das Logo-Drawing, nicht interaktive Fokus-/Accessibility-Attribute und die sichtbare Änderung der Deckkraft. Beim Wechsel auf den Android-Startbildschirm muss die Animation stoppen und ihre Deckkraft zurücksetzen; nach Rückkehr wird sie wieder aktiv. Der anschließende Wechsel zu einer temporären Android-PTY muss sofort die Konsole fokussieren und das Logo samt Animation ausblenden. Bei deaktivierten Systemanimationen wird ein vollständig sichtbares, statisches Logo erwartet. Der bestehende Tastaturtest prüft zusätzlich das ausgeblendete, gestoppte Logo vor der Befehlseingabe und führt weiterhin einen IME-Befehl bis zur echten Shell aus.

Der Hintergrundwechsel wird über Androids globale Home-Aktion ausgelöst. Der Tastatur-Menütest wartet vor der nächsten Lautstärketaste auf den tatsächlichen Fensterfokus der Activity, damit eine bereits geschlossene IME nicht mit einem noch schließenden Dialog verwechselt wird.

Für den lokalen Emulatorlauf werden Build und Emulator nacheinander ausgeführt. Gradle verwendet `--no-daemon`, maximal 1 GiB Heap, zwei Worker und keine parallelen Projekt-Builds. Die Emulatorparameter sind `-no-window -noaudio -no-snapshot -no-boot-anim -gpu swiftshader -feature -Vulkan -lowram -memory 768 -cores 2`. `-lowram` verhindert die hier beobachtete automatische Anhebung auf 2.048 MiB trotz kleinerem `-memory`-Wert. Eine Speicherüberwachung beendet den Testemulator bei mehr als 1.400.000 KiB RSS oder weniger als 384 MiB verfügbarem Host-RAM. Gast-RAM und gemessener Host-Verbrauch stehen unter `output/tests/0.1.5/`.

Die vollständige Android-Instrumentation besteht mit aktivierten und deaktivierten Systemanimationen (`archterminal.success=true` in beiden Läufen). Die erzeugte Startansicht wurde zusätzlich visuell geprüft. Der Emulator verwendete tatsächlich 768 MiB konfigurierten Gast-RAM; sein gemessener Spitzen-RSS lag bei 1.297.756 KiB (rund 1,24 GiB). Protokolle: `output/tests/0.1.5/android-animation-1.txt`, `android-animation-0.txt`, `emulator-memory.tsv` und `startup-logo.png`. Die Tests nutzen dieselbe Java-/JNI-Implementierung mit separat gebauter x86_64-Bibliothek; die ausgelieferte APK bleibt ausschließlich ARM64.

## Regression 0.1.4: Wartungsfehler blockiert Konsolenstart

Seit 0.1.3 rief die Laufzeitvorbereitung die Mirror-/Rechtereparatur vor dem PTY-Start verpflichtend auf. Ein Java-I/O-Fehler oder eine vom JNI gemeldete `IllegalStateException` landete dadurch im allgemeinen Fehlerpfad `Start fehlgeschlagen …`; die Shell wurde gar nicht gestartet. 0.1.4 behandelt ausschließlich diese Wartung als optional. Fehler bleiben als Hinweis sichtbar, und ohne Erfolgsmarker ist ein neuer Versuch möglich. Fehler beim Erzeugen der benötigten Laufzeitdateien oder beim eigentlichen Prozessstart bleiben echte Startfehler.

Der Android-Test verwendet ein temporäres Rootfs mit absichtlich abgeschnittenen gzip-mtree-Daten und einer eigenen Mirrorliste. Zunächst muss der echte JNI-Reparaturaufruf fehlschlagen. Anschließend werden dieselbe Laufzeitvorbereitung wie beim Konsolenstart und eine echte `/system/bin/sh`-PTY ausgeführt; DNS-Datei, Befehlsausgabe, unveränderter Mirror und fehlender Erfolgsmarker werden geprüft. Der Test berührt kein installiertes Arch. Beim unveränderten 0.1.3-Startpfad scheitert er mit `Optional rootfs repair blocked shell startup: … Cannot read package mtree`. Protokoll: `output/tests/0.1.4/before-fix.txt`.

Mit 0.1.4 besteht der Test auf demselben Android-11-Emulator mit unverändertem Test-APK: Laufzeitdateien werden trotz JNI-Fehler vorbereitet, die Shell führt den Prüf-Befehl aus, der eigene Mirror bleibt erhalten und der fehlgeschlagene Reparaturlauf erhält keinen Erfolgsmarker. Auch die übrige Instrumentation einschließlich TTY/UTF-8/Resize, Ausgabedrain, Ctrl-C und echter Tastaturbedienung besteht (`archterminal.success=true`). Protokoll: `output/tests/0.1.4/after-fix.txt`. Für diesen Emulator wurde dieselbe JNI-Implementierung separat für x86_64 gebaut; die ausgelieferte APK enthält ausschließlich ARM64.

Die 166 Java-Tests, 3 Python-Tests, 3 nativen CTest-Läufe und Android Lint sind erfolgreich. Alle drei ausgelieferten nativen Bibliotheken stimmen bytegenau mit 0.1.3 überein; der Produktfix beschränkt sich auf die Java-Startvorbereitung und die Ausgabe des Wartungshinweises. Die Test-Rootfs-Dateien werden anschließend wieder entfernt.

## Regression 0.1.3: Paketmirror und Verzeichnisrechte

RWTH Aachen (`https://ftp.halifax.rwth-aachen.de/archlinux-arm`) und dotsrc (`https://mirrors.dotsrc.org/archlinuxarm`) wurden mit allen vier Repository-Datenbanken und den daraus ermittelten aktuellen Paketdateien für `git`, `mc`, `nodejs` und `npm` geprüft: jeweils HTTP 200. Princeton lieferte bei dieser Prüfung ebenfalls HTTP 200; die zuvor gemeldeten 404 waren damit nicht erneut reproduzierbar. Der GeoIP-HTTPS-Endpunkt `mirror.archlinuxarm.org` scheitert weiterhin an einem TLS-Hostnamefehler. Protokoll: `output/tests/0.1.3/mirrors.txt`.

Die Rechtewarnung hat einen bestätigten Entpackerfehler als Ursache: Verzeichnis-Modi aus dem Archiv wurden ignoriert. Mit umask 0077 erhielten auch gemeinsame Paketverzeichnisse 0700 statt 0755. Der neue Regressionstest scheitert mit dem bisherigen Entpacker reproduzierbar (`archive directory mode lost under restrictive umask`), sowohl mit als auch ohne Hardlink-Sperre. Auch unter Host-umask 0022 wichen im vorhandenen vollständigen Rootfs 25 Archivverzeichnisse ab, darunter private Verzeichnisse und Sticky-/Setgid-Verzeichnisse. Die Reparatur korrigierte 12 durch lokale Paketmetadaten eindeutig belegte Verzeichnisse; nicht paketbeschriebene und widersprüchliche Angaben werden ausgelassen. Protokolle liegen unter `output/tests/0.1.3/`.

Der korrigierte Entpacker setzt endgültige Verzeichnismodi nach den Inhalten und Links. Die Regressionen prüfen außerdem unveränderte reguläre Dateien, eigene abweichende Modi, widersprüchliche Paketangaben, Symlink-Eltern, Pfad-Traversal, mtree-Standardattribute und oktal maskierte Leerzeichen. Java-Tests prüfen, dass nur der alte einzelne Standard-Mirror ersetzt wird und die Reparatur höchstens einmal erfolgreich läuft. Fehlgeschlagenes Staging mit einem 0555-Verzeichnis bleibt löschbar.

Der zusätzliche vollständige Archivabgleich fand zwei PAX-ACL-Sonderfälle: libarchive meldete für `/var/log/journal` 0755 statt 2755 und für `/var/lib/tpm2-tss/system/keystore` 0000 statt 2775. Eine gezielte Korrektur im gepinnten Tarleser bewahrt die Rechte des eigentlichen Tar-Headers, auch wenn zuvor ein PAX-ACL gelesen wurde. Eigene kleine PAX-ACL-Fixtures sichern beide Fälle ab, ohne auf Paketmetadaten angewiesen zu sein. Hosttests und APK verwenden dieselben korrigierten libarchive-Quellen; ACLs werden nicht auf Android-Nutzer/Gruppen abgebildet.

Das vollständige gepinnte Rootfs wurde anschließend mit dem korrigierten Leser, umask 0077 und gesperrten Hardlinks erneut entpackt. Alle 2.817 geprüften Archivverzeichnisse stimmen exakt mit den Tar-Modi überein, einschließlich der beiden ACL-Sonderfälle. Protokoll: `output/tests/0.1.3/full-rootfs-directory-modes.txt`. Die wiederholbare Host-/Java-/Python-/Lint-Suite und der APK-Build sind erfolgreich.

Im frischen ARM64-Rootfs wurden anschließend `git`, `mc`, `nodejs`, `npm` und `tree` einschließlich ihrer Abhängigkeiten mit pacman installiert: 22 Pakete, keine Warnung `directory permissions differ`. Die bereits im vorangegangenen Lauf vom neuen Mirror geladenen und von pacman geprüften Pakete wurden aus dem Cache weiterverwendet. Node.js 26.13.0, npm 12.2.2 und Midnight Commander 4.8.33 starten erfolgreich. Git 2.56.0-1 wurde über pacman und das ausführbare Binary geprüft; kein Git-Befehl wurde ausgeführt. Der Test setzt wie die App `TERM=xterm-256color` und prüft `mc --version` ohne verdeckende Pipeline. Installationsprotokoll: `output/tests/0.1.3/package-install.txt`; abschließende Laufzeitprüfung: `output/tests/rootfs-network.txt`.

Die fertige APK enthält die neuen Mirrors, den aktuellen nativen Build sowie den Hinweis auf die Archivleser-Korrektur. Ihr Signaturzertifikat stimmt mit 0.1.2 überein; ein Update über die bisherige APK ist möglich. Protokoll: `output/tests/0.1.3/update-signature.txt`.

## Regression 0.1.2: Bildschirmtastatur und Befehlseingabe

Der neue Instrumentationstest startet die tatsächliche `MainActivity` mit einer temporären `/system/bin/sh`-PTY. Er benötigt kein installiertes Arch und stellt die vorherige Dienst-Sitzung beim Aufräumen wieder her. Im Touch-Modus tippt er auf die Terminalansicht und prüft die sichtbare Android-Tastatur sowie die aktive IME-Verbindung. Ein über die `InputConnection` eingegebener `printf`-Befehl muss die erwartete Ausgabe in der Shell erzeugen; das Eingabeecho allein reicht nicht aus. Anschließend folgen Zurück, erneutes Antippen und der Menüschalter zum Schließen und Öffnen.

Mit dem unveränderten `MainActivity`-Code aus 0.1.1 scheitert der Lauf reproduzierbar mit `Tap did not open keyboard: focusable=false, focusableInTouchMode=false`. Die Korrektur setzt ausschließlich die beiden Fokusattribute der programmatisch erzeugten Terminalansicht. Mit dem identischen Test-APK besteht anschließend die gesamte Instrumentation einschließlich Tastatur- und Menüprüfung (`archterminal.success=true`). Vorher-/Nachher-Protokolle stehen in `output/tests/0.1.2/before-fix.txt` und `after-fix.txt`, Angaben zur isolierten Emulatorumgebung in `environment.txt` desselben Verzeichnisses. Für den Emulator wurde dieselbe JNI-Implementierung separat für x86_64 kompiliert; die ausgelieferte APK enthält weiterhin ausschließlich ARM64.

Der erste echte Android-Lauf deckte außerdem zwei Probleme im bisherigen Test-Harness auf: Der Android-Asset-Merger entpackt `.gz`-Dateien beim Bauen und entfernt diese Endung; die komprimierte Entpack-Fixture heißt deshalb jetzt `.tgz`. `test-device.sh` verwendet `am instrument -w -r`, damit das Erfolgskriterium `archterminal.success=true` neben der formatierten Textausgabe tatsächlich ausgegeben und ausgewertet wird.

## Regression 0.1.1: Entpacken ohne Hardlink-Berechtigung

Der Nutzer-Gerätetest von 0.1.0 erreichte den Arch-Download, scheiterte beim Entpacken aber mit `filesystem error ... Permission denied`. Der neue Host-Test mit gesperrten Hardlinks reproduziert beim alten Entpacker exakt `filesystem error: cannot create hard link: Permission denied`. Nach der Korrektur bestehen derselbe eingeschränkte Test und die normalen Entpacktests. Vorher-/Nachher-Protokolle: `output/tests/0.1.1/before-fix.txt` und `after-fix.txt`.

Zusätzlich wurde das vollständige gepinnte Arch-Archiv in ein neues Verzeichnis entpackt, während `link`/`linkat` EACCES liefern. Alle 1.833 Hardlink-Einträge wurden unabhängig geprüft: reguläre Dateien mit eigenen Inodes, identischen SHA-256-Inhalten und gleichen Dateirechten wie ihre Quellen (insgesamt 8.369.010 Byte Kopien). Ergebnis: `output/tests/0.1.1/full-rootfs-extraction.txt`. Die seccomp-Sperre bildet die konkrete Operation ab; weitere Android-/SELinux-Regeln sind damit nicht simuliert.

Im frisch entpackten Dateisystem starten anschließend Bash und pacman unter PRoot/QEMU; die lokale Paketabfrage für Bash und pacman ist erfolgreich. Protokoll: `output/tests/0.1.1/rootfs-smoke.txt`.

Testfristen in CTest und Instrumentation erkennen hängende Tests. Die produktive Sitzung verwendet diese Fristen nicht. Stille Host-Sitzungen werden auf Erreichbarkeit geprüft; ein achtstündiger Hardware-Dauertest steht noch aus.

## Fertige APK

`scripts/build.sh` baut die App und das Test-APK. `verify-apk.py` prüft mit Android Build Tools und direkt an den ELF-Headern:

- APK-Signatur und ZIP-Ausrichtung.
- App-ID `de.archterminal`, Minimum 28, Target 28, ausschließlich ARM64.
- JNI-Bibliothek, PRoot und Loader tatsächlich enthalten.
- ARM64-ELFs und 16-KiB-kompatible PT_LOAD-Segmente.
- Rootfs-Lock und Lizenztexte enthalten.

Die Prüfsummen stehen in `output/SHA256SUMS`. Der Debugschlüssel ist lokal erzeugt; unterschiedliche Entwicklerrechner erzeugen daher nicht automatisch identische APK-Bytes.

## Echtes Arch ARM64 auf dem Host

Ausgeführt und erfolgreich:

```bash
scripts/test-rootfs.sh --network
```

Der Test prüft den kompletten Rootfs-Download gegen SHA-256 und die offizielle RSA-Signatur, entpackt ihn mit dem produktiven C++-Code, wendet die App-pacman-Konfiguration an und verwendet den gepinnten PRoot-Quellstand als Host-Build. Auf x86_64 führt QEMU die aarch64-Binaries aus. QEMU wird aus dem mit SHA-256 geprüften Debian-Paket entnommen und nicht ins APK eingebaut.

Beobachtet: `uname -m` liefert aarch64, Bash 5.3.15 startet, pacman 7.1.0/libalpm 16.0.1 fragt die lokale Paketdatenbank ab. `pacman-key --init` und `--populate archlinuxarm` funktionieren. Repository-Datenbanken und Pakete wurden über HTTPS geladen; pacman hat Schlüsselbund und Paketintegrität geprüft. Seit 0.1.3 deckt die Paketprüfung neben `tree` auch `git`, `mc`, `nodejs` und `npm` ab. Im Test-Rootfs bleiben die Ausgaben unter `output/tests/rootfs-host.txt` und `rootfs-network.txt` nachvollziehbar.

Das erzeugte erste Loginprofil wurde zusätzlich auf Bash-Syntax geprüft und unter PRoot/QEMU ausgeführt. Der erfolgreiche Abschluss der Schlüsselinitialisierung ist als `FIRST_LOGIN_KEYS_OK` in `output/tests/first-login-host.txt` protokolliert.

Diese Pakettransaktion erfolgt ausschließlich im wegwerfbaren Test-Rootfs. Im laufenden Arch regulär `pacman -Syu` verwenden. Der Host-Test ersetzt keine Prüfung von Androids SELinux, ptrace/seccomp-Profil oder Herstellerkernel.

## Android-Instrumentation

```bash
scripts/test-device.sh
scripts/test-device.sh --rootfs
```

Die Skripte prüfen zuerst, ob ein autorisiertes ARM64-Gerät verbunden ist. Ohne Gerät brechen sie mit Exitcode 2 und klarer Meldung ab; es wird kein Bestehen vorgetäuscht. Die zweite Variante setzt ein in der App bereits installiertes Arch voraus.

Der eigene Runner testet seit 0.1.1 zuerst einen kleinen Archiv-Entpackvorgang mit Hardlink-Kette, Dateiinhalten, eigenen Inodes und Ausführungsrechten direkt in der Android-App. Danach folgen JNI-PTY, TTY-Dateideskriptoren, UTF-8, Resize, stille Sitzung, 256 KiB Ausgabe einschließlich letzter Zeilen vor Exit und Ctrl-C. Seit 0.1.2 prüft er außerdem die tatsächliche Activity samt Bildschirmtastatur und Befehlseingabe. `--rootfs` ergänzt PRoot, den glibc-Loader und pacman auf dem Android-Kernel. Ergebnisse landen in `output/tests/device.txt`. Die automatisierte Instrumentationssuite für 0.2.0 wurde noch nicht auf ARM64-Hardware ausgeführt. Frühere erfolgreiche Nutzer-Gerätetests für 0.1.6 und 0.1.8 sind in [OFFEN.md](OFFEN.md) dokumentiert.

## Lint-Hinweise

Keine Lint-Fehler. Bekannte Hinweise betreffen eine neuere Gradle-Plugin-Version, das fehlende ChromeOS-x86_64-ABI und den absichtlich zeitlich unbegrenzten WakeLock. Der WakeLock wird bei Ende/Abbruch/Service-Zerstörung freigegeben und kann im Menü deaktiviert werden. Deutsch und Englisch sind in Ressourcen ausgelagert und bleiben auch bei App-Bundle-Installationen gemeinsam verfügbar.
