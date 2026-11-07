# Architektur

```text
MainActivity / Termux TerminalView
  ↕ VT-Emulator, InputQueue, ByteQueue
TerminalService / SessionManager / mehrere TerminalSession-Instanzen
  (Java, Hauptthread und I/O-Threads je PTY)
  ↕ JNI
C++ PTY: /dev/ptmx → fork → setsid → TIOCSCTTY → execve
  ↕ controlling terminal / SIGWINCH
libproot.so → libproot-loader.so → Arch aarch64 / glibc → Bash / pacman
```

## PTY

`native/pty.cpp` erstellt Master/Slave, setzt UTF-8 und die Fenstergröße, deaktiviert XON/XOFF und startet ein eigenes controlling terminal. Eine CLOEXEC-Pipe meldet Fehler bei Sitzungsaufbau und `execve` an Java zurück. Es gibt keinen Timer. Der Kindpfad verwendet nach `fork` ausschließlich zuvor vorbereitete Daten und signalverträgliche Systemaufrufe. `waitpid` wiederholt bei EINTR und liefert Exitcode oder negatives Signal.

Emulator, Rendering und Callbacks laufen auf Androids Hauptthread. Reader, Writer und Waiter besitzen getrennte Aufgaben. Reader und Writer haben jeweils ein eigenes dupliziertes FD; dadurch kann ein schließender Thread kein anderweitig wiederverwendetes FD eines anderen Threads schließen. Ausgabe wird vor dem endgültigen Exit-Callback vollständig aus der Transportqueue entnommen. Pro Hauptthread-Durchlauf wird die Verarbeitung begrenzt und fortgesetzt, damit große Ausgaben die Eingabe nicht verdrängen.

Die Anzeige enthält 10.000 Transcript-Zeilen. 256 KiB Ausgabetransport erzeugen Backpressure bei langsamer Darstellung. Die Eingabequeue nimmt bis zu 1 MiB ausstehende Daten auf und blockiert ihren Produzenten nicht. Eine volle Queue meldet dies, ohne Prozess oder Emulator zu schließen.

`MainActivity` erzeugt den eingebetteten `TerminalView` per Java und setzt deshalb dessen Fokusattribute ausdrücklich: `setFocusable(true)` und `setFocusableInTouchMode(true)`. Der View-Konstruktor übernimmt diese Einstellungen nicht. Ohne Touch-Fokus kann Android die Konsole trotz vorhandener `InputConnection` nicht als aktiven Eingabeeditor auswählen; die Bildschirmtastatur bleibt beim Antippen geschlossen. Der Android-Regressionstest prüft den Weg vom Tippen über die Tastaturverbindung bis zur Shell.

## Startansicht

Seit 0.1.6 prüft die Startansicht den lokalen Installationsmarker bereits vor der Dienstverbindung. Während Verbindung und Konsolenstart bleiben Installationsbutton und Downloadhinweise verborgen. Erst ein verbundener Dienst mit fehlender Installation bietet die Installation an. `TerminalService.startFailed()` unterscheidet einen fehlgeschlagenen Start von der laufenden Startvorbereitung; nur im Fehlerfall erhält eine vorhandene Installation eine Wiederholen-Aktion.

Das 48-dp-Arch-Logo ist eine dekorative `ImageView` neben dem Titel der Start-/Installationsansicht. Ein `ObjectAnimator` pulsiert nur ihre Deckkraft. Es erhält weder Eingabe- noch Accessibility-Fokus. `render()` blendet die gesamte Startansicht aus, sobald eine Sitzung vorhanden ist, und stoppt die Animation; der PTY-Start erhält keine zusätzliche Wartezeit. Auch `onStop()` und fehlender Fensterfokus stoppen die Animation und setzen die Deckkraft zurück. Bei deaktivierten Android-Animationen bleibt das Logo statisch. Es gibt kein Logo-Overlay und keine Logoausgabe im Terminalpuffer.

## PRoot und Umgebung

