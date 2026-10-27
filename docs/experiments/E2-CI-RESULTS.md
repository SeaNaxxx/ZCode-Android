# E2 CI Results — embedded Bionic APK, offline app-level verification (x86_64 AVD)

**Verdict: 9/9 stages PASS (run `40370562643`, branch `e2/bionic-arm64-embedded`,
commit with workflow at `ee3c3a5` + CI fixes). The APK with the fully embedded
Bionic runtime provisions and runs the official ZCode agent OFFLINE at the
app level.**

## Claims boundary (owner directive: 模拟器≠手机, x86_64≠ARM64)

| Proven by this run | NOT proven by this run |
|---|---|
| App provisions 100% offline from APK-embedded assets (airplane mode before first launch) | **ARM64** — CI has no free arm64 runners (private repo); device test is the gate |
| Embedded node/git/bash exec from app filesDir (`platform:"android"`, node v24.22.1, git 2.56.0, bash 5.3.20) | **Vivo V2035 / Funtouch** behavior (SELinux enforcing on device, battery mgmt, WebView) |
| Official ZCode `--web` server healthy on-device + embedded mock provider on 127.0.0.1:9999 | Real provider auth/billing (mock used; separate test per owner rule 4) |
| Real agent loop end-to-end ON-DEVICE: `turn.terminal status=success`, 3 tool calls, 941 ms | UI-chat-driven agent turn (driver used the NDJSON protocol directly) |
| Effects verified on DISK, not app messages: hello.txt + bash-marker.txt + git commit `2e9e294 E1-MOCK-COMMIT` | Battery/thermal/long-run stability |
| Restart persistence: force-stop → relaunch → same marker sha, files intact, runtime reused, still offline | — |

## Stage table (run 40370562643)

| Stage | Result | Key evidence |
|---|---|---|
| install | PASS | 176 MB APK (`zcode-e2-x86_64-debug.apk`), all embedded entries verified by name |
| offline | PASS | airplane mode on, `8.8.8.8` unreachable — precondition for everything below |
| setup-tap | PASS | UI-driven "Set up local runtime" → **Ready in ~12 s**, marker: `{"mode":"bionic","nodeProbe":"v24.22.1 android x64",...}` |
| node-probe | PASS | `{"node":"v24.22.1","platform":"android","arch":"x64","execPath":".../files/runtime/usr/bin/node"}`; git 2.56.0; bash 5.3.20 |
| start-tap | PASS | "Start ZCode" → Running; embedded mock healthy on 127.0.0.1:9999 |
| e2-agent | PASS | driver → `app-server --stdio`: capabilities/create/send OK; `turn.terminal status=success toolCallCount=3 durationMs=941`; resourceSample frames report `platform:"android"` |
| e2-verify | PASS | disk effects: `E1 mock write: hello from Bionic` / `E1-BASH-OK` / `2e9e294 E1-MOCK-COMMIT` (git log on device) |
| persistence | PASS | marker sha identical across force-stop+relaunch; workspace files intact; node exec again — offline throughout |
| report | PASS | classification summary emitted; zero FAIL/BLOCKED |

## Failure classification log (owner rule 6 — no auto-PRoot escalation)

| Run | Stage | Classification | Resolution |
|---|---|---|---|
| #1 | install | workflow/setup | stage-lib functions not visible in `bash -c` children (`adb: command not found`) → `export -f` |
| #2 | install | workflow/setup | inner redirect clobbered the stage log (evidence lost, not the app) → no inner `$LOGS_DIR/<stage>.log` redirects |
| #3 | setup-tap | workflow/setup | **SystemUI ANR dialog** (emulator software rendering) covered the app; screenshot proves it → AVD hardening (4 cores / 6 GB, `-feature -Vulkan`, animations 0, ANR auto-dismiss) |
| #4 | node-probe | workflow/setup | `node -e "...(...)"` quoting mangled by adb shell → probe pushed as file. (Same run already proved: offline provisioning Ready in 12 s; app-side node probe `v24.22.1 android x64` recorded by the app itself) |
| #5 | e2-verify | workflow/setup | my `sh -c "cd $WS && git log"` lost quoting through adb → `git -C`. (Same run: agent loop turn success; Write + Bash effects verified on disk) |
| #6 | — | **9/9 PASS** | — |

