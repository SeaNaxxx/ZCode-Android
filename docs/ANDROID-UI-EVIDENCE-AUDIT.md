# ANDROID UI EVIDENCE AUDIT — x86_64-verify-evidence.zip

**Scope.** Forensic audit of historical verification evidence produced by CI run **42339426624** (dev @ `06a2120`, 2026-11-07, x86_64 AVD, API 33). Evidence: 9 screenshots, 2 UI hierarchy dumps, 22 stage logs, 22 result JSONs. Primary source: `x86_64-verify-evidence.zip` (artifact 13560933996). This audit documents what the application **actually showed and did** during past verification runs. No application code was modified.

**Classification legend.** [V] Verified (directly demonstrated) · [PV] Partially Verified · [UV] Unverified · [REG] Regression · [DI] Design Issue.

**Reading rule enforced throughout:** a PASS build/install result is never treated as proof of a UI interaction; only screenshots, UI dumps, and stage-log lines are UI evidence.

---

## 1. Screenshot inventory (all 9 inspected)

| # | File | Device clock | Stage (summary.json timestamp) | What is shown |
|---|------|-----------|-------------------------------|----------------|
| 1 | `screen-before-setup.png` | 4:55 | after install/first launch (offline 04:55:04) | Light theme, blank surface, plain text **"Preparing ZCode…"** left-aligned mid-height. No top bar, no branding, no spinner. |
| 2 | `screen-after-setup.png` | 4:55 | end of setup-tap (04:55:30) | **Pixel-identical to #1.** Provisioning is silent; the Preparing placeholder persists until runtime Ready. |
| 3 | `screen-installed.png` | 4:54 | after install | Device home screen (Pixel launcher). ZCode launcher icon in dock: dark circle, white "Z" glyph. |
| 4 | `screen-running.png` | 4:55 | after start-tap (04:55:48) | Native Home (light): top bar (green dot · "ZCode" / "This device" · ⋮), empty state "No conversations yet" + body copy, blue FAB bottom-right. |
| 5 | `screen-frontend-open.png` | 4:55 | frontend-smoke (04:56:00) | **Identical to #4** — name is a legacy of the retired WebView flow; the "frontend" is now the native surface. |
| 6 | `screen-agent-effects.png` | 5:00 | agent-effects (05:00:28) | Home unchanged ("No conversations yet"). Agent effects (Write/Bash/git) happened on disk via the runtime driver, **not** through app UI. |
| 7 | `screen-after-restart.png` | 5:04 | persistence (05:04:27) | Home now lists a task card: "Run the mock verification task: create a file, run a shel…" + **Idle** pill badge + "just now · `verify-mock/mock-model`" (model id in monospace). Note: the trailing ellipsis is **protocol-provided** — the `session_info_update` event title is already truncated server-side (`…run a shel...`, native-protocol.log 05:04:11.452); the card renders it verbatim. |
| 8 | `screen-dark-legA-light.png` | 4:56 | dark-mode leg A (04:56:24) | Light Home, identical palette to #4. `Night mode: no` → marker `dark=false`. |
| 9 | `screen-dark-legB-dark.png` | 4:56 | dark-mode leg B (04:56:24) | Dark Home: near-black surface, lighter header band, white/gray text, FAB becomes navy container with white plus, status dot brighter green. Marker `dark=true`. |

**No screenshot of the Chat/conversation screen exists** in this or any inspected evidence set. Every visual claim about Chat (bubbles, composer, tool cards) is currently **[UV] at the visual level** — Chat is verified only at protocol level (`native-protocol` stage) and code level.

## 2. Screen-by-screen visual analysis

### 2.1 Runtime placeholder ("Preparing ZCode…", #1–#2) — [DI]
- Unstyled `Text` (MainActivity `Preparing` state), left-aligned at mid-height, default platform font, on a blank `#f8f8f8`-family surface.
- No progress indicator during a ~9 s offline provisioning + ~3 s server start; the user sees a frozen-looking screen for 15–30 s on first run.
- Before/after-setup screenshots being identical is expected (silent provisioning, no setup UI) but proves the first-run experience communicates nothing about what is happening. **Highest-priority design debt on the first-run path.**

