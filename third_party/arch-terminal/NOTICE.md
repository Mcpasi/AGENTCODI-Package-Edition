# Komponenten und Herkunft

Arch Terminal 0.1.9 ist eine neue Android-App. Eigene Implementierungen sind unter GPL-3.0-or-later lizenziert; siehe LICENSE. Die Lizenzen der eingebundenen Komponenten gelten zusätzlich. Arch Linux ARM und Termux sind eigenständige Projekte; diese App wird von ihnen nicht herausgegeben.

| Komponente | Herkunft | Lizenz / lokale Texte |
| --- | --- | --- |
| Arch-Linux-Logo von Thayer Williams | [Offizielle Artwork-Quellen 1.6](https://sources.archlinux.org/other/artwork/archlinux-artwork-1.6.tar.gz), `logos/archlinux-official-dark.svg` | CC-BY-NC-SA-3.0 laut beiliegendem COPYING; `third_party/ARCHLINUX-ARTWORK-COPYING`, `third_party/ARCHLINUX-ARTWORK-AUTHORS.txt`; unveränderte SVG-Vorlage in `third_party/archlinux-official-dark.svg` |
| Terminalemulator, TerminalView und ursprüngliche Tests | [termux/termux-app v0.118.3](https://github.com/termux/termux-app/tree/5b657c6adf4304e5198951ce815fe0205dcac29c), Commit `5b657c6adf4304e5198951ce815fe0205dcac29c` | Apache-2.0-Ausnahme für `terminal-emulator` und `terminal-view`, siehe `third_party/termux/UPSTREAM-LICENSE.md` und `APACHE-2.0.txt` |
| PRoot | [termux/proot v5.1.107.102](https://github.com/termux/proot/tree/v5.1.107.102) | GPL-2.0-or-later, `third_party/PROOT-COPYING` |
| talloc 2.4.3 | [Samba talloc](https://www.samba.org/ftp/talloc/) | LGPL-3.0-or-later, `third_party/TALLOC-LGPL-3.0.txt` |
| libarchive 3.7.7 | [libarchive](https://www.libarchive.org/) | BSD und komponentenspezifische Hinweise, `third_party/LIBARCHIVE-COPYING` |
| AndroidX annotations 1.3.0 | [AndroidX](https://developer.android.com/jetpack/androidx/releases/annotation) | Apache-2.0 |
| Gradle Wrapper 8.14.3 | [Gradle](https://github.com/gradle/gradle/tree/v8.14.3) | Apache-2.0; unveränderter Standard-Wrapper aus einem vorhandenen lokalen Android-Projekt |
| Arch Linux ARM Rootfs | [Offizielle Downloads](https://archlinuxarm.org/about/downloads) über Princeton-Spiegel | Jeweilige Paketlizenzen im Rootfs unter `/usr/share/licenses`; das Rootfs ist kein APK-Bestandteil |
| QEMU 7.2, nur optionaler Host-Test | [Debian qemu-user-static](https://packages.debian.org/bookworm/amd64/qemu-user-static/download) | GPL-2.0 und weitere Komponentenlizenzen; kein APK-Bestandteil |

Das Startlogo in `app/src/main/res/drawable/ic_arch_linux.xml` isoliert das Symbol der offiziellen SVG-Vorlage als Android-Vektor mit beibehaltenen Proportionen und Originalfarbe; die TM-Kennzeichnung ist als Pfad umgesetzt. Die App animiert ausschließlich die Deckkraft. Diese Artwork-Anpassung steht wie die Vorlage unter CC-BY-NC-SA-3.0, getrennt vom eigenen GPL-App-Code. Der SHA-256 des Quellarchivs ist `9c6e7faea0ed66bf6ed64b134fad6316161cf146779e4e2f4cd95c644066aab2`. Name und Logo sind Marken von Arch Linux; siehe [offizielle Artwork-Seite](https://archlinux.org/art/) und [Markenrichtlinie](https://terms.archlinux.org/docs/trademark-policy/).

Die Emulator-/View-Dateien tragen die ursprünglichen Hinweise. Änderungen hier: eigene C++-JNI-Bibliothek, ersetzter `TerminalSession` mit getrennten Dateideskriptoren, nicht blockierender Eingabequeue, vollständigem Ausgabedrain vor Exit und wählbarem Beendigungssignal (für PRoot: SIGQUIT); neue `InputQueue` und Tests; explizite Zwischenablageaktionen in der Textauswahl. Originale unbenutzte JNI-C-Dateien sind nur als Herkunftsreferenz enthalten und werden nicht gebaut.

`AGENTCODI-Package-Edition` wurde auf wiederverwendbare Bestandteile untersucht. Übernommen wurde der dort bereits verwendete Architekturansatz für `targetSdk 28`, private ausführbare Pakete und langlebige Sitzungen. Weder Codex- noch Agent-Laufzeit oder deren Paketarchiv werden eingebunden. Die PTY, Installation, App und Buildskripte dieses Projekts sind eigenständig.

Die nativen Downloads sind in `scripts/dependencies.lock.json` mit SHA-256 festgelegt. PRoot wird ohne Quellcodepatch gebaut; ein erzwungenes, für Freestanding-Loader ausgeschaltetes Include behebt ein fehlendes `string.h`. Der 32-Bit-Loader ist für diese ARM64-App deaktiviert. talloc wird unverändert mit dem dokumentierten Kompatibilitätsheader gebaut. libarchive wird statisch mit Gzip-Unterstützung eingebunden. Seit 0.1.3 wendet `scripts/fetch-sources.py` eine gezielte Korrektur im Tarleser an, damit PAX-ACLs die ursprünglichen Tar-Modi einschließlich Setgid-Bits nicht überschreiben.

Bei APK-Weitergabe die passenden Archive aus `scripts/source-bundle.sh` und diese Hinweise mitliefern. Sie enthalten den App-Quellcode, Buildanweisungen und die exakten talloc-/PRoot-/libarchive-Quellen für Neubau bzw. erneutes Linken. Ein eigener Release-Schlüssel wird nicht mitgeliefert; selbst gebaute Varianten können durch Neuinstallation unter eigener Signatur verwendet werden.
