# ZCode Android

An Android application that runs the original ZCode coding agent entirely on
the device: the agent, its web UI, and a Node.js 24 runtime are embedded in
the APK and served locally on `127.0.0.1:3030`.

## How it works

- The official ZCode CLI distribution is built from pinned upstream source
  (zai-org/ZCode) and embedded at build time together with a
  Bionic-compatible Node.js runtime.
- On first launch the app provisions the runtime offline from bundled assets,
  then starts the agent's web mode through a foreground service that owns the
  runtime lifecycle.
- The web UI is displayed in a WebView; agent tasks run against a local
  workspace in app-private storage.

## Verification requirements

A successful build is not proof that the agent works on a device. The
project tracks behavior in two separate classes:

- Emulator (x86_64 AVD): offline provisioning, local server startup, agent
  loop verified by on-disk effects, restart persistence. Proves app logic
  only.
- Device (arm64): first-run offline provisioning, server reachability, real
  agent tasks, real provider calls, UI rendering and touch. Device rows stay
  NOT TESTED until executed on hardware.

See [docs/ENGINEERING-NOTES.md](docs/ENGINEERING-NOTES.md) for the
architecture reference, the verification model, and known failure classes.

## Building

CI is manual (`workflow_dispatch`) to keep resource usage intentional:

- `android-verify` — full pipeline: distribution build, APK per ABI,
  arm64 release artifact, emulator verification.
- `linux-arm64-build` — linux/arm64 distribution tree for Termux checks.

## Branching

- `main` — stable, receives changes through reviewed PRs.
- `dev` — active development; pull requests target `main`.

## Upstream

Original project: https://github.com/zai-org/ZCode (Apache-2.0). This
repository is independent and is not a fork; upstream source is used at a
pinned commit to build the embedded distribution.
