# FB Live Group Poster (Android)

An open-source Android proof of concept that shares a Facebook Live link to a user-approved list of Facebook groups through the installed Facebook app.

> **Important:** Meta removed the Facebook Groups API and `publish_to_groups` on April 22, 2024. This project therefore uses Android Accessibility to assist with the visible Facebook UI. It is not an official Facebook integration, selectors can break when Facebook changes its app, and use may be restricted by Facebook or individual group rules.

## What works in this MVP

- Paste a live link, or share a Facebook link to the app from another phone/app.
- Save Facebook group URLs, one per line.
- Open each approved group sequentially in Facebook or Facebook Lite.
- Fill a configurable message plus the live link.
- Default safer mode leaves the final **Post** tap to the user, detects that tap, and then advances after the configured delay.
- Optional experimental mode taps **Post** and waits at least 45 seconds before the next group.
- All configuration stays on the phone; no Facebook password, cookie, or token is collected.

## Important limitation: live on the same phone

Android and Facebook may pause or stop a live stream when Facebook is sent to the background or navigated away from the live producer screen. There is no supported API that gives third-party apps the current personal live link. For reliability, use a second Android phone: share/paste the live link into this app there, while the first phone continues streaming.

## Install and test

1. Download the debug APK from the latest GitHub Actions build artifact, or open this project in Android Studio.
2. Install the APK on Android 8.0 or newer.
3. Sign in to the official Facebook or Facebook Lite app.
4. Open FB Live Group Poster and enable its Accessibility service.
5. Paste the live URL and group URLs.
6. Start with automatic posting **off** and test one group you control.
7. If the Facebook UI is recognized correctly, test the optional automatic mode cautiously.

## Build

This project uses JDK 17, Android SDK 35, Android Gradle Plugin 8.7.3, and Gradle 8.9.

```bash
gradle assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Safety and acceptable use

- Post only to groups where you are a member and the rules allow your content.
- Do not use this for unsolicited bulk advertising, deceptive content, or rate-limit evasion.
- Start with a single test group. Facebook UI labels vary by language and app version.
- Automatic posting is intentionally opt-in and uses a minimum delay.
- This project does not bypass login, CAPTCHA, 2FA, checkpoints, or Facebook restrictions.

## Roadmap

- User-configurable UI labels for non-English Facebook installations.
- A persistent campaign notification with pause/stop controls.
- Per-group results and manual “continue” control.
- Device-tested selectors for Facebook Lite.
- Signed GitHub release builds after real-device validation.

## License

MIT. This project is independent and is not affiliated with or endorsed by Meta or Facebook.
