# Web TV (Android TV / Google TV)

Asks for a website address on first launch, then opens that site fullscreen like an app every time.

## Build the APK
- Automatic: every push to `main` builds the APK on GitHub. Open the repo's **Actions** tab, pick the latest
  "Build APK" run and download the APK under **Artifacts** (it comes as a .zip containing the .apk).
  You can also start a build by hand with **Run workflow**.
- Android Studio: open this folder, let Gradle sync, then Build > Build Bundle(s) / APK(s) > Build APK(s).

All builds are signed with `app/webtv-release.jks`, so a new APK installs over the old one as an update
(and keeps your logins). Keep this repository private.

## Install on the TV
- Enable Developer options + network debugging on the TV, then:
  adb connect <TV-IP>:5555
  adb install -r WebTV-<number>.apk
- Or copy the APK to a USB stick / Send Files to TV and open it with a file manager.

## Remote controls
- Arrow keys: jump to the nearest link/button in that direction (blue focus ring); scrolls when nothing is left
- OK: select. On a text field it opens a large typing box (Search / Submit / Done)
- Back: previous page; on the first page it opens the menu
- Menu / Info / Settings / Yellow: open the menu. Red: reload
- Play/Pause, Fast-forward, Rewind: control the video; Channel +/- or Page Up/Down: scroll
- Fullscreen video: OK = play/pause, Left/Right = seek 10 s
- Blue: Shields (ad blocker) options
- Menu > Navigation: Smart focus (default) / Cursor / Native

## Shields (built-in ad blocker)
Works like Brave Shields and uses the same default filter lists Brave ships:
EasyList, EasyPrivacy, uBlock Origin (filters, badware, privacy, unbreak, quick fixes),
Peter Lowe's list, Brave's own lists, plus uBlock's cookie-notice list.

- Blocks ad and tracker requests (network rules, including `$third-party`, `$domain=`, `$important`, `$redirect` and the rest of the usual options)
- Hides leftover ad boxes (cosmetic rules, `:has-text()`, `:upward()` and other uBlock procedural rules)
- Runs uBlock-style scriptlets (`##+js(...)`) that defeat anti-adblock walls and strip YouTube video ads, with an auto-skip safety net for YouTube
- Blocks pop-up ads, warns before opening known scam/malware sites, strips click-tracking parameters (fbclid, gclid, utm_...) from links
- Menu > Shields (or the Blue key): blocked counts, turn Shields down for one site, turn blocking off everywhere, hide cookie pop-ups on/off, update lists now
- Lists are bundled in `app/src/main/assets/filters` and refresh themselves from the internet every 3 days

Code: `app/src/main/java/com/example/webtv/adblock/` (engine + Android glue) and `app/src/main/assets/adblock.js` (page side).

## Staying signed in
- You stay signed in to websites until you sign out on the site. Cookies (including session cookies) are written to disk whenever the app is paused or a page finishes loading, so the TV killing the app in the background no longer logs you out.
- "Remember me / Keep me signed in" boxes on login forms are ticked automatically, so sites hand out long-lived logins.
- When you sign in, the app offers to save the password (stored encrypted with the Android Keystore) and fills it in next time. Menu > Saved passwords to view or delete them.
- Sign-in pop-ups (e.g. "Sign in with Google") open as a proper window; press Back to close it.
- Menu > Sign out of all websites clears every login at once.
