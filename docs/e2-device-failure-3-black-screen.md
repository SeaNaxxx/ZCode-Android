# E2 device failure 3 — server runs, UI stays black (Vivo V2035, 2026-10-28)

App: `0.3.2-e2 (5)` · device: vivo V2035 · Android 13 (SDK 33) · arm64-v8a
Branch: `e2/bionic-arm64-embedded` · export timestamp: 2026-10-28 05:40:36

## What the device export proves (evidence classes, per owner protocol)

| Check | Result | Evidence |
| --- | --- | --- |
| Embedded Bionic bundle provisioned | YES | `runtime provisioned: true`, marker `nodeProbe: v24.22.1 android arm64` |
| Node.js runs on device (ARM64) | YES | probe output in marker; provisioning gate passed |
| ZCode `--web` server runs locally | YES | RPC channels registered, `setting.get`/`oauth.getProviders`/`provider-settings.refresh` OK; `http://127.0.0.1:3030` answers 2xx (app health gate before `Running`) |
| Frontend JS actually loaded and connected | YES | RPC client traffic (`marketing-touch.query`, `zcode-agent.syncAppRuntimePreferences`) can only originate from a connected browser client; screenshot shows a rendered FAB inside the WebView |
| Frontend rendered usable UI | **NO — black screen** | user screenshot: boot-shell dark background, one floating button, no content |
| External provider connectivity | **UNPROVEN / mixed** | `codingPlanSubscription` config: undici `fetch failed`; builtin client-config: `invalid response`. Root cause not yet classifiable (DNS vs TLS vs HTTP) — needs the device-side probe |

## Root cause: NOT yet demonstrated — this build collects the missing evidence

What we can already rule out (with reasons):

1. **Cleartext policy block** — the frontend RPC traffic exists, so the WebView loaded
   the page and executed JS (`usesCleartextTraffic=false` did not block loopback).
2. **Server not serving the frontend** — upstream chain verified at pinned commit:
   `runner.mjs` sets `ZCODE_WEB_STATIC_ROOT=<dist>/zcode/web`, `http.ts` serves
   `index.html` with SPA fallback, and `GET /` passed the health gate (2xx).
3. **Frontend JS bundle failed to parse (old Chromium)** — the bundle ran (RPC
   traffic + rendered FAB). WebView *version* is still recorded in the new export.
4. **Boot-blocking RPC failure** — `marketing-touch.query` ZodError is caught
   client-side (`MarketingTouchProvider.tsx`); `window-controller` unknown-channel
   is desktop-only degradation; both are noise, not the black screen.

Remaining hypotheses (in priority order, all testable with the new instrumentation):

- H1: React mounted but the main view renders nothing on a narrow viewport
  (web UI is desktop-first; `packages/web` serves the remote-control/shell app
  whose `Root` comes from `@zcode/ui`).
- H2: a runtime JS error during the main view render (console capture will show it).
- H3: a required asset 404s (resource-error + HTTP-error capture will show it).
- H4: renderer/compositing quirk on the Vivo WebView (renderer-gone capture).

## Changes in this build (`0.3.3-e2 (6)`)

- `ui/LocalApp.kt` — instrumented WebView: page lifecycle (`onPageStarted`,
  `onPageCommitVisible`, `onPageFinished`, `onReceivedError`, `onReceivedHttpError`,
  `onRenderProcessGone`), JS console via `WebChromeClient.onConsoleMessage`,
  post-load DOM snapshot (`#root` children/html length, visible text, viewport,
  DPR, UA). No credential access.
- `local/WebDiagnostics.kt` — new: UI-side evidence store with its own ring
  buffer (frontend boot chatter can no longer evict it from the export) +
  logcat tag `ZCodeWeb` + WebView provider/version identification.
- `local/LocalServerProbe.kt` — new: after every start,
  - `[probe] local` — GET `/` (status, content-type, size, head) + GET of the
    JS entry referenced by `index.html` (asset-presence proof);
  - `[probe] external` — outbound HTTPS from the **embedded node** (HEAD only,
    cert verification on, **no credentials**) — separates provider-connectivity
    evidence from local-server evidence;
  - `[probe] persistence` — workspace marker whose `firstWrittenAt` survives
    app restarts (owner check 5).
- `local/ZcodeServer.kt` — node child pid + health-OK timing logged (owner
  checks 1–2 evidence).
