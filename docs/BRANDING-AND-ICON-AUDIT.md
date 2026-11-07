# BRANDING AND ICON AUDIT

**Scope.** Provenance, copyright, and redistribution status of the launcher icon and app branding in `SeaNaxxx/ZCode-Android` @ `06a2120`. **No branding files were changed during this audit.**

## 1. Launcher icon — file inventory and introduction [VERIFIED]

- 15 files, all introduced in a single commit **`f1fd509`** ("docs: native-first hybrid decision + runtime inspection baseline", 2026-11-05) and never modified since:
  - `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png` + `ic_launcher_round.png` (48→192 px, PNG)
  - `drawable-{m,h,xh,xxh,xxxh}dpi/ic_launcher_foreground.png` (48→432 px, PNG)
  - `mipmap-anydpi-v26/ic_launcher.xml` + `ic_launcher_round.xml` (adaptive: `background=@color/ic_launcher_background`, `foreground`/`monochrome` = `@drawable/ic_launcher_foreground`)
  - `values/colors.xml`: `ic_launcher_background = #101213`
- APK packaging confirms the same adaptive set ships (`mipmap-*`, `drawable-*/ic_launcher_foreground.png`) — verified against the extracted release APK.

## 2. Provenance — derivative of the upstream desktop logo, NOT a byte-copy [VERIFIED facts; derivation ASSUMED-strong]

1. **Upstream counterpart differs in format:** `packages/android/app/src/main/res/` @ pin uses **webp** rasters + **vector XMLs**, and its art is the **stock Android Studio template icon** (classic robot glyph, template green `#3DDC84`) — i.e. upstream's own Android shell never shipped a Z.ai-branded launcher icon.
2. **No hash matches any upstream asset** (webp vs PNG preclude identity; no identical PNG found anywhere in the upstream tree).
3. **Pixel evidence of derivation:** local `mipmap-xxxhdpi/ic_launcher.png` (192², dark rounded-rect bg + white glyph) compared against upstream candidates — mean-abs-diff **8.15/255** vs upstream `public/logo/icons/512x512.png` (the ZCode desktop logo: white+black+#141617), vs 60.15 for `public/icon_512@2x.png`, vs 83.58 for the upstream android webp. Round variant = circular alpha mask; square = rounded-rect mask.
4. **Conclusion:** the icon is a **PNG re-render + mask of upstream's desktop logo asset with the background recolored to `#101213`**. It is a **derivative work of an Apache-2.0 upstream asset** — redistribution is permitted under Apache-2.0 *provided* §4 notices accompany it — but the conversion process is undocumented (no committed script, no commit-message provenance).
5. Byte-identical upstream asset found in the repo: `res/drawable/ic_stat_remote_control.xml` (sha256 identical to upstream) — leftover from the removed remote-control feature; referenced by no live code; cosmetic.

## 3. Copyright & licensing status

- The underlying artwork (ZCode logo, `public/logo/icons/512x512.png` @ pin) is part of the upstream repo under **Apache-2.0, © Z.AI Co., Ltd** — redistribution and modification are permitted with license + notices; **"copyright 2026 Z.AI Co., Ltd" must be preserved** for the artwork.
- The repo currently ships **no LICENSE and no attribution** naming the artwork source → Apache-2.0 §4(a)/(b) obligations unmet for this asset.
- Apache-2.0 §7 does not license **trademarks**: the "Z" logo functions as a brand mark; using it as an app icon for a third-party distribution is nominative use at best — trademark risk cannot be cleared by the license (owner decision required).

## 4. Trademark and naming [VERIFIED strings]

- `res/values/strings.xml`: `app_name` = **"ZCode"**; notification strings: "Local ZCode runtime", "ZCode local runtime is active", **"The official ZCode web interface is running on this device."**, "Stop". `values-zh-rCN`: "ZCode".
- The word **"unofficial" appears nowhere** in the repo; the word "official" appears in the notification string. `applicationId` = `io.github.zcodeandroid.app` (namespace independent of z.ai).
- **Risk:** "ZCode" + Z logo + the word "official" together imply an authority relationship the license does not grant. Recommendation (requires owner approval): qualify the description — e.g. app_name stays "ZCode" only if legal advice permits; otherwise "ZCode for Android (unofficial)" — and reword the notification to "The ZCode web interface is running on this device." Remove the claim of officialness regardless.
- Upstream comparison: upstream shell `com.example.zcode_mobile` also uses app_name "ZCode" (with 24 remote-control strings); the local repo replaced strings with the local-runtime set — shared-terminology reuse is fine; the "official" phrasing is the local repo's addition.

## 5. Visual assets beyond the icon

- **No bundled fonts** (`*.ttf`/`*.otf`/woff: zero hits) — Compose uses system fonts; upstream's OFL/Hack fonts exist only inside the embedded dist's web assets (covered by upstream THIRD-PARTY-NOTICES.md, not the shell).
- Material icons come from `material-icons-extended` (Apache-2.0, Google) — safe.
- In-app imagery: none beyond the Z logo bootstrap inside the embedded web build (upstream-covered).

## 6. Compliance verdict and required actions (no changes made)

1. **Redistribution of the icon: permitted with conditions** — Apache-2.0 license text + attribution naming the source asset (`public/logo/icons/512x512.png` @ upstream pin `3c5b2276`) + a modified-file notice (PNG re-render, masked, bg recolored `#101213`). None of these exist today.
2. **Trademark: unresolved** — needs an owner decision on "ZCode"/logo/"official" phrasing; documented as UNRESOLVED, not assumed.
3. NOTICE-LOCAL's claim "no branding assets were invented or altered" is **factually wrong in detail** (assets were converted/masked/recolored) — must be corrected when license files are added (post-approval).
4. Optional cleanliness: remove the orphaned `ic_stat_remote_control.xml` (unused upstream copy) — cosmetic, post-approval.
5. If the owner prefers zero trademark exposure: regenerate a neutral, original launcher mark (documented generation script, original artwork) — the current derivative would then be retired, not shipped.
