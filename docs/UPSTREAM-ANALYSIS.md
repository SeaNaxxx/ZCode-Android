# Upstream Analysis — zai-org/ZCode

Phase 1 deliverable of the ZCode-Android roadmap. This document records evidence-backed
findings about the official ZCode source and distribution, the `app-server` protocol, and
the native/platform dependencies that decide runtime feasibility on Android.

Per the roadmap operating rules, this document separates **verified facts** (with exact
source references), **artifact observations**, **hypotheses**, and **untested assumptions**.
No Android compatibility, authentication, or agent-execution claim is made here.

---

## 1. Pinned references

| Ref | Value | Notes |
| --- | --- | --- |
| Upstream commit under analysis | `3c5b227649371507ddc4eb46ac5133afe6db1cab` | `main` HEAD at analysis time; root `package.json` version **3.16.2** (untagged) |
| Upstream latest tag | `v3.15.1` = `aac4755666d09fdcd70272fcf063c077a639015f` | main is ahead of this tag |
| Upstream latest published release | `v3.14.3` = `29628c9acdb81b703bbd4080c207a0e7ce5e276e` | published 2026-09-24 |
| Release v3.14.3 assets | `ZCode-3.14.3-win-x64.exe` (143.1 MB), `ZCode-3.14.3-mac-arm64.dmg` (176.8 MB) | **No Linux assets are published in the release** |
| This repository | `SeaNaxxx/ZCode-Android`, private, independent (not a fork) | `main` = `88e3db7c51a73a6adc7f6094a5522f3a204c8c8f` (README + .gitignore only, 3 commits, no workflows, no `dev` branch at analysis start) |

Commands executed (all read-only, exit code 0): `git clone` / `git fetch` of upstream,
`git show <ref>:<path>`, `git grep -n <pattern> <ref> -- <path>`, GitHub REST
`/repos/zai-org/ZCode/releases`, `/repos/SeaNaxxx/ZCode-Android/*`.

---

## 2. Upstream repository layout

Source: `pnpm-workspace.yaml` @ `3c5b2276`.

- Workspaces: `apps/dev-docs`, `packages/*`, `apps/zcode-cli`, `apps/zcode-cli/packages/*`,
  `apps/zcode-cli/tools/*`.
- Toolchain: root `package.json` — `engines.node >= 24.0.0`, `packageManager: pnpm@10.33.2`.
- Platform policy (verified fact, material for Android):
  `pnpm-workspace.yaml` sets `supportedArchitectures.libc: [current, glibc]` — the
  dependency install policy is **glibc-only** (no musl/bionic variants are fetched by pnpm).
- `allowBuilds` includes `node-pty`, `ssh2`, `esbuild`, `koffi`, `electron` (build-time
  script permissions, not runtime requirements).

Main packages relevant to this project:

| Package | Role |
| --- | --- |
| `apps/zcode-cli/packages/cli` | The agent CLI (`@zcode/cli`), bundles to a single-file `dist/zcode.cjs` |
| `packages/server` | HTTP server (`entry-http.ts`) + web-mode host services (Hono) |
| `packages/web` | Web client (Vite build, served statically by the HTTP server) |
| `packages/services` | Host-side service layer, incl. the agent process manager and its protocol client |
| `packages/shared` | `zcode-protocol` message schemas and method registries |
| `packages/desktop` | Electron desktop app (excluded from our path; see §9) |

---

## 3. Agent entry point and build outputs

Verified facts:

- `apps/zcode-cli/packages/cli/package.json` — `bin: { zcode: "./dist/zcode.cjs" }`,
  `build: node scripts/build.mjs`. The build produces a **single-file CommonJS bundle**
  `dist/zcode.cjs` (pure JavaScript; no native code in the agent bundle itself).
- Sub-command recognition: `apps/zcode-cli/packages/cli/src/arguments.ts:121,125` —
  positionals `app-server` and `agent-server` (aliases) are first-class CLI commands.
