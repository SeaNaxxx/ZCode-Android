# ZCODE DISTRIBUTION PROVENANCE

**Question answered here:** what exactly is each ZCode distribution, which upstream source does each correspond to, and how do they relate. Everything below separates **VERIFIED** (read from files/binaries on disk) from **OWNER-PROVIDED / UNVERIFIED** (claimed in the owner brief; artifact not present on this machine).

## 1. Distribution matrix

| Distribution | Version | Arch | Source of version | Upstream linkage | Status of evidence |
|---|---|---|---|---|---|
| Windows Desktop x64 installer (`ZCode-3.14.3-win-x64.exe`) | 3.14.3 | x64 | owner brief (filename + inventory) | Electron app built from `packages/desktop` pipeline | **UNVERIFIED** — exe/inventory not on this machine; version not reproducible from repo (see §3) |
| Windows Desktop ARM64 installer (`ZCode-3.16.3-win-arm64.exe`) | 3.16.3 | ARM64 | owner brief | same pipeline, later cut | **UNVERIFIED** locally; corroborated indirectly: ENGINEERING-NOTES.md:89–91 records v3.14.3 shipping no Linux assets and `cdn-zcode.z.ai/.../3.16.3/linux-arm64/...AppImage` existing — i.e. 3.16.3 is a real later release cut |
| **Upstream source pin used by this repo** | **3.16.2** (untagged main) | — | `/tmp/upstream-pin/package.json` = `{"name":"zcode","version":"3.16.2"}` @ commit `3c5b227649371507ddc4eb46ac5133afe6db1cab` (2026-10-20, "chore: sync ZCode source"); latest upstream tag at analysis time `v3.15.1` | — | **VERIFIED** |
| **Android bundled runtime** (`assets/runtime/zcode-dist.tgz`) | **3.16.2** | JS (arch-independent) + per-ABI bionic userspace | `zcode/package.json` extracted from the tgz: `{"name":"zcode-runtime","private":true,"type":"module","version":"3.16.2"}` | built from the pinned upstream by CI (`pnpm build:zcode`) | **VERIFIED** (sha256-matched tgz read streaming, 14,555 entries) |
| Android APK (`zcode-android-arm64-debug.apk`, release 0.6.0 asset) | versionName **0.6.0**, versionCode **23** | arm64-v8a runtime userspace; dex is arch-neutral | binary AndroidManifest parse | app shell = this repo @ HEAD; runtime = pinned upstream | **VERIFIED** (see §2/§4) |
| Bionic userspace (`assets/runtime/bionic/prefix.tgz`) | node **24.22.2**, git 2.56.0, bash 5.3.20, openssl 3.6.5 (Termux packages) | aarch64 (device APK) / x86_64 (CI APK) | `bionic/manifest.json`; ELF headers | Termux-packaged bionic closure, app-embedded | **VERIFIED** |

**Do-not-assume rule honored:** 3.14.3 and 3.16.3 installers are different version cuts of the desktop pipeline; their internal `app.asar` contents were **not** inspected here and are not assumed identical to the 3.16.2 source pin. The desktop app derives its shipped version from the workspace root `package.json` at build time (`packages/desktop/scripts/build-metadata.mjs` reads root version → `appVersion`), so 3.14.3/3.16.3 ⇒ root `package.json` versions 3.14.3/3.16.3 of the upstream tree at their respective build dates. That inference is **[ASSUMED from build scripts + owner inventory]**, not verified against the actual installers.

## 2. Hash ledger (computed 2026-11-07, release 0.6.0 asset)

| Artifact | sha256 | Sidecar | Verdict |
|---|---|---|---|
| `zcode-android-arm64-debug.apk` (downloaded from release 0.6.0 asset, 181,935,540 B) | `9ace62df6b7cd5a5fe7c31452032afb1c44c2b31b19c4f7a812f1b035a1b7556` | — | recorded |
| `assets/runtime/zcode-dist.tgz` (84,052,720 B) | `c6eff85a7beb11486845e4b8664b688067f1cf1ca55c968e0bdbe885df393ccc` | `zcode-dist.sha256` — identical | **MATCH** |
| `assets/runtime/bionic/prefix.tgz` (77,832,517 B) | `5cbe80ff6ac85c903dd86b6a696c010e0e48ae4d5e3f7ec1b822453c03ac3bf8` | `prefix.sha256` — identical | **MATCH** |
| Provisioning marker (device-side, x86_64 CI run 42339426624) | records `distSha256: c6eff85a…` + `bundleSha256: 35cd7dc92fc77ddae14c5b1a59e10b28c76b17423ddd7542d0c612ee0cdb9e7f` (Provisioner-staged offline agent kit) | — | cross-run consistency [V] |
| `bionic/manifest.json` `builtAt` | `2026-11-07T04:46:57Z` (same build day as dist 04:45 + APK repack) | — | [V] |

## 3. Desktop distributions — what is and is not established

