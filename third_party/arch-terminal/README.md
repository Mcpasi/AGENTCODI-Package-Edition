# Arch Terminal

Native Android-Konsole für **Arch Linux ARM aarch64**, mit C++-PTY, PRoot und pacman. Android ab 9 / API 28, ausschließlich `arm64-v8a`. Keine Termux-Installation und keine Rootrechte erforderlich.

Die App zeigt echte VT-Konsolen im Vollbild: ANSI/Truecolor, UTF-8, Cursorsteuerung, alternativer Bildschirm, Scrollback, Touch-Auswahl, Tastatur und Größenänderung der PTY. Die Zusatztasten können ausgeblendet werden. Die Sitzungen gehören einem Vordergrunddienst und bleiben beim Wechsel in andere Apps oder bei ausgeschaltetem Display bestehen, solange Android den Prozess laufen lässt.

0.1.9 behebt zwei Fehler in der Sitzungsverwaltung: Schließen und Neustart warten auf die vollständige letzte PTY-Ausgabe. Werden mehrere Tabs kurz nacheinander geschlossen, beendet sich der Dienst nach dem letzten Exit-Callback zuverlässig und entfernt Benachrichtigung und CPU-Wachhaltung. Die PRoot-Bereinigung einschließlich abgekoppelter Jobs aus 0.1.8 bleibt enthalten.

Seit 0.1.7 können mehrere Terminals gleichzeitig laufen. **+** in der oberen Sitzungsleiste oder **Neue Sitzung** im Menü öffnet eine weitere Shell. Die Tabs wechseln zwischen den Sitzungen mit jeweils eigenem Prozess, Arbeitsverzeichnis, Shellzustand und Scrollback; alle verwenden dieselbe Arch-Installation. Langes Drücken auf einen Tab oder **Sitzung schließen** im Menü schließt nach Bestätigung nur diese Sitzung. **Alle Sitzungen beenden** und die Aktion in der Android-Benachrichtigung beenden sämtliche Sitzungen. Beendete Shells behalten ihre Ausgabe; Enter startet nur ihren Tab neu.

Seit 0.1.5 zeigt die Start-/Installationsansicht ein kleines pulsierendes Arch-Linux-Logo. Sobald eine Sitzung angezeigt wird, verschwindet es ohne zusätzliche Wartezeit. Die Animation pausiert im Hintergrund und beachtet deaktivierte Android-Animationen; die Befehlseingabe erhält die volle Terminalfläche.

Seit 0.1.6 bleiben Installationsbutton und Downloadhinweise beim Verbinden und beim Start einer vorhandenen Arch-Installation verborgen. Ein fehlgeschlagener Konsolenstart bietet „Konsole erneut starten“ an; „Arch installieren / fortsetzen“ erscheint ausschließlich bei bestätigter fehlender Installation.

## Bauen

Voraussetzungen: Linux x86_64, JDK 17, Python 3.12+, CMake ab 3.22, make, C++17-Compiler, curl, unzip, libarchive-Entwicklungsdateien; optional GnuPG und `bsdtar` für den vollständigen Rootfs-Test.

```bash
# Bei vorhandenem SDK/NDK werden die Caches verwendet.
# Alternativ setzen: ANDROID_HOME und ANDROID_NDK_HOME.
scripts/setup-android.sh
scripts/test.sh
scripts/build.sh
```

SDK 36, Build Tools 35.0.0, NDK r28c (`28.2.13676358`), Gradle 8.14.3 und AGP 8.11.0 sind festgelegt. `scripts/android-env.sh` findet auch den vorhandenen Buildozer-Cache. PRoot, talloc und libarchive werden mit SHA-256 geprüft und aus Quellen gebaut. Der Gradle-Wrapper prüft seinen Download ebenfalls.

Gradle läuft standardmäßig ohne dauerhaften Daemon, mit maximal 1 GiB Java-Heap, einem Worker, einem sichtbaren JVM-Prozessor und serieller Garbage Collection; parallele Projekt-Builds und Test-Forks sind deaktiviert. Native Builds verwenden standardmäßig ebenfalls einen Worker (`ARCH_BUILD_JOBS=1`), CTest läuft seriell. Für eine zusätzliche Begrenzung auf einen verfügbaren CPU-Kern können die Skripte unter Linux mit `taskset -c <Kern>` gestartet werden. Gradle und Android-Emulator auf Rechnern mit wenig RAM nacheinander ausführen; der Emulator verwendet für ressourcenschonende Tests `-cores 1` und Softwaregrafik bei deaktiviertem Vulkan.