- `--surface` is accepted for `--prompt`, `--target`, `app-server`, `agent-server`
  (`src/run.ts:62`).
- `zcode login` exists: `src/login-command.ts:17` —
  `Usage: zcode login [zai|bigmodel] [--no-browser]`; only providers `zai` and `bigmodel`
  are accepted. OAuth authorize-URL flow with browser open; `--no-browser` prints the URL
  instead (same-device browser use is expected to work).
- stdout purity for protocol mode is enforced: `src/protocol-console.ts:6-19`
  (`installStderrConsoleBoundary`) redirects `console.*` to stderr so that stdout carries
  only protocol frames; `src/main.ts:31` documents stdout as a strict frame channel.

Distribution packaging (verified facts):

- `scripts/build-zcode.mjs` stages `dist/zcode/<v>/zcode/`:
  `bin/zcode.mjs` (the runner, `:199-204`), `web/`, `server/`,
  `agent/zcode.cjs` + `agent/provider/zcode-builtin.json` (asserted `:167`, staged `:188`),
  plus a `node_modules` runtime closure.
- Runtime closure contents: `scripts/zcode-distribution/assets.mjs:12+`
  `runtimePackageNames` = `@hono/node-server`, `@hono/node-ws`, `ssh2`, `node-pty`,
  `undici`, `axios`, `form-data`, `combined-stream`, … — **no Electron, no koffi, no
  esbuild in the runtime closure** (those are build/desktop-time only).
- Built-in provider registry is generated from
  `packages/provider-node/src/zcode-builtin-release.ts` by `scripts/builtin-provider-config.mjs:44,51-52,70`
  into `zcode-builtin.json` (production) — no secrets embedded at build time; provider
  account config is user-supplied at runtime.

---

## 4. The `app-server` protocol (interface boundary candidate)

This is the protocol the desktop host uses to drive the agent; it is the primary candidate
boundary for an Android client. All references @ `3c5b2276`.

- Implementation chain:
  `apps/zcode-cli/packages/cli/src/run.ts:256-286` (`runZCodeProtocolCommand`) →
  `apps/zcode-cli/packages/bootstrap/src/zcode-protocol-entrypoint.ts` (`runZCodeProtocolAgent`) →
  `.../bootstrap/src/zcode-protocol/server.ts` (`ZCodeProtocolAgentServer`) and
  `.../bootstrap/src/zcode-protocol/transport.ts` (`ZCodeProtocolNdjsonConnection`).
- **Framing: NDJSON over stdio.** One JSON message per line on stdout; requests, responses,
  notifications, and errors share one union:
  `packages/shared/src/zcode-protocol/index.ts` — `zcodeProtocolMessageSchema =
  union(request, notification, response, error)`; `zcodeProtocolMethods` registry at
  `:4109`. EOF drain window is 100 ms (`transport.ts:25`).
- **Method surface** (subset, from `zcodeProtocolMethods`):
  `runtime/capabilities`; `session/create|resume|list|subagents|read|messages|events|
  debug|subscribe|send|stop|cancelBackgroundTask|fork|compact|goal|close|setModel|
  setThoughtLevel|setMode`; `workspace/readPresentation|hooks/trustGrant|
  updateInteractionPreferences|updateModelIoPreferences|updateOffPeakToolPolicy|
  updateDynamicWorkflowPolicy|generateText|cancelGenerateText`;
  `provider/updateAccountConfig|refreshRegistry|testModelConnectivity`;
  `mcp/list|readResource|ui*`; `plugins/*`. A second plane `V4_METHODS` /
  `V4_NOTIFICATIONS` exists in `@zcode/shared/zcode-protocol-v4` (surface size TBD, §11).
- **Notifications** include `startup/storageState` (database startup phases:
  `checking → waiting_for_lock → migrating → committing → ready | failed`),
  `process/mcpTelemetry`, `process/resourceSample`, `plugins/operationProgress`.
