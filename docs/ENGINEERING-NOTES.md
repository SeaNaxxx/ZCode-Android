# Engineering Notes

Single reference for the architecture knowledge, verification model, and known
failure classes accumulated while bringing the ZCode agent to Android. Facts
cite the upstream source or a measured run; anything untested is marked as
such. The upstream reference commit is `3c5b227649371507ddc4eb46ac5133afe6db1cab`
(upstream `main` at analysis time, untagged 3.16.2; latest tag `v3.15.1`).

## 1. Upstream agent architecture (zai-org/ZCode)

Repository layout (pnpm workspace, Node >= 24, pnpm 10.33.2):

| Package | Role |
| --- | --- |
| `apps/zcode-cli/packages/cli` | Agent CLI (`@zcode/cli`), builds to a single-file `dist/zcode.cjs` |
| `packages/server` | HTTP server (`entry-http.ts`, Hono) + web-mode host services |
| `packages/web` | Web client, built by Vite and served statically by the HTTP server |
| `packages/services` | Host-side service layer, agent process manager, protocol client |
| `packages/shared` | `zcode-protocol` message schemas and method registries |
| `packages/desktop` | Electron shell — excluded from the Android path |

Key properties that make an embedded Android host feasible:

- The agent is a **pure-JavaScript single-file CommonJS bundle**; no native code
  ships inside the agent bundle itself.
- Hosts drive the agent over **NDJSON on stdio** (`app-server` command): one JSON
  frame per line, shared request/response/notification/error union
  (`packages/shared/src/zcode-protocol`). Frames: request `{id, method, params?}`,
  response `{id, result}`, error `{id, error:{code, message}}`. A client must
  tolerate out-of-order notifications interleaved with responses.
- The telemetry platform enum in the protocol explicitly includes `"android"`.
- `console.*` output is redirected to stderr in protocol mode
  (`src/protocol-console.ts`); stdout is a strict frame channel.
- A host must set `ZCODE_AGENT_SERVER_COMMAND` and `ZCODE_AGENT_SERVER_ARGS_JSON`
  or agent sessions cannot start (`zcodeAgentProcessManager.ts:443-459`).
- Native modules are **lazy and failure-tolerant**: `node-pty` is a dynamic import
  with a catch handler (terminal degrades gracefully), `ssh2` is only reachable
  when an SSH remote target is used. The core agent loop (sessions, model calls,
  file tools) therefore does not require glibc natives; the pnpm dependency
  policy itself is glibc-only (`supportedArchitectures.libc: [current, glibc]`).
- The Grep tool needs an `rg` (ripgrep) binary; the Linux distribution must
  supply one or resolve it from `PATH`.

Web mode (what the app embeds):

- Entry `packages/server/src/entry-http.ts`: `PORT` (default 3030),
  `ZCODE_SERVER_HOST`, `ZCODE_WEB_STATIC_ROOT`, optional `ZCODE_SERVER_AUTH_TOKEN`
  (loopback use without a token is the default).
- Workspace resolution: `ZCODE_SERVER_WORKSPACE || process.cwd()`.
- Distribution runner contract: `zcode --web [--host] [--port] [--workspace]
  [--open|--no-open] [--token|--no-token]`. On Linux the runner opens the
  browser via `xdg-open`; embedded hosts must pass `--no-open`.
- Standalone web-mode servers build a REMOTE project-storage host id by design;
  task creation without a registered project is rejected with
  `project.remote-project-required` (see §5.3).

Authentication:

- `zcode login [zai|bigmodel] [--no-browser]`; slash-command variants include
  both an OAuth path and an API-key path per provider.
- Credentials live in `${ZCODE_DATA_BASE_DIR ?? homedir()}/.zcode/v2/credentials.json`;
  `ZCODE_DATA_BASE_DIR` relocates the whole store — useful on Android where
  `$HOME` is app-private.
- `provider/updateAccountConfig` and `provider/testModelConnectivity` allow a
  host UI to configure and probe accounts without touching disk layout.
- No secrets are embedded at build time; the built-in provider registry carries
  endpoints/models only.
- Desktop registers `zcode://oauth/callback` deep links; on Android the app must
  handle the equivalent (App Link/custom scheme) or use `--no-browser` URL copy.

## 2. Distribution and packaging

- `scripts/build-zcode.mjs` stages `dist/zcode/<v>/zcode/` with
  `bin/zcode.mjs`, `web/`, `server/`, `agent/zcode.cjs`,
  `agent/provider/zcode-builtin.json`, and a runtime `node_modules` closure.