- CI (`e2-bionic-embedded.yml`) — new `frontend-smoke` stage on the API 33
  emulator: taps "Open ZCode", captures console/fatal-signal/ZCodeWeb logcat,
  screenshot, UI dump; **evidence collection** stage (does not gate the core
  acceptance stages; render verdict lives in the evidence, emulator results stay
  clearly separated from device evidence).
- Unit tests: `WebDiagnosticsTest` (ring-buffer capacity/order, probe script
  credential-free + HEAD-only + time-bounded).

## Next device test protocol (Vivo)

1. Install the `0.3.3-e2 (6)` arm64 APK, provision/start as before.
2. Tap "Open ZCode", leave the black screen ~30 s.
3. Share diagnostics — the export now contains the `webview/js/probe` section:
   console errors, DOM state, WebView version, local/external probe results.
4. Interpretation keys:
   - `dom-state: rootChildren: 0` → React never mounted → look for console
     SyntaxError/Uncaught right before it (H2) or asset 404 (H3);
   - `dom-state` with children but empty `bodyText` → mounted-but-invisible
     (H1) — next step is a viewport/DOM-tree dump follow-up;
   - `RENDERER GONE` → H4;
   - `probe external … FAILED` lines classify provider connectivity
     independently of the UI question.

## Addendum (same day, CI runs 40627330416 / 40630785519 / 40637657592)

Emulator evidence (API 33 AVD, x86_64, WebView Chromium 109) — the black
screen REPRODUCES in CI:

- `dom-state`: `readyState complete`, `rootChildren 1`, `rootHtmlLen 15007`,
  `bodyText: "Welcome to ZCode / Connect your account to start using ZCode /
  Connect to Z.ai (Global) / Connect to BigModel (CN) / Use API key"` — the
  intended FIRST SCREEN IS A LOGIN/CONNECT SCREEN, and its DOM is fully there.
- WebView bounds `[0,166][1080,2340]` (full size) — layout hypothesis dead.
- Zero JS console errors, zero `net::ERR`, no renderer death — H2/H3 dead.
- App theme is LIGHT: the black body is the WebView painting the PAGE
  background — so painting works for the background but NOT for the content.
- `LAYER_TYPE_SOFTWARE` A/B: screen STILL black with CPU rasterization
  (run 40637657592) — the GPU compositing path is NOT the trigger.

Remaining demonstrated fact: content exists in the DOM, background paints,
content pixels never appear, on both raster paths. Prime suspect for the
next experiment: entrance-animation state in the web UI
(`installDocumentHiddenMotionPause` in packages/web/src/main.tsx — motion
paused while `document.visibilityState` is reported hidden would leave an
opacity-0 first frame). Next instrumentation: add
`document.visibilityState` to the dom-state probe; if `hidden` in the
WebView, that is the demonstrated root cause and the fix is a
WebView-visible-state correction, not a rendering one.

Device build for the next Vivo test: `0.3.4-e2 (7)` (versionCode 7, release
e2-rc2 assets, updated 2026-10-28). It already exports everything needed to
discriminate the hypotheses on the device: dom-state (+ bodyText), WebView
provider/version, console, probes, raster mode.

## Addendum (2026-10-28, later): 0.3.4-e2 (7) device result + visual-paint evidence suite (0.3.5-e2 (8))

### What the 0.3.4-e2 (7) Vivo run established (owner export, 10:14:07)

The black screen PERSISTS on the device with the same signature as the AVD:
`readyState complete`, `rootChildren 1`, `rootHtmlLen 15007`, welcome text in
`bodyText`, WebView `com.google.android.webview 157.0.8103.14`, and the
software-raster mitigation active (`raster: SOFTWARE`) — i.e. the GPU
compositing path is confirmed NOT the trigger on real hardware either. The
WebView was (re)created three times in the export window and every load shows
the same DOM-complete/black-screen pair.

### Static source analysis done before this build (no fix possible from it)

`packages/web/index.html` (boot shell): the page intentionally boots DARK
(`DEFAULT_THEME = "zai-dark"`, body background `#0f172a`/`#161616` family) —
a black-looking background is NORMAL for this page; the missing part is the
content above it. `WelcomeScreen.tsx` / `ThemeHeroVisual` contain no
framer-motion and no base-`opacity:0` entrance pattern; the
`installDocumentHiddenMotionPause` CSS (`html[data-document-hidden] * {
transition: none; animation: none }`) only disables animations — static
content would stay visible under it. Conclusion: the source alone does not
prove any of the candidate mechanisms; the decisive data must come from the
LOADED DOM and the actual painted pixels — which is exactly what the next
build collects.