- **Lifecycle**: the host spawns the agent process and speaks NDJSON; the reference host
  client is `packages/services/src/zcode-agent/zcodeAgentProcessManager.ts` (process
  resolution §7; protocol client used by the desktop/web hosts).
- **Android is explicitly anticipated**: the telemetry platform enum in
  `packages/shared/src/zcode-protocol/index.ts` (`zcodeMcpTelemetryPlatformSchema`)
  includes `"android"`.
- Concurrency: `transport.ts:27-43` marks specific MCP-UI/plugin-read methods as
  concurrent/immediate — evidence that a third-party client must tolerate out-of-order
  notification delivery interleaved with responses.

---

## 5. HTTP server and web mode

- Entry: `packages/server/src/entry-http.ts`
  - `:17` `PORT` (default 3030); `:18` `ZCODE_SERVER_HOST` (fallback `HOST`);
    `:19` `ZCODE_WEB_STATIC_ROOT` (static web client root); `:20`
    `ZCODE_SERVER_AUTH_TOKEN` (optional auth token — loopback use without token is the
    default behavior).
  - Imports `createServerHostServices` from `@zcode/services/node` — the server process
    is the host that spawns the agent via the env described in §7.
- Workspace resolution: `packages/server/src/http.ts:172` —
  `ZCODE_SERVER_WORKSPACE || process.cwd()`.
- Official CLI distribution runner: `scripts/zcode-distribution/runner.mjs`
  - Usage contract `:18`: `zcode --web [--host <host>] [--port <port>] [--workspace <path>]
    [--open|--no-open] [--token <token>|--no-token]`.
  - Env wiring `:183-184`: `ZCODE_AGENT_SERVER_ARGS_JSON =
    JSON.stringify([agentEntry, "app-server", "--stdio"])`,
    `ZCODE_AGENT_SERVER_COMMAND = process.execPath`.
  - **Android hazard (verified)**: `openBrowser` at `:152-153` uses `xdg-open` on Linux;
    `--open` must be replaced by `--no-open` in any headless/embedded use.

---

## 6. Authentication and credential storage

- CLI login: `zcode login [zai|bigmodel] [--no-browser]` (`src/login-command.ts:17`);
  slash-command variants `/login zai-coding-plan` and `/login zai-coding-plan-api-key`
  (`src/command-center/login-flow.ts:14,42`) — i.e. both an account (OAuth) path and an
  API-key path exist per provider.
- Credential file: `apps/zcode-cli/packages/adapters/src/auth/shared-credentials.ts:288-289`
  — `${ZCODE_DATA_BASE_DIR ?? homedir()}/.zcode/v2/credentials.json`. `ZCODE_DATA_BASE_DIR`
  relocates the whole store (same env honored by `src/device/cli-device-mid.ts:9,58`).
- In-protocol account configuration: method `provider/updateAccountConfig` (§4) allows a
  host UI to configure provider accounts without touching disk layout; connectivity can be
  probed with `provider/testModelConnectivity`.
- No API keys are embedded in the repository; the built-in registry carries provider
  endpoints/models only (`scripts/builtin-provider-config.mjs`, §3).

---

## 7. How hosts locate and spawn the agent

`packages/services/src/zcode-agent/zcodeAgentProcessManager.ts` @ `3c5b2276`:

- `:446` first resolves `process.env.ZCODE_AGENT_SERVER_COMMAND` (explicit override).
- `:361` monorepo/dev fallback: `findUpward("apps/zcode-cli/packages/cli/dist/zcode.cjs")`.
- `:422` desktop-packaged fallback: run the bundled `zcode.cjs` under the Electron Node
  (`ELECTRON_RUN_AS_NODE`).
- Resolution order summary `:443-459`; hard failure `:977` and `:1394` —
  `"ZCode agent server command is not configured. Set ZCODE_AGENT_SERVER_COMMAND before
  integration."` — **an embedding host must set `ZCODE_AGENT_SERVER_COMMAND` and
  `ZCODE_AGENT_SERVER_ARGS_JSON` or agent sessions cannot start.**

