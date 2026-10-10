# AGENTCODI Package Edition — Umzug, Schritt 1

Das eigenständige öffentliche Repository ist
[Mcpasi/AGENTCODI-Package-Edition](https://github.com/Mcpasi/AGENTCODI-Package-Edition).
Sein `main` ist eine unveränderte Kopie von `Mcpasi/package-edition` aus
[Mcpasi/AGENTCODI](https://github.com/Mcpasi/AGENTCODI/tree/Mcpasi/package-edition).
Der Nutzer hat das leere Ziel-Repository angelegt, nachdem die GitHub-Anbindung
die Neuanlage über GraphQL und REST mit HTTP 403 abgelehnt hatte.

## Identität und Umfang

| Gegenstand | Nachweis |
| --- | --- |
| Quell-Branch | `Mcpasi/AGENTCODI:Mcpasi/package-edition` |
| Ziel-Branch | `Mcpasi/AGENTCODI-Package-Edition:main` |
| Commit auf beiden Seiten | `e656567e62492ebfa2129718034c04d32cf6f1f8` |
| Git-Tree auf beiden Seiten | `67f7551e790ee56a985ddb9205ff29254eb9298f` |
| Erhaltene Historie | 327 erreichbare Commits; kein Squash und keine neue Import-Historie |
| Quell-`main`, unverändert | `ff27ec7c30d373a864e845e9a7ceeae3380dd103` |
| Quell-Package-Branch, unverändert | `e656567e62492ebfa2129718034c04d32cf6f1f8` |

Die drei Package-Tags wurden mit ihren originalen Git-Objekten kopiert:

| Tag | Commit |
| --- | --- |
| `v0.1.0-package.3` | `cd1aaa9a80e7f0b5f581b3b8ca039c9908535819` |
| `V.0.1.1-package.3` | `6ab41c54b08f9020570e9dd26646ecc382a6cb06` |
| `v0.1.2-package.3` | `077cf9e200565a141bbaf4bab79a132f95653de7` |

Die originalen Release-Namen, Release-Texte, der Prerelease-Status und die
unveränderten APKs sind vollständig gesichert. Native Release-Asset-Uploads
werden von `uploads.github.com` mit HTTP 401 (Bad credentials) abgelehnt,
obwohl Git-Push und die Repository-API funktionieren. Die APKs werden deshalb
auf dem separaten `migration-backup`-Branch in verlustfreien Teilstücken
gesichert. Native Release-Dateien müssen mit einer funktionierenden
Upload-Anbindung nachgereicht werden.
Die APK-SHA-256-Werte stimmen mit den GitHub-Digests der Quell-Assets überein:

| Version | APK-SHA-256 |
| --- | --- |
| 0.1.0 | `028679df0ebeb2f1a5f9d8b373320122cba1778e67e0f198ac877c2771bc25ed` |
| 0.1.1 | `6caed73a317fda548d5aadc23236c3a2d0783bac9e82313b3ee6249184bcc29a` |
| 0.1.2 | `d55cbe2e15521b1efbe345a6a86406c9a204132092427b4b9900ab99023c35f4` |

Das Git-Bundle unter `git/AGENTCODI-Package-Edition.bundle` auf dem
Sicherungsbranch enthält den kompletten Package-Branch samt diesen drei Tags.
Es lässt sich unabhängig vom bisherigen Repository wiederherstellen:

```sh
git clone AGENTCODI-Package-Edition.bundle AGENTCODI-Package-Edition
```

## Signierter APT-Katalog

Der vollständige veröffentlichte Snapshot wurde aus dem bestehenden öffentlichen
HTTPS-Endpunkt gesichert. Er liegt vollständig unter `apt/package-edition/`
auf dem separaten `migration-backup`-Branch. Zusätzlich wurde lokal ein
vollständiges ZIP erstellt. Sein Upload als Release-Asset ist mit dieser
Anbindung gesperrt.
Ein bloßes Kopieren der Git-Dateien hätte diesen Katalog nicht enthalten:
die bisherige Veröffentlichung erfolgt über ein GitHub-Pages-Workflow-Artefakt.
Der direkte Actions-Download war in der Umgebung nicht verfügbar; deshalb wurden
die bereits veröffentlichten Bytes heruntergeladen. Kleine Quellen wurden aus
den signierten Source-Packs wiederhergestellt und einzeln gegen das signierte
Manifest geprüft.

| Eigenschaft | Wert |
| --- | --- |
| Aktiver Endpunkt | `https://mcpasi.github.io/AGENTCODI/apt/package-edition` |
| Snapshot-Run | `37771253765` |
| Snapshot-Commit | `39bb4090105447c86fb17fea6d0d84e1f6fee818` |
| Primärer Signier-Fingerprint | `2768291D12B6C3D22CFBAF9EEC79CBDF7E93DC89` |
| Pakete einschließlich Abhängigkeiten | 68 |
| Signierte Paket- und Quelldateien | 10.447 |
| Summe der signierten Nutzdaten | 671.182.765 Bytes |
| `Valid-Until` des unveränderten Snapshots | 2026-10-15 11:45:56 UTC |

Geprüft wurden `InRelease`, `Release.gpg`, die Übereinstimmung von `InRelease`
und `Release`, die signierten Paketindizes und das Manifest sowie Größe und
SHA-256 jeder darin aufgeführten Datei. Paket-Pool, historische erhaltene
Pakete, korrespondierende Quellen, Source-Packs, Herkunftsmanifeste, öffentliche
Schlüssel und `by-hash`-Indizes sind gesichert. Der Prüfbericht ist
`verification/apt-verification.json` auf `migration-backup`.

Das lokale ZIP enthält `apt/package-edition/` und kann vollständig entpackt
werden. Der Sicherungsbranch enthält bereits den entpackten vollständigen Baum.
Es bewahrt die bestehende Repository-Identität und die Originalsignaturen;
es wurde weder neu gebaut noch neu signiert. Der aktive APT-Endpunkt bleibt
für die vorhandenen APKs bestehen. Ein neuer aktiver Endpunkt und seine
regelmäßige Neusignierung gehören zu Schritt 2. Nach `Valid-Until` dient der
Snapshot weiterhin als nachweisbare Sicherung; für einen regulären APT-Betrieb
braucht es dann frisch signierte Release-Metadaten mit demselben privaten Schlüssel.

## Abhängigkeiten, die erhalten bleiben

- **App-Identität und APK-Signierung:** `de.agentcodi.pkg`, Prefix und alle
  Runtime-Pfade wurden unverändert übernommen. Die kopierten APKs behalten
  ihren bisherigen Signierer. Künftige Updates benötigen denselben privaten
  Keystore; es wurde kein Ersatzschlüssel erzeugt.
- **APT-Vertrauen:** Öffentlicher Schlüssel, Fingerprint, Keyring-Paket,
  `lock.json`, `repository.json`, Overlay und Katalogrezepte sind unverändert.
  Der private APT-Schlüssel ist kein Git-Bestandteil und lässt sich nicht aus
  einem GitHub-Secret exportieren.
- **Build-Input-Mirror:** Das private `Mcpasi/agentcodi-build-inputs`, Release
  `0.7.6-preview.1`, bleibt als externer Mirror bestehen. SDK-Backups werden
  nicht in das öffentliche Ziel-Repo übertragen. Alle 12 aktuellen Build-Pins
  stehen unverändert in `.github/ci/build-inputs.tsv` und besitzen einen
  konfigurierten Upstream-Download.
- **Bestehende Mirror-Abweichung:** Das Mirror-Asset `package.tgz` passt nicht
  zum aktuellen Community-Codex-Pin. Der schon vorhandene SHA-256-geprüfte
  Upstream-Fallback ist weiterhin erforderlich; das aktuelle Upstream-Asset
  ist mit passendem Digest verfügbar. Das ist eine bestehende Abhängigkeit,
  keine Änderung durch den Umzug.
- **Community-Codex:** `DioNanos/codex-termux`, Release `v0.156.1-termux.1`,
  Archiv-SHA-256 `44cee2f3a4a110fd79d4f7d61378d46fd72406f45cffb3163e809d63e86d946a`.
  Source-, Native- und Schema-Pins sowie Lizenz- und MPL-Quellenindizes sind
  mitkopiert. Der öffentliche Release-Digest stimmt mit dem Pin überein.
- **Paketrezepte und Toolchain:** Termux-Recipe-Commit
  `b6af76b353140fe17f299248fca1ac13ea91c5c5`, Builder-Digest, NDK r30,
  SDK-Pins und Android/Bionic-Zielwerte sind unverändert mitkopiert.
- **Vendoring und Lizenzen:** `third_party/`, mitgeführte Source-Archive,
  Provenienz, Lizenztexte, `NOTICE.md` und Source-Indizes sind Teil derselben
  Git-Historie. Es gibt keine zusätzlichen Git-Submodule oder Git-LFS-Pointer.
- **Externe Website:** `https://devsblog.com/` bleibt der vorhandene Verweis.

## Übergabe an Schritt 2

Alle Workflow-Dateien, Release-Links und APT-URLs im Code bleiben unverändert.
Der vorhandene Tests-Workflow wurde durch den neuen Push auf `main`
automatisch gestartet und ist erfolgreich abgeschlossen
([Run 38053904875](https://github.com/Mcpasi/AGENTCODI-Package-Edition/actions/runs/38053904875)).
Die Package-Builds haben weiterhin ihren bisherigen Branchfilter.
Ein Build auf dem neuen Repo ist durch Schritt 1 noch nicht als betriebsbereit
bestätigt. Die Sicherung liegt auf einem separaten Branch ohne Workflows; der neue
`main` bleibt dabei auf dem originalen Package-Commit. Ein vollständiger
Multi-Branch-Clone lädt auch die großen Sicherungsdaten; für reine
Code-Arbeit empfiehlt sich `git clone --single-branch --branch main`.
Auch Änderungen der Actions-Einstellungen und Repo-Beschreibung
wurden von GitHub mit HTTP 403 abgelehnt.

Für die spätere CI-Umstellung sind insbesondere zu berücksichtigen:

- Branchfilter und `github.ref`-Prüfungen von `Mcpasi/package-edition` auf das
  gewünschte Ziel im neuen Repository umstellen.
- Die alten Run-IDs `37493949315` (APK-Bootstrap) und `37703982102`
  (Katalog/Bootstrap) gehören weiterhin zu `Mcpasi/AGENTCODI`. Die bisherigen
  Reuse-Prüfungen verlangen dieselbe Repository-Identität. Actions-Run-IDs,
  Artefakt-IDs und Caches werden nicht durch einen Git-Push übertragen.
  Ein sauberer Neubau oder eine ausdrücklich geprüfte Cross-Repo-Übernahme
  ist bei der Workflow-Anpassung nötig.
- Für die Snapshot-Kontinuität den gesicherten vollständigen APT-Pool und seine
  Quellen als vorherigen Snapshot verwenden; bei einem neuen Endpunkt auch
  die im bisherigen Verifier fest geprüfte Repository-Identität anpassen.
- GitHub Pages, Environment `github-pages`, Deployment-Rechte und neue
  APT-Veröffentlichung einrichten; alte Clients weiterhin versorgen.
- Vorhandene private Signiermaterialien aus der ursprünglichen sicheren
  Ablage als neue GitHub-Secrets einrichten. Aus der unveränderten
  Workflow-Konfiguration ergeben sich diese Namen:
  `AGENTCODI_APT_SIGNING_KEY`, optional `AGENTCODI_APT_SIGNING_PASSPHRASE`,
  `AGENTCODI_RELEASE_KEYSTORE_BASE64`, `AGENTCODI_RELEASE_STORE_PASSWORD`,
  `AGENTCODI_RELEASE_KEY_PASSWORD`, `AGENTCODI_RELEASE_KEY_ALIAS`,
  `AGENTCODI_RELEASE_CERT_SHA256` und bei Mirror-Nutzung `AGENTCODI_INPUTS_TOKEN`.
  Das Auflisten von Actions-Secrets und Variablen ist mit dieser Anbindung
  ebenfalls gesperrt; die tatsächlichen Werte oder ihre Vollständigkeit
  wurden deshalb nicht behauptet.

Diese Punkte sind erfasst, aber für Schritt 1 nicht im Code umgesetzt.
Quell-`main` und Quell-Package-Branch bleiben als unveränderte Sicherheit bestehen.
