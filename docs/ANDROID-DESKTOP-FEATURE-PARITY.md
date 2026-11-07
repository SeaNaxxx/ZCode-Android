# ANDROID ⇄ DESKTOP FEATURE PARITY MATRIX

**Purpose (task 10.8 / 11.8).** For every ZCode product feature: desktop availability, native-Android availability, runtime support, UI implementation status, verification evidence, and platform interaction differences. **Visual parity ≠ functional parity** — a row can look right and still be non-functional; both columns are judged separately.

**Status vocabulary (per 10.3):**
- **A — Native, fully supported**: native UI + verified runtime operation (device/emulator evidence).
- **B — Native UI, verified runtime integration**: native UI drives the real runtime; end-to-end evidence exists at protocol level, visual evidence may be partial.
- **C — Native UI, documented limitation**: shipped with an explicit technical constraint.
- **D — Desktop-only**: exists on desktop; no Android path; reason stated.
- **E — Not implemented**: absent on both Android UI and (where relevant) runtime.

**Evidence keys:** `[CI:<stage>]` = CI run 42339426624 stage PASS · `[SRC:<path>]` = source/file read · `[EV:<evidence item>]` = x86_64-verify-evidence item · `[UV]` = unverified.

## 1. Core product areas

| Feature | Desktop (3.16.x pipeline) | Native Android | Runtime (embedded 3.16.2) | Status | Interaction differences | Evidence |
|---|---|---|---|---|---|---|
| Workspace/project selection | Sidebar projects (`project-sidebar/`), localStorage width, groups | Home = task list of the single device workspace; project auto-registered at server start | `project.saveProject` on first start | **B** | Desktop: multi-project tree. Android: one local workspace (`files/workspace`) by design; no project switcher | [SRC:ZcodeServer.kt] [CI:start-tap `[register] saveProject OK`] |
| Task/session list | `TaskList.tsx`, grouped rows, plan badge | HomeScreen task cards (status badge, model in mono) | `listTasks`, workspace events | **B** | same data source; Android adds no groups yet; foreign-created tasks now appear on ON_RESUME `listTasks` re-read (D2 fix); badges stay event-driven | [CI:native-protocol listTasks] [EV:screen-after-restart] [SRC:HomeScreen.kt] |
| Chat / conversation | `v4/SessionPane` (5982 ln), turn groups, ai-elements messages | ChatScreen: reversed LazyColumn, bubbles, minimal markdown | `sendPrompt`, `getTaskSnapshot`, 10 event types | **B** | Desktop: Lexical editor, attachments, mentions, slash commands. Android: plain composer + SAF attach; no mentions/slash | [CI:native-protocol sendPrompt+events] [EV: — Chat visuals [UV]] |
| Message composition | Lexical + IME fallback + drafts store | `ChatInputBar` (24dp composer), `rememberSaveable` draft | same sendPrompt path | **B** | Touch-first vs keyboard-first; IME behavior on device [UV] | [SRC:ChatInputBar.kt] |
| Agent activity / tool execution | 68 tool renderers, trajectory pane, background works | `ToolCard` (name/title/status; no output payload in pinned schema) | `tool_call`, `tool_call_update` events | **C** | Desktop renders rich per-tool bodies; Android renders compact cards — payload depth limited by event schema at pin | [SRC:ToolCallBlocks/] [SRC:ToolCard.kt] |
| Agent loop (real run) | host utilityProcess + agent CLI | runtime driver: same agent bundle | `session/create`+`session/send` over stdio; Write/Bash/git effects | **A** (runtime-level) | identical agent runtime; Android gates UI claims by effects | [CI:agent-loop, agent-effects] |
| Model selection | `ModelConfigSelect` dropdown, provider families | model-picker bottom sheet (`getTaskConfigOptions`/`setModel`) | `model-selection` channel | **B** | desktop hotkey Ctrl+M; Android sheet | [SRC:ConversationModel.kt:492–590] |
| Mode / reasoning level | composer mode controls | Mode sheet driven by the runtime's own `getTaskConfigOptions` (category=="mode" && type=="select"); `setMode` sent with the option value verbatim; honest empty sheet when the runtime offers none | `setMode` available | **B** (runtime-driven) | desktop hotkeys; Android: ⋮ menu → Mode | [SRC:ConversationModel.kt] [SRC:ZcodeApi.kt:189] |
| Permissions (agent asks) | ask-question / interaction surfaces | exactly-once `respondPermission` handling | `permission_request/response` | **B** | no visual evidence of the prompt UI [UV] | [SRC:ConversationModel.kt:473–486] |
| Stop generation | composer stop | send/stop toggle button | `stopGeneration` | **B** | — | [SRC:ConversationModel.kt:464] |
| Session persistence | tasksStorageWorker + DB | runtime-owned sqlite; survives force-stop | `provision-complete.json`, marker sha stable | **A** | — | [CI:persistence] [EV:screen-after-restart] |