**Zero Bionic/app incompatibilities found in CI.** Every failure was test
machinery. The only app-side finding so far is cosmetic: git warns
`templates not found in /data/data/com.termux/.../git-core/templates` (baked
Termux prefix scanned for templates; commit succeeds). Optionally set
`GIT_TEMPLATE_DIR` later.

## Build facts (owner's three size metrics)

- APK **compressed: 176–177 MB** — ABOVE the 100 MB preference. Composition:
  zcode dist 81 MB (official tarball, stored raw by AAPT2) + Bionic bundle 58 MB
  (57 Termux packages) + app. Decompressed on disk ≈ runtime dir ~330 MB.
- Memory: agent app-server ≈ **242 MB RSS** (resourceSample frames).
- Size optimization is a post-E2 work item (dist slimming analysis, bundle
  pruning); it does not gate the E2 verification criteria.

## Static bundle audit (build job, both arches)

aarch64: 504 ELFs scanned, 0 unsatisfied DT_NEEDED (allowlist: libc/libm/libdl/
liblog/libstdc++/ld-android/libpthread/librt). x86_64: 505 ELFs, 0 unsatisfied.
First audit run caught a real gap (`lsns`→`libmount` not in any Depends) —
fixed by seeding libmount+libblkid. Baked-prefix strings recorded
(node:19, git:11, bash:12) — all env-overridable paths; only observed effect
is the cosmetic git templates warning above.

## Artifacts

- Run: `https://github.com/SeaNaxxx/ZCode-Android/actions/runs/40370562643`
- `e2-apks` (arm64 + x86_64 APKs), `e2-build-logs` (bundle manifests),
  `e2-x86_64-verify-evidence` (per-stage logs, summary.json, screenshots)

## Next step (owner's E2 conditions)

The device gate: install `zcode-e2-arm64-debug.apk` on the Vivo V2035 and
execute `docs/experiments/E2-DEVICE-PROTOCOL.md` (T0–T5). ARM64, Funtouch,
real-provider rows stay NOT TESTED until then.

---

## DEVICE FAILURE 1 (2026-10-27, Vivo V2035, arm64 0.3.0-e2) — root cause + fix

Observed: `CANNOT LINK EXECUTABLE .../usr/bin/node: library "libz.so.1" not
found: needed by main executable`, provisioning failed at the node-probe gate.
The x86_64 AVD run of the SAME code was green — proof-power separation held.

Evidence chain:
1. Termux zlib 1.3.2 deb: `libz.so.1 -> libz.so.1.3.2` is a SYMLINK
   (`dpkg-deb -c`); node's `readelf -d` NEEDs libz.so.1, libicui18n.so.78,
   libicuuc.so.78, libsqlite3.so, libcrypto.so.3, libssl.so.3,
   libc++_shared.so (+ libc/libm/libdl from the system).
2. The closure DID include zlib (nodejs-lts Depends zlib) — the old audit
   passed legitimately because it matched NEEDED names against directory
   ENTRIES: a symlink NAME satisfied the check.
3. Vivo device log: ~150+ consecutive `symlink skipped` = every
   `Files.createSymbolicLink` failed on that device (real exception swallowed
   by the old extractor). AVD verify log: zero such lines; marker
   nodeProbe=v24.22.1 proves symlinks resolved there.