- The runtime closure (`scripts/zcode-distribution/assets.mjs`) contains the
  HTTP/server runtime packages (`@hono/node-server`, `@hono/node-ws`, `ssh2`,
  `node-pty`, `undici`, `axios`, ...). Electron, koffi, and esbuild are
  build/desktop-time only and are NOT in the runtime closure.
- Required build flags observed in CI: `--base-url` must be passed to
  `build:zcode`; the workspace packages (cli chain, server, web) and the tui
  chain must be built first; `@zcode/shared` dist is staged via esbuild
  (upstream gap at the pinned commit).
- Distribution: no Linux assets on upstream GitHub Releases (v3.14.3 ships
  Windows/macOS only). Official Linux ARM64 Electron builds DO exist on the
  vendor CDN (`cdn-zcode.z.ai/.../3.16.3/linux-arm64/...AppImage`, verified
  HTTP 200). This project still builds the non-Electron distribution itself —
  web mode needs no Electron.

## 3. Android runtime architecture (this project)

- The APK embeds the runtime at build time: Bionic-compatible bundles
  (`prefix-<abi>.tgz` + `.sha256` + critical-files list + manifest) under
  `assets/runtime/bionic/`, holding Node.js v24.22.1 (android builds) and the
  full ZCode distribution. First launch provisions OFFLINE from assets —
  no network is required before the local server answers on `127.0.0.1:3030`.
- Bundle guarantees (device-failure driven): every soname is a REAL file
  (hard links), zero tar hardlinks, and the extracted closure is verified
  against the sha256 manifest before the server starts. Android `tar` on some
  OEM builds refuses to create symlinks; hard-link-free real files survive.
- `LocalServerService` (foreground service, `dataSync`) OWNS the runtime
  lifecycle: it probes for a live Node process, adopts or restarts it, kills
  leftovers via `pidof` before start, and uses `START_REDELIVER_INTENT`. The
  UI never spawns Node directly; the service survives app close and process
  death paths better than `START_STICKY` with a null intent (which cannot
  restart the runtime by itself).
- The web UI is shown in a WebView (`LocalApp.kt`); the load happens after the
  first real layout (see §5.4). Workspace lives at
  `files/workspace`, runtime at `files/runtime`.
- `ZCODE_DATA_BASE_DIR` redirects the agent's credential/data store into
  app-private storage.

## 4. Verification model

Claims boundary (deliberate, kept for every future run):

- **x86_64 AVD proves app logic only** — offline provisioning, embedded node
  exec, official distribution web server, on-device agent loop verified by disk
  effects (Write/Bash/git), restart persistence. It is NOT proof of ARM64
  hardware or of any specific phone.
- **Device rows stay NOT TESTED until executed on the phone** (Vivo V2035,
  Android 13, arm64). CI runs the API 33 emulator image deliberately to match
  the acceptance device's API level, so CI is never more capable than the
  device in ways that mask app-API defects (a real past failure: Java API 34+
  present in CI, missing on the device).
- Device protocol gates: T0 device info; T1 first run OFFLINE (provisioning
  must succeed with airplane mode on); T2 local server up; T3 real agent loop
  verified by file effects; T4 persistence/recovery; T5 real provider call.
- Emulator stages that keep CI stable: pre-granted POST_NOTIFICATIONS, ANR
  dismissal, network state via `dumpsys`, wifi/data re-enabled after offline
  stages, quoting rules for `adb shell` (see §5.1 tooling notes).

## 5. Known failure classes and instruments

### 5.1 Device extraction failures (fixed)

- Failure 1: Vivo `libz.so.1` symlink creation failed. Fix: ship sonames as
  real files (hard links) instead of symlinks.
- Failure 2: OpenSSL/TLS env wiring missing on device + API 33 symlink paths.
  Fix: env wiring (`SSL_CERT_FILE`/`NODE_EXTRA_CA_CERTS` applied consistently)
  plus the hard-link bundle above.

### 5.2 TLS classification (instrumented, verification never weakened)

- `UNABLE_TO_GET_ISSUER_CERT_LOCALLY` against `api.z.ai`/`zcode.z.ai` was
  reclassified as a transient network-path event: the same chain
  (`*.z.ai` <- Sectigo DV R36 <- Sectigo Root R46 <- USERTrust RSA) verifies
  with the shipped CA bundle when the network path is healthy. The on-device
  TLS probe (`tls.connect`, `rejectUnauthorized: true`) captures the served
  chain per depth on failure for future classification. No CA verification is
  disabled anywhere.

### 5.3 `project.remote-project-required` (upstream contract, no bypass)

