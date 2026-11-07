# ANDROID RUNTIME COMPATIBILITY AUDIT (bionic userspace, native modules, PTY)

**Evidence basis:** binary-level inspection of release 0.6.0 APK (`assets/runtime/zcode-dist.tgz` 14,555 entries; `bionic/prefix.tgz` 1,972 entries; both sha256-MATCH their sidecars) + on-device probe evidence from CI run 42339426624. All items **VERIFIED** unless marked otherwise.

## 1. Userspace model

- The runtime is a **Termux-packaged bionic closure** (`bionic/manifest.json`: `"mode":"bionic","arch":"aarch64","node":"24.22.2"`, termux repo index pinned; 53 pinned packages incl. nodejs-lts 24.22.2, git 2.56.0, bash 5.3.20, openssl 3.6.5, ca-certificates, curl 8.23.0, libicu 78.3, libsqlite 3.54.1, termux-exec).
- **Embedded node:** `usr/bin/node` = ELF 64-bit **AArch64**, ET_DYN, **interpreter `/system/bin/linker64`**, "for Android 24, built by NDK r30", stripped; `strings` → **v24.22.2**. NEEDED: libz/libcares/libsqlite3/libcrypto.so.3/libssl.so.3/libicui18n|uc.so.78 (materialized in prefix) + libc/libm/libdl (Android system allowlist). `usr/bin/git` identical signature. The CI x86_64 APK carries the same closure built for x86_64 (device probes print `arch:"x64"` there) — one userspace, two ABIs.
- Manifest audit: **202 ELFs scanned, `unsatisfied_needed: []`**; `sonamesMaterializedAsRealFiles` = 11 linker-critical sonames shipped as real files (Vivo/OEM symlink robustness, "G1"); **zero hardlinks** in delivered tars ("G2"); `usr/etc/tls/{openssl.cnf,cert.pem}` shipped because Termux libcrypto compiles `OPENSSLDIR` as `/data/data/com.termux/...` ("G3") — the app sets `OPENSSL_CONF/OPENSSL_MODULES/SSL_CERT_FILE` to runtime copies (BionicEnv; on-device evidence: openssl-offline TLSv1.3 handshake, 118 root certs, zero network).
- Residual risk [V]: `bakedTermuxPrefixStrings` — 19 `/data/data/com.termux` strings in `usr/bin/node`, 13 in libcrypto.so.3 etc. Inert for prefix/config resolution (different app uid), mitigated by G3 env; documented, not fixed (upstream Termux binaries).
- TLS/CA: `bionic/cacert.pem` = Mozilla bundle, **118 certificates**, 200,116 B, duplicated at `usr/etc/tls/cert.pem`; consumed via `SSL_CERT_FILE/NODE_EXTRA_CA_CERTS/GIT_SSL_CAINFO/ZCODE_AGENT_CA_CERT`. Device evidence: api.github.com 200, api.z.ai chain verified (`*.z.ai` ← Sectigo ← USERTrust), zcode.z.ai device-flow init 200 — all with `tlsAuthorized:true`.

## 2. Native Node modules — **the compatibility blocker**

Inventory of `.node` binaries inside `zcode-dist.tgz` [V by `file`/`readelf`]:

| Path | Arch | libc |
|---|---|---|
| `node_modules/node-pty/build/Release/pty.node` | **x86-64** | glibc (`libstdc++.so.6`, `libgcc_s.so.1`, `libc.so.6`) |
| `node_modules/node-pty/bin/linux-x64-145/node-pty.node` | **x86-64** | glibc |
| `node_modules/node-pty/prebuilds/linux-arm64/pty.node` | AArch64 | **glibc** (needs `ld-linux-aarch64.so.1`, `libutil.so.1`, `libpthread.so.0`) |
| `node_modules/ssh2/.../sshcrypto.node` | x86-64 | glibc |
| `node_modules/cpu-features/build/Release/cpufeatures.node` | x86-64 | glibc |

node-pty 1.1.0 prebuilds cover win32/darwin/linux(-x64,-arm64) — **no android/bionic prebuild exists anywhere in the dist**. None of these binaries can dlopen under bionic (wrong arch or missing glibc SONAMEs).

**On-device verdict (CI, x86_64 emulator):** `PTY-PROBE-VERDICT: NEGATIVE` — load error names the exact missing binary: `Cannot find module './prebuilds/android-x64//pty.node'`. The probe requires a **real `pty.spawn()`**, not merely a successful module load.

**Consequences:**
- The upstream terminal feature **cannot work** on Android until node-pty (and ssh2's optional crypto addon) is rebuilt against the bionic Node ABI. Mitigating assets [V]: `usr/include/node/*` headers ship in the prefix and `node-gyp-build` is present in the agent closure — an on-device or cross-compile bionic rebuild is plausible but **unproven**; the alternative proven pattern is the **musl-kit approach** used for ripgrep (§3): ship a statically-linked loader kit instead of a glibc .node.
- ssh2's pure-JS fallback covers protocol use without `sshcrypto.node` (cpu-features is optional) [I — consistent with ssh2's documented optional-native design; not separately probed].
- The Android terminal UI stays **honestly gated** (Workflow + code both treat PTY as unavailable; UI evidence audit D5 confirms no terminal screen is claimed).

## 3. Ripgrep — the proven alternative pattern