`gradle/verification-metadata.xml` prüft die verwendeten Maven-Artefakte. Bei einem bewussten Dependency-Update die neuen Prüfsummen gegen die jeweiligen Quellen prüfen und die Metadaten zusammen mit der Versionsänderung aktualisieren.

Ergebnisse:

- `output/ArchTerminal-0.1.9-arm64-debug.apk` – installierbare, mit dem lokalen Android-Debugschlüssel signierte App.
- `output/ArchTerminal-tests.apk` – eigener Android-Instrumentationstest.
- `output/SHA256SUMS` – Prüfsummen der APKs.
- `scripts/source-bundle.sh` – App-Quellen und exakte native Quellarchive zur Weitergabe mit einer APK.

Die CI in `.github/workflows/build.yml` führt Hosttests, Java-Tests, Lint, Build und APK-Prüfung aus. Der große Rootfs-Smoke-Test ist manuell zuschaltbar. Signatur, Target 28, ARM64-ELFs und 16-KiB-Segmentausrichtung werden an der **fertigen APK** geprüft.

## Auf dem Smartphone

```bash
adb install -r output/ArchTerminal-0.1.9-arm64-debug.apk
```

App öffnen und **Arch installieren / fortsetzen** wählen. Der erste Download umfasst ungefähr 791 MiB; mindestens 5 GiB freien internen Speicher vorsehen. Das Arch-Archiv wird gegen den gepinnten SHA-256 geprüft, zunächst in ein separates Verzeichnis entpackt und erst nach vollständiger Konfiguration aktiviert. Unterbrochene Downloads lassen sich fortsetzen. Ein fehlgeschlagener Installationsversuch überschreibt kein vorhandenes Arch.

Version 0.1.4 behebt einen Startfehler aus 0.1.3: Eine fehlgeschlagene optionale Rootfs-Aktualisierung darf den Start der vorhandenen Konsole nicht verhindern. Die Konsole zeigt dazu einen Hinweis; der Reparaturlauf bleibt für einen späteren Versuch offen. Als Update über die vorhandene App installieren; Arch-Dateisystem, Pakete und eigene Konfigurationen bleiben erhalten.

Seit 0.1.3 verwendet die App den HTTPS-Paketmirror der RWTH Aachen mit dotsrc als Ausweichserver und bewahrt beim Entpacken die Verzeichnisrechte aus dem Arch-Archiv. Beim Konsolenstart wird ausschließlich der alte Princeton-Standard ersetzt; selbst gewählte Mirrors bleiben erhalten. Eine einmalige Reparatur gleicht betroffene Verzeichnisrechte mit der installierten pacman-Paketdatenbank ab. Nach einem Mirrorwechsel einmal `pacman -Syyu` ausführen, um auch zwischengespeicherte Paketlisten vollständig zu erneuern.

Die Tastaturkorrektur aus 0.1.2 bleibt enthalten: Die Konsole antippen oder im Menü **Tastatur ein / aus** wählen.

Der seit 0.1.1 enthaltene Fix für Archiv-Hardlinks bleibt enthalten. Nach einem in 0.1.0 gescheiterten Entpackvorgang **Arch installieren / fortsetzen** wählen: Ein vollständig geladenes Archiv wird weiterverwendet, das unvollständige Entpackverzeichnis wird neu aufgebaut. Die App dafür nicht deinstallieren.

Beim ersten Login richtet die Konsole den mitgelieferten Arch-Paketschlüsselbund ein. Danach:

```bash
pacman -Syu
pacman -S git vim base-devel
```

Die Daten liegen privat unter `files/arch` der App. Eine Deinstallation entfernt sie. Es gibt in dieser Version keine eingebaute Sicherung oder Dateifreigabe.

- Tippen öffnet die Bildschirmtastatur; lange drücken markiert Text zum Kopieren/Einfügen.
- ESC, TAB, CTRL, ALT und Pfeile stehen in der unteren Leiste bereit. CTRL/ALT werden durch erneutes Antippen ausgeschaltet.
- **Lautstärke leiser gedrückt halten** wirkt als CTRL. **Lautstärke lauter** öffnet das Menü, auch bei ausgeblendeter Leiste.
- Die obere Leiste bleibt auch bei ausgeblendeten Zusatztasten sichtbar; **+** öffnet eine weitere Sitzung, Antippen eines Tabs wechselt die Konsole.
- Im Menü: neue Sitzung, einzelne Sitzung schließen, alle Sitzungen beenden, Tastatur, Ctrl-C, Schriftgröße, Zusatztasten, CPU-Wachhaltung, PRoot-Kompatibilität und Lizenzen.
- `exit` beendet ausschließlich die jeweilige Shell. Enter startet ihren Tab neu; die anderen Sitzungen laufen weiter.
- Der Kompatibilitätsmodus setzt für die nächste Sitzung `PROOT_NO_SECCOMP=1`.
- Androids Meldung über das ältere Target ist wegen Target 28 zu erwarten.