- On any standalone `--web` server, `entry-http.ts` builds host services with a
  REMOTE project-storage host id; `remote-policy.ts` rejects task creation when
  the owner host is non-local and no project is registered. The bare composer
  therefore can NEVER create a session on a fresh standalone server — this is
  upstream design, not a port bug.
- Supported path: register the workspace as a project via the official
  Projects UI (add project -> workspace root -> trust confirm -> save), then
  start tasks from that project. The app surfaces this as guidance on the
  Running card; no protocol bypass is implemented.

### 5.4 WebView black screen (isolated, fix pending)

Evidence chain (emulator + device, via the paint-evidence suite):

- The page DOM is fully populated and visibilityState is sane; the paint
  pipeline works (forced-style bitmaps paint; an isolated minimal page paints).
- Inside the app WebView: JS viewport metrics are sane
  (`innerHeight`/`clientHeight`/`visualViewport` ~791) while ALL CSS
  viewport-height units resolve to 0 (`vh/dvh/%` probes = 0), `html` computes
  `height:0` with `scrollHeight:791`, the `h-dvh` RootShell and the
  `height:100%` chain collapse, and the login card is clipped out of a
  zero-height container. Only the dark background paints = the black screen.
- The same URL renders fine in external browsers; forcing `height:100vh`
  inline cannot repair the state (document-level latch, not a style problem).
- Dead ends ruled out with A/B evidence: compositor/raster path,
  visibilityState/motion pause, pre-layout `loadUrl()` latch (loading after
  the first real layout is correct practice and is kept, but did not clear
  the collapse), edge-to-edge and `useWideViewPort` toggles.
- Remaining unknown: the exact engine trigger of the zero-height latch inside
  the Compose-embedded WebView. Next decisive experiment: post-load size-nudge
  re-measure (force a size change after load, then re-probe the units).
- The visual-state + bitmap paint suite stays in every future export as a
  regression instrument.

### 5.5 `adb shell` tooling notes (recurring CI pitfalls)

- Single-quoted `bash -c` blocks break on apostrophes in comments or UI
  strings; heredocs inside them break the quoting. Pass variable values as
  argv (shell vars are NOT exported into python), avoid apostrophes in
  scripted strings, and use `unzip -Z1` for full-path APK entry checks
  (`unzip -l` truncates long names).

### 5.6 Web-UI sticky error toasts and persisted console evidence

Findings from the 2026-11-01 on-device investigation run through the embedded
agent (read-only; the web/server bundles are upstream-staged dist, so the
fixes below are scoped, not all actionable in this repo):

- `Group action failed. Please retry.` is the web bundle's `taskGroup.failed`
  i18n key. It is shown by the task-group admit/move path and is sticky BY
  DESIGN (`durationMs: 0`, dedupe key). The success path of the Retry action
  calls only `groups.refresh()` and never dismisses the toast; reopening the
  app clears it because the toast lives in React memory while
  `LocalServerService` keeps the server running. Cosmetic persistence, not a
  server defect.
- The concrete admit throw is logged ONLY to the WebView console
  (`J.error('[task-groups] ...')`); no server-side log records it. Candidates
  are four throws: client `task-group.service-unavailable`, server
  `task-group.created-task-unavailable`, `task-group.not-found`, transport
  failure. The `v4.hydrate` `Session not found` race (warn-level, once per
  session creation) reads `cli/db.sqlite`+rollout while admit reads
  `tasks-index.sqlite` — separate stores, no code path between them, so it is
  NOT a proven cause of the toast.
- Console evidence previously lived only in memory (DiagLog/RuntimeBus) or
  rotating logcat — all lost on app restart, which is exactly when users
  report sticky toasts. `WebDiagnostics` now mirrors every web/js/probe line
  to `files/logs/webconsole.log` (rotated to `.old` at 512 KiB) and the
  diagnostics export appends its tail, so the exact throw survives restarts.
- Upstream-side residuals (documented, not patched here): the sticky toast UX
  itself; `exchangeZcodeJwtToken` passes no `timeoutMs` (userinfo is capped
  at 60 s, token exchange can in principle hang unbounded on a stalled
  network); task rows reserved with `created_at=0` stay unenriched when
  session persist fails (ghost rows; the reader has a fallback, so cleanup is
  cosmetic and must stay manual — dry-run, backup, 7-day grace, no FK risks).
- OAuth perception note: the browser page "Sign-In Complete / authorization
  is syncing" is served by Z.ai, NOT the local server (string absent from the
  web bundle). Login completion is driven by the server-side device flow
  (§1, `oauth-flow-probe.cjs`); the page's apparent wait does not reflect
  app-side state.

### 5.7 Self-diagnostics bridge and the mobile UI route (2026-11)