---

## 8. Native and platform dependencies (feasibility-critical)

Verified facts:

- `node-pty` is declared by `@zcode/server` (`packages/server/package.json`:
  `node-pty ^1.0.0`, plus prebuilt `@lydell/node-pty-linux-arm64@1.2.0-beta.10`;
  lockfile `pnpm-lock.yaml:3292`). The prebuilt binaries are **glibc-linked** (upstream
  project policy `supportedArchitectures.libc = [current, glibc]`, §2).
- **`node-pty` is lazily imported and failure-tolerant**:
  `packages/services/src/terminal/terminalService.ts:6` is a type-only import;
  `:17` a type-only module handle; `:31`
  `nodePtyModulePromise = import("node-pty").catch(...)` — a runtime load failure is
  caught by design (comment at `:34` records that a previous top-level import was
  deliberately changed). Consequence: the terminal feature can degrade without taking
  down the server/agent.
- **`ssh2` is only loaded on demand**: static import inside
  `packages/server/src/remote/ssh-backend.ts:2`, but the module itself is reached via
  `packages/server/src/remote/create-backend.ts:12`
  (`case "ssh": await import("./ssh-backend.js")`) — unreachable unless an SSH remote
  target is used.
- The agent's Grep tool is ripgrep-based: `apps/zcode-cli/packages/core/src/tool/handlers/grep.ts:25,141`.
  Binary resolution for Linux ARM64 is an open question (§11); the Windows installer
  bundles `rg.exe`/`ugrep.exe` (user artifact observation), so the Linux distribution
  must supply or resolve an `rg` binary.
- Shell assumptions: `apps/zcode-cli/packages/core/src/runtime/methods/bash-shell-snapshot.ts:197-203`
  special-cases `cmd.exe` on Windows; POSIX shells are resolved from `PATH`.

Hypothesis (marked, not verified): because the agent bundle is pure JS and both native
modules are lazy, the **core agent loop (sessions, model calls, file tools) may run on a
Node.js ARM64 build without glibc** — the glibc requirement may be limited to the terminal
and SSH-remote features. This is exactly what Phase 2 must test (§12).

---

## 9. Desktop-only components (excluded from the Android path)

- `packages/desktop` is the Electron app: main/host/renderer/preload; the preload exposes
  a large `contextBridge` surface (`packages/desktop/src/preload/index.ts:73` — 60+
  invoke channels per prior artifact inspection). This UI layer cannot be reused in an
  Android WebView; the reuse boundary is the `app-server` protocol (§4) and/or the web
  mode (§5).