Classification (owner's five): **Android permission/device filesystem
limitation** (Vivo refuses app symlink creation; exact errno unknown until
the fixed build reports it) + **app bug** (failures swallowed, bundle relied
on symlinks for linker-critical files). NOT a Bionic/ARM64 incompatibility;
no PRoot escalation triggered.

Fix (commit a8c4985, branch e2/bionic-arm64-embedded):
- Bundle builder: hard-link materialization of every file-symlink before
  tarring (359 aarch64 / 361 x86_64; tar hardlink entries = zero payload;
  bundle still 58 MiB). Audit now symlink-STRICT: every DT_NEEDED must
  resolve to a REAL file in usr/lib or a bionic system allowlist entry
  (libstdc++ removed — nothing NEEDs it). Negative test: the pre-fix bundle
  is rejected with 11 symlink-class problems. Tarball re-extraction re-audit
  added. `prefix-critical.txt` (36 sonames) embedded for the device gate.
- App: extraction report logs the REAL exceptions (first 3 + totals);
  hardlink fallback copy with 200MB budget; fail-fast critical-soname gate
  ("bionic runtime incomplete on device: missing usr/lib/…") instead of a
  late confusing linker error; node probe output classified when it contains
  CANNOT LINK. versionCode 4 / 0.3.1-e2.
- Workflow: APK artifacts split per-ABI (owner request — no combined zip) +
  prerelease `e2-rc2` with direct .apk asset downloads.

Local validation (no Android SDK locally; CI compiles + reruns AVD):
- Symlink-denying device simulation: 36/36 critical sonames extracted as real
  files, 501 ELFs, 0 unresolved NEEDED, 0 symlinks created.
- Worst case (symlinks AND hardlinks refused): critical gate still passes
  inside the 200MB copy budget; only non-critical git-core helper copies are
  skipped (git builtins unaffected).

## Run 40555932163 (fix verification, x86_64 AVD)

- build: SUCCESS — aarch64 359 / x86_64 361 symlinks materialized into hard
  links; audit OK on stage AND re-extracted tar (501/502 ELFs, 36
  linker-critical real files); APK 176.1 MiB arm64 / 175.5 MiB x86_64
  (unchanged vs pre-fix — hardlink entries carry no payload).
- Release `e2-rc2` published with both APKs as DIRECT .apk assets (owner
  request: no combined zip).
- verify: 8/9 PASS — install/offline/setup-tap/node-probe/start-tap/e2-agent/
  e2-verify all green with the hard-link bundle (offline, agent loop, disk
  effects re-verified). persistence FAIL classified **workflow/setup**:
  screenshot `screen-after-restart.png` shows the app IN "Ready" state with
  the Android-13 POST_NOTIFICATIONS system dialog covering the window, hiding
  app texts from uiautomator. Fix: pre-grant POST_NOTIFICATIONS after install
  + defensively tap "Don't allow" in the persistence stage. App state, marker,
  and workspace were intact (visually proven) — no re-provision happened.
- Intermediate failure of build (run 40552242105) classified **app bug**:
  `companion object` not applicable inside a standalone object (Kotlin 2.2.10)
  — caught by standalone frontend compile, fixed in bcedf85.

## Run 40569715105 (final, d1c1780) — FULL GREEN

9/9 stages PASS on x86_64 AVD with the hard-link bundle: install / offline /
setup-tap (offline provisioning) / node-probe (v24.22.1 android) / start-tap /
e2-agent / e2-verify (disk effects) / persistence (marker unchanged, files
intact, POST_NOTIFICATIONS pre-granted). Release `e2-rc2` refreshed with the
final APKs (direct .apk downloads). Interim failures this session, all
classified: build `companion object` in standalone object (app bug, bcedf85);
release asset 422 already_exists (workflow/setup: shell var not exported to
python — NAME passed as argv now); persistence quoting break on `Don't` +
dialog covering Ready (workflow/setup: apostrophe-free `t allow` target +
pre-grant). ARM64/Vivo/real-provider rows remain NOT TESTED — next evidence
comes from the device retest on 0.3.1-e2.