- The dist ships **no `rg`** and the prefix has no ripgrep/openssl/npm binaries [V]. `bionic/get-rg.cjs` provisions ripgrep at runtime from Alpine 3.20: downloads `ripgrep`, **`ld-musl-aarch64.so.1`** loader, pcre2, libgcc; builds a symlink-free kit and an `rg.sh` launcher (`lib/ld-musl --library-path lib lib/rg`); best-effort, read-only prefix safe. This **static-loader-kit** pattern is the sanctioned way to deliver glibc-unfriendly binaries on bionic and is the template for any future node-pty fix.

## 4. Runtime contract helpers (bionic/*.cjs — what each proves)

| Script | Proves on-device | Marker |
|---|---|---|
| `verify-web-api-contract.cjs` | `GET /` 200 SPA; `GET /remote` 200 SPA; `GET /ws` 404 (upgrade-only); `/api/__contract_probe__` 404 (no SPA fallback) | `CONTRACT-OK all 4 assertions` |
| `verify-agent-driver.cjs` | full agent turn over stdio JSON-RPC (`runtime/capabilities` → `session/create` mode yolo, mock provider → `session/send` → tool calls → sentinel) | driver sentinel / effects |
| `verify-second-send.cjs` | second-send regression (ownerHostId `remote:server:<id>` vs UI `"local"`; probes `project.readTree(local)` + `resolveSessionDirectories(local)`) | `SECOND-SEND-REGRESSION-OK` |
| `verify-pty-probe.cjs` | real `pty.spawn()` attempt → **NEGATIVE** verdict kept as the gate | `PTY-PROBE-VERDICT` |
| `verify-mock-provider.cjs` | offline OpenAI-compatible mock on 127.0.0.1:9999 with real tool_calls loop | request log JSONL |
| `get-rg.cjs` | ripgrep kit provisioning (musl loader pattern) | `kit-ok … sha256` |
| `diag-collector.cjs` | redacted one-shot diagnostics (PSS, statfs, server 200s, log counters) | `diag-report.json` |
| `tls-diagnose.cjs` | TLS self-diagnosis, verification never disabled, clock skew | `TLS-DIAGNOSE-OK` |
| `oauth-flow-probe.cjs` | device-flow init+poll against `zcode.z.ai`, no credentials | `OAUTH-FLOW-PROBE-OK` |

## 5. Server & web assets inside the dist

- `zcode/server/`: bundled `entry-http.js` (5.4 MB) + chunks + `remote/zcode-server.cjs` (12.2 MB) + its own `THIRD-PARTY-NOTICES.md`; version string `"3.16.2"` present; hono 4.12.12, ws 8.20.0, axios 1.13.6, undici 6.24.1; 42 top-level runtime `node_modules` packages (incl. node-pty 1.1.0, ssh2 1.17.0, node-addon-api, nan).
- `zcode/web/`: **built Vite SPA** (6,121 entries) — `index.html` (title "ZCode", theme-color `#161616`, inline `zcode-theme`/`zai-dark` bootstrap), entry `assets/index-CUyp48yK.js` (6.8 MB) + `index-Djn8_6OI.css` (474 KB) + 50 modulepreloads, pdfjs assets, material icons. CSS contains the full **`.theme-zai-light` / `.theme-zai-dark`** blocks (values identical to upstream source — bg `#f8f8f8`/`#161616`, primary `#000`/`#fff`, success `#166b32`/`#87d9a4` surfaces, terminal palettes). Settings symbols (`openSettingsTab`, `providerSettingsView` with zai/bigmodel families) present in the bundle.
- Agent bundle `zcode/agent/zcode.cjs` (30.1 MB) + `provider/zcode-builtin.json` (revision 33; account families `account:zai-*`, `account:bigmodel-*`); agent `node_modules` = 59 top-level packages (shiki, web-tree-sitter, koffi, ws, zod, yoga-layout…). Protocol strings 3.0.121/3.0.65/3.0.81/3.4.0.

## 6. APK shell platform facts

- versionName **0.6.0** / versionCode **23**; minSdk 24, **targetSdk 28 (deliberate)**, compileSdk 36; `debuggable=true` (debug build); AGP 9.2.1; `extractNativeLibs=false`.
- `lib/<abi>/`: only `libandroidx.graphics.path.so` (10,096 B) × 4 ABIs — **no node, no libc++_shared, no JNI runtime code**; the entire server runtime ships via `assets/runtime/*` and executes as child processes of the app (exec of extracted userland enabled by the targetSdk-28 W^X latitude).
- Permissions: INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE(_DATA_SYNC), POST_NOTIFICATIONS, READ/WRITE_EXTERNAL_STORAGE; network security config = cleartext loopback only.

## 7. Windows desktop comparison (context for parity)

Desktop ships node-pty/ssh2 for Win32 (ConPTY/winpty prebuilds) inside Electron's Node — none of that is portable to Android/bionic; the Android side must be treated as its own binary-compatibility domain. No Windows ConPTY→Android mapping exists or is attempted.

## 8. Conclusions and gating rules

1. **ARM64 claims discipline:** the prefix in the *device* APK is AArch64 bionic and the CI x86_64 APK proves app logic — but on-device ARM64 exec evidence exists only as deferred device acceptance; CI's x86_64 run must never be quoted as ARM64 proof.
2. **Terminal = blocked, honestly:** keep the terminal UI unbuilt until a bionic node-pty exists (rebuild against shipped headers or musl-kit style static approach); `verify-pty-probe` stays the CI gate.
3. **TLS/OAuth/network:** fully verified path with pinned CA bundle; do not regress the G3 env wiring.
4. **ripgrep:** keep get-rg provisioning; its pattern is the model for any new native dependency.
5. **Residual Termux strings:** documented; no action unless a CVE or path leak probe (failure-2 class) reclassifies them.