PRoot und sein Loader liegen im extrahierten nativen APK-Verzeichnis. `LaunchSpec` übergibt Argumente als Array, ohne Shellquoting. Das Guest-Dateisystem ist `files/arch`; `/dev`, `/proc` und `/sys` werden gebunden. `/dev/shm` wird auf ein privates, beschreibbares Verzeichnis abgebildet. DNS-Server stammen nach Möglichkeit aus Androids aktivem Netzwerk; ohne diese Daten werden 1.1.1.1 und 9.9.9.9 verwendet. Die DNS-Datei wird beim Sitzungsstart erzeugt.

`PROOT_LOADER` und `PROOT_TMP_DIR` verweisen auf app-eigene Pfade. `LD_PRELOAD` und `LD_LIBRARY_PATH` des Android-Hosts werden nicht an Arch weitergereicht. `/usr/bin/env -i` setzt für den Guest HOME, PATH, TERM, Locale und weitere Shell-Grundwerte. `--kill-on-exit` räumt Guest-Prozesse beim Shellende auf. Explizites Beenden sendet seit 0.1.8 SIGQUIT an den Tracer. Der gepinnte PRoot ignoriert SIGTERM; nur sein SIGQUIT-Pfad ruft die Bereinigung aller Tracees auf und lässt die Ereignisschleife deren Ende abwarten. Damit werden auch abgekoppelte Prozesse erfasst. Die generische Terminalbibliothek bietet dafür ein vom Prozessbesitzer wählbares Signal; gewöhnliche Shell-Tests behalten ihren bisherigen SIGTERM-Standard. Das muss zusätzlich auf Android-Herstellerkerneln geprüft werden.

PRoot ist eine Übersetzungsschicht ohne Rootrechte und ohne eigenen Kernel. systemd als PID 1, echte Mounts, Kernelmodule und echte Linux-Capabilities sind damit nicht verfügbar. Guest-Root entspricht weiterhin der Android-App-UID. PRoot ist keine zusätzliche Sicherheitsgrenze gegenüber Programmen, die der Benutzer in dieser App startet.

## Installation

`RootfsInstaller` lädt das gepinnte Archiv mit Range-Fortsetzung und prüft Größe und SHA-256. Änderungen am offiziellen Image werden am Entwicklungsrechner zusätzlich mit dessen GPG-Signatur geprüft. Die App vertraut dem mit der APK gelieferten Lock; sie lädt keinen neuen Hash nach.

Der C++-Entpacker verwendet libarchive/Gzip. Er akzeptiert relative Pfade, verwirft Geräte-/proc-/sys-Inhalte, erstellt Dateien ohne Symlink-Folgen und bewahrt ausführbare Modi. Symlinks werden erst nach Dateien und Hardlinks angelegt. Absolute Symlinkziele sind für den späteren Guest gültig; beim Entpacken werden sie nicht traversiert. Pfad-Traversal, doppelte reguläre Dateien und ausbrechende Hardlinks werden zurückgewiesen.

Seit 0.1.1 werden Archiv-Hardlinks als unabhängige reguläre Dateien mit identischen Inhalten und Ausführungsrechten gespeichert. Androids App-SELinux-Domänen verbieten echte Hardlinks auch bei Target 28. Der Installer verwendet daher weder `link` noch `linkat`. Vorwärtsreferenzen und Hardlink-Ketten werden vor den Symlinks aufgelöst; fehlende oder zyklische Ziele erzeugen einen Fehler. Die Dateien teilen nach dem Entpacken keinen Inode. PRoots `--link2symlink` bleibt für spätere Guest-Paketoperationen aktiv. [AOSP-Regel](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/app_neverallows.te).

Seit 0.1.3 erhalten Verzeichnisse nach Abschluss aller Inhalte und Links ihre Archiv-Modi einschließlich Sticky-/Setgid-Bits. Bis dahin bleiben sie für den Entpacker beschreibbar. Kinder werden vor Eltern abgeschlossen; die Android-umask verändert die endgültigen Rechte nicht. Nicht im Archiv beschriebene notwendige Eltern-/Laufzeitverzeichnisse erhalten 0755. Das Aufräumen eines fehlgeschlagenen Staging-Verzeichnisses stellt dessen Schreibrecht zum Löschen wieder her und folgt keinen Symlinks.

