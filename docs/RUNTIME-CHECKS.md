# Runtime Checks — three device gates before any Android code

Owner directive (2026-10-26): run a limited investigation, then move directly to
implementation, with no new planning round. The outcome of these checks must be a
practical decision: either the Linux ARM64 path is runnable and the Android app is
built around it, or a specific blocker is identified and the next alternative is
tested instead of expanding the project.

**Status: PROPOSED. Nothing on this page has been executed on a device. All device
results are NOT TESTED until logs exist.** This document is the instrument, not the
result. Auto-update work is explicitly deferred (owner decision) and is not part of
these checks.

Evidence classes follow roadmap rule 4 and `UPSTREAM-ANALYSIS.md` §10/§14:

- Verified facts: cited to source or to a command with exit code.
- Device results: none yet — every row below starts NOT TESTED.

---

## 0. Preconditions

### 0.1 Package supply (verified fact, corrects the record)

No official Linux ARM64 package exists (UPSTREAM-ANALYSIS.md §14, re-checked
2026-10-26: only release v3.14.3, assets win-x64 + mac-arm64, no Linux assets, no
v3.16.3 tag). The packages used by these checks therefore come from **our own CI**:

- Workflow: `.github/workflows/linux-arm64-build.yml` (manual `workflow_dispatch`).
- Artifacts it must produce:
  - `zcode-agent-dist-linux-arm64` — the CLI distribution tree
    (`agent/zcode.cjs`, `server/`, `web/`, `bin/zcode.mjs`, runtime closure) from
    `pnpm build:zcode` at pinned upstream commit `3c5b2276`.
  - `zcode-desktop-linux-arm64` — AppImage/deb from
    `node packages/desktop/scripts/bundle.mjs --os linux --arch arm64`
    (experimental: a CI failure here is itself a recorded result, not a blocker).

### 0.2 Device prerequisites

- Physical device: arm64 Android, ~6 GB free storage. Reference device so far:
  Vivo Y51 (Snapdragon 665, Android 13).
- Termux installed (F-Droid build recommended), `proot-distro` installable.
- Termux:X11 app installed (needed only for Check 1-Desktop).
- Phone browser (any) for web-mode surfaces.

### 0.3 Execution protocol (applies to every check)

1. Run stages in order: CI build → Check 1 → Check 2a → Check 3.
2. Every stage writes a timestamped log under `~/zcode-checks/logs/`.
3. Results are recorded only as PASS / FAIL / BLOCKED / NOT TESTED, with the log path.
4. Provider billing failures (`[1113] Insufficient balance`, HTTP 429 class) are
   classified as **auth-chain-works / billing-blocked**, not as runtime failures —
   the owner's Winlator session already established this evidence class.
5. No output is ever fabricated; a stage that cannot run stays NOT TESTED.

---

## Check 1 — Linux ARM64 runtime/GUI inside a Linux env on the phone

### 1-Desktop (owner's chosen first path: full Electron GUI)

Goal: run the CI-built ZCode desktop package (linux-arm64) inside an Ubuntu ARM64
proot env, displayed via Termux:X11 — the direct analogue of the owner's Winlator
result, but native ARM64 with zero instruction translation.

Steps (device script stage `deps` then `check1-desktop`):

1. `proot-distro install ubuntu` (24.04, arm64).
2. Inside the env, install the Electron system-library set (gtk3, nss, alsa, gbm,
   x11 extras — exact list in the script; missing names are recorded verbatim).
3. Install Node.js 24 arm64 tarball (agent `engines.node >= 24`), print its sha256.
4. Unpack the desktop artifact (`.deb` → `dpkg -x`, or AppImage →
   `--appimage-extract-and-run`).
5. Start Termux:X11, then launch the app with
   `DISPLAY=:0 --no-sandbox --disable-gpu --disable-dev-shm-usage` (proot has no
   user namespaces; these flags are standard for this environment) and capture the
   process log.
6. Liveness criterion: process still alive after 60 s; then visual confirmation by
   the operator (window rendered, UI interactive).

PASS: Electron window visible in Termux:X11, interactive for ≥ 60 s, log free of
fatal errors.
FAIL classes to record verbatim: missing shared libraries (exact sonames), crash
(signal + stack), blank renderer (flags already tried).

