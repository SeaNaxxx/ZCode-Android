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
