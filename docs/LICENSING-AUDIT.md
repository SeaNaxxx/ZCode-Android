# LICENSING AUDIT

**Scope.** Repository-wide licensing audit of `SeaNaxxx/ZCode-Android` (dev @ `06a2120`) against upstream `zai-org/ZCode` @ `3c5b2276`. **No license or branding files were changed during this audit** — findings and recommendations only. Companion docs: BRANDING-AND-ICON-AUDIT.md, THIRD-PARTY-LICENSE-INVENTORY.md.

## 1. Upstream license — VERIFIED

- `LICENSE` at the pin = **Apache License 2.0**, standard text, copyright line (the only one in the file): **"Copyright 2026 Z.AI Co., Ltd"**.
- Root `package.json`: `"license": "Apache-2.0"`, version 3.16.2. Workspace packages are `private` with no per-package license (desktop/web/cli inherit root Apache-2.0 de facto; only `packages/zcode-cua` carries an explicit field).
- `NOTICE.md` (73 lines, Chinese): functional/risk disclosure + §四 third-party declaration — first-party code Apache-2.0 per root LICENSE; that license does **not** cover third-party software, copied code, native binaries, **fonts, icons, web assets** under their own terms; details in THIRD-PARTY-NOTICES.md.
- `THIRD-PARTY-NOTICES.md` (51,311 lines): **generated** by `node scripts/licenses.mjs notices` from `third-party/inventory.json`; 1,219 npm entries + copied-source/assets + embedded native/WASM sections. License distribution: MIT 1018, ISC 86, Apache-2.0 57, BSD-3 38, BSD-2 19, BlueOak 3 + dual/odd entries (CC-BY-4.0 `caniuse-lite`, MPL/Apache dual `dompurify`, EPL/GPL dual `elkjs`, MIT/GPL dual `jszip`, BSD/GPL dual `node-forge`, OFL fonts in knip docs, …).

## 2. Android repository state — VERIFIED

1. **No root `LICENSE` file exists.** No root `NOTICE`. The only license-adjacent file is `android/NOTICE-LOCAL.md` (18 lines, added in the founding commit `f1fd509`).
2. **No copyright headers** on any of the 42 Kotlin sources (no SPDX/Copyright/Licensed-under strings anywhere in `.kt`/`.xml`).
3. `README.md:51–53` states: original project zai-org/ZCode (Apache-2.0); "this repository is independent and is not a fork; upstream source is used at a pinned commit to build the embedded distribution."
4. `android/NOTICE-LOCAL.md` inventories: Android shell reused from upstream `packages/android` @ pin ("unmodified except: MainActivity mode switch, added `local/` package, targetSdk change, string resources"); **proot (GPL-2.0+)** and **libtalloc (LGPL-3.0+)** as `jniLibs/arm64-v8a/*.so`; ZCode CLI dist (Apache-2.0, sha256-verified); Ubuntu rootfs + Node v24.14.0 (MIT, on-device downloads). It also claims "No Z.ai/ZCode branding assets were invented or altered" — **inaccurate in detail** (see BRANDING-AND-ICON-AUDIT.md §2: icons are modified derivatives).
5. **Inaccuracy [V]:** MainActivity at HEAD differs from the upstream shell by **374 lines** (dark-mode workaround, local runtime wiring, native-only routing) — "mode switch" understates a near-rewrite. Apache-2.0 §4(b) modified-file notices are thin.

## 3. Is Apache-2.0 appropriate for the original contributions? — YES, with conditions

- The repo's own contributions (Kotlin native app, provisioning/runtime-manager code, CI workflow, verify scripts, docs) are **eligible for Apache-2.0**, consistent with the upstream license (simplifies redistribution of derived files).
- Apache-2.0 §4 conditions currently **unmet**:
  - (a) **No LICENSE text ships** → must add root LICENSE (Apache-2.0) with the upstream copyright line **plus** the project's own copyright line for modifications.
  - (b) **No modified-file notices** for changed upstream-derived files (MainActivity.kt, res/values/{strings,colors}.xml, adaptive-icon XMLs, icon PNGs, `upstream-patches/0001-*.patch` — the patch itself is a modification of upstream source and carries upstream Apache-2.0).
  - (c) **NOTICE**: if a NOTICE file is created, upstream attribution must be included; upstream's own risk-disclosure NOTICE content is worth mirroring for the embedded runtime.

