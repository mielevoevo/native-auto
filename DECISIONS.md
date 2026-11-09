# Decisions made without asking (unattended phases)

During the unattended run (8d → 11c), choices that would normally go to the user are taken here with
the recommended option. Review them before the phone test; any of them can be changed.

Format: **sub-phase · decision** — why, and how to change it.

## Setup (2026-11-09)

- **8d–11c · One phone test at the end** — the user's call, replacing the roadmap's per-phase tests.
- **10a · TomTom key is a placeholder** — the user adds the real key to `local.properties` at the next
  supervised session. Until then search falls back to Photon.
- **10b · HERE skipped** — the roadmap already deferred it until the user's card issue is solved.
- **9a · Auto-pause 5 min / auto-end 30 min** — the proposed values in Rider-Feedback.md ("to confirm");
  editable in settings.

## 8d · Smoothness

- **Release build signed with the debug key** — no release keystore exists, and a sideloaded personal
  app doesn't need one. Same signature means a release APK installs over the debug one (emulator and
  phone). Change: add a real keystore in `app/build.gradle.kts` before ever publishing.
- **The phone test should use the release APK** (`app/build/outputs/apk/release/app-release.apk`) —
  the debug build has no R8 and no baseline profile, the first suspect in §12.1. Debug-only tools
  (Fake GPS, Simulate navigation) aren't in it; install the debug APK for those.
- **Map during a move: laid out at the full window, centred and clipped** (rather than a snapshot
  freeze) — no frozen picture, the map keeps drawing; only two resizes per move.
- **Refresh rate: highest mode while panes move, system default 0.5 s after** (rather than 120 Hz
  always) — saves power on 1 Hz data. Done through `preferredDisplayModeId`, which works on every API
  level the phone has.
- **Thermal: logged only** (`adb logcat -s NativeAuto/Thermal`), no on-screen warning.
- **Benchmarks run on the emulator only** (workspace rule); numbers are relative. Real 120 Hz frame
  times need the phone, at the end-of-run test.
- **Emulator benchmark results (2026-11-09), relative only:** every move sits at SwiftShader's floor
  (CPU frame time P50 44–59 ms at 60 Hz), so the emulator can't show the 120 Hz gains. Settings open/close:
  P50 53.5 ms without the profile → 48.7 ms with it (about −9%). Startup timing and the profiled
  maximize/swap runs didn't complete there: the pre-9a trip service kept the process alive across "cold"
  starts, and one run lost a button under emulator load. Not chased further. The real check is the phone
  (release build, `adb shell dumpsys gfxinfo com.nativeauto` after maximizing / opening settings).

