# THIRD-PARTY LICENSE INVENTORY

**Scope.** Every third-party component that ships in (or is fetched by) the Android distribution, with license, version, and evidence. Generated-component note: the **embedded ZCode runtime** carries upstream's own generated notices (`zcode/server/remote/THIRD-PARTY-NOTICES.md`, `zcode/web/THIRD-PARTY-NOTICES.md` — 1,219 npm entries); this inventory covers the **Android shell layer** and points into the upstream notices for the runtime layer. Licenses marked ⚑ need explicit handling before any public distribution.

## 1. Android shell dependencies (gradle, `android/gradle/libs.versions.toml` + `app/build.gradle.kts`)

| Component | Version | License | Role / evidence |
|---|---|---|---|
| AGP (com.android.application) | 9.2.1 | Apache-2.0 | build tool |
| Kotlin stdlib + compose compiler plugin | 2.2.10 | Apache-2.0 | language/runtime |
| androidx.compose:compose-bom | 2026.02.01 | Apache-2.0 | ui/graphics/tooling/material3 via BOM |
| compose material3 + material-icons-extended | BOM | Apache-2.0 | UI toolkit + icons |
| androidx.core:core-ktx | 1.10.1 | Apache-2.0 | |
| androidx.lifecycle runtime/viewmodel-compose | 2.6.1 | Apache-2.0 | |
| androidx.activity:activity-compose | 1.8.0 | Apache-2.0 | |
| com.squareup.okhttp3:okhttp | 4.12.0 | Apache-2.0 | WS/RPC transport (+okio/Conscrypt transitive, Apache/BSD-family per POM) |
| org.apache.commons:commons-compress | 1.27.1 | Apache-2.0 | tgz provisioning |
| org.tukaani:xz | 1.10 | **0BSD** (public-domain-style) | xz support in provisioning |
| kotlinx-coroutines | transitive | Apache-2.0 | via compose/lifecycle |
| androidx.test.ext:junit / espresso-core | 1.1.5 / 3.5.1 | Apache-2.0 | androidTest |
| junit:junit | 4.13.2 | ⚑ **EPL-1.0** | **test scope only** — not in APK; EPL has its own redistribution terms if tests were redistributed |
| org.json:json | 20240303 | Public Domain (JSON-java) | test scope only |
| Gradle wrapper jar | committed | Apache-2.0 | build |
| androidx.camera-* / ML Kit barcode | removed | (ML Kit = proprietary Google ToS) | **dropped vs upstream shell** — posture improvement; never shipped |

**APK dependency graph contains no GPL/LGPL/CC-BY** — the only license-family obligations ship via jniLibs and the embedded runtime.

## 2. Bundled native libraries (jniLibs) — ⚑ highest priority

| File | Component | License | Notes |
|---|---|---|---|
| `jniLibs/arm64-v8a/libproot.so` | proot (Termux build) | ⚑ **GPL-2.0-or-later** | must ship license text + corresponding source offer; consider dropping with legacy proot mode (bionic mode is primary) |
| `jniLibs/arm64-v8a/libtalloc.so` | libtalloc (Termux build) | ⚑ **LGPL-3.0-or-later** | same handling |

## 3. Embedded runtime — `assets/runtime/*`

| Layer | Component | Version | License | Handling |
|---|---|---|---|---|
| bionic prefix | Node.js | **24.22.2** (Termux bionic build, NDK r30, API 24) | MIT (+ICU/OpenSSL/zlib/c-ares/etc. per Node notices) | bundle `LICENSE.node.txt` equivalent |
| bionic prefix | git 2.56.0, bash 5.3.20, curl 8.23.0, openssl 3.6.5, libicu 78.3, libsqlite 3.54.1, coreutils/util-linux, termux-exec | pinned in `bionic/manifest.json` (53 pkgs) | GPLv2/3, LGPL, BSD, MIT family per package | import Termux package license list into the shippable notices |
| bionic assets | `cacert.pem` (Mozilla CA bundle, 118 certs) | as of 2026-10-20 build | MPL-2.0-family / curl distribution terms | inventory entry + source URL |
| zcode-dist | ZCode CLI/server/web (`zcode-runtime` 3.16.2, built from upstream pin `3c5b2276`) | 3.16.2 | **Apache-2.0 © Z.AI Co., Ltd** | root attribution + upstream LICENSE |
| zcode-dist | 1,219 npm packages (agent 59 top-level + server 42 + web deps) — MIT 1018 / ISC 86 / Apache-2.0 57 / BSD-3 38 / BSD-2 19 / BlueOak 3 | per upstream THIRD-PARTY-NOTICES.md | incl. ⚑ unusual: `caniuse-lite` **CC-BY-4.0**; `dompurify` MPL/Apache dual; `elkjs` EPL/GPL dual; `jszip` MIT/GPL dual; `node-forge` BSD/GPL dual; `isexe`/`minimatch`/`sax` BlueOak-1.0.0 | **ship upstream THIRD-PARTY-NOTICES.md with the distribution** (already embedded in `server/remote/` + `web/`) and reference it from the app notices |
| zcode-dist | copied source/assets inside upstream (shadcn MIT, vercel ai-elements Apache-2.0, Material Icon Theme MIT, VS Code IPC MIT, Source Sans Pro OFL-1.1 + Hack MIT/Vera in docs) | per upstream notices | covered by the same file | — |
| zcode-dist | embedded native/WASM (Skia in `@napi-rs/canvas`, QuickJS-NG in `quickjs-wasi`) | per upstream notices — upstream itself flags Skia provenance incomplete | covered by same file | flagged by upstream; inherited |
| zcode-dist | node-pty 1.1.0 (MIT), ssh2 1.17.0 (MIT) + optional deps (nan MIT) | x86-64/glibc binaries — see RUNTIME-AUDIT §2 | MIT | license fine; binary incompatible with bionic (functional, not licensing) |
| runtime provisioning | ripgrep kit via `get-rg.cjs` (Alpine: ripgrep + musl loader + pcre2 + libgcc) | on-device fetch | ripgrep **MIT OR Apache-2.0**; PCRE2 **BSD**; musl **MIT** | inventory entry + source URLs |
| legacy proot mode downloads | Ubuntu 24.04.3 base rootfs; Node.js v24.14.0 linux-arm64 (SHASUMS-verified) | on-device fetch | Ubuntu per its EULA set; Node MIT | inventory if legacy mode is retained |

## 4. CI/tooling (not shipped, listed for completeness)

GitHub Actions (hosted), Android SDK/emulator images (SDK license), sdkmanager/avdmanager, `build-tools` aapt — all under their respective SDK licenses; no redistribution obligations for the repo itself.

## 5. Shipping artifacts checklist (post-approval, not done in this audit)

1. Root LICENSE (Apache-2.0, dual copyright line) + NOTICE (upstream ZCode/Z.AI attribution, modified files list, artwork provenance).
2. `THIRD-PARTY-LICENSE-INVENTORY` as a shippable file (this doc's content, or a generated notices file wired into CI next to `zcode-dist.sha256`).
3. proot/libtalloc: license texts + written source offer — **or** removal decision for legacy proot mode.
4. Node.js license text bundled next to the runtime assets.
5. Verify upstream THIRD-PARTY-NOTICES.md reaches end users (it already exists twice inside the dist; add an app-side pointer in settings/about or docs).
