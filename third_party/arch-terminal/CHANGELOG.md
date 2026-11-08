# Änderungen

## 0.2.0 · versionCode 11

- Pacman-Standard auf den direkten HTTPS-Mirror `ca.us.mirror.archlinuxarm.org` umgestellt, mit RWTH Aachen über HTTPS als Fallback. Eine separate Migration erfasst auch die alte RWTH-/dotsrc-Liste nach bereits abgeschlossener Verzeichnisreparatur; eigene Listen und Symlinks bleiben erhalten. Der Konsolenhinweis nennt `pacman -Syyu`, damit auch veraltete Paketlisten erneuert werden.
- Dauerhafte Auswahl **Deutsch / English** in Startansicht und Konsolenmenü ergänzt. Oberfläche, Installation, Benachrichtigungen, Prozessende-/Eingabehinweise und verwaltete Shell-Texte sind übersetzbar. Der Sprachwechsel erhält laufende PTYs und deren Zustand; neue englische Shells verwenden `C.UTF-8` ohne zusätzliches Sprachpaket.
- Exakt erkannte frühere App-Texte in regulären Shellprofilen werden idempotent übersetzbar gemacht; individuelle Inhalte und Profilsymlinks bleiben erhalten.
- Regressionstests für Bestandsmigration, eigene Konfigurationen, Bash-Syntax und beide Shell-Sprachen, Guest-Umgebung, Downloadsprache und vollständige Übersetzungen ergänzt. Android-Instrumentation prüft Auswahl, Speicherung, Activity-Neuaufbau, weiterlaufende PTY, Benachrichtigung und englischen Exit-Hinweis. Ein separater sequenzieller Netzwerktest prüft beide Mirrors einschließlich der referenzierten Paketdateien und Signaturen.
- Version, APK-/Quellarchivnamen, Geräte-Testskript und Lizenzhinweis auf 0.2.0 angehoben. Tests und Builds bleiben nacheinander auf einen CPU-Kern und einen Worker begrenzt.

## 0.1.9 · versionCode 10

- Vorzeitiges Entfernen oder Ersetzen einer Sitzung zwischen Prozessende und vollständigem PTY-Ausgabedrain behoben. Der Sitzungsmanager merkt sich den abschließenden Exit-Callback; Schließen und gemeinsames Beenden behalten den Eintrag bis dahin, ein Neustart ist erst danach möglich.
- Dienstabschluss beim kurz aufeinanderfolgenden Schließen aller Tabs korrigiert. Bereits schließende Tabs zählen nicht mehr als offen; nach dem letzten Exit-Callback verschwinden Vordergrundbenachrichtigung und WakeLock, und eine verbundene Activity schließt sich.
- Beide Fehler vor ihrer jeweiligen Korrektur reproduziert: JUnit sowie echte Android-PTYs mit kontrolliert verzögerter letzter Ausgabe; überlappende Schließanforderungen mit echtem PRoot und seinem produktiven Stop-Backend. Regressionstests bleiben für Schließen, Neustart und gemeinsame Bereinigung enthalten.
- Version, APK-/Quellarchivnamen, Geräte-Testskript und Lizenzhinweis auf 0.1.9 angehoben. Dokumentation zu mehreren Sitzungen, Abschlusszeitpunkt und vorhandenen Hardware-Rückmeldungen abgeglichen. Tests und Builds laufen mit einem CPU-Kern und einem Worker nacheinander.

## 0.1.8 · versionCode 9

- Beenden über Menü und Android-Benachrichtigung korrigiert: Der gepinnte PRoot ignoriert SIGTERM. Der Dienst sendet jetzt SIGQUIT, wodurch PRoots eigene Bereinigung sämtliche Tracees einschließlich abgekoppelter Prozesse beendet und abwartet. Die generische Terminalbibliothek lässt das Beendigungssignal durch ihren Prozessbesitzer wählen.
- Nach der letzten Sitzung werden WakeLock und Vordergrundbenachrichtigung ausdrücklich entfernt, auch wenn der Dienst noch gebunden ist. Späte Aktualisierungen und wiederholte Stop-Aktionen erzeugen keine leere Benachrichtigung erneut.
- Android-Regressionen verwenden echtes PRoot und den produktiven Stop-Backendpfad: einzelne/letzte Sitzung über das Menü, Benachrichtigungs-PendingIntent im Hintergrund, zwei parallele Sitzungen, abgekoppelte Kindprozesse und vollständige Freigabe von Benachrichtigung/WakeLock.
- App-Version, APK-/Quellarchivnamen und Geräte-Testskript auf 0.1.8 angehoben. Tests und Builds bleiben auf einen Worker begrenzt.

## 0.1.7 · versionCode 8