## W^X und lange Laufzeiten

`compileSdk` und `targetSdk` erfüllen verschiedene Aufgaben: kompiliert wird gegen SDK 36; das Target bleibt bewusst auf **28**. Android untersagt ab Target 29 die direkte Ausführung von Dateien aus dem beschreibbaren App-Verzeichnis. Genau dort müssen pacman und die installierten Arch-Binaries liegen. Das ist der dokumentierte Kompatibilitätsweg, kein Kernel- oder SELinux-Patch. PRoot und sein Loader werden zusätzlich als native APK-Dateien vom Paketinstaller entpackt. [Android: Ausführungsbeschränkung](https://developer.android.com/about/versions/10/behavior-changes-10#execute-permission), [Termux-Ausführungsumgebung](https://github.com/termux/termux-packages/wiki/Termux-execution-environment).

Die App hat **keine Befehls-, Leerlauf- oder Ausgabelaufzeitgrenze**. 10.000 Scrollback-Zeilen und 256 KiB Transportpuffer begrenzen den Speicherverbrauch; zusätzliche Ausgabe verdrängt alte Anzeigezeilen bzw. erzeugt Backpressure, ohne die PTY zu beenden. Eingaben werden getrennt geschrieben, damit ein nicht lesender Prozess die Oberfläche nicht blockiert. Ein voller Eingabepuffer wird gemeldet und beendet die Sitzung ebenfalls nicht. pacmans `DisableDownloadTimeout` verhindert dessen Geschwindigkeits-/Downloadfristen.

Der Arch-Installer hat einen 20-Sekunden-Verbindungsaufbauversuch und keinen Lese- oder Gesamtzeitdeckel. Ein Verbindungsfehler ist erneut versuchbar und betrifft keine laufende Konsole. Prüfsummenfehler verhindern die Aktivierung eines falschen Arch-Archivs.

Vordergrunddienst und abschaltbarer, während der Arbeit gehaltener CPU-WakeLock unterstützen lange Sitzungen. Androids Prozessbeendigung, Hersteller-Energiesparregeln und Neustarts können eine unprivilegierte App weiterhin beenden. Erfolgreiche Geräte-Rückmeldungen für 0.1.6 und 0.1.8 sind dokumentiert; Langzeitverhalten unter Hersteller-Energiesparregeln und die Änderungen in 0.1.9 bleiben auf Geräten zu prüfen. Target 28 ist keine Google-Play-Veröffentlichungsstrategie. Siehe [offene Arbeiten](docs/OFFEN.md).

## Tests

```bash
scripts/test.sh                   # C++-Integration, neue Java-Tests, originale Emulator-Tests, Lint
scripts/test-rootfs.sh            # ca. 791 MiB, GPG+SHA, Entpacken, echtes ARM64-Bash/pacman unter PRoot/QEMU
scripts/test-rootfs.sh --network  # wegwerfbares Test-Rootfs: git, mc, nodejs, npm, tree installieren und prüfen
scripts/test-device.sh            # eigenes Test-APK auf verbundenem ARM64-Android
scripts/test-device.sh --rootfs   # zusätzlich PRoot und pacman in bereits installiertem Arch
```

Details und tatsächlich ausgeführte Prüfungen stehen in [TESTS.md](docs/TESTS.md). Test-Harnesses haben Fehlerfristen, damit eine fehlerhafte Testsuite beendet werden kann; diese Fristen sind kein Bestandteil der App.

Das Arch-Image ist SHA-256-gepinnt und wurde mit dem offiziellen Fingerprint `68B3537F39A313B3E574D06777193F152BDBE6A6` geprüft. Der Spiegel verwendet einen veränderlichen `latest`-Dateinamen: ändert sich sein Inhalt, muss eine neue Version ausdrücklich geprüft und `scripts/rootfs.lock.json` aktualisiert werden. Die App akzeptiert keinen neuen Hash automatisch. [Arch-Downloads und Signaturen](https://archlinuxarm.org/about/downloads).

Architektur: [ARCHITEKTUR.md](docs/ARCHITEKTUR.md). Komponenten und Lizenzen: [NOTICE.md](NOTICE.md).
