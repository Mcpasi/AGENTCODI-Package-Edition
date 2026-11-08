# Offene Arbeiten und Geräteabnahme

Stand: Version 0.2.0, 8. November 2026.

## Vor einer belastbaren Smartphone-Freigabe

Der erste Nutzer-Gerätetest mit 0.1.0 konnte Arch herunterladen, scheiterte beim Entpacken an der auf Android verbotenen Hardlink-Erstellung. Seit 0.1.1 werden dafür Dateikopien verwendet. Für 0.1.2 wurde der Tastaturfehler im Android-11-Emulator (AOSP x86_64) reproduziert und durch Aktivieren des Terminal-Touch-Fokus korrigiert. Der Emulator verwendet die echte Activity und JNI-PTY mit einer Android-Shell.

**Laut Nutzerrückmeldung wurde der Arch-Ablauf mit 0.1.6 auf einem Redmi Note 14 und einem Redmi 14C erfolgreich getestet. Auch für 0.1.8 liegt eine erfolgreiche Hardware-Rückmeldung vor.** Diese Rückmeldungen bleiben gültig; die Änderungen in 0.2.0 benötigen eine erneute Geräteprüfung.

Für 0.2.0 den Mirrorwechsel nach Update, `pacman -Syyu` und den Wechsel Deutsch/English bei laufenden Terminals auf diesen Geräten prüfen. Außerdem mehrere parallele Arch-/PRoot-Sitzungen erneut prüfen: getrennte Shellzustände und Ausgabe, Wechsel während laufender Befehle, gezieltes Schließen, gemeinsames Beenden, Hintergrund/Rückkehr und Wachhaltung bis zum letzten Prozess. Zusätzlich Tabs kurz nacheinander schließen sowie Schließen/Neustart bei ausstehender letzter Ausgabe prüfen. Die automatisierten Android-Regressionen verwenden temporäre Android-Shells und für die Stop-Tests echtes PRoot ohne Arch-Installation.

- `scripts/test-device.sh` auf ARM64 Android ausführen; danach Arch in der App installieren und `scripts/test-device.sh --rootfs` ausführen.
- Android 9/10, 13 sowie 15/16 prüfen, jeweils soweit Geräte verfügbar sind. Mindestens ein Gerät mit 16-KiB-Speicherseiten testen.
- Den ersten Arch-Download, Unterbrechung, Fortsetzung, TLS, Abbruch beim Entpacken, Speicherknappheit und Wiederaufnahme nach einem tatsächlichen App-Prozessabbruch prüfen.
- Direkt aus dem **privaten Android-App-Verzeichnis** pacman, neu installierte ELF-Dateien, Skripte und den glibc-Loader ausführen. Target 28 ist dokumentiert und im APK geprüft; OEM-/SELinux-Abweichungen sind noch nicht gemessen.
- Standard- und `PROOT_NO_SECCOMP=1`-Modus auf Herstellerkerneln vergleichen. ptrace und Prozessaufteilung müssen auf dem Gerät funktionieren.
- pacman-Schlüsselinitialisierung beim ersten Login, `pacman -Syu` und Paketinstallation auf dem Android-Kernel durchführen. Signierte Installationen von `git`, `mc`, `nodejs`, `npm` und `tree` wurden unter PRoot/QEMU geprüft; Node.js, npm und Midnight Commander starten dort. Git wurde gemäß Vorgabe nicht aufgerufen.
- Vollbild, Displayausschnitte, Gestennavigation, Gboard/Samsung-Tastatur, Querformat, Größenänderung während Vim/less und Copy/Paste auf Smartphones prüfen. Der neue Tastaturtest deckt Android 11 mit AOSP-Tastatur im x86_64-Emulator ab; andere Android-Versionen und Hersteller-Tastaturen bleiben offen.
- Mindestens acht Stunden mit stillem Befehl, großer Ausgabe, Display aus/an und wiederholtem Appwechsel laufen lassen. Android-Phantomprozessregeln und herstellerspezifische Energiesparfunktionen können trotz Vordergrunddienst und WakeLock Prozesse beenden.
- Explizites Beenden mit normalen, im Hintergrund laufenden und per `setsid` abgekoppelten Guest-Prozessen prüfen; keine verbliebenen Prozesse oder WakeLocks. Seit 0.1.8 verwendet die App PRoots SIGQUIT-Aufräumlogik. Das Beenden per Menü und Benachrichtigung einschließlich abgekoppelter Kinder ist mit echtem PRoot im Android-11-Emulator bestanden; auf den ARM64-Herstellergeräten erneut prüfen.

## Distribution und Reproduzierbarkeit

- Es liegt ein lokal signierter **Debug-Build** vor. Eigener Release-Schlüssel, signierter Release-Prozess und Veröffentlichung sind noch einzurichten. Schlüssel werden nicht aus anderen Projekten übernommen.
- Der GitHub-Actions-Workflow liegt vor, wurde in diesem neuen lokalen Repository noch nicht auf GitHub ausgeführt. Es wurde kein Remote angelegt und nichts gepusht.
- Lokal wurden vorhandene SDK-/NDK-Caches wiederverwendet. Der vollständige SDK-Neuaufbau mit `setup-android.sh` auf einem Rechner ohne diese Caches steht noch aus.
- Die veränderliche Arch-`latest`-URL ist auf die am 6. November geprüften Bytes gepinnt. Für dauerhafte Verfügbarkeit ist ein eigenes, unveränderliches, zulässig verteiltes Release-Artefakt oder ein Archivspiegel sinnvoll. Bis dahin meldet eine neue Upstream-Datei einen Prüfsummenfehler, und der Lock muss nach Signaturprüfung bewusst aktualisiert werden.
- pacman verwendet seit 0.2.0 die direkt geprüften HTTPS-Adressen von `ca.us.mirror.archlinuxarm.org` und RWTH Aachen. Das SHA-256-gepinnte Bootstrap-Archiv bleibt bei Princeton. Erreichbarkeit und Fallback auf dem Zielgerät prüfen.
- Maven-Inputs sind in `gradle/verification-metadata.xml` mit SHA-256 erfasst; native Archive, SDK/NDK-Versionen, Gradle und Arch-Image sind gepinnt. Für Byte-identische Builds sind zusätzlich der Signaturschlüssel und weitere Host-/Zeitstempelparameter zu vereinheitlichen.

## Aktuelle Grenzen

- Mehrere Sitzungen mit eigenen PTYs, Shellzuständen und Scrollback sind vorhanden; Arch-Dateisystem und Paketdatenbank werden gemeinsam verwendet.
- Keine eingebaute Sicherung, Wiederherstellung oder Android-Dateiauswahl. App-Deinstallation löscht das Arch-Dateisystem.
- DNS wird beim Sitzungsstart ermittelt; automatisches Aktualisieren bei einem Netzwerkwechsel ist noch nicht implementiert. Eine neue Sitzung verwendet die aktuelle Verbindung.
- Die CPU-Wachhaltung ist standardmäßig aktiv und abschaltbar. Bildschirm-Wachhaltung, WLAN-Lock, Batterie-Optimierungsdialog und herstellerspezifische Automatisierung sind nicht integriert.
- Kein systemd-Boot, keine echten Mounts, Kernelmodule, Linux-Capabilities oder zusätzliche Kernelisolation; das ist eine grundsätzliche PRoot-Grenze.
- Target 28 ist für die Ausführung beschreibbarer Guest-Binaries gewählt. Ein moderner Google-Play-Target-Build bräuchte eine andere Ausführungs-/Distributionsarchitektur und ist hier nicht implementiert.
