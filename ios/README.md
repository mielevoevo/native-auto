# Native Auto for iOS: Mac setup

Free Apple ID, wired install to your own iPhone. Works on any iPhone on iOS 17+ (an iPhone 11 Pro Max is fine), in any
country: this is ordinary developer install, not the EU sideloading rules.

## Once, on the Mac

1. **Xcode** from the App Store. Open it once, accept the licence, let it install the iOS platform.
2. **Homebrew** (https://brew.sh), then:
   ```
   brew install xcodegen
   ```
3. **Apple ID in Xcode:** Xcode → Settings → Accounts → + → Apple ID. A "Personal Team" appears.
4. **Get the code** (private repo, so sign in to GitHub when asked; a personal access token works as the password):
   ```
   git clone https://github.com/mielevoevo/native-auto.git
   cd native-auto/ios
   git config user.email 152770342+mielevoevo@users.noreply.github.com
   ```
5. **Team ID:** `cp Local.xcconfig.example Local.xcconfig`, then put your team ID after `DEVELOPMENT_TEAM =`.
   To find it: build once (below), pick your Personal Team under the target's Signing & Capabilities, and copy the ID
   Xcode shows (10 characters), or read it from the generated project's build settings.

## Once, on the iPhone

1. Plug it into the Mac with the cable, unlock it, tap **Trust**.
2. In Xcode open Window → Devices and Simulators and wait until the iPhone is ready (it copies debug symbols).
3. iPhone: Settings → Privacy & Security → **Developer Mode** (at the bottom; it only appears after step 2) → on →
   restart → confirm.

## Every build

```
cd native-auto/ios
git pull
xcodegen                  # rewrites NativeAuto.xcodeproj from project.yml
open NativeAuto.xcodeproj
```
Choose your iPhone at the top, press **Run** (⌘R). Tests: **⌘U** (or on the simulator).

The first launch on the phone says "Untrusted Developer": Settings → General → VPN & Device Management → your Apple
ID → Trust.

## Free Apple ID limits

- The app **expires after 7 days**; just Run again from Xcode to renew it.
- At most 3 of your own apps on the phone at once, and a handful of new app IDs per week.
- No TestFlight, no push notifications, no App Store.

## When signing fails

- "No account for team" / "Signing requires a development team": step 5 above, then `xcodegen` again.
- "Failed to register bundle identifier": the ID is taken; change `com.mielevoevo.nativeauto` in `project.yml`
  (both targets) to something unique.
- Device "not available" / "preparing": unlock the phone, keep it plugged in, wait for the Devices window.
