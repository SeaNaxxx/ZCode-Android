
# ZCode Android

An independent Android application project exploring how to run the original ZCode coding agent on Android.

## Project Goal

Provide an Android application with a mobile interface, a local workspace, and access to the original ZCode agent, subject to runtime and dependency compatibility.

## Technical Approach

The project will evaluate the following options before committing to a runtime architecture:

- Native Android application and local service.
- Node.js ARM64 running directly on Android.
- Node.js ARM64 inside a Linux ARM64 environment when required.
- The original ZCode agent server protocol as a potential interface boundary.
- FEX or other compatibility layers only if simpler approaches prove insufficient.

## Validation Requirements

A successful Android build is not proof that the agent works on a device.

The following must be tested separately:

1. Runtime and dependency compatibility on ARM64.
2. Agent authentication with a supported model provider.
3. Real file reads and writes inside the workspace.
4. Execution of a real agent task.
5. Communication between the Android interface and the local agent service.
6. Process lifecycle, error handling, and recovery.

## Upstream

Original project: https://github.com/zai-org/ZCode

The upstream repository is a reference source. This repository is independent and is not a fork.

## Docs

- [docs/UPSTREAM-ANALYSIS.md](docs/UPSTREAM-ANALYSIS.md) — evidence-backed upstream source and distribution analysis (Phase 1, incl. §14 addendum: no official Linux ARM64 package exists; our CI builds it).
- [docs/RUNTIME-CHECKS.md](docs/RUNTIME-CHECKS.md) — the three device gates before any Android code, with the execution protocol (all device rows NOT TESTED until executed).
- [.github/workflows/linux-arm64-build.yml](.github/workflows/linux-arm64-build.yml) — manual CI build producing the linux/arm64 agent distribution tree and (experimental) desktop package used by the checks.
- [device-checks/](device-checks/) — Termux check script and NDJSON protocol smoke client for running the checks on the phone.

## Current Status

Initial project setup. Runtime architecture and Android device compatibility have not yet been validated.

## Development

The primary development environment is GitHub Actions, with Android device testing performed separately.

No claim of successful end-to-end Android execution is made until device evidence is available.
