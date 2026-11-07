# Native UI Integration Report — P5 deliverable

**Status:** implementation report per
[ANDROID-UI-ARCHITECTURE-REVISED.md](../ANDROID-UI-ARCHITECTURE-REVISED.md)
§5 Phases 1–4, §4 design review gate, §7 state management, §8 persistence,
§10 security. Evidence labels: **VERIFIED** (code/CI actually executes it,
cited), **CI-verified** (x86_64 AVD, android-verify workflow),
**REFERENCED** (pinned upstream source, not yet exercised on-device),
**PENDING** (owner device run, Phase 6).

Upstream pin: `3c5b227649371507ddc4eb46ac5133afe6db1cab` (zai-org/ZCode).
The pinned method surface is dumped by CI into `rpc-surface.txt`
(android-verify build job, "Pin the RPC channel surface" step) — every
native call below cites that artifact or the source file directly.

------------------------------------------------------------------------

## 1. What is implemented (files changed, per phase)

### Phase 1 — design system (`ui/theme/`, `ui/components/States.kt`)

- Color tokens: z.ai-amber accent over warm stone neutrals; semantic
  status colors (running/completed/failed/approval) defined once
  (`Color.kt`, `Theme.kt` `ZcodeStatusColors`).
- Typography: compact scale, one monospace `CodeStyle` for code/tool/path
  text (`Type.kt`). No oversized display styles.
- Shapes: 4/6/8/12/16 dp radii (`Shape.kt`). Spacing scale
  4/8/12/16/24 (`ZcodeSpacing`) provided via CompositionLocal.
- Full Material 3 light+dark schemes; theme mode owned by the app UI
  (system/light/dark), persisted in `zcode_native` prefs.
- Shared components: `StatusDot`, `SectionHeader`, `EmptyState`,
  `ErrorState` (recoverable, with retry), `LoadingIndicator`,
  `MetadataRow` — used by every screen; no bespoke state styling.

### Phase 2 — native conversation surface (`ui/conversation/`)

- `MainActivity` routes `Running` → `ConversationScreen` (native-only
  decision; the WebView product path and `LocalWebViewScreen` are
  deleted). Diagnostics overlay (LocalApp) reached from the ⋮ menu.
- `ConversationViewModel` owns projection state (§7): tasks list,
  selected task, timeline, running flag, connection state. Conversation
  and agent execution state remain runtime-owned — the VM never
  simulates the engine.
- History restore via `getTaskSnapshot` (honest restore before live
  events), streaming via `onDynamicTaskEvent` (`zcode-task` channel),
  send via `createTask`+`sendPrompt`, stop via `stopGeneration`,
  approvals via `respondPermission` — all restored 0.5.0 stack methods
  over the CI-proven binary WS protocol.
- Process-death restore: selected task id in `rememberSaveable`, handed
  to the VM before `start()`.
- Composer: send/stop toggle, disabled while disconnected
  (`conn != READY`), IME insets handled (`imePadding`).

### Phase 3 — agent activity rendering (same timeline components)

Event → component mapping, each cited to the pinned source shape (the
0.5.0 native shell rendered the same schema):

| Event (`type`) | Row | Notes |
| --- | --- | --- |
| `agent_message_chunk` | AssistantMsg (append) | nested `parentToolUseId` chunks ignored (main thread only) |
| `agent_thought_chunk` | ThoughtMsg (collapsible) | visually secondary |
| `tool_call` / `tool_call_update` | ToolRow (teal, code font, status dot) | running/completed/failed map to status colors; unknown statuses render verbatim |
| `permission_request` | PermissionCard (approval-bordered) | options render as buttons, each carries its own `response.decision` |
| `permission_response` | card removal | |
| `task_run_started` / `task_complete` / `task_error` / `task_warning` | running flag + Notice rows | |
| anything else | **logged verbatim, never rendered** | §12: no fabricated progress |

### Phase 4 — workspace (`ui/workspace/`)

- Read-only file explorer over `file.readdir` / `file.readTextFile`
  and git status over `git.getRepositorySummary` / `git.getChanges`
  (all REFERENCED→CI-exercised shapes from
  `upstream/packages/services/src/file/file.ts` and `git/git.ts`).
  Write operations are NOT exposed (no workspace-file write method at
  this pin) — explicit "read-only" in the top bar.