### The 0.3.5-e2 (8) evidence suite (this build, per owner protocol)

One-shot chain per WebView instance (~5 s after load; never for the isolated
data:-URL test itself; every step timestamped in the `webview/js/probe`
export section):

1. `visual-state:` — `document.visibilityState` / `document.hidden` /
   `hasFocus` (the WebView-visible-state question behind
   `installDocumentHiddenMotionPause`), the `data-document-hidden` attribute,
   boot theme + `dark` class, iframe count, live animation list with play
   states, `elementFromPoint()` at five viewport points, and computed
   `display/visibility/opacity/color/background` + `getBoundingClientRect()`
   for `html`, `body`, `#root` and the REAL top-3-level subtree walked from
   the loaded DOM (selectors are read, never assumed; shadow roots marked).
2. `bitmap[before]` — the WebView's OWN painted output via `view.draw()`:
   quantized pixel statistics (`uniformBlack` / `distinctColors` /
   `dominant`) + PNG saved under `files/logs/webview-before-*.png`. This
   separates "renderer produced nothing" (uniform bitmap) from "bitmap has
   the UI but the screen is black" (occlusion/compositing ABOVE the WebView).
   Near-black threshold (r,g,b < 14) deliberately excludes the dark-theme
   backgrounds (`#060816`, `#0f172a`, `#161616`) so a dark THEME is never
   misclassified as a black screen.
3. `force-vis:` + `bitmap[forced]` — diagnostic-only experiment: inline
   `opacity:1/visibility:visible/display:block(if none)/transform:none/
   animation:none/transition:none/content-visibility:visible` on the walked
   subtree, `data-document-hidden` removed, and a bright `#ffcc00` marker
   background forced on html/body. Then `styles-restored:` returns every
   touched inline property to its saved value (the production CSS is never
   changed). Interpretation: pixels turn yellow but no content → restyle
   reaches the compositor, content itself is hidden by its own styles; content
   appears → it was style-hidden (the walk names the element); bitmap stays
   black → the renderer produces no frames at all.
4. `minimal-html` + `bitmap[minimal]` — isolated solid-background page with
   visible text and an interactive button loaded in the SAME WebView
   instance, then the original page is reloaded. Distinguishes a general
   WebView paint failure (minimal page also black) from a ZCode-frontend
   -specific one (minimal page paints red, real UI stays black). Evidence
   only — the real UI is never replaced.

`force-vis` also reports `docHiddenAttrWas` — the direct test of whether
`installDocumentHiddenMotionPause` was active at load time; the production
behavior itself is NOT modified (per owner constraint).

Unit tests: `WebDiagnosticsTest` now pins the pixel-classification semantics
(dark-theme background ≠ black screen; painted content ⇒ distinct buckets).

CI: the `frontend-smoke` stage (API 33 AVD, where the black screen
reproduces) now surfaces the suite lines (`visual-state:` / `bitmap[` /
`force-vis` / `styles-restored` / `minimal-html` / `shot saved`). Emulator
results remain clearly separated from Vivo results; the device verdict comes
only from the physical-device export.

Release hygiene: this build publishes to the NEW tag `e2-rc3` with explicit
version labels (0.3.5-e2, versionCode 8) — e2-rc2 artifacts are not touched
(owner constraint: no ambiguous asset overwrites).

### Interpretation keys for the next Vivo export

| Evidence pair | Verdict |
| --- | --- |
| `bitmap[before]` uniformBlack≈100% + `bitmap[minimal]` red | paint pipeline works; ZCode frontend content is the problem → read `visual-state` nodes for the hidden/zero-opacity element |
| `bitmap[before]` uniformBlack≈100% + `bitmap[minimal]` uniformBlack≈100% | renderer produces no frames at all → WebView/compositing-level failure, not CSS |
| `bitmap[before]` shows content (distinct colors, text) while SCREEN is black | occlusion above the WebView → Android view-hierarchy problem |
| `visual-state vis:"hidden"` or `docHiddenAttrWas:true` | WebView visible-state bug → fix is attach/visibility handling, not rendering |
| `bitmap[forced]` shows content | style-hidden content (opacity/visibility/animation stuck) — the walked node list names the culprit |
| `hits` show an unexpected full-screen element at all 5 points | an overlay covers the UI — its selector is in the log |

## Addendum 2 (2026-10-28, export 11:38:27): three SEPARATE faults identified and traced to source

