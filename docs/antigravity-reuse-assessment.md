# Antigravity-Android — REUSE / ADAPT / NOT NEEDED Assessment

**Standalone report — independent of the Bionic E0/E1 experiment (Task 16).**
Subject: `SeaNaxxx/antigravity-android` v2.4.1 (fork of werdio325-png/antigravity-android,
Apache-2.0, commit cbf132e, ~407 MB).
Method: source-level audit of `modules/{core,env,io,patches,runner,builder}` + `env/` + `tools/`.
Author: GLM agent, 2026-10-26.

## خلاصة تنفيذية (Arabic TL;DR)

فورك antigravity يحلّ **مشكلة مختلفة عن مشكلتنا**: تشغيل ثنائي مبني على glibc
(`language_server` من Google) فوق Bionic عبر محمّل glibc + ترقيع ثنائي + محاكاة
LSE. نحن لا نحتاج هذا لأن Node.js المبني لـ Bionic (Termux) أثبت تشغيل توزيعة
ZCode الرسمية (E0: 12/12 PASS على محاكي x86_64). القيمة الحقيقية فيه:
مدير الحزم `env/pkg` (يقرأ فهرس Termux ويثبّت deb دون root)، نمط ترقيع
البادئة `patch-prefix.py`، ونمط التضمين المسبق للحزم داخل APK. الترخيص
Apache-2.0 يسمح بإعادة الاستخدام مع حفظ الإشعارات.

---

## 1. What antigravity-android actually is (evidence-based)

| Aspect | Finding | Evidence |
|---|---|---|
| Target binary | Google Antigravity `language_server` — a **glibc Linux binary** (Go: pclntab patching) | `modules/core/README.md` §1, `modules/patches/engine/pclntab_scanner.py` |
| Strategy | Bare-metal on Bionic via **glibc loader shim** (`ld-linux-aarch64.so.1` → `libloader.so`) + minimal glibc libs | `modules/core/glibc/` (libc.so.6, libpthread, libresolv, libnss_dns...), `modules/runner/start_core.sh:20-28` |
| Platform workarounds | seccomp bypass, Bionic libc gaps, ARMv8.0-A LSE emulation (`liblse_compat.so`) | `modules/patches/README.md`, `modules/core/glibc/liblse_compat.so` |
| Userspace | **Termux bootstrap** retarred into the app's files dir (`env/bootstrap/bootstrap-aarch64.zip`, prefix patched) + prebundled `git.deb` | `env/README.md` §1, `env/bootstrap/patch-prefix.py`, `env/packages/git.deb` |
| Package manager | Self-written `pkg` (POSIX sh): reads Termux-format `Packages.gz`, resolves deps, fetches+installs debs, no root | `env/pkg/pkg` + `env/pkg/lib/{index,fetch,deb,resolver,db}.sh` |
| Shims | `xdg-open`, browser-open, Shizuku `rish` privilege bridge | `modules/core/shims/{browser,system,privilege}/` |
| App plumbing | I/O event bus (FIFO/UDS/Intents), micro-daemon (cron/watcher), Gradle-less APK builder | `modules/io/README.md`, `env/overlay/bin/agy-daemon`, `modules/builder/` |

## 2. Why the architectural core does NOT transfer to ZCode

ZCode's runtime is **Node.js**, and the Bionic path is already proven:
Termux `nodejs-lts` 24.22.1 boots the official ZCode distribution on a real
Android Bionic userspace (E0 run `40245458592`: 12/12 stages PASS on the x86_64
AVD; reproduction + agent-loop E1 in flight on branch `experiment/bionic-e0-e1`).
Node on Bionic means there is **no glibc binary left to load** in the core
runtime path — the exact problem antigravity's loader stack exists to solve.
Path C (glibc compatibility layer) therefore remains a documented fallback, not
an implementation target. The only glibc artifacts inside the ZCode dist are the
lazy `node-pty` prebuilds (terminal feature only, `terminalService.ts` catches
the load failure), which the agent loop does not touch.

## 3. REUSE / ADAPT / NOT NEEDED table

