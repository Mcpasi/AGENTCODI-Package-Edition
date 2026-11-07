# Kompatiblität mit Packeten

**npm install -g @openai/codex**

Codex benötigt eine bubbleware Sandbox die auf Android nicht verfügbar ist, Dies kann zu abstürzen von Codex führen, und beweist keinen fehler von Arch Terminal.

Wollen sie Codex ohne Sandbox verwenden geben sie im eingabefeld von codex `/permission` ein und wählen sie Full Access aus.

Sollten weiterhin abstürze auftretten starten sie codex ohne daemon, hierzu geben sie folgenden befehl ein: `codex --no-daemon`
