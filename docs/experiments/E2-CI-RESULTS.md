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