Der gepinnte libarchive-Tarleser erhält beim Vorbereiten der Quellen eine gezielte ACL-Korrektur. PAX-ACLs werden vor dem eigentlichen Tar-Header gelesen und dürfen dessen vollständigen Modus nicht verdrängen: Sonst fehlt bei `/var/log/journal` das Setgid-Bit; ein reiner Default-ACL bei `/var/lib/tpm2-tss/system/keystore` ergibt sogar 0000. Der korrigierte Leser bewahrt die Tar-Modi 2755 bzw. 2775. Der Patch wird idempotent auf die hashgeprüften Quellen angewendet und lehnt unerwartete Quellstände ab. Hosttests und Android-Build verwenden diese identischen Quellen. Host-ACLs und Zuordnungen fremder Nutzer/Gruppen werden nicht übernommen.

Vor dem ersten Sitzungsstart einer älteren Installation liest libarchive die komprimierten mtree-Dateien der lokalen pacman-Datenbank. Nur paketbeschriebene, tatsächlich vorhandene Verzeichnisse mit einem bekannten alten mkdir-Modus werden korrigiert. Reguläre Dateien, Symlinkpfade, /dev-/proc-/sys-Inhalte, abweichende eigene Modi und widersprüchliche Paketangaben werden ausgelassen. Ein Marker verhindert weitere Reparaturläufe; neue Installationen benötigen diese Migration nicht.

Seit 0.1.4 ist diese Wartung vom verpflichtenden Konsolenstart entkoppelt. Java-I/O-Fehler und native Reparaturfehler erzeugen einen Hinweis in Logcat und in der gestarteten Konsole. Ohne Erfolgsmarker bleibt ein erneuter Versuch möglich. Das Erzeugen der benötigten Laufzeit-/DNS-Dateien und ein erfolgreicher PTY-Prozessstart sind weiterhin Voraussetzungen für eine laufende Sitzung.

Der Paketmirror ist RWTH Aachen über HTTPS, gefolgt von dotsrc über HTTPS. Beide erhalten Arch-Linux-ARM-Pakete per Upstream-Synchronisation. Beim einmaligen Upgrade ersetzt die App ausschließlich die unveränderte einzelne Princeton-Serverzeile; eigene Mirrorlisten und Shellprofile bleiben bestehen. Nach dem Wechsel erneuert `pacman -Syyu` die Repository-Datenbanken samt vollständigem Systemupdate. Das separat gepinnte Bootstrap-Archiv behält seinen verifizierten Download-Lock.

Ein separates `arch.installing` enthält alle Änderungen bis zum Abschluss. Die Installation prüft Bash, pacman und die Paketdatenbank, schreibt Shellprofile und passende pacman-Optionen und aktiviert das Verzeichnis durch atomare Umbenennung. Die Signaturpflicht für Pakete bleibt erhalten. `CheckSpace` wird wegen fehlender echter Mount-Tabelle deaktiviert; `DisableSandbox` berücksichtigt fehlende Landlock-/seccomp-Fähigkeiten. Der Schlüsselbund wird beim ersten interaktiven Login initialisiert. Ein Fehler lässt die Shell offen und zeigt den Reparaturbefehl an.

Download und Entpacken sind abbrechbar. Der Entpacker prüft ein atomares Abbruchsignal zwischen Archiveinträgen und beim Schreiben großer Dateien. Das Trennen einer Netzwerkverbindung geschieht abseits des Hauptthreads. Ein Teilarchiv bleibt für einen erneuten Versuch erhalten; ein abgebrochenes Staging-Verzeichnis wird beim nächsten Versuch neu angelegt.

## Lebenszyklus

Die Activity bindet den Dienst und hält keine Prozesshoheit. Rotation wird durch Konfigurationsbehandlung und PTY-Resize verarbeitet; Activity-Neuanbindung verwendet dieselben Sitzungen und dieselbe Auswahl. Jede Activity meldet ausschließlich ihren eigenen Listener ab, damit ein später `onStop()`-Aufruf keine bereits neu verbundene Activity abmeldet. Bildschirm- oder Appwechsel beendet keine Sitzung. Der gestartete Vordergrunddienst hält während Installation oder mindestens einer laufenden Sitzung optional einen CPU-WakeLock ohne Ablaufzeit. Er wird erst nach Ende der letzten laufenden Sitzung oder bei zerstörtem Dienst freigegeben. Der Dienst ist nicht exportiert. Nach einer tatsächlichen Android-Prozessbeendigung wird keine laufende Shell vorgetäuscht oder ein Benutzerbefehl automatisch wiederholt.

