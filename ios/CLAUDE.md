# Native Auto iOS (for Claude Code on the Mac)

Read `ios/PLAN.md` (phase plan, iOS limits, versioning) and `ios/README.md` (Mac setup). The root `CLAUDE.md` is the
Android app's history and spec; its Windows-only workspace rules (`.dev/env.sh`, emulator, drive D) don't apply on
the Mac.

- Build: `cd ios && xcodegen && xcodebuild -scheme NativeAuto -destination 'platform=iOS Simulator,name=iPhone 16' test`
  (pick an installed simulator: `xcrun simctl list devices`). Install on the phone from Xcode (⌘R).
- Never commit `NativeAuto.xcodeproj` (generated) or `Local.xcconfig` (the rider's team ID).
- **Privacy (rider, 2026-11-16):** never write the rider's bike plate initials, home base or real email into files or
  commits. Bikes in tests and examples are "Gen 1" / "Gen 2". Commits use the GitHub no-reply address.
- Git: branch `main`, one commit per finished sub-phase, push to the private GitHub repo when the rider says so.
  Licence is WIP (see `Rider-Feedback.md`): keep MIT for now, don't add another.
- `ios/NativeAuto/Core/` is pure Swift (Foundation only), ported from the Kotlin with its tests; UI and platform code
  live elsewhere. Keep the figures identical to Android (same thresholds, same filters).