## 2. Settings (desktop sections → Android plan)

Desktop settings = state-based layered page, 18 sections in 3 groups (`settingsPageConfig.ts`) [SRC]. Android now ships a **native Settings screen** (Home/Chat ⋮ → Settings; Route+Screen pattern) with the desktop's group structure; appearance moved out of the Diagnostics overlay (single source). Mapping after the 2026-11 implementation wave:

| Desktop section | Android status | Notes / limitation |
|---|---|---|
| General | **C** | native Settings General group: device/runtime facts (version, model, ABIs, runtime state); runtime-side settings untouched |
| Appearance (system/zai-light/zai-dark, UI font 12–20, code themes ×2, line numbers, wrap, code font) | **B** (theme modes) / **C** (text size) | theme mode System/Light/Dark chips (Settings + ⋮ quick cycle; prefs `theme_mode`); three-step UI text-size scale (Small/Default/Large → Density fontScale ×0.90/1.00/1.15, composes with the system accessibility scale); code-preview settings absent; brand-token decision note below stands |
| Model (provider settings) | **C** | Settings Model group points to the per-task picker (top-bar Model button); provider config file runtime-owned |
| Browser Use | **D** | desktop computer-use stack; no Android runtime equivalent |
| Keyboard Shortcuts | **D/C** | desktop shortcut registry; Android: external keyboard support unevidenced |
| Memory / Subagents / Plugins / MCP / Skills / Commands / Hooks | **E (UI, honest)** | listed in Settings Agent-capabilities group with an explicit "Not on Android" state (10.3); no fake toggles |
| Remote devices | **D** | desktop relay removed from app (2026-11-04 decision) |
| Usage stats / Archives | **E (UI, honest)** | listed in Settings Data group with "Not on Android" |
| Computer Use | **D** | platform-gated on desktop already |

## 3. Workspace, files, terminal

| Feature | Desktop | Native Android | Status | Evidence |
|---|---|---|---|---|
| File browser | `WorkspaceFileTree`, file nodes | WorkspaceScreen read-only explorer | **B** (read-only by design — no write method at pin) | [SRC:WorkspaceViewModel.kt] |
| File editor | read-only viewers (shiki/pdf/docx/xlsx/pptx), diffs `@pierre/diffs` | **E** | no editor at pin | [SRC:PreviewPane.tsx] |
| Terminal | xterm + node-pty (host process) | **C — gated**: PTY-PROBE NEGATIVE (no bionic node-pty) | terminal UI unbuilt until bionic binary exists | [CI:pty-probe] [AUDIT:RUNTIME §2] |
| Git | `GitPane`, git-graph | summary + status read-only | **B** (read-only) | [SRC:git.getChanges] |
| Preview/share | share-ui, web remote control | **D** (relay removed) | — | [SRC:WEBVIEW-DECISION.md] |

## 4. Appearance & theming