Known honest risks (hypotheses, NOT tested): SwiftGPU/GL fallback may be slow on
SD 665; app may assume systemd/xdg helpers absent in proot; AppImage runtime may
need `--appimage-extract` instead of `-and-run` under proot.

### 1-Web (fallback instrument, upstream-supported, always run after 1-Desktop)

Goal: isolate agent/server/web health from GUI risk. The web surface
(`zcode --web --no-open`) is upstream's own non-Electron path
(UPSTREAM-ANALYSIS.md §5) and needs no X server.

Steps (stage `check1-web`): inside the same env,
`node bin/zcode.mjs --web --no-open --host 127.0.0.1 --port 3030 --workspace <dir>`;
poll `curl http://127.0.0.1:3030`; then open it in the phone browser.

PASS: HTTP 200 and the web UI renders and responds in the browser.

Decision map for Check 1:

| 1-Desktop | 1-Web | Consequence |
| --- | --- | --- |
| PASS | any | GUI path continues; app wraps the env + X server |
| FAIL (specific blocker) | PASS | app wraps **web mode** (already the §5 boundary); blocker recorded |
| FAIL | FAIL | env-level blocker; report both logs, test next alternative |

---

## Check 2 — Login (split into 2a now / 2b deferred)

### 2a — Login works inside the env (no host app needed; run after Check 1)

Steps (stage `check2a`):

1. OAuth account path: `node bin/zcode.mjs login zai --no-browser` — prints the
   authorize URL (UPSTREAM-ANALYSIS.md §6); complete it in the phone browser.
   Verify `~/.zcode/v2/credentials.json` (or `ZCODE_DATA_BASE_DIR` redirect) is
   created and non-trivial.
2. API-key path: configure via the web UI account settings, or protocol method
   `provider/updateAccountConfig` — no secret is ever written to any repo file.
3. Verify with `provider/testModelConnectivity` (protocol) or the connected state
   in the web UI.

PASS: credentials file exists AND a connectivity probe answers without a protocol
or transport error.
Classification (mandatory): DNS/network failure ≠ provider billing failure; a
`[1113]`/429-class reply means **the login chain works and billing is the blocker**.

Redirect caveat recorded from source: the desktop registers `zcode://oauth/callback`
(`desktopOAuthDeepLink.ts:412,431`); under `--no-browser` this registration is not
involved, so 2a does not test it.

### 2b — Browser → Android host → env redirect chain (DEFERRED by design)

The full redirect chain (Chrome → host app deep link → pass into the env) requires
the minimal Android host app to exist — a chicken-and-egg that cannot be tested
before some host-app code exists. It is therefore scheduled immediately after the
first host app scaffold (Phase 3+), not silently dropped. Sub-facts to reuse then:
scheme `zcode://oauth/callback`; second scheme `zcode://workspace/open`
(UPSTREAM-ANALYSIS.md §14).

---

## Check 3 — Agent, files, PTY (the standing success criterion)

Goal: a real agent task reading and writing an actual file on the device — this, not
`--help` nor a server merely staying up, is the project's definition of success.

Steps (stage `check3`, after 2a):

1. PTY probe: `NODE_PATH=<dist runtime closure> node -e "require('node-pty')"` —
   expected to load inside Ubuntu proot (glibc prebuilt) and expected to fail on
   bare Termux (bionic) — the difference is exactly the §8 libc question.
2. Workspace on shared storage: `termux-setup-storage`; workspace directory under
   `/sdcard/` bound into the proot env; create `note.txt` with a known marker
   string.
3. Real task: via the web UI (or the protocol client `device-checks/ndjson-smoke.cjs`
   for the raw-frame path), prompt the agent: *read note.txt and create summary.md
   containing the marker string*.
4. Verify `summary.md` exists on disk with the marker (bytes, not UI claims).

PASS: `summary.md` created with the marker string → proceed to building the Android
app around the working surface.
Failure classification (mandatory): protocol-level failure (frames, crashes) vs
provider/billing failure vs filesystem-permission failure — each is a different
next action.

---

## 5. Reporting

Each executed check appends to `docs/POC-RESULTS.md` (Phase 3 deliverable): date,
commit/artifact sha256, device, stage, result (PASS/FAIL/BLOCKED/NOT TESTED), log
path. Nothing is reported without a log behind it.