## 4. Files that cannot be covered by a root license — VERIFIED list

| Asset | Why not covered | Required handling |
|---|---|---|
| `jniLibs/arm64-v8a/libproot.so` — **GPL-2.0+** (Termux) | GPL is not Apache-2.0-compatible for *distribution* withoutGPL obligations | license text + source offer in the distribution inventory; **highest-priority compliance item**; consider build-time optionality |
| `jniLibs/arm64-v8a/libtalloc.so` — **LGPL-3.0+** (Termux) | LGPL obligations | same treatment |
| Embedded Node.js (v24.22.2 bionic / v24.14.0 download path) | MIT + its own dep tree | ship `LICENSE.node.txt` (upstream distributions expose via SEA `--licenses`; our dist must bundle equivalent) |
| zcode-dist contents (1,219 npm packages incl. CC-BY-4.0, EPL/GPL-dual, BlueOak items) | per-package licenses | import upstream THIRD-PARTY-NOTICES.md into the APK/docs inventory |
| ripgrep (+PCRE2) provisioned by `get-rg.cjs` | MIT OR Apache-2.0 + BSD (PCRE2) | inventory entry |
| `cacert.pem` (Mozilla bundle) | MPL-2.0-family/curl distribution terms | inventory entry + source URL |
| Launcher icon PNGs | derivative of upstream logo asset (Apache-2.0 *works* carve-out + trademark limits) | see BRANDING-AND-ICON-AUDIT.md |
| Gradle wrapper jar | Apache-2.0 (fine) | inventory entry |

## 5. Trademark — open question

Apache-2.0 §6 explicitly **does not grant trademark rights**. The app ships `app_name = "ZCode"` and a notification string "The official ZCode web interface is running on this device." Both reuse the ZCode/Z.ai marks. Whether "ZCode" is a registered mark of Z.AI and whether a nominative "unofficial Android client" qualifier is required is a **business/legal decision the audit cannot settle** — flagged as UNRESOLVED with a recommendation (BRANDING-AND-ICON-AUDIT.md §4).

## 6. Findings summary & recommendations (no files changed)

**Verified facts:** upstream Apache-2.0 (Z.AI Co., Ltd 2026); repo ships no LICENSE/NOTICE at root; 42 unheadered original sources; proot GPL-2.0+ + libtalloc LGPL-3.0+ inside jniLibs; NOTICE-LOCAL exists but understates MainActivity changes and misdescribes icon provenance; ML Kit/CameraX (proprietary Google ToS) were **removed** vs upstream shell — posture improvement.

**Recommended (post-approval) actions, in order:**
1. Add root **LICENSE** (Apache-2.0) carrying the upstream copyright line + own line for original code.
2. Add root **NOTICE** (or extend NOTICE-LOCAL into it): upstream attribution, embedded-runtime attribution, modified-upstream-file list (MainActivity.kt, res resources, adaptive icons, patch file).
3. Fix the two inaccuracies in NOTICE-LOCAL (MainActivity scope; icon derivation).
4. Produce the third-party inventory (THIRD-PARTY-LICENSE-INVENTORY.md draft exists as a doc; the *shippable* artifact — e.g. a notices file in the APK or docs — needs the same content).
5. Decide GPL/LGPL handling for proot/libtalloc (ship texts + source offer, or drop proot mode entirely — bionic mode is now primary, making removal plausible; requires owner approval).
6. Add Apache-2.0 boilerplate headers (or a single repo-level copyright statement) on original Kotlin sources.
7. Resolve the trademark qualifier question with the owner.

**Unresolved questions:** exact upstream release/commit identity of the 3.14.3/3.16.3 desktop installers (installers not on this machine); whether Z.AI operates a trademark policy; whether the "2026" year in the upstream copyright line is intentional (verbatim, cosmetic).