### 2.2 Launcher icon (#3) — [V] with licensing caveat
- White "Z" glyph on dark (#101213-family) rounded circle, matching the described derivative of upstream's desktop logo (see BRANDING-AND-ICON-AUDIT.md). Renders correctly in the Pixel dock at dock size.

### 2.3 Home screen, light (#4–#6, #8) — [V]
- **Top bar:** leading green status dot (content-desc `runtime connected`), bold "ZCode", gray "This device" subtitle, trailing ⋮. Thin divider under the bar. Follows upstream mobile pattern: status is never icon-only — a connected/dark caption exists next to the dot (satisfies the "non-connected states need visible text" contract).
- **Empty state:** centered bold heading "No conversations yet"; left-aligned full-width body "Start the first task on this device with the + button. The ZCode runtime runs locally; your prompt goes to the connected provider." — honest copy, no fabricated capabilities. Mixed alignment (centered heading vs left body) reads slightly unresolved [DI, minor].
- **FAB:** light-blue rounded-square container, dark-blue plus. Brand color = the app's current blue primary — deviates from upstream Zai black/white brand (tracked in the design audit; not a regression, a deliberate pre-audit choice).
- **No bottom navigation, no drawer** — stack navigation confirmed visually.

### 2.4 Home screen, dark (#9) — [V]
- System dark mode reaches the native surface end-to-end (`cmd uimode night yes` → Compose dark palette → marker `dark=true`, grep-verified in CI).
- Surfaces switch cleanly; no clipped text, no contrast failure observed; FAB swaps to navy/white (same hue family as light FAB). Status dot brightens. No leftover light artifacts.

### 2.5 Home with a task card (#7) — [V]
- Card: white surface, ~12 dp radius, title shown on 2 lines; the trailing ellipsis is **not a UI clamp artifact** — the `session_info_update` protocol event already carries the truncated string `Run the mock verification task: create a file, run a shel...`, and `ui-dump.xml` shows the rendered TextView text contains the literal `shel...` [V]. UI must therefore be designed for server-truncated titles (not assume it can always re-derive a full title).
- Right-aligned status pill "**● Idle**" (dot + label, gray pill; in the dump the dot carries `content-desc="Idle"` **and** the label text is "Idle" — duplicated a11y naming, harmless). Metadata row "just now · `verify-mock/mock-model`" with the model id in **monospace** (upstream mono-for-technical-strings convention).
- The card row is `clickable=true focusable=true` in the dump ([44,345][1036,565]) — the navigation target exists in the hierarchy, but no screenshot/dump evidences the post-tap state.
- Task persisted across force-stop + relaunch and is listed by `listTasks` after restart.

### 2.6 Cross-stage differences — [V]
- #1/#2 (provisioning) → #4 (Home): the app transitions from placeholder to full Home without user action (silent auto-start).
- #4/#5 (4:55) vs #7 (5:04): task card appears only after the app relaunch; the earlier driver-run task never appeared live (see §4, finding F3).
- #8 vs #9: complete, consistent light/dark pairing of the same screen — the only A/B visual pair in the evidence set.

## 3. UI hierarchy findings (uiautomator dumps)

`frontend-smoke-ui.xml` (Home, empty) — 21 nodes; `ui-dump.xml` (Home, post-restart) — 27 nodes. Both rooted at `ComposeView` inside `rid=content`.

Present and labelled in both dumps [V]:
- Header `View desc="runtime connected"` → `TextView "ZCode"`, `TextView "This device"`; clickable `View desc="Menu"` (⋮).
- Clickable `View desc="New conversation"` (FAB) — has an accessibility description; good.
- Empty state `TextView "No conversations yet"` + body (frontend-smoke dump).
- Task card row (ui-dump): title TextView, `View desc="Idle"` + `"Idle"` text, `"just now"`, `"·"`, `"verify-mock/mock-model"`.

Findings:
- **F-H1 [V]** Interactive elements are few and correctly described (Menu, New conversation). No unlabeled clickables in the dumps.
- **F-H2 [V]** No drawer/bottom-bar nodes — confirms single-column stack IA.
- **F-H3 [V]** Composer absent from both dumps. The frontend-smoke stage itself logs `WARN: composer text not in dump (keyboard state may hide it)`; given the dump is the Home screen (which has no composer), the warning is structurally expected — but it also means **no dump ever captured the Chat composer**. Chat UI remains visually unevidenced.
- **F-H4 [PV]** Nothing in the hierarchy is invisible-but-present; however, since both dumps are Home, any claim about hierarchy defects on other screens is unsupported either way.

## 4. Verification log correlations (timestamps + stages)

Stage chain, all PASS (summary.json), x86_64 AVD, airplane mode ON from 04:55:04 until net-restore at 05:04:32:

| t (UTC) | Stage | UI-relevant evidence |
|---|---|---|
| 04:54:27 | emu-boot | sdk 33, abi x86_64 |
| 04:54:59 | install | streamed install of `zcode-android-x86_64-debug.apk` (173 MB) → `screen-installed` |
| 04:55:04 | offline | airplane ON; 8.8.8.8 unreachable — all later successes are download-free |
| 04:55:2x | setup-tap | provisioning marker written in ~9 s: `{"mode":"bionic","nodeProbe":"v24.22.2 android x64","distSha256":"c6eff85a…","bundleSha256":"35cd7dc9…","provisionedAt":1794027326900}` → #1/#2 show only "Preparing ZCode…" while this runs |
| 04:55:30 | node-probe | embedded node v24.22.2 / git 2.56.0 / bash 5.3.20 / curl 8.23.0 exec on-device |
| 04:55:31 | openssl-offline | TLSv1.3 handshake with zero network, 118 root certs, app-owned openssl.cnf |
| 04:55:48 | start-tap | server healthy ~3 s; `[register] saveProject OK id=9f6e7852…`; TLS diag to api.z.ai logged from app start |
| 04:56:00 | frontend-smoke | `native surface visible after ~6s`; `FRONTEND-SMOKE: native surface VERIFIED (ConversationScreen visible, no fatals)`; composer WARN (§F-H3) → #5 |
| 04:56:00 | diag-snapshot | mem 5.79/3.27 GB, storage 4.5/6.2 GB free; server `/` and `/remote` both 200 text/html 17337 B |
| 04:56:24 | dark-mode | leg A `dark=false`, leg B `dark=true` → #8/#9 |
| 04:56–05:00 | agent-loop + agent-effects | driver session over stdio JSON-RPC (`session/create` mode yolo, mock provider 127.0.0.1:9999); effects on disk: `hello.txt` (Write), `VERIFY-BASH-OK`, git commit `3cbc4f3…`; mock request log shows real tool-call rounds. **Home screen did not change** → #6 |
| 05:00:31 | second-send | `PROBE A readTree(local): OK tasks=0`, `PROBE B resolveSessionDirectories(local): OK`, `SECOND-SEND-REGRESSION-OK` (ownerHostId regression guard) |
| 05:04:04–13 | native-protocol | app's own OkHttp WS client: READY → readProjects (2) → **`listTasks -> 0 existing task(s)`** → createTask `session_fe84a457…` → sendPrompt → live task-event stream (`session_info_update` title "Run the mock verification task…", `task_run_started` with `inputOrigin=desktop`); full round: Write+Bash+Bash(git) tool events, **3 permission_request→respondPermission round-trips** (`allow_once`, responses `true`), `agent_message_chunk` ("VERIFY-MOCK-DONE"), `task_complete stopReason=success`, `mode_update` (modes: build "Ask before changes" / guarded "Autonomous mode" / plan "Plan mode" / yolo), `glm_agent_model_state_update` (thoughtLevel disabled/enabled), snapshot keys `[meta, messages, fileChanges, configOptions, slashCommands, runtime]`; `NATIVE-PROBE-PASS event-types=[Bash, Write, agent_message_chunk, glm_agent_model_state_update, mode_update, permission_response, session_info_update, task_complete, task_network_debug_status, task_run_started] permission-requests=3 concurrent-readProjects-ok=3` |
| 05:04:13 | pty-probe | `PTY-PROBE-VERDICT: NEGATIVE` — node-pty load fails: `Cannot find module './prebuilds/android-x64//pty.node'` (exact missing binary named in evidence; the doubled slash `x64//pty.node` is verbatim in the log) |
| 05:04:27 | persistence | provisioning marker sha256 identical before/after force-stop+relaunch; node re-exec OK → #7 (task card now visible) |
| 05:04:32–35 | net-restore → tls-https → tls-zai → oauth-flow | api.github.com 200; api.z.ai 301 (chain *.z.ai/Sectigo verified); zcode.z.ai device-flow init 200 + poll pending, `OAUTH-FLOW-PROBE-OK` — no credentials used anywhere |

**Additional protocol-level evidence from the native-protocol round [V]:**
- **C4 [V]** The first `Write` tool call **failed** in-session: `tool_call_update … status=failed,error=File has not` (05:04:12.003, message truncated in logcat) — the agent then recovered via two `Bash` calls (`VERIFY-BASH-OK`, git commit) and the turn still completed with `stopReason=success`. Failure→recovery tool sequences are **real, evidenced runtime behavior**; chat tool-card design must accommodate failed tool calls inline, not only success states.
- **C5 [V]** The same round evidences configuration surfaces the settings/model UIs must render: `mode_update` with `availableModes=[build "Ask before changes", guarded "Autonomous mode", plan "Plan mode", yolo]`, `glm_agent_model_state_update` with `thoughtLevel{disabled,enabled}`, driver `state.updated` with model metadata (`contextWindow:200000`, `maxOutputTokens:128000`, reasoning levels), and `getTaskSnapshot` keys including `configOptions` and `slashCommands`.
- **C6 [V]** Cold-display performance: `ActivityTaskManager: Displayed io.github.zcodeandroid.app/.MainActivity: +1s755ms` (frontend-smoke-logcat 04:55:53.182); the `NativeConversation: surface visible dark=false` marker fires at 04:55:56.253, ~3 s later, once the runtime/server are up.

**Key correlations:**
- **C1 [V]** The task card in #7 is the task created by `native-protocol`'s probe (`session_fe84a457`, title identical to the card text **including the protocol-provided ellipsis**), listed after the persistence restart — native Home → `listTasks` → card round-trip is fully evidenced.
- **C2 [V]** Agent effects (#6 stage) were produced by the runtime driver **outside** the app UI; the Home screen correctly stayed empty — but see F3.
- **C3 [V]** Frontend-smoke greps the `NativeConversation: surface visible` marker which HomeScreen emits; the stage name "ConversationScreen visible" in the log overstates what the dump shows (Home). Marker provenance re-verified across all three logcats — identical single-line format, no surface identity: 04:55:56.253 `dark=false` (frontend-smoke), 04:56:10.166 `dark=false` (leg A), 04:56:21.714 `dark=true` (leg B). Marker provenance in code: HomeScreen.kt:83 / ChatScreen.kt:113 — the CI grep cannot distinguish which surface fired. UI-specific smoke value is therefore **Home-only** [PV].

## 5. Historical UI reconstruction (chronological)

1. **Before setup** (04:55, #1): blank light surface + "Preparing ZCode…" [V].
2. **During setup** (silent, ~9 s): no UI change; provisioning completes off-screen [V].
3. **After setup** (#2): identical placeholder until server Ready [V].
4. **Installed state** (#3): launcher icon verified in dock [V].
5. **Running state** (#4/#5): native Home, connected dot, empty state [V].
6. **Frontend opened**: native-only app; stage name is legacy WebView terminology [V].
7. **Agent interaction** (#6): effects on disk via driver; Home unchanged [V]; live UI reflection of agent work — **not evidenced** [UV].
8. **Restart & persistence** (#7): task card listed after relaunch; marker sha stable [V].
9. **Dark/light verification** (#8/#9): system theme reaches the surface both ways [V].

**Supported by evidence:** first-run provisioning flow, silent auto-start, Home (empty + task card) in both themes, launcher icon, task persistence, protocol-level chat round.
**Unknown (never captured):** Chat screen rendering (bubbles, tool cards, model sheet, task-history sheet), composer + IME behavior, task card tap → Chat navigation, Workspace/Diagnostics screens, FAB interaction, any interaction-dependent state (permission request UI, error states, streaming in the UI).

## 6. Confirmed defects, regressions, and design issues

| ID | Class | Finding | Evidence |
|---|---|---|---|
| D1 | **[DI]** | First-run "Preparing ZCode…" placeholder is unstyled, uncentered, progress-free; 15–30 s of silent wait with no feedback | #1, #2; MainActivity placeholder states |
| D2 | **[DI]** | Home task list does not live-refresh when tasks are created by other clients (driver/probe); list appears only after relaunch | #6 (5:00, empty) vs #7 (5:04, card); native-protocol `listTasks -> 0` at 05:04:10 even though **two** driver-created sessions existed minutes earlier (`sess_0741abc1…` via `session/create` in agent-loop, `session_72de6e4e…` via `createSession` in second-send), while the probe's `createTask`-created `session_fe84a457…` *did* surface after restart. Mechanism confirmed at source level: `HomeViewModel.subscribeWorkspaceEvents()` consumes workspace task events **only to update running/completed badges** and never calls `refresh()` — a task created by another client cannot enter the list without explicit refresh/relaunch [V] |
| D3 | **[DI]** | Empty-state mixed alignment (centered heading, left body) | #4 |
| D4 | **[DI]** | Brand color (blue FAB/primary) diverges from upstream Zai black/white brand — pre-audit choice, tracked in the design audit, pending owner decision | #4/#9 vs upstream tokens |
| D5 | **[PV]** | frontend-smoke's "ConversationScreen visible" log wording vs Home-only dump: stage name and marker cannot prove Chat rendering | frontend-smoke.log + frontend-smoke-ui.xml |
| — | **[REG]** | None found. No evidence indicates previously-working UI behavior broke in this run; all 19 stages PASS and every earlier failure in the chain (runs 42335630499, 42338086609) was a compile error fixed before this run | summary.json; CI history |

## 7. Evidence limitations

- **Platform (§F of the brief):** every screenshot/dump/EMU log comes from an **x86_64 AVD, API 33** (`emu-boot.log: sdk: 33 abi: x86_64`; node-probe `arch:"x64"`). Conclusions apply to *app logic* on Android 13-class emulators. ARM64 device behavior (Vivo V2035 acceptance device) is deliberately deferred by CI design; the released APK's bionic runtime is AArch64 (NDK r30, interpreter `/system/bin/linker64` — see ANDROID-RUNTIME-COMPATIBILITY-AUDIT.md). **No ARM64 runtime compatibility claim may be drawn from this evidence set.**
- **No Chat visuals:** no screenshot/dump of ChatScreen exists anywhere in the archive (§2, F-H3).
- **Single-session depth:** only one task ever appears in a screenshot; no long-list, scrolling, permission-request, error, or streaming UI evidence.
- **Driver-vs-UI split:** the agent loop never exercises the native chat UI; UI-level agent rendering (tool cards, bubbles) is untested by this evidence.
- **Screenshot timing:** screenshots are stage-scoped stills; transitions/animations are unevidenced.
- **Stage-name drift:** `frontend-open`, `setup-tap`, `start-tap` are legacy WebView-era names; they no longer describe taps (silent auto-start) — harmless but misleading when read cold.

## 8. Prioritized recommendations

1. **P1 — Close the Chat-visual evidence gap:** add a CI stage (or device-run capture) that opens a conversation, renders an agent reply + one tool call in the native UI, and screenshots/dumps ChatScreen. Until then, treat all Chat visual design as proposal-only.
2. **P1 — Redesign the first-run placeholder** (spinner + runtime state + progress or, minimally, upstream-style centered status text). This is the first thing every new user sees.
3. **P2 — Live task list refresh:** refresh Home on task/workspace events (or on reconnect) so driver/second-client tasks appear without relaunch; document how driver-created sessions map into the task index.
4. **P2 — Tighten the smoke marker contract:** separate markers per surface (`surface visible home` / `surface visible chat`) so CI can assert *which* screen rendered; update the frontend-smoke log wording accordingly.
5. **P3 — Empty-state alignment unification**; adopt upstream text hierarchy for the Home empty state.
6. **P3 — Rename legacy stages** (`frontend-open` → `home-render`, `setup-tap`/`start-tap` → `provision`/`server-start`) or annotate them in the workflow to prevent future evidence misreading.
7. **P3 — Capture ARM64 evidence** on the acceptance device to complement this x86_64 set (device checklist already tracks this).
8. **P3 — Design tool cards for failure states from day one:** the evidenced round contains a failed `Write` followed by Bash recovery (§4, C4); the card component must render failed/recovered tool sequences as first-class states, using the upstream event vocabulary (`status=failed`, `task_error`).

---

*Sources: `x86_64-verify-evidence.zip` (artifact 13560933996, run 42339426624: 22 logs, 23 result JSONs, 9 PNGs, 2 XMLs); repo HEAD 06a2120 read-only inspection. Second verification pass (2026-11-07): all 9 screenshots re-inspected visually, both UI dumps re-read, every stage log and result JSON re-checked against the re-extracted artifact, and marker/refresh claims cross-checked against source (HomeScreen.kt, ChatScreen.kt, HomeViewModel.kt). Cross-references: ANDROID-RUNTIME-COMPATIBILITY-AUDIT.md (binary-level runtime evidence), BRANDING-AND-ICON-AUDIT.md (launcher icon provenance), ANDROID-NATIVE-ARCHITECTURE-AUDIT.md (code-level counterpart of every behavior observed here).*
