# Device Validation — P6 checklist (owner device run)

**Status:** the Phase 6 deliverable of
[ANDROID-UI-ARCHITECTURE-REVISED.md](../ANDROID-UI-ARCHITECTURE-REVISED.md).
Per §5 Phase 6, CI (x86_64 AVD) is supplementary — the acceptance device
is the owner's **Vivo V2035, Android 13** (matches CI API 33 on purpose;
see android-verify.yml header for the API-masking lesson).

Each row: what to do on the phone, what evidence to capture, and what is
already proven in CI (so the device run only needs to confirm, not
re-derive). Capture with the diagnostics overlay (⋮ → Diagnostics →
diag report) + `adb logcat` for the tagged lines.

## Checklist

| # | Item | Device evidence | CI status |
| --- | --- | --- | --- |
| 1 | Cold startup, silent provisioning | no setup button; `provision-complete.json` marker | CI-verified (setup-tap) |
| 2 | Runtime readiness | server answers 200 on loopback; `[register]` VERIFIED line | CI-verified (start-tap) |
| 3 | Native surface renders | logcat `NativeConversation surface visible dark=…` | CI-verified (frontend-smoke, dark-mode A/B) |
| 4 | Conversation creation | first prompt creates a task (`createTask -> taskId=…` in diag log) | CI-verified (native-protocol, mock model) |
| 5 | Streaming responses | assistant text appears incrementally (agent_message_chunk rows) | CI-verified with mock; **device verdict pending** (real provider) |
| 6 | Agent activity | tool rows: running→completed; titles legible | CI-verified (agent-effects effects on disk) |
| 7 | Permission requests | approval card appears, option button resolves it, turn continues | CI-verified path; **device verdict pending** |
| 8 | Stop generation | stop button halts the turn without corrupting the timeline | pending device |
| 9 | Task history | ⋮ → Task history lists prior tasks; tapping restores snapshot | pending device |
| 10 | Workspace browsing | ⋮ → Workspace lists files; text preview works; git card correct | CI build-verified; **device verdict pending** |
| 11 | Terminal availability | record `PTY-PROBE-VERDICT` line (pty-probe stage / diagnostics) | evidence stage in CI; **device verdict pending** |
| 12 | Force-stop + relaunch | task list restored from runtime; no re-provision | CI-verified (persistence) |
| 13 | Background/foreground | return to app keeps connection; status dot recovers | pending device |
| 14 | Keyboard behavior | composer stays above IME; send reachable | pending device |
| 15 | Rotation | timeline + input survive configuration change | pending device |
| 16 | Memory pressure | runtime survives backgrounding under pressure; recovery path works | pending device |
| 17 | Dark/Light + font scaling | both schemes readable at largest font scale | CI A/B partial; font scale pending device |
| 18 | Data export/restore | backup/restore overlay round-trips; credentials excluded | overlay CI-verified only |
| 19 | Real provider turn | one real (non-mock) authenticated turn on the phone | **owner-only** (billing/auth not exercised in CI by design) |
| 20 | Accessibility | talk-over of the main screen: labels present | pending device |

## How to record

1. Run through the table on the acceptance device; for each row mark
   PASS/FAIL + one evidence line (logcat tag or screenshot reference).
2. Attach the diag report JSON from the diagnostics overlay.
3. Failures land back in this repo as issues with the logcat excerpt —
   no silent workarounds (§12).

## Probes on the phone (no PC needed)

- Diagnostics overlay → diag report (processes, storage, server probes,
  persisted logs — diag-collector.cjs).
- The PTY verdict is printed by the runtime's pty probe; capture the
  `PTY-PROBE-VERDICT` line from the persisted runtime log.
