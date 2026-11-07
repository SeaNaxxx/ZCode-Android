# Desktop feature classification matrix — P10 deliverable

Per [ANDROID-UI-ARCHITECTURE-REVISED.md](../ANDROID-UI-ARCHITECTURE-REVISED.md)
§5 Phase 10: supported / partial / unsupported, with evidence per row.
State in the UI must match this matrix; no desktop-only capability is
presented as working.

| Desktop surface/use case | Android state | Evidence | Gap / next step |
|---|---|---|---|
| Conversation (text, streaming) | Supported (native Compose) | CI native-protocol run 42103274954 (mock provider full round) | Real-provider turn = owner-only row 19 |
| Agent activity (tool rows, approvals) | Supported | Same CI run: 10 event kinds, 3 permission grants, task_complete | Tool coverage stays event-schema-bound; unknown events logged, never rendered |
| Stop generation | Supported | `stopGeneration` RPC wired; DEVICE row 8 pending | — |
| Task history / restore | Supported | Snapshot restore + rememberSaveable task id (P2) | DEVICE rows 9/12 pending |
| File explorer (read-only) + git status | Supported (read-only) | `file.readdir`/`readTextFile`, `git.getRepositorySummary`/`getChanges` (P4) | No workspace-file write method at this pin — UI never pretends otherwise |
| File editor / diff presentation | Unsupported | — | No editor integration at this pin; not exposed in UI |
| Terminal (PTY) | Unsupported (gated) | PTY-PROBE-VERDICT: NEGATIVE (run 42103274954) | Blocked until positive on-device PTY evidence; separate from Agent Bash |
| Sign-in / OAuth (device flow + browser handoff) | Partial | `oauth` channel wired at API level; `zcode://oauth` deep link registered | Native sign-in UI not yet migrated; marked unavailable in the native shell, no fake login |
| Model / mode selection | Partial | `setModel`/`setMode` wired AND surfaced: top-bar model button opens the config-option picker (per-task `getTaskConfigOptions` category=model), sends official `ModelSelection {providerId, modelId}`; no provider id → honest refusal (UI brief 2026-11) | Mode picker + reasoning levels not yet surfaced |
| Message actions (copy/share), voice, image upload | Unsupported | — | Not implemented; not claimed |
| Settings / Providers | Partial | Theme (system/light/dark) persisted; provider config is runtime-owned (mock seed) | Full settings surface not migrated |
| Memory / MCP / Plugins / Skills / Subagents | Unsupported (unmigrated) | Extension inventory UNKNOWN (audit §2) | No on-device inventory + load test yet; not exposed as native features |
| Backup / export / restore | Supported (diagnostics overlay) | `ZcodeBackup`: schema-versioned zip + sha256 manifest, credential paths excluded | Uninstall-restore DEVICE row 18 pending |
| Diagnostics (logs, diag report) | Supported | Runtime log + persisted diag JSON (diag-collector) | — |
| Trusted external web content | Supported within §2.1 retention list only | Read-only in-app preview; never product UI | See WEBVIEW-DECISION.md — not a fallback, not conversation/workspace/settings/terminal/agent |
| Long sessions / high density | Partial | Bit-exact frame reassembly; bounded dedup sets | DEVICE long-session row pending |
| Dark / Light + font scaling | Supported (theme) | `dark-mode` CI stage A/B; UiModeManager workaround for targetSdk 28 | Largest font scale = DEVICE row 17 |

**Rule:** a row marked Unsupported must not appear as a working feature in
the Compose UI. If a future change ships one of these, update this matrix
in the same commit with CI/device evidence.
