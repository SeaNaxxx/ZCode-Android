# Target Architecture

The layered model this repository transitions toward (owner direction,
2026-11). The goal state is an independent Android agent platform where the
upstream ZCode engine is one embeddable component among several, not the
definition of the product surface. Derived from
[DEPENDENCY-AUDIT.md](DEPENDENCY-AUDIT.md); every "today" row cites current
code.

## 1. Layers

| Layer | Responsibility | Today (dev @ `ac7ffd6`) | Target |
| --- | --- | --- | --- |
| Android Host | App lifecycle, permissions, notifications, UI shell | `MainActivity.kt`, `local/LocalServerService.kt`, theme, manifest (deep links, storage perms) | Keep; becomes the sole UI owner (Compose) instead of hosting a WebView |
| Agent Core | Agent loop, sessions, context, permission decisions | Upstream `agent/zcode.cjs` built from `UPSTREAM_PIN` (`android-verify.yml:21`) | Keep pinned upstream engine; do not fork logic. Host-side session persistence/backup is this repo's job |
| Provider Layer | Model providers, connectivity, credentials | Upstream provider registry + `provider_config.json` + offline mock (`verify-mock-provider.cjs` on `127.0.0.1:9999`) | Keep; add host-side sign-in UX (device flow + `zcode://oauth/callback`) |
| Tool Runtime | Local execution of agent tools | Bionic prefix (`usr/bin/node|git|bash`), `rg` kit (`get-rg.cjs`), env contract (`BionicEnv.kt`) | Keep; add on-device verification of lazy natives (`node-pty`) before terminal UI work |
| Workspace Engine | Files, projects, git | `files/workspace`, workspace registration shim (`register-workspace-project.cjs`), SAF import (`LocalApp.kt:88-108`) | Keep; project management moves into the native UI, shim retires |
| Extension System | Plugins, MCP, skills | Upstream dist plugin/skill trees (staged under `dist/zcode/agent/packages`); never exercised on-device | UNKNOWN until an on-device inventory + load test exists; no code commitment before that |
| Android Bridge | Device data and OS integration for the agent | `DiagReport.kt` (state, processes, memory, battery), `WebDiagnostics.kt`, `DiagFileSink.kt`, `diag-collector.cjs` | Keep extending with public APIs only (no root); ADB/Shizuku/REA stay out of scope until later phases |

Rule inherited from the audit: the Android Host must not grow dependencies on
Desktop assumptions (desktop web UI, hosted relay, desktop deep links). New
product surfaces talk to the local stack over the documented protocol, not
by reusing the desktop UI.

## 2. What the layers imply for existing code

- `LocalApp.kt` splits: the runtime status/log/file-import parts are reused
  inside the native shell; `LocalWebViewScreen` + viewport shim remain only
  as a fallback route.
- `ZcodeServer.kt` keeps its exact ownership semantics (health probe, adopt,
  no duplicate spawn) — it is the Android Host's edge to the Agent Core, and
  its diagnostic probes (`tls-diagnose`, `oauth-flow-probe`,
  `register-workspace-project`) stay as startup self-checks.
- `RuntimeBus`/`RuntimeState` stays the single state source; the native UI
  consumes the same `StateFlow` the service writes.
- Nothing in this model requires replacing the Bionic runtime, re-pinning
  upstream, or weakening any TLS/verification guarantee.

## 3. Invariants that survive every phase

1. No simulated states: every UI status reflects a real probe (`RuntimeBus`,
   `serverHealthy()`), as stated in `RuntimeState.kt`.
2. TLS certificate verification is never disabled (`BionicEnv.kt` rules).
3. Secrets never enter diagnostics, logs, or the repository
   (`DiagReport.redact`, diag redaction chain).
4. The verification model stands: CI green proves emulator-level behavior
   only; device rows stay NOT TESTED until run on hardware
   (ENGINEERING-NOTES section 4).
5. Upstream remains Apache-2.0 and pinned; patches to it stay minimal and
   reviewed (`scripts/upstream-patches/`).