- Browsing is rooted at the app workspace; failures render as
  recoverable error states, never empty lists (§6).
- Terminal: gated on the PTY evidence probe (see §5 below). It is NOT
  built pending `PTY-PROBE-VERDICT: POSITIVE` on-device.

------------------------------------------------------------------------

## 2. §4.4 design review (AI-slop gate) — conversation + workspace

- **Functional purpose:** chat with the local agent; inspect workspace
  state. Every element serves those tasks: timeline, composer, status
  dot, overflow menu, file list, git card.
- **Deliberate decisions:** single-column workbench layout (not a
  bubble-chat template); user rows use `primaryContainer` only for
  sender identification; tool activity uses the tertiary teal + code
  font so it cannot be mistaken for prose (Phase 3 criterion);
  approval cards use a hard border + status dot in the approval color
  and CANNOT read as completed work.
- **Anti-slop check:** no gradients, no glow, no glassmorphism, no
  decorative illustrations, no fabricated metrics; headings stay
  compact (20 sp max); empty states state the next action only.
- **Accessibility:** touch targets are IconButton (48 dp) and buttons
  (min 40 dp height); status colors meet contrast on both schemes
  (amber-700 on white ≥ 4.5:1; amber-400 on stone-900 ≥ 7:1);
  content descriptions on all status dots and icon buttons; font scale
  follows sp throughout.
- **Known issues / to remove:** with many permission options the
  options row can overflow horizontally (single `Row`) — acceptable
  while options are ≤ 3 (upstream permission prompts offer ≤ 3);
  revisit with FlowRow if wider sets appear. Dark/light verified in CI
  (dark-mode stage A/B on the native surface marker).
- **Evidence that controls act:** each control dispatches a real RPC
  (send/stop/respondPermission/openTask/readdir/readTextFile/
  getRepositorySummary) — no decorative buttons.

------------------------------------------------------------------------

## 3. §7 state ownership (as built)

| State | Owner |
| --- | --- |
| Navigation (product/diagnostics/workspace) | MainActivity UI state |
| Theme mode | MainActivity + `zcode_native` prefs |
| Composer input | Compose `rememberSaveable` |
| Conversation/task data | ZCode runtime (snapshot + task events) |
| Agent execution state | ZCode runtime (`running` is a projection) |
| Workspace state | ZCode runtime file/git services |
| Auth state | OAuth service (runtime) — native sign-in flow NOT migrated yet; marked unavailable, no fake login UI |
| Runtime lifecycle | Android host layer (RuntimeState machine, unchanged) |

Connection handling: `ZcodeRpcClient` auto-reconnects with bounded
backoff and re-arms event subscriptions on `[200] initialize`; the VM
projects `conn` into the status dot and disables the composer while
disconnected. Duplicate event processing is prevented by disposing the
previous listener before re-subscribing (`taskListener?.dispose()`).

------------------------------------------------------------------------

## 4. §8 persistence, backup, reinstallation (audited as built)

- Local sessions/tasks: runtime-owned under
  `files/runtime/zcode-data/.zcode/v2/` (VERIFIED paths, BionicEnv.kt);
  survive restarts (persistence CI stage proves no re-provision).
- Workspace files: app-private `files/workspace`; imported via SAF
  picker (diagnostics overlay). Uninstall deletes them — the recovery
  path is the existing backup/restore overlay (`ZcodeBackup`), which
  **excludes credential paths** (VERIFIED exclusions).
- Credentials: provider config is written by the runtime itself;
  Keystore-backed encryption is NOT implemented. Honest limitation:
  encrypting runtime-owned config would break the runtime's own reads
  without upstream support; recorded here as the open §8 item.
- Reinstall expectation: sign-in does NOT restore local data (no sync
  implementation is claimed anywhere in the UI).

------------------------------------------------------------------------

## 5. §10 local runtime and security (audited as built)

- Server bind: `127.0.0.1:3030` (VERIFIED, ZcodeServer.kt launch args).
- Auth: tokenless loopback (VERIFIED); §10's warning stands — TCP
  loopback is NOT app isolation. UDS comparison remains the least
  invasive hardening candidate and is NOT changed in this phase
  (audit-only per §10 instruction).