| Capability | Desktop | Native Android | Status |
|---|---|---|---|
| Theme modes | system / zai-light / zai-dark (`zcode-theme`) | Settings chips + ⋮ quick cycle (prefs) | **B** — semantic tokens differ pending approval (brand black/white vs current blue) |
| Dark propagation | html classes + CSS vars | Compose `darkColorScheme` via system config (`cmd uimode` verified) | **A** [CI:dark-mode legs A/B] [EV:screens 8–9] |
| WebView↔native theme sync | n/a (file:// renderer) | **requires explicit mechanism** — Compose theme does not change WebView CSS; not built | **E** (documented per 10.5) |
| UI font size 12–20 | `--ui-font-size` token | **C** — native three-step text-size scale (0.90/1.00/1.15 multiplier over the system font scale; sp-only, dp geometry untouched); mobile simplification of the desktop slider |
| Independent code themes / line numbers / wrap / code font | `zcode-code-preview-settings` | not implemented | **E** |

## 5. Desktop WebView experience on Android (10.2)

| Requirement | State |
|---|---|
| WebView preserved as desktop compatibility surface | The embedded server serves the real desktop SPA (`GET /` 200, Zai tokens) [CI:contract-api]; **but no WebView is instantiated in the app today** — the desktop surface is reachable by the runtime, not wired to a screen (WEBVIEW-DECISION.md; WebView black-screen defect documented in ENGINEERING-NOTES §5.4) |
| External display / Samsung DeX | **E** — nothing built (multi-window/resizable activity config unverified) |
| Keyboard/mouse interactions | **UV/E** |
| Constraint honored | native screens never depend on WebView [SRC:grep no-WebView] |

## 6. Platform limitations (explicit, per 10.3)

1. **Terminal**: blocked on a bionic `node-pty` build (RUNTIME-AUDIT §2) — no desktop-equivalent terminal until then.
2. **Computer Use / Browser Use**: desktop-only stacks, no Android runtime path.
3. **File writes / editor**: upstream file channel exposes no write methods at the pin → Android file UI is read-only by contract, not by choice.
4. **Remote control / relay**: feature removed from the app by owner decision (2026-11-04); desktop relay exists but is out of scope for Android parity.
5. **ARM64 proof**: all runtime PASSes are x86_64-emulator-level; ARM64 device acceptance is a separate gate (acceptance device checklist).

## 7. Visual vs functional parity summary

- **Recognizably ZCode:** partial — layout/terminology/mono conventions already match; **brand tokens do not** (blue vs upstream black/white) → design-system decision pending owner approval.
- **Functionally parity-complete areas:** provisioning, server lifecycle, agent loop, task persistence, theme propagation, read-only workspace.
- **Parity gaps that matter:** tool-activity depth (§1), editor/terminal (§3), appearance granularity beyond text size (§4), DeX/WebView desktop surface (§5).

## 8. Implementation wave 2026-11 (owner-approved "start implementation")

| Change | Parity effect | Evidence class |
|---|---|---|
| Native Settings screen (`ui/settings/SettingsScreen.kt`, Route.Settings) with General/Model/Agent-capabilities/Data groups; theme moved out of Diagnostics | §2 General E→C, Appearance E→B/C, Model E→C, capability rows now honest E listings | [SRC] — CI/device verification pending |
| UI text-size setting (Small/Default/Large) plumbed through `ZcodeTheme(uiTextScale=…)` as a Density fontScale multiplier | §4 UI font size E→C | [SRC] — visual verification pending |
| Mode sheet: `getTaskConfigOptions` category=="mode" + `setMode` (option value verbatim; honest empty sheet) | §1 Mode E(UI)→B | [SRC] — runtime behavior on emulator/device pending |
| Home ON_RESUME `listTasks` refresh (D2) | §1 task list: foreign-created tasks no longer restart-gated | [SRC] — CI/device verification pending |
