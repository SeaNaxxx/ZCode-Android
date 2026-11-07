# ANDROID NATIVE ARCHITECTURE AUDIT

**Repo state audited:** `/home/z/zcode-android` dev @ `06a2120` (2026-11-07). Everything is **[V] VERIFIED** by source read unless **[I] INFERRED**. `A/` = `android/app/src/main/java/io/github/zcodeandroid/app/`.

## 1. Entry points

- **Manifest:** permissions `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE(_DATA_SYNC)`, `POST_NOTIFICATIONS`, `READ/WRITE_EXTERNAL_STORAGE` (targetSdk-28 legacy storage is load-bearing for `/sdcard/Download` by the runtime); application `allowBackup=true`, `networkSecurityConfig` = cleartext **only** for `127.0.0.1`/`localhost`; components: `MainActivity` (exported, MAIN/LAUNCHER, deep link `zcode://oauth`) + `.local.LocalServerService` (non-exported, `foregroundServiceType="dataSync"`). **No WebView activity.**
- **MainActivity.kt (304 ln):** the app is **native-only** — there are no Route/Screen mode flags at this HEAD. Navigation = sealed `Route` table (`Home`, `Chat(taskId, openMostRecent)`, `Workspace(returnTo)`, `Diagnostics(openBackup, returnTo)`) driven by `mutableStateOf` — no Navigation-Compose dependency. Startup: theme pref restore → `Provisioner.isProvisioned()` → `RuntimeBus(Ready)` or `provision()` → `LocalServerService.start()`. Preparing/Failed/Stopped are plain-`Text` placeholder states (see UI evidence audit D1). Dark-mode workaround via `UiModeManager.nightMode` in `attachBaseContext` (pre-Q targetSdk-28 apps don't get night mode from Configuration). `BuildConfig.DEBUG` → `WebView.setWebContentsDebuggingEnabled(true)` — the **only** WebView API call in the app.

## 2. Screen inventory (all Compose, all real — no stubs)

| File | Purpose |
|---|---|
| `ui/LocalApp.kt` (386 ln) | Diagnostics overlay (`Route.Diagnostics`): runtime state + %, workspace SAF import, backup/restore, theme chips, diag export, monospace log console |
| `ui/home/HomeScreen.kt` (349 ln) | Home dashboard: task cards via `listTasks` + status badges, FAB new chat, ⋮ menu (Workspace/Diagnostics/theme), connection dot; **CI marker L83** |
| `ui/home/HomeViewModel.kt` (161 ln) | `ZcodeRpcClient("ws://127.0.0.1:3030/ws")` + `listTasks` + workspace-event badges; badges never invented |
| `ui/conversation/ChatScreen.kt` (598 ln) | Native chat: reversed LazyColumn, task-history bottom sheet, model-picker bottom sheet, SAF attach, ⋮ menu; **CI marker L113** |
| `ui/conversation/ConversationModel.kt` (617 ln) | ViewModel: snapshot→timeline projection, stream-event handler, serialized send (project resolution + `createTask`), stop, exactly-once permission response, model options via `getTaskConfigOptions`/`setModel` |
| `ui/workspace/WorkspaceScreen.kt` (354 ln) | Read-only file explorer + git status ("This device · read-only") |
| `ui/workspace/WorkspaceViewModel.kt` (237 ln) | `file.readdir/readTextFile`, `git.getRepositorySummary/getChanges`; no write ops by design |
| `ui/components/{MessageBubble,ChatInputBar,ToolCard,States,ZCodeTopBar,ComponentPreviews}.kt` | Bubbles + minimal markdown renderer; rounded-24 composer; dark terminal-style tool card; status dot/badges/empty/error states; top bar; light+dark @Previews |

**Honest gaps (verified absences):** ToolCard renders name/title/status only (no output payload exists on the pinned event schema); Workspace is read-only; no Chat visual evidence exists from device runs (see ANDROID-UI-EVIDENCE-AUDIT.md §2/§7).

## 3. Runtime manager (embedded Node lifecycle)

- **`local/Provisioner.kt` (592 ln)** — two modes. **Bionic (default)**: verify sha256 + extract `bionic/prefix.tgz` → `<filesDir>/runtime/usr` (linker-critical sonames checked as REAL files against `prefix.critical.txt`), extract `zcode-dist.tgz` → `runtime/dist/zcode`, stage offline agent kit, on-device **node exec gate** (`runNodeProbe`, failure classes: CANNOT LINK / OpenSSL config / Termux-path leak), OpenSSL config + CA self-heal, seed mock provider config + `.gitconfig`, write `provision-complete.json` (`{"mode":"bionic","nodeProbe":…,"distSha256":…,"bundleSha256":…,"provisionedAt":…}`). Legacy proot mode: downloads Ubuntu rootfs + Node from nodejs.org (SSRF-guarded allowlist {nodejs.org, cdimage.ubuntu.com}, official SHASUMS verification).
- **`local/BionicEnv.kt`** — pure-JVM env contract (unit-tested): `PREFIX, HOME, TMPDIR, PATH, LD_LIBRARY_PATH, LANG=C.UTF-8, NODE_OPTIONS="", GIT_EXEC_PATH, ZCODE_DATA_BASE_DIR` + TLS wiring `OPENSSL_CONF, OPENSSL_MODULES, SSL_CERT_FILE, NODE_EXTRA_CA_CERTS, ZCODE_AGENT_CA_CERT, GIT_SSL_CAINFO…`; **never** disables TLS verification.
- **`local/ZcodeServer.kt`** — bionic launch: start embedded mock provider (`verify-mock-provider.cjs --port 9999`, 15 s health wait) then `<usr>/bin/node <dist>/zcode/bin/zcode.mjs --web --no-open --host 127.0.0.1 --port 3030 --workspace <filesDir>/workspace`; stdout pumped to RuntimeBus + `files/logs/server.log`; HTTP health poll 120 s; TLS/OAuth probes + workspace project registration + diag staging. Proot path retained for legacy mode.
- **`local/LocalServerService.kt`** — foreground dataSync service owns lifecycle; UI never spawns Node. `ensureRuntime()`: owned live process + port OK → no-op; port answers w/o handle → `adoptIfHealthy` (no duplicate spawn); dead → restart. `START_REDELIVER_INTENT`; `ACTION_STOP` kills leftover node via `pidof`; `onDestroy` deliberately does NOT kill Node; diag-report loop every 5 min. Crash recovery is **service-start-driven** (adopt/restart), not an in-process watchdog; WS reconnection (0.5→8 s bounded backoff) is independent.
- Ports: 3030 server, 9999 mock provider — both `127.0.0.1`-bound only.

## 4. Native ↔ runtime bridge

- **`protocol/ZcodeRpcClient.kt` (511 ln):** OkHttp WebSocket → `ws://127.0.0.1:3030/ws`; wire = **13-byte header** `[type u8][id u32 BE][ack u32 BE][len u32 BE]` + payload, fragment reassembly; message-type constants pinned to upstream commit (`REQUEST_PROMISE=100, REQUEST_EVENT_LISTEN=102/103; RESPONSE_INITIALIZE=200, PROMISE_SUCCESS/ERROR…, EVENT_FIRE=204`); bounded reconnect while `wantsConnection`; `CallCompleted` wrapper fixes void-RPC false-timeouts; watchdog logs; `healthProbe()` = `project.readProjects`; server→client requests answered empty-success.
- **`protocol/Vql.kt` (293 ln):** byte-exact `@zcode/shared` VQL codec (tags 0–6, 7-bit LE varint, signed-int32 semantics, JSON fallback) — 16 unit tests.
- **`native/ZcodeApi.kt` (296 ln):** typed wrappers over channels `zcode-task, project, oauth, setting, model-selection, file, git` — `readProjects/findRegisteredProjectId/initializeWorkspace/listTasks/createTask (projectId + deferPersistenceUntilFirstPrompt + permissionScope:"session")/sendPrompt (traceId, clientId="zcode-android-native", clientMode="desktop-continuous")/stopGeneration/respondPermission/getTaskSnapshot/getTaskConfigOptions/setModel/taskEvents/workspaceEvents/file.readdir/file.readTextFile/git.getRepositorySummary/git.getChanges/oauth.getProviders|startOAuth|pollPendingOAuth|restoreCachedSession|logout`. **Dead code [V]:** `setMode` (no UI callers), `CHANNEL_SETTING`/`CHANNEL_MODEL_SELECTION` constants unused.
- **Event handling (`ConversationModel.kt:287–390`):** `agent_message_chunk` (skips nested parentToolUseId), `agent_thought_chunk`, `tool_call`, `tool_call_update`, `permission_request/response` (256-key dedup), `task_run_started/complete/error/warning`; unknown events logged verbatim, never rendered.

## 5. WebView ownership — none in the product

No WebView is instantiated anywhere [V: grep over `src/main`]. The loopback server is used only as HTTP health target + WS endpoint. `docs/architecture/WEBVIEW-DECISION.md` is current: product surface native-only; WebView retained as limited technical capability (3 categories: user-opened trusted external content, read-only docs/preview, future security-reviewed web tools); never a fallback for conversation/workspace/settings/terminal/agent. **Stale-doc finding [V]:** `docs/ENGINEERING-NOTES.md` §3 (L112–114) still claims "web UI shown in a WebView (LocalApp.kt)" and §5.7 references a `config-web-url` override — neither matches HEAD (0 grep hits); both predate the native-only decision (2026-11-05).

## 6. Persistence

- SharedPreferences `"zcode_native"`: **only** `theme_mode`. UI route saved via `onSaveInstanceState`; composer text + restored task id via `rememberSaveable`.
- Provisioning marker `<filesDir>/runtime/provision-complete.json` survives restarts/updates (validated layout check on read).
- Runtime workspace `<filesDir>/workspace` (bound as `/workspace`); runtime data `<filesDir>/runtime/zcode-data` via `ZCODE_DATA_BASE_DIR` (provider config at `zcode-data/.zcode/v2/provider_config.json`); sessions/tasks are runtime-owned sqlite — restart persistence proven by CI `persistence` stage (marker sha identical, task card listed post-restart).
- Logs: `files/logs/{server.log, webconsole.log(+.old, 512 KiB rotation), diag-report.json}` (redacted, atomic writes). Backup: schema-v1 zip + sha256 manifest, credential paths excluded, traversal-guarded restore; `backup_rules.xml` untouched sample templates (cloud backup = platform default for `allowBackup=true`).

## 7. Theme — current values (the deviation)

- `ui/theme/Color.kt`: light primary **`Blue60 = 0xFF2E6BE6`**, dark **`Blue80 = 0xFF8AB4F8`**; warm-stone neutrals; amber accent (status-only); teal tool-activity; terminal surface; semantic status colors (running/ok/fail/pending). 
- `Theme.kt`: full M3 schemes; `ZcodeTheme(darkTheme = isSystemInDarkTheme())` default, MainActivity forces resolved mode (THEME_SYSTEM/LIGHT/DARK); **CompositionLocals `LocalZcodeStatusColors`, `LocalZcodeTerminal`, `LocalZcodeSpacing`, `LocalZcodeRadii`** + `ZcodeThemeValues` accessor. Dimens 4/8/12/16/24 spacing, radii 8/12/16/24; Type compact scale + `CodeStyle` mono 12.5 sp.
- **Deviation from upstream (V):** upstream brand/primary = `#000000`/`#ffffff` (Zai Light/Dark), background `#f8f8f8`/`#161616`, foreground neutral-800/300, success/warning/destructive/idle palette per DESKTOP-UI-ARCHITECTURE-AUDIT.md §3. The CompositionLocal extension structure can carry upstream values without touching call sites — implementation is a values swap, pending owner approval of the design system.

## 8. CI contract hooks

- Markers [V]: `HomeScreen.kt:83`, `ChatScreen.kt:113` → `Log.i("NativeConversation", "surface visible dark=$dark")` (inspection-mode guarded); `ConversationModel.kt:386` unknown-event log. CI greps `NativeConversation:? surface visible` + `surface visible dark=false|true` (frontend-smoke L901–936, dark-mode L956–998). Marker cannot distinguish Home vs Chat (evidence audit D5/P2 rec).

## 9. Platform limits & build

- `build.gradle.kts`: compileSdk 36.1, **minSdk 24**, **targetSdk 28 (deliberate)** — keeps exec() of extracted Linux userland permitted under Android 10+ W^X; documented 3-point strategy incl. the on-device node probe gate (Vivo V2035 / Android 13 acceptance device). Java 11; Compose BOM 2026.02.01; deps: commons-compress 1.27.1 + xz 1.10 (provisioning), okhttp 4.12.0 (WS), material3 + icons-extended, lifecycle, org.json (test). No navigation/DI/Room libs. Signing: stable release key from gitignored `keystore.properties`/env, falls back to debug key.
- **Not implemented natively (honest inventory):** file editor/diffs, terminal PTY UI (gated: `PTY-PROBE-VERDICT NEGATIVE`), native sign-in UI (OAuth channel wired + deep link, no screen), mode picker UI, message actions/voice/image upload, provider/settings screens (theme only today), Memory/MCP/Plugins/Skills/Subagents UI, workspace file writes (no write method at pin), trusted-external WebView category.

## 10. Unit tests — 56 (V)

`protocol/VqlTest` (16), `local/WebDiagnosticsTest` (11), `local/TarExtractorTest` (8), `local/BionicEnvTest` (8), `local/ProvisionerSsrfTest` (6), `local/DiagReportTest` (6), `local/ApiCompatSourceTest` (1) + 2 instrumented tests (`NativeProtocolProbeTest` = the CI live WS chat round). No ViewModel/UI unit tests — UI verified via emulator stages instead.

## 11. Cross-cutting notes

1. Runtime crash recovery is service-start-driven; an in-process crash watcher does not exist [V] — acceptable today (foreground service + redelivery), worth a dedicated decision later.
2. `RuntimePaths.distShaFile` appears unused; minor dead code alongside §4's.
3. Security posture is tight: loopback-only cleartext, non-exported service, SSRF allowlist, sha256-verified provisioning, TLS never disabled, redacted diagnostics.
4. The native app satisfies 10.7's bar where implemented: every active control maps to a verified RPC/event or is honestly absent — the remaining risk is visual (Chat unverified) and coverage (§9 list).