- Mehrere unabhängige Terminal-Sitzungen in derselben Arch-Installation: Die obere Sitzungsleiste und „Neue Sitzung“ öffnen weitere PTYs; Tabs wechseln zwischen Shells mit eigenem Zustand und Scrollback. Die Navigation bleibt bei ausgeblendeten Zusatztasten verfügbar.
- Sitzungsverwaltung als Android-unabhängiges `SessionManager`-Modul und Darstellung als eigenes `SessionBar`-Modul umgesetzt. Ein fehlgeschlagener neuer Start erhält vorhandene Sitzungen und Auswahl; Enter ersetzt ausschließlich den beendeten Tab.
- Einzelne Sitzungen lassen sich per langem Tabdruck oder Menü nach Bestätigung schließen. Die übrigen Prozesse laufen weiter. Menü und Benachrichtigung bieten zusätzlich das gemeinsame Beenden an. Wachhaltung und Dienstlebenszyklus berücksichtigen alle Sitzungen und verspätete Exit-Callbacks.
- JUnit-Tests für Auswahl, unabhängigen Zustand, Start-/Neustartfehler, stabile Nummerierung, isoliertes Schließen, natürliches Ende, gemeinsame Bereinigung und verspätete Callbacks ergänzt. Android-Instrumentation prüft mehrere echte PTYs samt IME, Tabs, Hintergrund/Rückkehr, Scrollback und WakeLock.
- Version und APK-/Quellarchivnamen auf 0.1.7 angehoben. Gradle und native Builds verwenden standardmäßig einen Worker; Java-Laufzeiten sehen einen Prozessor und nutzen serielle Garbage Collection, CTest läuft seriell.

## 0.1.6 · versionCode 7

- Überflüssige Installationsanzeige beim Start eines vorhandenen Arch behoben. Der Installationsmarker wird bereits vor der Dienstverbindung berücksichtigt; solange die Verbindung fehlt, erscheinen weder Installationsbutton noch Downloadhinweise.
- Eine vorhandene Installation zeigt „Konsole wird gestartet …“. Bei einem tatsächlichen Startfehler bleibt der Fehlertext erhalten und „Konsole erneut starten“ bietet einen erneuten Versuch an. Der anfängliche Installationsstatus des Dienstes wird dabei nicht mehr als Startmeldung angezeigt.
- UI-Regressionen für Verbindung, vorhandenes/fehlendes Arch, fehlgeschlagenen Konsolenstart und laufende Installation ergänzt. Logo, Tastatureingabe und die speichersparenden Build-/Emulator-Einstellungen bleiben enthalten.

## 0.1.5 · versionCode 6

- Kleines, dezent pulsierendes Arch-Linux-Vektorlogo neben dem Titel der Start-/Installationsansicht ergänzt. Die Konsole startet ohne zusätzliche Wartezeit; mit einer Sitzung verschwindet die gesamte Startansicht, sodass Terminalfläche, Touch-Fokus und Befehlseingabe frei bleiben.
- Die Animation stoppt bei fehlendem Fensterfokus, im Hintergrund und beim Wechsel zur Konsole. Androids Einstellung für deaktivierte Animationen wird berücksichtigt.
- Android-Regressionstest für sichtbare Animation, dekorative Fokusattribute, Hintergrund/Rückkehr und unmittelbaren Wechsel zur Konsole ergänzt. Der Tastaturtest prüft zusätzlich, dass bei der tatsächlichen IME-Befehlseingabe kein Logo mehr aktiv ist.
- Offizielle Logoquelle, Urheber und gesonderte Artwork-Lizenz dokumentiert und mitgeliefert. Version und Ausgabedateien auf 0.1.5 angehoben.
- Speicherbedarf beim Bauen begrenzt: kein dauerhafter Gradle-Daemon, maximal 1 GiB Java-Heap, zwei Worker und keine parallelen Projekt-Builds.

## 0.1.4 · versionCode 5

- Startregression aus 0.1.3 behoben: Fehler bei der optionalen Mirror-/Verzeichnisreparatur verhinderten den Start einer vorhandenen Konsole. Solche Wartungsfehler werden jetzt protokolliert und nach dem Start als Hinweis in der Konsole ausgegeben. Benötigte Laufzeitdateien und der eigentliche PTY-Start bleiben verpflichtend.
- Ein fehlgeschlagener Reparaturlauf wird weiterhin nicht als erfolgreich markiert und kann beim nächsten Start erneut laufen. Eigene Mirrors, Shellprofile und installierte Pakete bleiben erhalten.
- Android-Regressionstest mit echten JNI-Aufrufen ergänzt: beschädigte mtree-Metadaten, anschließende Laufzeitvorbereitung und eine tatsächlich ausgeführte PTY-Shell. Zusätzliche Java-Tests prüfen beide Fehlerklassen und den erfolgreichen erneuten Versuch.
- Version, APK-/Quellarchivnamen und Updatehinweise auf 0.1.4 angehoben.

## 0.1.3 · versionCode 4

