# Architecture Dependency Audit

Classifies every component of this repository for the transition from
"Android host of the upstream ZCode web UI" to "independent Android agent
platform" (owner direction, 2026-11). Each classification is grounded in the
files of this repository at commit `ac7ffd6` (dev). Evidence labels follow
docs/ENGINEERING-NOTES.md: CONFIRMED (source or measured run), OBSERVED
(behavior seen, cause not settled), INFERRED (interpretation), UNKNOWN.

Companion documents: [ARCHITECTURE.md](ARCHITECTURE.md) (target layer model)
and [MIGRATION-PLAN.md](MIGRATION-PLAN.md) (phased transition).

## 1. Method

Every Kotlin file under `android/app/src/main/java/io/github/zcodeandroid/app/`,
`AndroidManifest.xml`, `android/app/build.gradle.kts`,
`.github/workflows/android-verify.yml`, `scripts/embedded/`, and the two docs
were read in full for this audit. Upstream behavior is cited from
docs/ENGINEERING-NOTES.md, which pins upstream at
`3c5b227649371507ddc4eb46ac5133afe6db1cab`. Static observations of the
embedded distribution's protocol surface (below) were collected on-device
2026-11-02 by extracting `web/assets/index-*.js.map` sources and
`agent/node_modules/@zcode/shared/dist/index.js` from the staged dist;
runtime confirmation is UNKNOWN until a second client connects.

## 2. Component classification

| Component | Files (this repo) | Class | Basis |
| --- | --- | --- | --- |
| Bionic runtime provisioning (offline, sha256-verified) | `local/Provisioner.kt`, `local/BionicEnv.kt`, `local/TarExtractor.kt`, `local/RuntimeState.kt`, `scripts/embedded/build-bionic-bundle.py`, `scripts/embedded/test-bundle-guarantees.py` | Reusable | Proven offline path (CI `setup-tap` runs it in airplane mode); carries the device-failure 1/2 fixes (hard-link-free sonames, OpenSSL env wiring) |
| Runtime lifecycle ownership | `local/LocalServerService.kt`, `local/ZcodeServer.kt` | Reusable (as internal component, not product surface) | Foreground service owns Node; probe order prevents duplicate spawns (`ensureRuntime`, `ZcodeServer.adoptIfHealthy`) |
| Agent + server distribution | built from `UPSTREAM_PIN` (`android-verify.yml:21`), applied via `scripts/upstream-patches/0001-project-route-local-alias-for-standalone-server.patch` | Decouple (keep pinned engine) | The engine is upstream; the Desktop assumptions live in its web UI and host-id policy, not in the agent loop |
| Agent-side diagnostics | `scripts/embedded/assets/diag-collector.cjs`, `tls-diagnose.cjs`, `oauth-flow-probe.cjs`, `verify-*.cjs` | Reusable | Evidence instruments with honest verdict markers; independent of the UI question |
| App-side diagnostics bridge | `local/DiagReport.kt`, `local/DiagFileSink.kt`, `local/WebDiagnostics.kt`, `local/LocalServerProbe.kt` | Reusable, extend | Agent-facing JSON report and persisted console evidence; battery/device stats were missing (extended 2026-11) |
| Runtime status/console UI (non-WebView part of `LocalApp`) | `ui/LocalApp.kt:77-340` | Reusable (restyle into the native shell) | Honest state machine UI, log console, SAF file picker |
| Web UI in WebView | `ui/LocalApp.kt:378-1054` (`LocalWebViewScreen`, viewport shim), serves upstream `packages/web` | Replace | Core owner decision: desktop-oriented web UI is not the mobile product surface. Keep as fallback route during migration |
| Remote control client | `ui/RemoteControlApp.kt`, `remote/RemoteControlKeepAliveService.kt`, `remote/RemoteControlUrlPolicy.kt`, `remote/RemoteControlRecentLinksStore.kt`, deep links in `AndroidManifest.xml:50-70`, `BuildConfig.REMOTE_CONTROL_DEFAULT_URL` | Remove from product UX | Owner decision: a hosted relay client is not needed once the local stack is self-sufficient; code stays until the native UI fully replaces its value |
| Workspace project registration shim | `scripts/embedded/assets/register-workspace-project.cjs`, `ZcodeServer.registerWorkspaceProject` | Reusable (temporary) | Neutralizes the standalone-server `project.remote-project-required` contract; a native UI should own project management instead |
| Release engineering | `build.gradle.kts` (no `signingConfigs`), `android-verify.yml:199,242` (`assembleDebug` only) | Missing | Every distributed APK is debug-signed with a per-runner keystore: update-over-install from a previous run fails (INSTALL_FAILED_UPDATE_INCOMPATIBLE class). Matches the owner-reported upgrade problem |
| Session persistence across uninstall | runtime `zcode-data/` (`ZCODE_DATA_BASE_DIR`, `BionicEnv.apply`) | Missing (backup/restore) | Data survives app updates (app-private dir kept) but is deleted on uninstall; no export/import exists |
| Device stats bridge | `local/DiagReport.kt` (mem, node processes) | Missing (battery etc.), partially built | Owner requirement: agent could not read battery via sysfs; public-API bridge added 2026-11 |
| Native mobile UI | none yet | Missing | Protocol surface documented in section 4; implementation is migration Phase 2 |
| Terminal (PTY) | upstream `node-pty` (lazy import with catch) | UNKNOWN | Present in the runtime closure but never executed on-device; test before any UI commitment |
| ADB / Shizuku / REA tooling | none | Out of scope now | Explicit owner non-goal until an independent agent loop exists |