Resolutions of the external review conditions on the self-diagnostics /
native-UI study (evidence per claim; nothing below is assumed):

- Engine-health evidence is CONCLUSIVE BY CODE: the viewport shim logs
  `[zcode-shim] engine healthy (vh/dvh resolve) - shim idle` exactly once per
  page load when its live probe (`engineBroken()`) passes, and it logs
  `[zcode-shim] applied: ...` (throttled 250 ms) whenever it does not. The
  healthy path now also refuses to log before `document.body` exists, so a
  premature "healthy" line cannot poison the verdict. Device logs that contain
  `applied` lines and zero `engine healthy` lines therefore prove the broken
  engine state for that log's whole retention window (current + `.old`).
- The mobile UI is INSIDE the bundled dist: upstream `packages/web` is the
  same bundle that serves the hosted `/remote/v4` entry
  (`resolveWebRemoteControlRoutePathFromBaseUrl` maps a root BASE_URL to the
  `/remote` route, and the standalone server's SPA fallback serves `/remote`
  with `index.html` — `isStaticFallbackAllowed`). `GET /remote` on the local
  server therefore boots the built-in web-remote-control (mobile) entry from
  the SAME dist with zero patches. Whether that mobile UI fully connects to
  the LOCAL server session (vs. expecting the hosted relay) is UNKNOWN until
  the `mobile-ui-probe` CI stage records its first real run.
- Upstream web-surface contract is PINNED BY TEST: `verify-web-api-contract.cjs`
  (CI stage `contract-api`) asserts `GET /` and `GET /remote` return 200
  text/html (both OBSERVED on the emulator run 41348375789 — the mobile route
  is servable at HTTP level), `GET /ws` never serves the SPA (at the pin it is
  an upgrade-only route; plain GET observed 404), and an authored control
  (`/api/__contract_probe__`) must 404 — proving the no-SPA-fallback semantics
  for `/api/*`. A pin bump that moves this surface fails the build instead of
  silently breaking the phone UI. The upstream pin is
  `3c5b227649371507ddc4eb46ac5133afe6db1cab` (declared in
  `android-verify.yml` `UPSTREAM_PIN`).
- Agent-side diagnostics (P0, no app changes): `diag-collector.cjs` gathers
  one redacted JSON snapshot — same-uid node processes with BOTH RSS and PSS
  in kB (RSS double-counts shared pages across the node process family; PSS
  is the honest per-process figure where readable), MemTotal/MemAvailable,
  storage free, `GET /` and `GET /remote` probes, webconsole verdict counters
  (applied vs engine-healthy, last `htmlPx` values — the vehicle for the
  still-unexplained 941x436 landscape-shaped geometry inside portrait
  screenshots), and the persisted log tails. CI runs it as the `diag-collector`
  stage.
- App-side diagnostics bridge (P1a): `ZcodeServer` mirrors runtime stdout
  (`agent|`/`mock|` lines) to `files/logs/server.log` and
  `LocalServerService` keeps `files/logs/diag-report.json` fresh (on every
  state transition + every 5 min). Both rotate at 512 KiB via the shared
  `DiagFileSink`. Every string in the report passes a secret-shape redactor
  (GitHub PATs, z.ai key shapes, Bearer/credential assignments) before it is
  written, because log lines can carry untrusted page text that the agent
  will read.
- Cheap mobile-UI experiment (review condition 6, before any native shell):
  `LocalApp` honors a `files/config-web-url` file containing a loopback PATH
  (same opt-in pattern as `config-edge-to-edge-off`; unwritable on release
  installs) and the CI `mobile-ui-probe` stage boots the WebView on
  `/remote`, capturing navigation, console, visual-state and bitmap evidence.
  The stage gates only on what must be true (override fired, `/remote`
  finished loading, renderer alive); whether the mobile UI connects to the
  local session is recorded as an observation.

## 6. CI

- `android-verify.yml` (manual, `workflow_dispatch`): builds the official
  distribution from the pinned upstream source, builds Bionic bundles for
  aarch64 and x86_64, builds the APK per ABI, uploads the arm64 APK as the
  device deliverable, keeps the x86_64 APK for 1 day (emulator job input
  only), publishes the arm64 APK to a versioned release, and runs the
  x86_64 AVD verification job. The x86_64 build exists because GitHub AVDs
  are x86_64-accelerated; the arm64 build is what devices install.
- `linux-arm64-build.yml` (manual): builds the linux/arm64 distribution tree
  used by the Termux device checks.
- A green verify run proves compilation/packaging and app-level emulator
  behavior only — never device behavior (§4).
