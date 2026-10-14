# Editor bugfix report

Package Edition 0.1.5 · Target branch: `main`

Version 0.1.5 was published on 2026-10-14 as an experimental prerelease,
tagged [`V.0.1.5-package`](https://github.com/Mcpasi/AGENTCODI-Package-Edition/releases/tag/V.0.1.5-package).
The results below document the editor fixes before that release.

Historical bug investigation on branch `feat/modular-file-editor`:
baseline `88be555`, confirmed fixes `43861cf`. The test results below come
from that revision, before the version bump to 0.1.5.

Each of the eight findings was first reproduced with a failing test. The
changes address the confirmed causes in input handling, the editor lifecycle
or the relevant language lexers.

The reproduction tests were committed separately in `74fc035`. With product
code unchanged at that revision, all five new syntax checks and 24 Android
test cases (API 29/35) fail. Both suites pass with the targeted fixes applied.

| Finding | Reproduction | Targeted fix |
| --- | --- | --- |
| JavaScript regular expressions after control conditions are split into division tokens. | `javascriptRegexAfterControlConditions`: `if (ready) /a[\\/]+/giu.test(value)`; also nested conditions and loops. | Parenthesis tracking distinguishes control conditions, function calls and grouped values. |
| Keyword-named properties cause incorrect regular-expression detection. | `javascriptKeywordPropertiesKeepDivisionOperators`: `object.return / 2 / 3`, also `?.` and other keyword names. | Property names no longer inherit the meaning of standalone JavaScript keywords. |
| Python strings end prematurely at escaped CRLF. | `pythonEscapedCrLfStaysInsideStrings`: raw, byte and formatted strings with a backslash before CRLF. | An escaped CRLF is consumed completely within the string. |
| HTML raw text consumes the HTML content that follows it. | `htmlRawTextClosesBeforeTrailingSlash`: `</SCRIPT/>`, plus style, textarea and title closing tags. | The raw-text scanner also recognizes `/` as a boundary of the closing-tag name. |
| CSS grouping rules confuse selectors with declarations. | `cssGroupingRulesKeepSelectorsSeparateFromDeclarations`: classes, pseudo-classes and hexadecimal-looking IDs inside `@media`, `@supports` and other groups. | A context stack preserves the parent block's grammar; color values remain numbers and properties remain attributes. |
| IME deletion removes only half of a displayed CRLF. | `imeBackspaceRemovesDisplayedCrLfAsOneLineBreak`, `imeForwardDeleteRemovesDisplayedCrLfAsOneLineBreak`, code-point and save tests. | The input connection expands deletion ranges whose boundary falls between CR and LF. Composition and emoji are preserved. |
| Copy/cut copies display replacement characters instead of source text. | `copyingMixedLineEndingsUsesOriginalSource`: CRLF is displayed as U+FEFF + LF, and a standalone CR as LF. Also includes a cut/paste check. | The clipboard receives the selected original text without syntax spans. |
| Activity recreation loses drafts and pending save state. | `activityRecreationKeepsUnsavedDraftAndSelection`, `activityRecreationKeepsAnEmptyNewFileDraft`, `activityRecreationWaitsForThePendingSaveAndKeepsItsRevision`. | The draft, selection, scroll position, directory and save revision are retained in memory. Pending saves update the retained session and then the recreated UI. |

The lifecycle regressions also cover two consecutive Activity recreations
during a save, subsequent save revisions, save conflicts and "Save and back".
Large documents are still excluded from serialization into Android's
view-state bundle. This handoff covers configuration changes; it does not
persist drafts across process termination.

## Validation

- Java host suite: **441 tests passed**, previously **436**.
- Android UI suite: **152 tests passed** on API 29 and 35, previously **126**.
- Architecture and Package Edition contracts passed.
- Android sources, resources and manifest components compiled/verified.
- Six APK signing tests passed; the update signer remains identical across two builds.
- No whitespace errors in the diff.

The existing save checks for UTF-8/BOM, mixed line endings, file size, atomic
replacement, executable permissions, external changes and filename collisions
continue to pass. The UI tests use Robolectric with native graphics and the
existing host file storage. No physical-device test was performed as part of
this historical investigation. Physical Android device tests for the published
0.1.5 release have since passed. All product features are preserved.
