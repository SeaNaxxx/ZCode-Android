# Runtime Inspection — Native-only transition baseline

**Status:** inspection report per
[ANDROID-UI-ARCHITECTURE-REVISED.md](../ANDROID-UI-ARCHITECTURE-REVISED.md)
(§5 Phase 0 Runtime Inspection; §15 agent instructions). Originally
produced under the superseded Native-first Hybrid doc (§5/§12); extended
by the §10 addendum below after the Native-only revision.
Evidence-based only — no implementation shipped in this change.

**Baseline:** branch `dev` @ `db17c79` (versionName 0.6.0, unified web
surface, owner decision 2026-11-04). **Owner direction 2026-11-05
(revised):** Native-only — see the §10 addendum.

Evidence labels: **VERIFIED** = the app or a process it spawns actually
executes it (file:line cited); **REFERENCED** = documented only;
**UNKNOWN** = not determinable from the repo today.

------------------------------------------------------------------------

## 1. Current architecture findings (§12.1)

### 1.1 Entry point and navigation

- `MainActivity.kt` (267 lines) is the single entry. It maps
  `RuntimeState` to UI: `Running` → `LocalWebViewScreen` (product) or
  `LocalApp` (diagnostics overlay, reached from the web UI ⋮ menu);
  `Failed` → error text; post-stop → honest restart button; else
  "Preparing…" (MainActivity.kt:154–205). Silent first-run:
  provision → Ready → `LocalServerService.start` (MainActivity.kt:133–146).
