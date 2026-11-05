# Migration Plan

> **Direction update (owner decision 2026-11-04 — supersedes success
> criterion #1).** The product surface is UNIFIED on the official ZCode web
> UI: ONE interface, the upstream web bundle served by the local server and
> rendered full-screen in the WebView, with phone adaptation via a
> conservative self-gating CSS layer and the upstream mobile route
> (`/remote`) one tap away in the overflow menu. The Phase 2 native shell
> and the relay remote-control client are REMOVED from the product path
> (the 0.5.0 native shell stays parked in git history, tag 0.5.0). The
> WebView inherits the upstream settings surface (plugins, MCP, skills,
> hooks, usage) for free — rebuilding it natively would take months and
> drift from upstream. Success criterion #1 ("No WebView in any primary
> product flow") is superseded by: *the web UI is the product; app-side
> code shrinks to the runtime host + diagnostics overlay.* The phases
> below remain the historical record of the native direction and the
> runtime-engineering groundwork that still applies (provisioning,
> storage, backup, release engineering).

Transition from "Android host of the upstream ZCode web UI" to an
independent native Android agent platform, in four phases that never break
the current build or the existing device workflow. Owner direction
2026-11; component facts from [DEPENDENCY-AUDIT.md](DEPENDENCY-AUDIT.md),
layer model from [ARCHITECTURE.md](ARCHITECTURE.md).

Ordering rule from the owner: the dependency audit and decoupling come
before any new agent-core or UI construction. Nothing in Phase 2+ starts
until its Phase 1 exit criteria hold.

## Phase 1 — Decouple from Desktop assumptions (current phase)

Done in this phase so far:

- Storage access: legacy external storage permission pair + startup request
  (`ac7ffd6`) — closes the `/sdcard/Download` import blocker.
- Agent search capability: `rg` provisioning kit + CI verification
  (`2c97f9d`).
- Automatic workspace registration at server start (already shipped in
  `ZcodeServer.registerWorkspaceProject`) — the standalone-server
  `project.remote-project-required` contract no longer blocks first tasks.
- Architecture documentation: this plan, the audit, the layer model.

Remaining Phase 1 work, in order:

1. Device-verify the two fixes above on the Vivo V2035 (Android 13):
   Download import via the SAF picker AND direct runtime read of
   `/sdcard/Download`; `rg.sh` real search. Until then they are
   build-verified only.
2. On-device inventory of the Extension System: enumerate
   `dist/zcode/agent/packages`, `provider/`, plugin/skill caches; record
   which load on Bionic (fills the audit's UNKNOWN rows).
3. Test `node-pty` and `ssh2` loadability on-device (audit touchpoint 6).
4. Release engineering foundation: one stable signing key for distributed
   APKs, `versionCode` incremented per release, debug APKs kept separate
   from the published prerelease. Prerequisite for every later phase that
   ships an installable build (the owner-reported update-over-install
   failure traces to per-runner debug keys).

Exit criteria: all audit rows are CONFIRMED/RESOLVED or explicitly
accepted; a release APK installs as an update over a previous release
without data loss.

## Phase 2 — Own the agent engine experience

Status 2026-11-03: items 1, 2, 4 and the sign-in half of 5 shipped as the
Phase 2 MVP (build-verified; on-device chat cycle still pending — exit
criteria below are the gate, not this list):

1. [SHIPPED, device-pending] Native Compose client speaking the documented
   protocol (DEPENDENCY-AUDIT section 4): WebSocket `ws://127.0.0.1:3030/ws`,
   binary frames + tagged serialization (`protocol/Vql.kt`,
   `protocol/ZcodeRpcClient.kt` — byte-exact against the pinned
   `packages/rpc` source), channels `project`, `zcode-task`, `oauth`
   (`native/ZcodeApi.kt`).
2. [SHIPPED, device-pending] MVP surface: task list, conversation (snapshot
   history + `onDynamicTaskEvent` live frames), composer (`sendPrompt`),
   permission prompts (`permission_request` events ->
   `respondPermission`), theme setting (system/light/dark), `This device`
   wording throughout (`native/NativeZcodeApp.kt`). Model/mode pickers are
   wired at API level (`setModel`/`setMode`) and surface in a later
   iteration of the picker UI.
3. [SHIPPED] The web UI stays available as an explicit fallback route:
   settings -> "Open web UI (fallback)" (`LocalWebViewScreen` reused; the
   proven WebView path is untouched). No removal of the WebView before the
   native client covers the same feature set on-device.
4. [SHIPPED, round-trip device-pending] Session persistence: local sessions
   continue to live under `zcode-data/`; export/import now exists
   (`backup/ZcodeBackup.kt`): schema-versioned zip + sha256 manifest,
   credential paths excluded by policy, restore integrity-checked.
5. [SHIPPED, device-pending] Sign-in UX in the native shell: the oauth
   channel's `startOAuth` authorize URL opens the external browser,
   `pollPendingOAuth` completes the loop, `restoreCachedSession` skips
   sign-in when a session exists; `zcode://oauth` deep link registered.

Exit criteria: a full chat cycle (create task, prompt, stream, permission,
stop) works on-device through the native UI with the WebView untouched and
unused; a backup/restore round-trip restores sessions.

## Phase 3 — Android integration

1. Device bridge expansion via public APIs only (BatteryManager, memory,
   storage, thermal where available) — extends the existing
   `DiagReport`/`diag-collector` instruments, no root.
2. Project/file management native (workspace picker over SAF, git status
   via the `git` channel), file upload by share intent.
3. On-device build tooling evaluation (NDK/Gradle from the runtime) —
   feasibility-gated, not a commitment.
4. Optional ADB/Shizuku integration — explicitly deferred until here and
   gated behind user approval per owner non-goals.

Exit criteria: the agent can read device state through the bridge, manage
projects and files natively, and the app needs no WebView for any primary
flow.

## Phase 4 — Expansion

1. APK/DEX/ELF/SO analysis tooling, REA integration, Mesa/Turnip/NDK
   work: only after Phases 1-3 exit criteria hold.
2. Agent-developed tools: a tool lifecycle (create -> isolated test ->
   validate -> register) built on the upstream skills/extension system,
   with the constraint that a tool never grants itself Android
   permissions beyond the app's own.

## Explicit non-goals (owner list, unchanged)

- Do not replace ZCode wholesale before the reuse analysis (this audit) is
  consumed; do not rewrite the Node.js/Bionic runtime without a proven
  technical cause.
- Do not add REA, Shizuku, or ADB before an independent agent loop exists.
- Do not treat OpenAI-compatible endpoints as a substitute for owning the
  agent core.
- Do not allow agent-driven self-update of the installed app without
  review, test, and user confirmation (Android's installer consent is the
  hard gate).
- No verification weakening: TLS stays on, failures stay visible, mock
  results are never presented as provider results.

## Success criteria (product definition of done)

1. No WebView in any primary product flow.
2. No technical runtime-setup screens for users (provisioning stays
   automatic and silent; it already is offline and single-tap).
3. No hosted relay requirement (`remote control` out of the user path).
4. `This device` concept everywhere; no `This computer`.
5. Sign-in never erases or replaces local sessions.
6. APK update over a previous release preserves user data.
7. Uninstall + reinstall + backup restore recovers sessions, workspace
   files, and memory.
8. Native UI with dark and light themes, phone-adapted layout.
9. Tools, files, and memory function for real on-device, verified by the
   existing evidence chain (disk effects, persisted logs), not by build
   success.