- Standard-Paketmirror auf RWTH Aachen (HTTPS) umgestellt, mit dotsrc (HTTPS) als Ausweichserver. Beim nächsten Sitzungsstart wird der bisherige einzelne Princeton-Standard ersetzt; angepasste Mirrorlisten und Shellprofile bleiben erhalten.
- Bestätigten Entpackerfehler behoben: Verzeichnisrechte aus dem Archiv wurden ignoriert und stattdessen durch die Host-umask bestimmt. Die endgültigen Modi einschließlich Sticky-/Setgid-Bits werden jetzt nach allen Dateiinhalten und Links von innen nach außen gesetzt.
- ACL-Sonderfälle des Bootstrap-Archivs im gepinnten libarchive-Tarleser korrigiert: Ein vorab gelesener PAX-ACL darf die vollständigen Rechte aus dem eigentlichen Tar-Header nicht ersetzen. Dadurch bleiben Setgid-Bits erhalten, und reine Default-ACLs ergeben nicht Modus 0000. Hosttests und APK verwenden denselben korrigierten Leser.
- Bestehende Installationen erhalten einmalig eine gezielte Verzeichnisreparatur anhand der lokalen pacman-mtree-Daten. Nur bekannte alte mkdir-Modi werden angefasst; reguläre Dateien, Symlinkpfade, Laufzeit-Mounts und widersprüchliche Paketangaben werden ausgelassen.
- Regressionen für restriktive umask, private/schreibgeschützte Verzeichnisse, Sonderbits und sichere/idempotente Bestandsreparatur ergänzt. Java-Tests sichern Mirrorwechsel, Erhalt eigener Mirrors/Profile und Wiederaufnahme nach Reparaturfehlern ab.
- Version und Ausgabedateien auf 0.1.3 angehoben. Nach einem Mirrorwechsel einmal `pacman -Syyu` ausführen, damit keine veralteten Repository-Datenbanken weiterverwendet werden.

## 0.1.2 · versionCode 3

- Fehlenden Touch-Fokus der programmatisch erzeugten Terminalansicht behoben. Die Konsole kann dadurch Androids aktiver Eingabeeditor werden; Antippen öffnet die Bildschirmtastatur und ermöglicht die Befehlseingabe.
- Android-Regressionstest ergänzt: tatsächliches Antippen, sichtbare Tastatur, aktiver IME-Editor und Befehl über die InputConnection bis zur PTY-Shell. Außerdem werden Schließen mit Zurück, erneutes Antippen und der Menüschalter geprüft. Eine temporäre Android-Shell ersetzt im Test das große Arch-Rootfs.
- Test-Harness korrigiert: `.tgz` bewahrt die komprimierte Entpack-Fixture im Android-Test-APK; rohe Instrumentation-Ergebnisse machen das Erfolgskriterium des Geräte-Testskripts auswertbar.
- App-Version, APK-Namen, Geräte-Testskript, Quellarchive und Dokumentation auf 0.1.2 aktualisiert.

Ursache: Der eingebettete `TerminalView` setzt seine Fokusattribute nicht selbst. Bei der Erzeugung per Java fehlten die entsprechenden Einstellungen; `requestFocus()` konnte im Touch-Modus den für die Tastatureingabe benötigten Fokus nicht herstellen. [Android: Fokus und Bildschirmtastatur](https://developer.android.com/develop/ui/views/touch-and-input/keyboard-input/visibility).

## 0.1.1 · versionCode 2

- Android-Fehler `filesystem error: cannot create hard link: Permission denied` beim Entpacken behoben. Die 1.833 Hardlink-Einträge des gepinnten Arch-Archivs werden als Dateikopien angelegt; Inhalt und Ausführungsrechte bleiben erhalten.
- Hardlink-Ketten und Vorwärtsreferenzen werden aufgelöst, ohne Symlinks zu traversieren. Fehlende/zyklische Ziele und Kollisionen bleiben Fehler.
- Neuer Regressionstest sperrt `link` und `linkat` mit EACCES. Der ursprüngliche Entpacker reproduziert damit den gemeldeten Fehler; die korrigierte Version muss weiterhin entpacken können.
- Android-Instrumentation enthält einen eigenen kleinen Archiv-Entpacktest mit Hardlink-Kette, Inhalts-/Rechteprüfung und unabhängigen Dateien.
- Paketversion, APK-Namen, Quellarchive und Geräte-Testskript aktualisiert. Die APK-Prüfung kontrolliert zusätzlich die gebaute Version.
- Fehlende AAPT2-Prüfsummen aus Googles Maven-Downloads verifiziert und ergänzt, damit auch ein erneutes Ressourcen-Linken mit aktiver Dependency-Prüfung funktioniert.
- Download und App-Daten bleiben beim APK-Update erhalten. Installation erneut fortsetzen; kein erneuter Download nötig, wenn das Archiv vollständig vorhanden ist.

Ursache: Android verbietet Hardlinks für normale App-Domänen unabhängig von der Target-28-Ausnahme für ausführbare Dateien. [Android SELinux](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/app_neverallows.te).

## 0.1.0 · versionCode 1

- Erste Version mit C++-PTY, PRoot, Arch Linux ARM64, pacman, Vollbildkonsole sowie Build- und Testsuite.