- Deep-link auth: `packages/desktop/src/main/desktopOAuthDeepLink.ts:412,431`
  (`app.setAsDefaultProtocolClient("zcode://…")`). The desktop registers `zcode://oauth/callback`;
  inside Wine/Winlator this is why browser-redirect login failed (observation; consistent
  with the user's device report). On Android the equivalent must be handled by the app
  (App Link / custom scheme or `--no-browser` URL copy).
- Auto-update: `app-update.yml` in the packaged app points to a `generic` provider
  (artifact observation from the user's extracted installer) — irrelevant to Android;
  updates will be handled by our own release channel.

---

## 10. Evidence classes (roadmap rule 4)

- **Verified facts** (source, commit-cited): everything in §2-§9 above.
- **Artifact observations**: the user's extracted `ZCode-3.14.3-win-x64.exe` (NSIS →
  `app-64.7z` → Electron tree incl. `resources.pak`, `v8_context_snapshot.bin`,
  `LICENSE.electron.txt`, `rg.exe`); the desktop app ran under Winlator on an Android
  phone and reached `zai-api` (server error `[1113] Insufficient balance`, `status=429`,
  `model=GLM-5.3`) — proving network + provider-path reachability from the device, not
  agent execution.
- **Hypotheses**: core agent loop runs without glibc natives (§8); an external NDJSON
  client can drive `session/*` end-to-end (the desktop host does exactly this, §4/§7).
- **Untested assumptions**: all device-side behavior — agent startup, protocol handshake,
  login flow, file operations, process lifecycle on Android. None are claimed.

## 11. Upstream completeness and unresolved questions

The upstream source **does include everything required to build and run the agent**
(CLI + server + web + packaging scripts; verified by build script contracts §3/§5).
Open questions that materially affect the Android design:

1. **ripgrep resolution** for the Grep tool on Linux ARM64 (bundled in the official dist?
   `PATH` lookup? hard fallback?) — determines whether we must ship an `rg` binary.
2. **V4 protocol plane** (`@zcode/shared/zcode-protocol-v4`): how much larger the required
   client surface is beyond the v1 method registry.
3. Session persistence paths (`SqliteSessionStore` / `getSessionDbPath`): exact locations
   and whether they respect `ZCODE_DATA_BASE_DIR`/`HOME` redirection cleanly under Android.
4. `ssh2` optional native binding (`sshcrypto.node`) behavior when absent — only affects
   the SSH-remote feature, but worth recording.
5. Web-mode auth: whether the web client relies on the optional
   `ZCODE_SERVER_AUTH_TOKEN` in flows an Android WebView must replicate.
6. Whether official Linux builds of the distribution tree are published anywhere (none in
   GitHub releases for v3.14.3) — we currently must build it ourselves (CI), which is
   acceptable but must be planned.

## 12. Smallest next experiment (answers the highest-risk question)

**Highest-risk question**: does the original agent require a glibc userland on ARM64, or
can it run on a non-glibc Node.js (Path A) — i.e. is glibc mandatory only for the
terminal/SSH extras?

**Experiment (CI-only, no device, reproducible)** — entry point of Phase 2:

1. On `ubuntu-24.04-arm` (GitHub Actions arm64 runner):
   `pnpm install --frozen-lockfile --filter @zcode/cli…` then
   `pnpm --filter @zcode/cli build` → record whether the ARM64 build of
   `dist/zcode.cjs` succeeds and which optional platform artifacts are fetched.
2. `node dist/zcode.cjs --version` / `--help` (sanity only — not proof of execution).
3. Minimal NDJSON smoke client (~60 lines): spawn
   `node dist/zcode.cjs app-server --stdio`, send a `runtime/capabilities` request,
   assert a response frame arrives on stdout (and that `console` noise stays on stderr).
4. Repeat steps 2-3 inside an Alpine (musl) arm64 container as a **non-glibc proxy**:
   record exactly which imports fail (expected candidates: `node-pty`, `ssh2`) and whether
   `runtime/capabilities` still answers.

**Interpretation limits (stated honestly)**: musl is not bionic; a musl pass does not
guarantee Android Path A, but a musl failure that isolates to `node-pty`/`ssh2` would
(a) confirm glibc is needed only for those features and (b) justify jumping to Path B
(Linux ARM64 userland under PRoot) with a prebuilt distribution tree instead of spending
device time on Path A. Either outcome produces concrete rows of the Phase 2 compatibility
matrix.

---

## 13. Phase 1 report summary

- **Findings**: see §1-§9. Most decision-relevant: (a) no official Linux release assets —
  we build the distribution tree ourselves; (b) the agent is a pure-JS single-file bundle
  driven over NDJSON stdio by any host process; (c) native modules are lazy and
  failure-tolerant; (d) workspace `libc` policy is glibc-only for pnpm installs; (e) login
  supports `--no-browser` and relocatable credential storage.
- **Changes**: `docs/UPSTREAM-ANALYSIS.md` added on branch `dev` (created from `main`;
  default branch untouched). No other files, no workflows, no application code.
- **Tests**: none runnable in this phase (source inspection only); all commands were
  read-only git/API queries, exit code 0.
- **Blockers**: none for Phase 1. Highest-risk technical question deferred to the §12
  experiment; device-level questions remain open by design.

---

## 14. Addendum — 2026-10-26: re-verification and the Linux desktop build path

Trigger: an external analysis quoted by the project owner asserts that (a) ZCode
officially ships Linux ARM64 packages (`.deb`/`.rpm`/`.AppImage`) and (b) references a
version "3.16.3". Both claims were re-checked against live GitHub data on 2026-10-26.

**Verified facts (second independent check):**

- `GET /repos/zai-org/ZCode/tags` returns exactly two tags: `v3.15.1` (`aac4755666d0`)
  and `v3.14.3` (`29628c9acdb8`). **No `v3.16.3` tag exists.**
- `GET /repos/zai-org/ZCode/releases` returns exactly one published release, `v3.14.3`
  (2026-09-24), with exactly two assets: `ZCode-3.14.3-win-x64.exe` (150,014,088 B) and
  `ZCode-3.14.3-mac-arm64.dmg` (185,386,581 B). **No Linux assets are published.**
- Consequence (correcting the record): an official Linux ARM64 package **does not exist
  today**. Any Linux ARM64 desktop runtime must be built by this project from the pinned
  upstream source (CI). This supersedes the external claim.

**Verified facts — upstream's own pipeline anticipates Linux targets** (all @ `3c5b2276`,
exit 0):