- Dark mode on targetSdk 28: `attachBaseContext` qualifies the base
  context with `UI_MODE_NIGHT_YES` from `UiModeManager` (legacy-target
  apps don't receive system night mode otherwise) — this is what makes
  the WebView report `prefers-color-scheme: dark`
  (MainActivity.kt:61–90). Any native UI inherits this for free.
- Edge-to-edge is toggleable via the `config-edge-to-edge-off` marker
  (A/B instrument for the failure-3 WebView bug; MainActivity.kt:94–106).

### 1.2 Toolkit state — key finding

**Jetpack Compose is already the only UI toolkit**: plugin
`org.jetbrains.kotlin.plugin.compose` (Kotlin 2.2.10),
`buildFeatures { compose = true }`, Compose BOM 2026.02.01 with
`material3`, `activity-compose`, `lifecycle-runtime-compose`
(android/gradle/libs.versions.toml; app/build.gradle.kts). There are no
View/XML screens. The product surface is the upstream web bundle inside
an `android.webkit.WebView` embedded in a Compose screen
(`LocalWebViewScreen`, ui/LocalApp.kt:719–815).

Not present: okhttp/ktor (removed at `ce2066c` with the native shell),
navigation-compose, room. Present: commons-compress + xz (provisioning),
core-ktx.

### 1.3 WebView compatibility layer (verified behaviors to preserve)

- Loopback-only navigation policy; non-loopback http(s) and
  `window.open` hand off to the external browser (LocalApp.kt:398–442,
  541–566, 720–769).
- Third-party cookies enabled — the Z.ai accounts domain sets cookies
  during OAuth redirects (LocalApp.kt:494–499).
- File upload via `onShowFileChooser` → SAF (LocalApp.kt:674–718).
- Phone-adaptation CSS layer (self-gating, kill-switch
  `config-mobile-css-off`) and the failure-3 `VIEWPORT_SHIM_JS`
  (LocalApp.kt:1052–1239).
- `config-web-url` hook can point the WebView at a loopback path such as
  `/remote` (LocalApp.kt:450–473).
- **No JS bridge exists** (no `addJavascriptInterface`); the app observes
  the web UI only through console mirrors (`WebDiagnostics`, four sinks)
  and `LocalServerProbe` (LocalApp.kt:162–172).

### 1.4 Runtime lifecycle (owner of the truth)

- `LocalServerService` (foreground, `dataSync`) is the only runtime
  owner; the UI never spawns Node (LocalServerService.kt:23–37).
  `onDestroy` intentionally does not kill Node; explicit stop wins
  (notification action or ⋮ menu → `ZcodeServer.stop()` +
  `killLeftoverNode()`).
- `ZcodeServer.startBionic`: optional embedded mock provider
  (`node verify-mock-provider.cjs --port 9999`, health-gated on
  `GET /healthz`, 15 s) then
  `node dist/zcode/bin/zcode.mjs --web --no-open --host 127.0.0.1
  --port 3030 --workspace <filesDir>/workspace` (ZcodeServer.kt:175–287).
  Health gate: `GET http://127.0.0.1:3030`, 1500 ms timeouts, 2 s poll,
  120 s deadline (ZcodeServer.kt:529–536). Adopt-if-healthy prevents
  double-spawn (ZcodeServer.kt:469–488).
- Post-start hooks (thread-isolated, never fail the start): TLS
  diagnose, OAuth flow probe, **workspace project registration over the
  WS `project` channel** (`registerWorkspaceProject`,
  ZcodeServer.kt:253–276, 397–461), kit self-heal.
- `RuntimeBus` (StateFlow + 400-line ring) is the single state source;
  `RuntimeState` machine: NotProvisioned/Provisioning/Ready/Starting/
  Running/Stopped/Failed (RuntimeState.kt:12–55).

### 1.5 Auth & session (§5.7)

- No native auth in the current tree. Sign-in is the upstream web UI's
  OAuth device flow (`POST /api/v1/oauth/cli/init`,
  `GET /oauth/cli/poll/:flowId` on `zcode.z.ai`; the app probes it
  diagnostically via `oauth-flow-probe.cjs`, ZcodeServer.kt:343–395).
- `zcode://oauth` deep link is registered (AndroidManifest.xml:49–58)
  but **unhandled in Kotlin** (dead code since the native shell was
  removed) — decision needed at Phase 5.
- Credentials live runtime-side under `files/runtime/zcode-data/.zcode/v2/`
  (`ZCODE_DATA_BASE_DIR`, BionicEnv.kt:113); backup explicitly excludes
  credential paths (ZcodeBackup.kt:35–48).

### 1.6 Workspace, file APIs, error handling (§5.11–12)

- Workspace: `files/workspace`, passed as `--workspace`; import via SAF
  ("Add files to workspace") and via the WebView file chooser; legacy
  external-storage pair requested at startup so the runtime can read
  `/sdcard/Download` (MainActivity.kt:225–244).
- Backup/restore: schema-versioned zip with per-file sha256, credential
  paths excluded (backup/ZcodeBackup.kt); diagnostics export share-intent.
- Errors surface through `RuntimeState.Failed(stage, message)`,
  `DiagReport` (redacted diag-report.json, rewritten on transitions),
  `WebDiagnostics` (page lifecycle, JS console, HTTP errors, renderer
  death), `DiagFileSink` (never throws) — RuntimeState.kt:12–33,
  DiagReport.kt:104–154, WebDiagnostics.kt:6–31, DiagFileSink.kt:22–35.

------------------------------------------------------------------------

## 2. Verified runtime communication contracts (§12.3)

### 2.1 WebSocket RPC — the native UI transport (VERIFIED)

`ws://127.0.0.1:3030/ws` — binary frames, 13-byte header
`[type u8][id u32be][ack u32be][len u32be]`, VQL tagged serialization
(tags 0–6: undefined/string/buffer/vsbuffer/array/object/int); RPC types
Promise=100, PromiseCancel=101, EventListen=102, EventDispose=103;
responses 200/201/202/203/204. Exercised **live on every server start**
by `register-workspace-project.cjs` (spawned by ZcodeServer.kt:408–461):
RPCs `project.readProjects` and `project.saveProject`
(scripts/embedded/assets/register-workspace-project.cjs:14–18, 270–301).
`GET /ws` (plain HTTP) must never serve the SPA — pinned by
`verify-web-api-contract.cjs:89–96`.

### 2.2 Channel registry (static extraction; live semantics UNKNOWN beyond `project.*`)

`zcode-session, zcode-task, zcode-agent, task-groups, project, oauth,
file, file-watcher, setting, terminal, git, model-selection,
provider-settings, usage-stats, media-preview, broadcast,
conversation-share` (docs/architecture/DEPENDENCY-AUDIT.md:133–137).
Task methods: `initialize/createTask/sendPrompt/stopGeneration/
respondPermission/resumeTask/listTasks/getTaskSnapshot/getTaskMeta/
renameTask/deleteTask/setModel/setMode`; streaming events
`onDynamicConversationFrame`, `onDynamicTaskEvent`
(DEPENDENCY-AUDIT.md:138–146). **Per the architecture doc §3/§9, the
agent-activity renderer must gate on events verified in a live cycle —
no fabrication.**

### 2.3 HTTP endpoints actually used (VERIFIED)

- `GET http://127.0.0.1:3030/` — health/adopt probe + WebView home.
- `GET /remote` — upstream phone-first route (⋮ menu, `config-web-url`,
  CI mobile-ui-probe).
- `GET /api/__contract_probe__` — authored control: `/api/*` must 404.
- Mock provider `127.0.0.1:9999`: `GET /healthz`,
  `POST /v1/chat/completions` (SSE streaming) — consumed by the agent,
  not by the app.
- External, diagnostic-only, credential-free: `https://api.z.ai`,
  `https://zcode.z.ai`, `https://www.baidu.com` (TLS/connectivity
  probes; LocalServerProbe.kt:42–123, 169–237).

### 2.4 Documented-but-unexercised contracts (REFERENCED)

- NDJSON-stdio agent contract (`runtime/capabilities`, `session/create`,
  `session/send`, `session/messages`): documented and probe-verified on
  Termux, but the app never sets `ZCODE_AGENT_SERVER_COMMAND`/`_ARGS_JSON`
  (ENGINEERING-NOTES.md:26–40) — the WS path above is the app-relevant one.
- `ZCODE_SERVER_AUTH_TOKEN` documented upstream; the app launches
  tokenless on loopback.

### 2.5 UNKNOWN ledger (must not be assumed)

Live semantics of every channel except `project.*`; `/remote`
local-vs-relay connectivity; `node-pty`/`ssh2` loadability on Bionic;
whether a future hardening adds a server auth token.

------------------------------------------------------------------------

## 3. Recoverable asset — the 0.5.0 protocol stack

The native client removed at `ce2066c` is parked at **tag `0.5.0`**:

- `protocol/Vql.kt` — byte-exact VQL serializer (17-case verified corpus).
- `protocol/ZcodeRpcClient.kt` — OkHttp WebSocket client (connect 5 s,
  ping 20 s, `extraBufferCapacity=256`).
- `native/ZcodeApi.kt` — typed wrappers: `sendPrompt`,
  `respondPermission`, `getTaskSnapshot`, `taskEvents`/
  `onDynamicTaskEvent`, oauth (`getProviders/startOAuth/
  pollPendingOAuth/restoreCachedSession/logout`).

Honest status: build-verified in 0.5.0 CI, but **never completed a live
chat cycle on-device** (its own header says so). The `okhttp` dependency
must be re-added when restoring. This stack is the Phase-2/3 starting
point — recover, don't rewrite (architecture doc §2 constraint 3).

------------------------------------------------------------------------

## 4. Reusable components (§12.2)

| Layer | Components | State |
|---|---|---|
| Runtime host | `ZcodeServer`, `LocalServerService`, `Provisioner`, `BionicEnv`, `TarExtractor`, `RuntimeState`/`RuntimeBus` | Reusable as-is; native UI consumes `RuntimeBus` only |
| Diagnostics | `DiagReport`, `WebDiagnostics`, `DiagFileSink`, `LocalServerProbe` | Reusable; native surfaces add their own console sink |
| UI kit | Compose M3 + `ui/theme` (dark/light), `LogConsole`, SAF flows, MainActivity dark-mode workaround | Reusable; extend `ui/theme` into design tokens (Phase 1) |
| Backup | `ZcodeBackup` | Reusable unchanged |
| Protocol | 0.5.0 `protocol/` + `native/ZcodeApi.kt` | Recover from tag + re-add okhttp |

------------------------------------------------------------------------

## 5. Required new Compose components (§12.4) — mapped to phases

- **Phase 1 — Design system:** `ui/designsystem/` — color/type/spacing/
  shape/elevation tokens on top of the existing `ui/theme`, light+dark;
  buttons, text fields, icon buttons, menus, dialogs, loading, error and
  empty states; shared tokens only (no hardcoded styling).
- **Phase 2 — Conversational UI:** `ui/chat/` — conversation screen,
  message list, streaming presentation, composer (text entry, send,
  stop-generation, keyboard-safe), empty/loading/error states. Stream
  source: verified task events over the WS client. No multimodal claims
  until runtime-verified (§Phase 2 of the doc).
- **Phase 3 — Agent UI:** `ui/agent/` — event renderer mapping verified
  events to components (tool invocation/result, file read/edit, terminal
  execution/output, task progress, approval request, error, completion);
  activity visually distinct from assistant text; approval ≠ completed.
- **Phase 4 — Workspace UI:** file explorer/preview on `file`/
  `file-watcher` channels (after live verification), todos panel,
  terminal only if `node-pty` on Bionic is verified.
- **Phase 5 — Hybrid integration:** navigation boundaries; conversation
  and execution state stay runtime-owned (single source of truth — the
  WS server); WebView remains the compatibility layer reachable from the
  native overflow (settings/plugins/MCP/skills); `zcode://oauth` ownership
  decided here.

------------------------------------------------------------------------

## 6. File-level implementation plan (§12.5)

1. **P0 — protocol proof (no product-surface change):** restore
   `protocol/` + `native/ZcodeApi.kt` from tag 0.5.0; re-add `okhttp`;
   add a unit test suite for the VQL codec (0.5.0 corpus); add an
   emulator CI stage that drives **one real chat turn** against the mock
   provider over the WS (reusing `verify-second-send` patterns) and
   records received event types. Exit: verified `sendPrompt` → streamed
   frames → `respondPermission` path in CI logs.
2. **P1 — design system:** `ui/designsystem/Tokens.kt`, `Theme.kt`,
   `Components.kt` + previews; acceptance per doc §Phase 1.
3. **P2 — conversation:** `ui/chat/{ChatScreen,MessageList,Composer,
   ChatViewModel}.kt`; `MainActivity` gains a two-surface choice —
   native chat becomes the default product surface, WebView becomes the
   compatibility route (⋮). RuntimeBus gating unchanged.
4. **P3 — agent activity:** `ui/agent/AgentActivityRenderer.kt` + event
   component set, gated on the P0 event-type evidence.
5. **P4–P6:** workspace components, integration polish, device
   validation report (doc §Phase 6 checklist).

Each deliverable lists files changed, affected functionality, tests run,
build results, known limitations, remaining work (doc §10).

------------------------------------------------------------------------

## 7. Compatibility risks (§12.6)

1. **Live-cycle proof gap:** the 0.5.0 client never ran a full chat turn
   on-device; P0 exists precisely to close this before any UI work.
2. **UNKNOWN channel semantics:** renderer must degrade honestly — show
   raw unclassified events rather than invented categories.
3. **State duplication risk:** conversation/execution state must stay
   runtime-owned; Compose renders streamed frames only (doc §6).
4. **Concurrent WS clients:** `register-workspace-project` already uses
   the WS at every start; a second concurrent client (native UI) is
   unproven — P0 test must run both simultaneously.
5. **CI coupling to the unified surface:** `mobile-ui-probe`, `dark-mode`
   pcs probe and `contract-api` assume the web UI is the product; the
   P2 default-flip requires updating those stages in the same change.
6. **targetSdk 28 quirks** (night-mode qualification, edge-to-edge
   WebView interaction, exec() W^X) persist for the compatibility layer;
   native Compose screens are unaffected but keyboard/insets behavior
   needs its own probes.
7. **Dead deep link** `zcode://oauth` — handled or removed at Phase 5;
   not a blocker for P0–P2.

------------------------------------------------------------------------

## 8. Test & validation strategy (§12.7)

- **Unit:** VQL codec + frame header tests against the pinned corpus;
  `ZcodeApi` request serialization tests.
- **Emulator (CI x86_64 AVD API 33 — deliberately matches the acceptance
  device):** P0 chat-turn stage; composer/keyboard probe after P2;
  state-restoration probe (force-stop + relaunch, mirroring the existing
  `persistence` stage); WebView compatibility route still reachable.
- **Contract pins kept:** `verify-web-api-contract.cjs` (web surface),
  upstream pin `3c5b2276…`; new ws-contract probe for the task channels
  the native layer consumes.
- **Device validation:** doc §Phase 6 checklist on the acceptance device
  (Vivo V2035, Android 13); emulator ≠ device, per README.

## 9. Approval gate

Per §12: **wait for implementation approval before broad architectural
changes.** P0 (protocol restore + proof stage) touches no product
surface and is the recommended first approval; P1–P2 change the default
surface and require the owner's explicit go-ahead.

------------------------------------------------------------------------

## 10. Addendum — Native-only revision (owner decision, 2026-11-05)

**ANDROID-UI-ARCHITECTURE-REVISED.md is the governing direction (it
supersedes the Native-first Hybrid doc, which was removed): the product
interface is NATIVE-ONLY — no
WebView product UI, no `/remote`/Desktop embedding, no WebView fallback
for unmigrated features (mark them unavailable instead).** The inspection
sections above remain valid as the runtime baseline; this addendum maps
the revision onto the plan.

### 10.1 Decision deltas vs the Hybrid plan

| Hybrid plan (superseded) | Native-only (governing) |
|---|---|
| WebView = compatibility layer for settings/plugins/MCP/skills | WebView never renders product UI; unmigrated features are marked unavailable, upstream web functionality itself is untouched (§2.1.6–7) |
| Native default surface at Phase 2, WebView one tap away | Native screens only; the local server stays an internal service (§10), not a user-facing web interface |
| Focus: streaming chat + agent activity | Same, plus **"This device" host identity** (§2.3), **AI-slop design gate per screen** (§4), **security audit** (§10), **persistence/Keystore report** (§8) |

### 10.2 Conflicts with the current 0.6.0 surface (to resolve at their phase, not in an audit)

1. Product surface IS the web UI (MainActivity.kt:154–174 →
   `LocalWebViewScreen`) — §2.1 violation, resolved by Phase 2/5.
2. Phone CSS layer + `/remote` overflow menu (fb84819, ce2066c) —
   become obsolete for the product path once native screens ship.
3. Storage permission pair requested at startup (MainActivity.kt:233–244)
   — §9 requires just-in-time requests; the app-private runtime needs no
   broad storage permission. Move to the workspace-import flow.
4. Mock provider binds `0.0.0.0:9999` (verify-mock-provider.cjs:182)
   and the web server binds tokenless loopback — §10 explicitly warns
   TCP loopback is NOT isolated from other Android apps; audit-only for
   now, UDS comparison recommended (least invasive secure design).
5. `ZCODE_SERVER_AUTH_TOKEN` unset — §10 auth inspection item.

### 10.3 New Phase-0 evidence requirements (§15) beyond the report above

- Typed RPC shapes (parameter names/types, event payloads, cancellation/
  timeout, reconnection/subscription lifecycle) verified against the
  PINNED upstream source — not from method names (§6, §Phase 0.7).
- Session/memory/settings storage inventory (§Phase 0.11) — currently
  only `zcode-data/.zcode/v2/` paths and ZcodeBackup exclusions are
  verified.
- Existing tests inventory (§Phase 0.14): unit tests exist
  (DiagReportTest et al.); CI stages enumerated in the report above.
- Distinguish per fact: confirmed from source / test-verified /
  device-verified / inference (§15) — the report above labels VERIFIED/
  REFERENCED/UNKNOWN; device-verified rows stay empty (emulator ≠
  device, per README).

### 10.4 What survives unchanged

P0 protocol proof (restore 0.5.0 stack + live chat turn in CI) is MORE
critical under Native-only — it is the sole verified transport candidate
for the Kotlin integration layer. Runtime host, diagnostics, backup,
`RuntimeBus`, and Compose-only toolkit findings all carry over. The
2026-11-04 unified web surface remains the interim product until native
screens replace it (phased, reviewable, per §11).

------------------------------------------------------------------------

## 11. Addendum — P0 protocol proof status (2026-11-05)

Restored from the 0.5.0 tag per §3 (commit `901cedc`): `protocol/Vql.kt`,
`protocol/ZcodeRpcClient.kt`, `native/ZcodeApi.kt`, `protocol/VqlTest.kt`
(JVM codec corpus), okhttp 4.12.0. Honest status labels per §15:

- **Test-verified:** VQL codec round-trips (VqlTest, JVM unit tests, runs
  in the existing `testDebugUnitTest` CI step).
- **CI-verified (x86_64 AVD, android-verify `native-protocol` stage,
  added with this addendum):** one real chat round driven by the Kotlin
  client — `NativeProtocolProbeTest` launches MainActivity (real silent
  auto-start path), connects, `createTask` → `sendPrompt` → collects
  `onDynamicTaskEvent` frames → `respondPermission(allow)` when a
  `permission_request` arrives → `VERIFY-MOCK-DONE` sentinel from the
  embedded mock provider; a second concurrent native client runs
  `project.readProjects` throughout (coexistence with the
  register-workspace-project machinery). Evidence: logcat tag
  `NativeProtocolProbe`, marker `NATIVE-PROBE-PASS` with the observed
  event-type list.
- **Device-verified:** none yet — ARM64 on-phone chat round remains the
  open item (emulator ≠ device, per README).
- **Inference:** permission event shape (`requestId`/`options[]`/
  `optionId`) is REFERENCED from pinned source; the probe treats any
  unrecognized shape as log-only evidence, never a guess. Product
  surface untouched: instrumented test only.

### 11.1 Codec fix from the first CI run (2026-11-05)

The first CI execution of VqlTest caught three real defects in the
restored 0.5.0-era decode path (the probable 0.5.0 chat-cycle failure
class): JSON `null` literals decoded to the `JSONObject.NULL` sentinel
instead of Kotlin null; org.json number-type variance (Maven
BigDecimal/Long vs Android Integer/Long/Double) leaked untyped values;
and the varint test corpus compared `List<Byte>` against `List<Int>`.
Fixed by normalizing decoded numbers to the JS-like Kotlin types
(integral → Int/Long, fractional → Double) and routing scalar literals
through the null-mapping converter (`ae6f05d`). Unit tests CI-green.