## 3. Upstream / Desktop touchpoint map

Each numbered touchpoint is a place where the Android product currently
depends on a Desktop-derived assumption, with the decoupling action.

1. **The engine itself is upstream-pinned.** `android-verify.yml:21,65`
   builds the distribution from `UPSTREAM_PIN`. CONFIRMED. Impact: none at
   runtime; the engine is Apache-2.0 upstream, embedded offline. Action: keep
   the pin + the existing contract test (`verify-web-api-contract.cjs`) so a
   pin bump that moves the web surface fails the build instead of silently
   breaking the phone UI.
2. **Standalone web-mode host id is "remote" by upstream design.**
   Task creation without a registered project is rejected
   (`project.remote-project-required`), documented in ENGINEERING-NOTES
   section 5.3. CONFIRMED. Neutralized by: upstream patch 0001 (UI "local"
   treated as its own host) + automatic workspace registration on server
   start (`ZcodeServer.registerWorkspaceProject`, official `project`
   channel RPC). Action: keep both until the native UI manages projects
   natively; then drop the shim.
3. **The product surface is the upstream desktop-oriented web UI** rendered
   in a WebView at `http://127.0.0.1:3030` (`LocalApp.LocalWebViewScreen`).
   CONFIRMED. This is the single largest dependency: every UX defect
   reported (viewport shim working around a zero-height CSS engine latch,
   `This computer` wording, desktop keyboard shortcuts, plugin editors) is
   inherited from upstream `packages/web`. Action: replace with a native
   Compose client speaking the documented protocol (section 4); keep the web
   UI reachable as a fallback route (`config-web-url` override already
   provides the mechanism).
4. **OAuth login is a server-side device flow with external-browser
   handoff.** The app hands non-loopback http(s) navigations and
   `window.open` popups to the external browser (`LocalApp.kt:496-510,
   663-697`); the authorization state lives on the local server. CONFIRMED
   by code + device logs. Desktop additionally registers a
   `zcode://oauth/callback` deep link; the Android manifest registers only
   `zcode://remote` and `https://zcode.z.ai/remote`. The browser-handoff
   path works without the deep link, so this is a gap, not a break.
   Action: register the OAuth callback deep link when the native UI owns
   sign-in.
5. **Remote control depends on the hosted relay** (`zcode.z.ai/remote`,
   `REMOTE_CONTROL_DEFAULT_URL`, keep-alive foreground service). CONFIRMED.
   Owner decision: not part of the product surface going forward. Action:
   remove from the user path after the native UI ships; keep the URL policy
   class (it is also the loopback-safety reference for any future
   network-facing feature).
6. **Native modules (`node-pty`, `ssh2`) are lazy and untested on Bionic.**
   ENGINEERING-NOTES section 1: both are failure-tolerant dynamic imports.
   UNKNOWN on-device. Action: on-device test before the native terminal
   milestone; no code change until then.