- Mock provider bind: **fixed this phase** — `0.0.0.0:9999` →
  `127.0.0.1:9999` (verify-mock-provider.cjs; §10.2 conflict resolved).
- Host/Origin validation: unchanged upstream behavior; the native
  client adds no new origin surface (binary WS, no HTTP product calls).
- Cleartext local traffic: unchanged (loopback only).
- Dead `zcode://oauth` deep link: still registered in the manifest;
  removal belongs to a manifest-cleanup change with the owner's sign-off
  (recorded, not silently changed).

------------------------------------------------------------------------

## 6. Remaining work / known limitations

- Terminal UI: blocked on PTY evidence (pty-probe stage; 0.5.0 device
  report says the android-arm64 prebuild failed to load — the probe
  reproduces or refutes that with named paths).
- Auth/sign-in: not migrated (native OAuth UI absent); the runtime's
  provider config + mock default keeps the agent loop working offline.
- Message actions (copy/share), model picker, voice/image inputs: not
  implemented; none are claimed.
- Phase 6 device validation: owner device run required (see
  DEVICE-VALIDATION.md). CI (x86_64 AVD) is supplementary evidence,
  never a substitute per §5 Phase 6.

------------------------------------------------------------------------

## 7. Phases 7–10 (implemented on this branch; CI/device evidence below)

**Reliability (P7):** `ZcodeRpcClient` now keeps a persistent
`consecutiveFailures` counter (reset on `[200] initialize`) and loops
reconnect attempts with the existing bounded backoff (0.5 s..8 s) until
READY instead of a single fixed attempt per failure; each attempt logs
`reconnect attempt #n`. `call()` logs `rpc requestId=<id> <channel>.<method>`
at dispatch and `rpc TIMEOUT requestId=<id>` on expiry — timeout value
unchanged (20 s). `ConversationViewModel.send()` is serialized through a
single `sendJob` (previous send cancelled first), closing the
double-`createTask` race on fast double-submit. Applied event keys
(`perm-req:`, `perm-resp:`, `complete:`, `error:`) are held in a bounded
256-entry set and deduplicated; `respondPermission` removes `requestId`
from `pendingPermissions` on first response, so a second tap cannot replay
the approval. `onCleared` cancels send/collector jobs and disposes the
listener. PTY verdict stays NEGATIVE — no terminal UI until a positive
on-device `PTY-PROBE-VERDICT`.

**Security (P8):** `Provisioner.requireSafeHttpUrl()` rejects
non-http/https schemes, loopback, private, CGNAT, documentation and
reserved addresses (literal IPs and resolved addresses — every resolved
address must be public), and any host outside the fixed download allowlist
(`nodejs.org`, `cdimage.ubuntu.com`). `ws://127.0.0.1:3030/ws` stays an
explicit, narrow product-socket exception and never goes through the
download path. `ProvisionerSsrfTest` covers the negative cases (loopback,
RFC 1918, CGNAT, TEST-NET, link-local, non-http scheme, allowlist). HTTP/RPC/WS
auth and second-app loopback isolation map to tokenless-loopback runtime
behavior (§10 audit above) — a negative second-app test on a real device
remains DEVICE evidence.

**Release (P9):** signing reads `keystore.properties` / `ZCODE_RELEASE_STORE_*`
env (debug fallback keeps CI green); the targetSdk 28 retention strategy
(library-dir launch path gated on runNodeProbe evidence on the acceptance
device) is documented at the `targetSdk = 28` declaration; Bionic + dist
bundles remain sha256-verified with the sealed-archive builder audit
zero-hardlink assertion; tampered bundles fail provisioning rather than
being silently used. Update-over-install and clean-install remain DEVICE rows.

**Coverage/accessibility (P10):** see FEATURE-MATRIX.md (supported / partial /
unsupported rows with evidence). Controls carry content descriptions,
touch targets ≥ 40 dp / 48 dp; the §4 AI-slop gate in §4.4 of this report
persists. TalkBack, keyboard traversal, and largest font scale remain DEVICE
rows (DEVICE-VALIDATION 17/20).

**VQL targetSdk note:** `writeVql` emits the unsigned 32-bit reinterpretation
of a signed int and the reader re-signs it — matching the pinned upstream
`(n|0)` semantic (documented in `Vql.kt`); lengths/counts (non-negative
upstream) are unaffected. No behavior change.