| # | Component (path) | Verdict | Rationale & conditions |
|---|---|---|---|
| 1 | `env/pkg` package manager (sh, ~1k LOC) | **ADAPT** | On-device provisioning of `nodejs-lts`/`git` from the Termux repo without root — exactly the APK provisioning gap after E0. It already speaks the Termux `Packages`/deb format our CI resolver uses. Adapt: pin repo+channel, add SHA256 enforcement per package (it currently trusts index presence; our CI resolver verifies), wire into `Provisioner` to replace runtime downloads (acceptance ① ②). |
| 2 | `env/bootstrap/patch-prefix.py` (ELF prefix rewrite) | **ADAPT** | The APK cannot ship files under `/data/data/com.termux`; Termux bootstrap binaries carry that prefix baked in. Their rewriter is the proven in-repo solution for relocating a Termux userspace into an arbitrary app dir. Needed the moment we embed a rootfs in the APK (Phase E2). |
| 3 | Prebundled-debs pattern (`env/packages/git.deb` + bootstrap tar) | **ADAPT** | Validates acceptance criteria ① ② (no post-install downloads): embed `nodejs-lts` + closure debs + `zcode-dist.tgz` as APK assets, install locally. Mirrors our existing `zcode-dist.tgz` embedding; extends it to the Node runtime. |
| 4 | `modules/core/manifest/` (streaming SHA-256 manifest builder/verifier) | **REUSE (optional)** | Clean, chunked SHA-256 tooling (Apache-2.0). Our tarball-sidecar sha256 already covers current needs; consider only if per-file integrity of an embedded tree becomes a requirement. |
| 5 | `modules/core/shims/system|browser` (xdg-open etc.) | **ADAPT (trivial, conditional)** | `zcode --web` tries `xdg-open` on localhost by default (`runner.mjs openBrowser`); we pass `--no-open` in CI and the APK opens its own WebView, so a shim is unnecessary today. If any CLI path ever calls `xdg-open` on-device, their 10-line `am start` shim is the reference. |
| 6 | `modules/runner/start_core.sh` lifecycle patterns (loader fallback chain, port preflight, reserved-port guard, CSRF note) | **ADAPT (pattern, not code)** | Directly applicable hardening ideas for `LocalServerManager`/`LocalServerService`: port-collision preflight before spawning, reserved-port guard, explicit `$HOME`/`$TMPDIR` defaults. Reference only; our Kotlin layer differs. |
| 7 | `env/overlay/bin/agy-fix-paths` (dynamic shebang/path normalization) | **ADAPT (conditional)** | Backup for `termux-exec` LD_PRELOAD shebang rewriting if any bundled script misbehaves on-device. E0/E1 invoke everything via explicit `node <file>`, so not needed initially. |
| 8 | `modules/core/glibc/` loader + libs, `liblse_compat.so` | **NOT NEEDED (for Bionic path)** | Required only to run glibc binaries on Bionic — our runtime is Bionic-native node. `liblse_compat.so` becomes relevant ONLY if we ever ship LSE-targeted binaries for ARMv8.0-A (the Vivo Y51's Snapdragon 665 has no LSE; Termux node targets plain armv8-a, so it does not need it). Keep as Path-C reference. |
| 9 | `modules/patches/` (Go pclntab MOVZ patching, seccomp/bionic byte patches) | **NOT NEEDED** | ZCode ships no Go binary and no binary we compile; V8/JIT constraints are already solved inside Termux node. Their patch *classes* are useful documentation if a future native component hits seccomp/Bionic issues. |
| 10 | `modules/io/` event bus, `agy-daemon`, `modules/builder/` (Gradle-less APK), `tools/r8.jar`, `tools/debug.keystore` | **NOT NEEDED** | We have Kotlin+Gradle CI (`android-build.yml`), NDJSON stdio transport (proven), and an Android foreground service for lifecycle. The debug keystore must NOT be copied (our APK signs with our own config; keystores are not reusable artifacts). |
| 11 | `modules/core/shims/privilege/rish.sh` (Shizuku) | **NOT NEEDED** | ZCode runs fully inside its own app sandbox; no privileged operations are on the roadmap. |

## 4. License compliance

Apache-2.0 throughout (verified `LICENSE` at cbf132e). Any adapted component
(items 1-3, 5-7) requires: preserve copyright notices, include the Apache-2.0
text or reference, and state changes. Recommended: a `NOTICE-antigravity.md`
next to `jniLibs/NOTICE.md` when adaptation lands in the APK.

## 5. Bottom line

- **Reuse intent:** items 1-3 form a coherent, proven "on-device provisioning
  kit" (bootstrap relocation + deb resolver + prebundling) that Phase E2
  (embedded-runtime APK) can adopt instead of reinventing provisioning.
- **Path C status:** antigravity is the strongest available reference for the
  glibc-compatibility path, but the Bionic E0 result demotes that path to
  documentation-only unless on-device ARM64 testing surfaces a glibc-only blocker.
- **No overlap with app code today:** nothing in the current ZCode-Android dev
  branch needs to change to benefit from items 1-3; they slot into the
  provisioning layer when that phase is authorized.