7. **The distribution ships no `rg`.** The agent's Grep tool resolves
   ripgrep from PATH and was degraded on-device. CONFIRMED (owner device
   session 2026-11-02: no `rg` in `usr/bin`). Fixed by
   `scripts/embedded/assets/get-rg.cjs` (commit `2c97f9d`): Alpine musl
   ripgrep + loader + pcre2/libgcc kit, symlink-free, staged at
   provisioning; CI `rg-provision` stage verifies it per ABI. Residual
   UNKNOWN: real on-device search execution (CI stage covers the emulator
   path).
8. **Shared-storage access was absent.** The manifest requested no storage
   permission, so the runtime could not read `/sdcard/Download` (owner
   report 2026-11-02). Fixed by commit `ac7ffd6`: legacy external storage
   pair + startup request (targetSdk 28 keeps legacy storage semantics).

## 4. Native UI protocol contract (static observation of the embedded dist)

Collected 2026-11-02 on-device from the staged distribution (source maps of
`web/assets/index-*.js` + `@zcode/shared/dist/index.js` at the pinned
commit). Static observation only; live behavior on-device is UNKNOWN until a
client connects. Kept here because it defines what a native client must
implement, and it is the exact surface `verify-web-api-contract.cjs` and
`register-workspace-project.cjs` already exercise at HTTP/RPC level.

- Transport: WebSocket `ws://127.0.0.1:3030/ws`; VS Code-style binary
  frames: 13-byte header (type u8, id u32 BE, ack u32 BE, length u32 BE) +
  payload; every message serialized with a 1-byte type tag (Undefined=0,
  String=1, Buffer=2, VSBuffer=3, Array=4, Object=5, Int=6) and variable
  quantity-length integers (7 bits per byte, little-endian groups).
- RPC core: RequestType Promise=100 / EventListen=101 / EventDispose=103;
  ResponseType Initialize=200 / PromiseSuccess=201 / PromiseError=202 /
  EventFire=203. A request serializes `[type, id, channelName, methodName]`
  plus the args array; the server answers `[200]` then `[201, data]` or
  `[202, error]`; events arrive as `[203, id, arg]` frames.
- Channel registry (ServiceChannels, `@zcode/shared`): `zcode-session`,
  `zcode-task`, `zcode-agent`, `task-groups`, `project`, `oauth`, `file`,
  `file-watcher`, `setting`, `terminal`, `git`, `model-selection`,
  `provider-settings`, `usage-stats`, `media-preview`, `broadcast`,
  `conversation-share`, and others.
- Core task methods (`IZCodeTaskService`): `initialize`, `createTask`,
  `sendPrompt`, `stopGeneration`, `respondPermission`, `resumeTask`,
  `listTasks`, `getTaskSnapshot`, `getTaskMeta`, `renameTask`, `deleteTask`,
  `setModel`, `setMode` (full list extracted in the same pass).
- Streaming: workspace-level frame subscriptions
  (`onDynamicConversationFrame` for `v4/conversation/frame`,
  `onDynamicSessionsIndexFrame`, `onDynamicTaskEvent`, ...) plus ranged
  history reads (`conversationRowsRangeV4`). Permission prompts arrive as
  `permission.request(ed)` events and resolve via `respondPermission`.
- The `project` channel is proven on-device: `register-workspace-project.cjs`
  performs `readProjects` + `saveProject` over this stack on every server
  start (CI-verified), which is a live, exercised subset of the contract.

## 5. Evidence ledger

- All file/line references in sections 2-4: read directly from this
  repository at `ac7ffd6` (CONFIRMED, static).
- Protocol surface (section 4): static extraction of the embedded dist,
  2026-11-02 (OBSERVED artifacts; live semantics UNKNOWN).
- Device findings cited (Download unreadable, no `rg`, sticky toasts,
  viewport latch): owner device sessions 2026-10-28..2026-11-02 as recorded
  in docs/ENGINEERING-NOTES.md and commit messages (CONFIRMED as reports,
  re-verification on device pending for the two fixes in sections 3.7-3.8).