Owner-provided inventory (not on disk) states for `ZCode-3.14.3-win-x64.exe`: Electron app with `resources/app.asar`, `app.asar.unpacked`, `resources/glm/zcode.cjs`, `resources/config`, `resources/tools`, node-pty, ssh2, Chromium resources.

Correlation against the verified upstream source at the pin (**all [V] in source, none against the actual exe**):
- `packages/desktop/package.json`: `@zcode/desktop`, Electron 41.0.3, `productName: "ZCode"`, `node-pty ^1.0.0`, `ssh2 ^1.16.0` — matches the inventory's node-pty/ssh2 presence.
- `packages/desktop/scripts/prepare-agent-node-bundle.mjs` stages the CLI bundle into `bundled-agents/<platform>/glm/`; `resources/glm/zcode.cjs` in the exe = `apps/zcode-cli/packages/cli/dist/zcode.cjs` built from the pinned upstream — matches the inventory path exactly.
- Electron main loads `file://…/renderer/<page>.html` in packaged builds (no local HTTP server for the UI) — see DESKTOP-UI-ARCHITECTURE-AUDIT.md.
- Version provenance: `appVersion` from root `package.json`; `zcodeCompatibility.minCompatibleVersion` from build metadata.

**Conclusion:** the owner inventory is fully consistent with the upstream desktop package structure at the pin, but the exe trees themselves remain **UNVERIFIED** artifacts; per-version file hashes, asar contents, and whether 3.14.3/3.16.3 carry additional runtime differences cannot be established from this machine.

## 4. Android APK ↔ repo ↔ runtime lineage (VERIFIED)

- Release **0.6.0**: created 2026-11-05, tag target `ae6f05d` ("fix: vql decode number normalization across org.json variants"); release **asset updated 2026-11-07T04:50:34Z** — i.e. the APK file currently attached was built by CI run **42339426624** (head `06a2120`). The tag target is therefore 28 commits behind the published asset; provenance of the *binary* is the run, not the tag.
- `android/app/build.gradle.kts`: `versionCode = 23`, `versionName = "0.6.0"` (unchanged across the native-UI wave — the released 0.6.0 asset contains both the pre- and post-native-UI lineages' builds at different times).
- APK internals [V]: `assets/runtime/{zcode-dist.tgz, bionic/*}` as above; `lib/<abi>/` contains only `libandroidx.graphics.path.so` (10,096 B) per ABI — **100 % of the server runtime ships via assets**, no bundled node/libc++ .so; minSdk 24, targetSdk 28 (deliberate, W^X rationale), compileSdk 36; debug build (`debuggable=true`); AGP 9.2.1.
- Runtime chain inside the APK [V from dist contents]: `bin/zcode.mjs --web` → spawns `node server/entry-http.js` (`ZCODE_WEB_STATIC_ROOT=<dist>/web`, `ZCODE_AGENT_SERVER_COMMAND=process.execPath`, `ZCODE_AGENT_SERVER_ARGS_JSON='[<agent>,"app-server","--stdio"]'`, auto token on `ZCODE_SERVER_AUTH_TOKEN`) → server serves the built Vite SPA (`zcode/web/index-CUyp48yK.js` + `index-Djn8_6OI.css`, Zai Light/Dark tokens) and bridges stdio to `agent/zcode.cjs` (30,128,856 B single-file bundle, protocol strings 3.0.121/3.0.65/3.0.81/3.4.0).
- On-device provisioning (CI evidence, x86_64 run): marker `mode=bionic`, nodeProbe `v24.22.2 android x64`, dist sha matches the APK-embedded sidecar — the APK asset and the runtime actually provisioned are the same bytes.

## 5. Upstream commit usage across artifacts

| Consumer | Upstream reference | Verified how |
|---|---|---|
| Android repo docs/patches | pin `3c5b2276…` | repo files + workflow env `UPSTREAM_PIN` [V] |
| CI build job | checks out upstream at `3c5b2276…`, applies 1 local patch (`0001-project-route-local-alias-for-standalone-server.patch`), builds CLI + dist | workflow source [V]; device-side sha equality [V] |
| Desktop exes 3.14.3 / 3.16.3 | unknown upstream commits (versions imply root package.json at build date) | **UNVERIFIED** — requires the actual installers or upstream release metadata |
| Bundled runtime 3.16.2 | pin's root version 3.16.2 [V] | dist `package.json` [V] |

## 6. Unresolved differences / open items

1. Desktop installers absent → asar contents, native module versions inside the exes, and exact upstream SHAs for 3.14.3/3.16.3 remain unestablished. If provenance must be closed, hash the two exes and record upstream release metadata per version.
2. Release tag 0.6.0 ≠ published asset commit (`ae6f05d` vs `06a2120` build). Recommendation (post-approval): tag releases at the build commit or record the run id in release notes.
3. `zcode/agent/zcode.cjs` contains protocol-level version strings (3.0.x) newer-looking than 3.16.2 — these are protocol revisions inside the bundle, **not** distribution versions; documented to prevent misreading.
4. `versionName 0.6.0` was not bumped for the native-UI wave; next release should bump (e.g. 0.7.0) so APK version identifies the UI generation.
