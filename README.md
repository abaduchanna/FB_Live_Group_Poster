# FB_Live_Group_Poster (Android)


> **3SVerse standard:** this app ships as an Android APK built to the same 3SVerse brand standard as every 3SVerse app — new logo, Segoe UI/Roboto type, cyan-magenta accent language, standardized header and footer. Developed by www.3SVerse.com.
An open-source Android proof of concept that shares a Facebook Live link to a user-approved list of Facebook groups through the installed Facebook app.

> **Important:** Meta removed the Facebook Groups API and `publish_to_groups` on April 22, 2024. This project therefore uses Android Accessibility to assist with the visible Facebook UI. It is not an official Facebook integration, selectors can break when Facebook changes its app, and use may be restricted by Facebook or individual group rules.

## What works in v0.4.0

- **Import your groups from Facebook** — one tap opens your Facebook app; the accessibility service reaches the Groups screen, scrolls it, and reads the visible group names into the app. No Groups API needed (Meta removed it in April 2024).
- **Checkbox group list** — every imported group gets a checkbox, plus **Select all**, a live "N of M selected" counter, manual URL entries, and a clear-list button.
- **Auto post to selected groups** — one button posts your live link to exactly the groups you ticked, one by one, with a safety delay.
- Works with **Facebook, Facebook Lite and Facebook clones/mods** — the chooser scans every installed Facebook-family app and **always asks you which one to use**; it never silently picks one.
- The message box starts **empty** — leave it empty to post just the live link.
- Paste a live link, or share a Facebook link to the app from another phone/app.
- Fill the message plus the live link in each group's composer.
- Default safer mode leaves the final **Post** tap to the user; optional experimental mode taps **Post** automatically and waits at least 45 seconds before the next group.
- Facebook-blue light and dark themes with an in-app switch.
- All configuration stays on the phone; no Facebook password, cookie, or token is collected.

## How group import works (and its limits)

Meta removed the Facebook Groups API, including `publish_to_groups`, in April 2024, and Android forbids reading another app's private session data. The only honest route left is the accessibility service this app already uses for posting:

1. Tap **Import groups from Facebook** and pick which Facebook app to open.
2. In that app, reach the **Groups** screen and stay there for a few seconds while it scrolls and reads visible rows.
3. Come back to this app — imported names appear as unselected checkboxes; tick what you want (or Select all).

Limits to know:

- Import reads what is on screen, so Facebook's "Suggested for you" rows may appear too — **uncheck anything you have not joined**.
- Very long lists need the screen to scroll; the importer scrolls up to 18 screens per run and you can simply run Import again.
- Labels are matched against the English Facebook UI; other app languages may need a manual URL list instead (still supported).
- Facebook can change its UI at any time; if Import stops finding groups, add URLs manually.

## Important limitation: live on the same phone

Android and Facebook may pause or stop a live stream when Facebook is sent to the background or navigated away from the live producer screen. There is no supported API that gives third-party apps the current personal live link. For reliability, use a second Android phone: share/paste the live link into this app there, while the first phone continues streaming.

## Install and test

1. Download the normal APK from the latest GitHub Release. If Android says
   **App not installed** because an older/debug build has a conflicting
   signature, use the `FreshInstall` APK; it has a separate package name and
   can install alongside the old build.
2. Install the APK on Android 8.0 or newer.
3. Sign in to the Facebook app you want to post from — official, Lite or your clone.
4. Open FB Live Group Poster and enable its Accessibility service.
5. Tap **Import groups from Facebook**, choose the app, reach the Groups screen, wait, come back.
6. Tick the groups (or Select all), paste the live URL, leave the message empty or type one.
7. Start with automatic posting **off** and test one group you control.
8. If the Facebook UI is recognized correctly, test the optional automatic mode cautiously.

## Build

This project uses JDK 17, Android SDK 35, Android Gradle Plugin 8.7.3, and Gradle 8.9.

```bash
gradle assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/FB_Live_Group_Poster-debug.apk`.

## Safety and acceptable use

- Post only to groups where you are a member and the rules allow your content.
- Do not use this for unsolicited bulk advertising, deceptive content, or rate-limit evasion.
- Start with a single test group. Facebook UI labels vary by language and app version.
- Automatic posting is intentionally opt-in and uses a minimum delay.
- Imported names are only suggestions from your own screen; verifying membership is your job.
- This project does not bypass login, CAPTCHA, 2FA, checkpoints, or Facebook restrictions.

## Roadmap

- User-configurable UI labels for non-English Facebook installations.
- A persistent campaign notification with pause/stop controls.
- Per-group results report after the campaign.
- Signed GitHub release builds after real-device validation.

## License

MIT License. Copyright (c) 2026 **3SVerse**. This license applies only to this repository; it does not automatically apply to other 3SVerse repositories.

This project is independent and is not affiliated with or endorsed by Meta or Facebook.
