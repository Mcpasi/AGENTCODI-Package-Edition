# Editor-Bugprüfung

Package Edition 0.1.5 · Zielbranch: `main`

Historische Bugprüfung im Branch `feat/modular-file-editor`:
Ausgangsstand `88be555`, bestätigte Fixes `43861cf`. Die unten genannten
Testergebnisse stammen von diesem Stand vor dem Versionsbump auf 0.1.5.

Für jeden der acht Befunde wurde zuerst ein fehlschlagender Reproduktionstest
ausgeführt. Die Änderungen beheben die bestätigten Ursachen in der Eingabe,
dem Editor-Lebenszyklus oder den zuständigen Lexern.

Die Reproduktionstests sind separat in `74fc035` committed. Auf diesem Stand mit
unverändertem Produktcode schlagen alle fünf neuen Syntaxprüfungen und 24
Android-Testfälle (API 29/35) fehl; mit den gezielten Fixes sind beide Suiten grün.

| Befund | Reproduktion | Gezielte Korrektur |
| --- | --- | --- |
| JavaScript-RegExp nach Kontrollbedingungen wird als Division zerlegt. | `javascriptRegexAfterControlConditions`: `if (ready) /a[\\/]+/giu.test(value)`; auch verschachtelte Bedingungen und Schleifen. | Klammern behalten den Unterschied zwischen Kontrollbedingung, Funktionsaufruf und gruppiertem Wert. |
| Schlüsselwort-Properties führen zur falschen RegExp-Erkennung. | `javascriptKeywordPropertiesKeepDivisionOperators`: `object.return / 2 / 3`, auch `?.` und weitere Schlüsselwort-Namen. | Property-Namen übernehmen nicht die Bedeutung eigenständiger JavaScript-Schlüsselwörter. |
| Python-Strings enden vorzeitig bei escaped CRLF. | `pythonEscapedCrLfStaysInsideStrings`: Raw-, Byte- und Format-Strings mit Backslash vor CRLF. | Ein escaped CRLF wird vollständig innerhalb des Strings konsumiert. |
| HTML-Raw-Text verschluckt den nachfolgenden HTML-Inhalt. | `htmlRawTextClosesBeforeTrailingSlash`: `</SCRIPT/>`, außerdem Style-, Textarea- und Title-Endtags. | Der Raw-Text-Scanner erkennt auch `/` als Grenze des Abschlusstag-Namens. |
| CSS-Gruppen verwechseln Selektoren und Deklarationen. | `cssGroupingRulesKeepSelectorsSeparateFromDeclarations`: Klassen, Pseudoklassen und hexadezimal aussehende IDs innerhalb von `@media`, `@supports` und weiteren Gruppen. | Ein Kontextstapel erhält die Grammatik des übergeordneten Blocks; Farbwerte bleiben Zahlen und Eigenschaften bleiben Attribute. |
| IME-Delete löscht nur die Hälfte eines dargestellten CRLF. | `imeBackspaceRemovesDisplayedCrLfAsOneLineBreak`, `imeForwardDeleteRemovesDisplayedCrLfAsOneLineBreak`, Codepoint- und Speichertests. | Die Eingabeverbindung erweitert Löschbereiche, deren Grenze zwischen CR und LF liegt. Komposition und Emoji bleiben erhalten. |
| Copy/Cut kopieren Darstellungsersatzzeichen statt des Quelltexts. | `copyingMixedLineEndingsUsesOriginalSource`: CRLF wird zu U+FEFF + LF und einzelnes CR zu LF. Zusätzlich Cut/Paste-Prüfung. | Die Zwischenablage erhält den ausgewählten Originaltext ohne Syntax-Spans. |
| Activity-Neuerstellung verliert Entwürfe und laufende Speicherzustände. | `activityRecreationKeepsUnsavedDraftAndSelection`, `activityRecreationKeepsAnEmptyNewFileDraft`, `activityRecreationWaitsForThePendingSaveAndKeepsItsRevision`. | Entwurf, Auswahl, Scrollposition, Verzeichnis und Speicherrevision werden im Arbeitsspeicher übergeben. Laufende Saves aktualisieren die erhaltene Session und anschließend die neue Oberfläche. |

Die Lebenszyklus-Regressionen prüfen zusätzlich zwei aufeinanderfolgende
Neuerstellungen während eines Saves, weitere Speicherrevisionen, Speicherkonflikte
sowie „Speichern und zurück“. Große Dokumente werden weiterhin nicht in Androids
View-State-Bundle serialisiert. Diese Übergabe betrifft Konfigurationswechsel;
sie ist keine Speicherung von Entwürfen über einen Prozessabbruch hinweg.

## Validierung

- Java-Host-Suite: **441 Tests bestanden**; vorher **436**.
- Android-UI-Suite: **152 Tests bestanden** auf API 29 und 35; vorher **126**.
- Architektur- und Package-Edition-Verträge bestanden.
- Android-Quellen, Ressourcen und Manifest-Komponenten kompiliert/geprüft.
- Sechs APK-Signaturtests bestanden; Update-Signer bleibt über zwei Builds identisch.
- Diff ohne Whitespace-Fehler.

Die vorhandenen Speicherprüfungen für UTF-8/BOM, gemischte Zeilenenden, Dateigröße,
atomare Ersetzung, ausführbare Dateirechte, externe Änderungen und Namenskollisionen
sind weiterhin grün. Die UI-Tests nutzen Robolectric mit nativer Grafik und der
vorhandenen Host-Dateispeicherung; ein Test auf einem physischen Gerät wurde nicht
durchgeführt. Alle Produktfunktionen bleiben erhalten.