The 4th Vivo export (still 0.3.4-e2 (7)) adds decisive datapoints: the SAME
device rendered the UI in an EXTERNAL browser, the app WebView DOM reached the
FULL workspace UI (`rootHtmlLen 49585`, "Command palette / New task / Morning,
how can I help?"), and a NEW failure appeared on send:
`zcode-agent.sendConversationCommandV4 FAIL project.remote-project-required`
(repeating, every 1–2 s, during draft-session creation — UI error surface:
草稿会话创建失败，请重试).

### Fault A — `project.remote-project-required`: upstream CONTRACT, not a port bug (traced to source)

Full chain at upstream pin 3c5b2276:

1. `packages/server/src/entry-http.ts` — the `--web` entry ALWAYS builds
   services via `createServerHostServices({ sourceServerId: resolveHttpServerId() })`;
   `projectStorageHostId = remoteHostIdFromWorkspaceIdentity(
   buildServerRemoteWorkspaceIdentity({ serverId, workspacePath: "/" }))` → a
   REMOTE owner host id (node.ts: "入口不得以 local owner 开放远端项目 RPC").
2. `packages/services/src/project-storage/remote-policy.ts` —
   `assertRemoteTaskDirectories(ownerHostId, project, …)`: returns silently
   ONLY when `ownerHostId` is undefined/"local"; otherwise a NULL project
   throws exactly `project.remote-project-required`.
3. `packages/services/src/project-storage/project-repository.ts`
   `reserveCreatedTask` → assert with `input.projectId ? requireProject(…): null`.
4. `packages/services/src/zcode-agent/zcodeAgentService.ts`
   `buildConversationCommandEnvelope` → `projectId: params.projectId ?? null`.
   The device UI sent NO projectId (settings: `lastActiveWorkspace kind=local`,
   `recentProjects: []` — no project registered server-side).
5. `packages/services/src/project-storage/CONTRACT.md:32` — "远程创建必须有项目"
   (remote creation MUST have a project; prepareSession / reserveCreatedTask /
   moveTask must not produce unowned tasks). `prepareSession` without
   projectId throws the same error for a remote owner (project/index.ts:211).

Conclusion: on ANY standalone `--web` server (which is what E2 runs), the bare
composer can NEVER create a session — this is the documented upstream
contract, not a defect of the Android port. The supported fix path is the
official project flow: **Projects pane → add project (server device) → select
the workspace root → folder-trust confirm → saveProject → create the task
from that project** (SessionPane then sends `projectId`, `reserveCreatedTask`
passes: exactly one root == workspacePath, no additionalDirectories). No
contract bypass, no fabricated remote project (owner constraint honored).
`ProjectEditor.tsx`/`ProjectSidebarNode.tsx` implement this flow in the
shared UI. The app now shows a one-line hint on the Running card; automation
(pre-registering the workspace via the official saveProject RPC after server
start) is a candidate NEXT step only after the manual flow is validated on
the device.

Proof the failure precedes any provider request: the stack ends in
`reserveCreatedTask` inside `prepareSessionCreation` — the envelope never
reaches the CLI/provider. This is consistent with the Winlator comparison:
THERE the request reached OpenRouter and got a real provider error (402
Insufficient credits) — a LATER stage on a host whose UI flow had a project.
Fixing OpenRouter credits cannot fix Fault A; fixing Fault A will surface
provider errors in OpenRouter's natural form.

### Fault B — Node TLS `UNABLE_TO_GET_ISSUER_CERT_LOCALLY` to zcode.z.ai: transient / network-path, NOT a bundle CA gap

Evidence-backed classification:

- api.z.ai and zcode.z.ai serve the IDENTICAL chain (leaf `*.z.ai` ←
  `Sectigo Public Server Authentication CA DV R36` ← `Sectigo … Root R46` ←
  `USERTrust RSA Certification Authority`) — verified from this environment
  (`openssl s_client -showcerts`, Verify return code 0 for both).
- The DEVICE's own probe verified `https://api.z.ai` OK (HTTP 301, tls=true)
  at 11:19:30 using `LocalServerProbe` — which spawns node with the EXACT
  same `applyBionicEnv` as the server (SSL_CERT_FILE/OPENSSL_CONF wired,
  verification on). Same env + same chain + success ⇒ the bundled CA store
  CAN verify this chain.
- The Termux `ca-certificates` 1:2026.10.20 package (the bundle source)
  contains BOTH `USERTrust RSA Certification Authority` and
  `Sectigo Public Server Authentication Root R46` (checked from the actual
  .deb, 118 certs).
- Timeline in the same export: 11:19 probes OK → 11:21 BOTH external probes
  TimeoutError (network flap) → 11:38 server fetch fails TLS-verify. The 11:38
  failure is therefore classified as transient network-path behavior
  (captive-portal/ISP interception or a leaf-only-serving edge) — pending the
  on-device chain capture, NOT something "fixable" by weakening verification
  (which remains forbidden).

New instrumentation (0.3.6-e2): `probe-tls-chain` — connects with
`rejectUnauthorized: true`; on failure it reads the SERVED chain
(subject/issuer per depth) from the SAME failed socket (public metadata only,
no keys/credentials) and logs `TLS-CHAIN` lines for `zcode.z.ai` +
`api.z.ai` (control). Unit test pins verification-on + credential-free.

### Fault C — WebView fetch 403 + CORS on zcode.z.ai/api/v1/client/configs: separate, functionally covered

The WebView (system trust store, TLS fine) got a real HTTP 403 from the
server and no ACAO header → browser-side fetch blocked by CORS policy.
Reading: server-side rejection (WAF/origin/geo policy) of a browser-origin
fetch to the client-configs endpoint; the CORS error only MASKS the 403.
Functionally covered: the SERVER fetches the same config (provider-runtime
"Builtin 远端更新失败，继续使用本地配置" falls back to local config), and the
desktop host — the only caller upstream exercises — is not a browser. No
permissive CORS workaround is added (owner constraint: establish the real
server-side contract first). Existing `onReceivedHttpError` instrumentation
already records status+URL for evidence. Priority: low; unrelated to the
black screen and to Fault A.

### Fault D (unchanged) — WebView black screen: WebView-paint-specific

This export strengthens it: external browser renders the SAME server page;
the app WebView DOM held the FULL workspace UI; screen stayed black. The
0.3.5/0.3.6-e2 visual-state + bitmap suite (above) is the decisive
instrument; the first emulator evidence set lands with CI run 40652320738
(attempt 2) and the verdict comes from the Vivo retest.

Release hygiene: release tags are now DERIVED from versionName
(`e2-<versionName>`), so every future bump publishes to its own tag —
e2-rc2/e2-rc3 history stays untouched.

## Addendum 3 (2026-10-28, CI run 40660693522): FIRST emulator evidence set — root-cause CLASS demonstrated

Run 40660693522 (0.3.6-e2, API 33 AVD, 9/9 stages green; the two earlier
setup-tap failures in run 40652320738 attempts 1-2 were emulator ANR flakes —
the identical stage passed here). The visual suite captured, for the FIRST
time, the actual rendered state inside the black-screening WebView:

- `visual-state`: `vis:"visible"`, `hidden:false`, `docHiddenAttr:false` →
  the `installDocumentHiddenMotionPause` hypothesis is DEAD (the document is
  visible and no pause marker is set).
- **LAYOUT COLLAPSE (the demonstrated class)**: html/body/#root and the
  RootShell root (`div.relative.h-dvh` = `100dvh`, upstream comment explains
  it avoids fixed 100vh for mobile address bars) all measure **393x0**; the
  welcome `main` is 393x48; the login card sits at `[16,-168 361x384]` —
  i.e. pushed ABOVE the viewport and clipped by `overflow-hidden`.
  `elementFromPoint` at all five probe points returns `html` — nothing else
  is laid out at any sample point.
- `bitmap[before]`: `uniformBlack=0% distinctColors=1 dominant=#161616
  100%` — the WebView bitmap contains ONLY the page background (`#161616`
  IS the zai-dark theme background; the near-black threshold correctly does
  NOT classify it as black).
- `bitmap[forced]`: `dominant=#ffcc00 100%` → style restyling reaches the
  compositor instantly → **the paint pipeline WORKS**.
- `bitmap[minimal]`: `dominant=#e11d48 93%, 8 colors` → the isolated page
  paints fully in the SAME WebView.
- AVD screenshot `screen-frontend-open.png`: uniform dark WebView area under
  the light app top bar — matches the Vivo symptom exactly.

Conclusion: NOT a compositor failure, NOT visibilityState, NOT an overlay,
NOT a stuck opacity animation — **the page's viewport-height chain collapses
to 0 in this WebView** (RootShell `100dvh` + the `height:100%` chain), so the
whole app tree lays out into a zero-height strip, the login card is clipped
out, and only the dark background paints. The page renders fine in external
browsers (Vivo 4th export), so the trigger is the app↔WebView integration.

### Round 0.3.7-e2 (10) — the decisive experiments (this build)

1. `visual-state` now measures what each unit ACTUALLY resolves to in this
   engine state: `vp.vh100` / `vp.dvh100` / `vp.pct100` (offscreen probe
   elements), `documentElement.clientHeight`, `visualViewport`
   height/offset/scale, and computed cssH/offH/scrollH for
   html/body/#root/wrapper — pinpointing WHICH link collapses.
2. `layout-repair` experiment: forces `height:100vh` inline on
   html/body/#root/wrapper, captures `bitmap[repaired]`, reports the wrapper
   and main rects, then RESTORES the original cssText. If the card becomes
   visible in the bitmap, the dvh/ICB collapse is the proven mechanism.
3. `frontend-smoke-b` CI stage: full A/B with **edge-to-edge OFF** (runtime
   marker `files/e2-no-edge-to-edge` → MainActivity skips
   `enableEdgeToEdge()`; default behavior unchanged). If config B recovers
   real viewport heights and the card paints, the root cause is the
   edge-to-edge ↔ WebView insets interaction on API 33, and the fix is
   app-side decor-fitting — no upstream page changes, no contract changes.

Interpretation keys: `vp.dvh100=0` + `vp.vh100>0` → dvh collapse
(edge-to-edge/insets); `vp.pct100=0` too → ICB collapse; `bitmap[repaired]`
shows the card → mechanism proven; config-B `bitmap[before]` shows content →
trigger = edge-to-edge.

## Addendum 4 (2026-10-28, CI run 40665560040): mechanism PINNED to the unit level — fix implemented (0.3.8-e2 (11))

Run 40665560040 (0.3.7-e2) completed 9/9 on the API 33 AVD. The unit-
resolution probes measured, inside the black-screening WebView:

```
inner=393x791   client=393x791   vv=791@0 s=1.000   ← JS-level viewports SANE
vh100=0  dvh100=0  pct100=0                          ← ALL CSS viewport units = 0
html cssH=0px offH=0 scrollH=791                     ← root collapses, content 791
#root cssH=0px offH=0 scrollH=48
layout-repair: forcing height:100vh!important on
html/body/#root/wrapper -> wrapperRect STILL [0,0,393,0];
bitmap[repaired] unchanged (100% #161616)
```

Interpretation: the document was CREATED while the WebView had no size
(`loadUrl()` runs in the AndroidView factory, BEFORE Compose attaches/ lays
out the view) and this WebView state latches the CSS viewport-height
resolution to 0 for the document's lifetime — JS viewport properties update
(innerHeight/clientHeight/visualViewport all 791) but viewport UNITS and
percentage chains never re-resolve. Forcing 100vh inline cannot repair a
frozen document-level latch. RootShell (`h-dvh`, upstream's mobile-browser
fix) and the `height:100%` chain BOTH collapse → the whole tree lays out
into a zero-height strip → login card clipped → only the dark background
paints = the black screen. The page renders fine in external browsers
(document created by a properly-sized engine).

### The fix (0.3.8-e2 (11)) — smallest correct, evidence-backed

`LocalWebViewScreen` no longer calls `loadUrl()` in the factory. The load now
happens on the FIRST REAL LAYOUT (OnLayoutChangeListener with width>0 &&
height>0, then self-removes) with a 2 s fallback. Creating the document after
the view has a real size avoids the latch — this is standard correct Android
WebView practice, no upstream page changes, no contract changes, no
diagnostic-only override.

Also removed in this build: the `LAYER_TYPE_SOFTWARE` mitigation (A/B-proven
ineffective — the raster path was never the cause); hardware rasterization is
restored. The `frontend-smoke-b` edge-to-edge A/B stage stays for comparison
evidence (its earlier run-as path bug is fixed with absolute paths).

### Acceptance keys for this build's CI + the Vivo retest

| Check | Fixed expectation |
| --- | --- |
| `visual-state` | `vh100/dvh100/pct100` ≈ viewport height (> 700), html cssH > 0 |
| `bitmap[before]` | distinctColors > 1 — the welcome card VISIBLE in the bitmap |
| screen-frontend-open.png | login/workspace UI visible under the app top bar |
| Vivo retest | the interface visibly renders and responds to touch (owner's decisive criterion) |
| Regression guard | the paint suite itself (visual-state + bitmap assertions) stays in every future export |