- `packages/desktop/scripts/bundle.mjs:56,67-81` — OS alias `linux` → electron-builder
  flag `--linux`; linux artifact extensions declared as
  `[".AppImage", ".deb", ".rpm", ".pkg.tar.zst"]`.
- `packages/desktop/electron-builder.config.js:561-562` — comment documents Linux `deb`
  (fpm) metadata validation and patches `homepage`/`author.email` inline for it.
- `packages/desktop/package.json` — `electron 41.0.3` (official linux-arm64 prebuilt
  exists), `electron-builder ^26.8.1`, and **both** `@lydell/node-pty-linux-arm64@1.2.0-beta.10`
  and `@lydell/node-pty-linux-x64@1.2.0-beta.10` as desktop dependencies.
- Invocation (verified via `parseArgs`, `bundle.mjs:289-359`):
  `node packages/desktop/scripts/bundle.mjs --os linux --arch arm64`
  (env fallbacks `ZCODE_TARGET_OS` / `ZCODE_TARGET_ARCH`; defaults `mac`/`arm64`).
- CLI distribution tree (non-Electron surface, §3/§5): root `package.json` script
  `build:zcode` = `node scripts/build-zcode.mjs`.

**New source detail relevant to Check 2 (login):**

- A second deep link exists: `zcode://workspace/open`
  (`packages/desktop/src/main/desktopOAuthDeepLink.ts:281`, comment), beside the OAuth
  callback registration at `:412,431`.

**Exact NDJSON frame schemas** captured for check clients
(`packages/shared/src/zcode-protocol/index.ts:392-437`, all `.strict()`):

- request: `{"id": string|int, "method": nonEmptyString, "params"?, "trace"?}`
- notification: `{"method": nonEmptyString, "params"?, "trace"?}`
- response: `{"id", "result"}` — error: `{"id", "error": {"code": int, "message", "data"?}}`

**Unknowns recorded for the CI experiment (not asserted either way):** whether the linux
prepare steps (`prepare:native-search`, `prepare:glm`, runtime-asset staging) complete on
a public-CI linux host; whether electron-builder produces arm64 AppImage/deb artifacts
cleanly; runtime behavior of the artifact. The first CI run is treated as evidence, not
as a guaranteed pass (roadmap rule 5).

Consequence for the owner-mandated three device checks: see `docs/RUNTIME-CHECKS.md`
(verification instrument; all device rows NOT TESTED until executed).