## Mehrere Sitzungen

`SessionManager<T>` verwaltet seit 0.1.7 auf dem Hauptthread eine geordnete Liste mit stabilen Terminalnummern und der ausgewählten Sitzung. Sein Backend kapselt Erzeugung, Laufzustand und Beenden; dadurch lassen sich Auswahl und Lebenszyklus ohne Android oder JNI prüfen. Ein neuer PTY wird vor der Änderung der Liste erzeugt. Scheitert sein Start, bleiben vorhandene Terminals und Auswahl erhalten. Der Neustart eines beendeten Tabs ersetzt nur dessen Terminal unter derselben Nummer und Listenposition; verspätete Callbacks des alten Terminals werden anhand seiner Identität verworfen.

Seit 0.1.9 speichert jeder Eintrag zusätzlich, ob sein abschließender Exit-Callback eingetroffen ist. `TerminalSession.isRunning()` wird bereits nach `waitpid` falsch; der PTY-Reader und die Darstellung können zu diesem Zeitpunkt noch letzte Ausgabe verarbeiten. Schließen und gemeinsames Beenden behalten den Eintrag deshalb bis zum Callback. Ein Neustart ist erst danach möglich, damit die letzte Ausgabe der alten Sitzung vollständig verarbeitet wird.

Jede `TerminalSession` besitzt einen eigenen PTY-Prozess, VT-Emulator, Scrollback und getrennte I/O-Queues/Threads. Die Arch-Installation einschließlich Dateien und Paketdatenbank ist gemeinsam. `TerminalService` hält sämtliche Sessions; nur die ausgewählte Sitzung ist an den einzelnen `TerminalView` gebunden. Hintergrundausgabe wird weiterhin in ihrem eigenen Emulator verarbeitet, löst aber keine unnötige Neuzeichnung der sichtbaren Konsole aus. Beim Wechsel setzt die Activity eingerastete CTRL-/ALT-Zustände zurück und fokussiert die Konsole.

`SessionBar` kapselt die horizontale Tabnavigation und die ständig erreichbare Plus-Aktion. Die Tabs zeigen Terminalnummer, gegebenenfalls Shelltitel sowie den Ende-/Schließzustand. Ihre Views werden nur bei geänderter Sitzungsliste neu aufgebaut. Die Leiste bleibt unabhängig von den optionalen Zusatztasten verfügbar. Einzelnes Schließen erfordert eine Bestätigung und beendet ausschließlich den zugehörigen Prozess. Ein schließender PTY bleibt bis zum Exit-Callback mit vollständigem Ausgabedrain verwaltet; danach wird bei Bedarf ein benachbarter Tab ausgewählt. Natürlich beendete Sitzungen behalten ihren Scrollback bis zum Schließen oder Neustart. Gemeinsames Beenden und Dienstzerstörung schließen auch die Hintergrundsitzungen.

Sobald keine offenen Tabs mehr übrig sind, wechselt der Dienst in den Stop-Zustand; bereits schließende Einträge zählen dabei seit 0.1.9 nicht als offen. Das gilt auch, wenn mehrere Tabs vor ihren Exit-Callbacks einzeln geschlossen werden. Nach dem Callback der letzten schließenden Sitzung entfernt der Dienst die Vordergrundbenachrichtigung und gibt den WakeLock frei, auch wenn eine Activity oder ein Testclient noch gebunden ist. Während dieses abgeschlossenen Stop-Zustands publiziert er keine neue Benachrichtigung. Die Activity kann anschließend fertig schließen und ihre Bindung lösen; der Dienst stoppt ohne Sitzungsneustart. Natürlich beendete, noch nicht geschlossene Tabs behalten weiterhin ihren Scrollback.
