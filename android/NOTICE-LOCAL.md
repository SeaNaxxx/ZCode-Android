# Third-party components bundled into this APK

| Component | Source | License | Use |
| --- | --- | --- | --- |
| Android app shell (`com.example.zcode_mobile` shell, remote-control client, theme, icons) | zai-org/ZCode `packages/android` @ commit `3c5b227649371507ddc4eb46ac5133afe6db1cab` | Apache-2.0 (upstream repo license) | Reused unmodified except: MainActivity mode switch, added `local/` package, added targetSdk change, added string resources |
| proot | Termux packages repo (`packages.termux.dev`, aarch64 deb, resolved at CI build time) | GPL-2.0+ | Executes the Linux ARM64 userland without root; binary shipped as `jniLibs/arm64-v8a/libproot.so` |
| libtalloc | Termux packages repo (proot runtime dependency) | LGPL-3.0+ | proot dependency; shipped as `jniLibs/arm64-v8a/libtalloc.so` |
| ZCode CLI distribution (`zcode-<v>.tar.gz`) | Built in CI from pinned zai-org/ZCode source (`pnpm build:zcode`) | Apache-2.0 | Embedded as `assets/runtime/zcode-dist.tar.gz`, sha256 verified on device before extraction |
| Ubuntu base rootfs | cdimage.ubuntu.com `ubuntu-base-24.04.3-base-arm64.tar.gz` (downloaded on device, SHA256SUMS-verified) | Ubuntu (Cannonical) — base system | Linux ARM64 userland for Node.js and the agent |
| Node.js `v24.14.0` linux-arm64 | nodejs.org (downloaded on device, SHASUMS256-verified) | MIT (Node.js), includes bundled ICU/root CAs under their licenses | Runs the official `zcode --web` server + agent |

Notes:
- No Z.ai/ZCode branding assets were invented or altered; launcher icons are the
  upstream Apache-2.0 assets from the same verified source.
- No region-bypass or authentication-circumvention code or patterns are used
  anywhere in this project.
- The embedded distribution contains no secrets; provider accounts are configured
  by the user at runtime through the official UI.
