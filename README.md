# Samsung Health Bridge

[![Android CI](https://github.com/c0t0ber/samsung-health-bridge/actions/workflows/android.yml/badge.svg)](https://github.com/c0t0ber/samsung-health-bridge/actions/workflows/android.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

Private, serverless Android bridge: **Samsung Health → Health Connect → one Google Sheet**.

The project is source-first: there is no universal prebuilt APK because Android Google OAuth clients are bound to an application ID and signing-certificate SHA-1. Build the app with your own OAuth client by following the setup below.

The app reads only Steps, Exercise sessions, Sleep, Weight, and Body fat. It never asks for heart-rate, medical, route, or write permissions. Every normal sync recomputes every local calendar date touched by the last 72 hours and upserts by the ISO `date`, so delayed Galaxy Watch/Samsung Health data updates existing rows instead of creating duplicates. A separate foreground-only diagnostic action can export every accessible raw record and every aggregate metric exposed for those five types.

## One-time Google setup

This is the only unavoidable external setup. No client secret, service-account JSON, API key, or spreadsheet ID is needed.

1. In [Google Cloud Console](https://console.cloud.google.com/), create/select a project and enable **Google Sheets API**.
2. Open **Google Auth Platform**. Configure Branding/Audience; while the app is in Testing, add your own Google account as a test user.
3. Run `./gradlew signingReport` and copy the SHA-1 for the build variant you will install.
4. Create an **Android OAuth client** with:
   - Package name: `com.roktober.samsunghealthbridge`
   - SHA-1: the value reported for your signing key

If you change `applicationId`, use that same package name in the OAuth client. Forks intended for public distribution should use their own application ID and release signing key.

The app requests only `https://www.googleapis.com/auth/drive.file`. After consent, it creates one spreadsheet named **Samsung Health Bridge** with a **Daily** tab, then stores only that spreadsheet ID locally. The diagnostic export adds one necessary **Raw** tab to the same spreadsheet; it never creates another spreadsheet.

## Build and install

The project uses JDK 21 and Android SDK 36:

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew testDebugUnitTest lintDebug assembleDebug
"$ANDROID_HOME/platform-tools/adb" install -r app/build/outputs/apk/debug/app-debug.apk
```

On the phone:

1. In Samsung Health, enable its Health Connect integration and allow it to write the needed data types.
2. Open **Samsung Health Bridge** and tap **Grant health permissions**.
3. If shown, tap **Allow background sync**. This permission is requested only when the device supports it.
4. Before relying on unattended sync, exempt **Samsung Health Bridge** from Android battery optimization. On Samsung, also set App info → Battery to **Unrestricted**; some One UI versions expose these as two separate controls.
5. Tap **Connect Google Sheet**, choose the intended Google account, and approve the `drive.file` request.
6. Tap **Sync now**. The app writes rows, reads the same dates back, and only then reports success.
7. Optional: tap **Import 90 days once**. History permission is requested only for this explicit initial import.
8. For troubleshooting, tap **Export all raw data + aggregates**. If available, Health Connect asks once for history access. The app then fills `Daily`, creates/updates `Raw`, and verifies every exported row by reading it back. Repeating the export updates matching keys instead of creating duplicates.

## Sheet schema

One row per local date, with exactly these columns:

```text
date, timezone, weight_kg, body_fat_percent, steps, active_minutes,
workout_count, sleep_minutes, synced_at, source_status
```

- Steps, exercise duration, and sleep duration use Health Connect aggregate APIs. The aggregate API keeps Health Connect's source-priority and deduplication behavior.
- Missing Steps, exercise duration, and sleep duration remain blank. A numeric `0` therefore means a measured zero, never "no record returned". `source_status` is `ok:health_connect`, `partial:health_connect;missing=...`, or `no_data:health_connect`.
- `timezone` is the dominant record `ZoneOffset` for that experienced local date in both rolling sync and complete historical export. `source_status` adds `timezone_record_offset`, `timezone_mixed_offsets`, or `timezone_fallback`; only a day with no usable record offset falls back to the phone's current IANA zone. This prevents stale device timezone settings or travel from relabelling health history.
- `active_minutes` is exercise-session duration, because the MVP deliberately does not request activity-intensity permission.
- The latest Weight and Body fat measurement in each experienced local day is used; missing values stay blank and are listed in `source_status`.
- Steps, exercise duration, and sleep duration are aggregated without a package filter. This lets Health Connect apply its Activity/Sleep source priority and deduplication and includes Android 14+ on-device steps, whose source can be system-generated rather than Samsung's package. Supporting Exercise, Weight, and Body fat reads also accept all Health Connect origins.

The optional diagnostic `Raw` tab has these columns:

```text
record_key, type, start_time, end_time, timezone_offset, value, unit,
subtype, data_origin, client_record_id, last_modified_time, details_json,
exported_at
```

- Raw Steps, Sleep sessions, Exercise sessions, Weight, and Body fat are read from every Health Connect data origin, without a Samsung-only filter.
- `details_json` preserves the remaining metadata, both interval offsets, sleep stages, and exercise title/notes/segments/laps/planned-session ID. Exercise routes are deliberately never read.
- Six `daily_aggregate` rows are emitted per exported local date: steps total, exercise duration total, sleep duration total, and Weight average/minimum/maximum. Body fat has no aggregate metric in Health Connect 1.1.0.
- Raw upsert uses `record_key = type + Health Connect record ID`; aggregate keys use date plus metric. Duplicate keys are rejected and every write is checked by exact readback.
- `exported_at` is the snapshot marker. A record deleted later from Health Connect is not destructively removed from the user-owned sheet; when comparing repeat exports, filter `Raw` to the newest successful `exported_at` value so older stale rows are excluded.
- When history permission exists, the export covers the complete accessible history. On providers without that feature it is explicitly limited to the platform-accessible 30-day window.

## Background task

`daily-health-sheet-sync-v2` is registered as unique periodic WorkManager work with a 24-hour interval and a 2-hour flex window. It intentionally has no JobScheduler network constraint: on Samsung, a background UID can be denied connectivity until its job starts, so requiring connectivity before launch creates a deadlock. Transient Google/Sheets network failures are retried inside the worker instead. After the one-time Health Connect and Google grants, normal operation is unattended. Each run writes the dates touched by the last 72 hours and reads one additional preceding context day so overnight sleep and other interval records are not clipped at the first boundary. The extra context day is never written unless it is itself inside the rolling window.

On Samsung, also exempt **Samsung Health Bridge** from battery optimization. `Unrestricted` background usage alone may still leave JobScheduler with `readyNotRestrictedInBg=false`; the app must appear in the device-idle whitelist for the OEM freezer to release it for scheduled jobs.

The foreground buttons are diagnostics and recovery controls only: use them after reinstalling permissions, resolving Google consent, or deliberately rebuilding all accessible history. They are not part of the daily collection workflow.

To inspect it, open Android Studio → **App Inspection** → **Background Task Inspector**, select `com.roktober.samsunghealthbridge`, and look for `daily-health-sheet-sync-v2`. If Google returns an interactive consent resolution or Health permission was revoked, the worker records a safe status and waits for a foreground tap instead of opening UI in the background.

## Permission recovery

- Health permission revoked: open the app and tap **Grant health permissions** again. Background and 90-day history permissions remain separate.
- Google access revoked: tap **Sync now** or **Check Google access** and complete Google consent again.
- Spreadsheet moved or renamed: no action is required; its ID is stable.
- Spreadsheet deleted: restore **Samsung Health Bridge** from Google Drive trash so the app keeps using the same single file.

## Privacy

Health values remain in Health Connect and the user's Google Sheet. The app does not run a server, does not persist access/refresh tokens, does not persist daily rows or preview values, and does not log health values. Local preferences contain only the spreadsheet ID, sync timestamps, import completion, and operational status codes.

## License

MIT. See [LICENSE](LICENSE).

## Verification commands

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
"$ANDROID_HOME/platform-tools/adb" shell dumpsys package com.roktober.samsunghealthbridge
```

Official references: [Health Connect setup](https://developer.android.com/health-and-fitness/health-connect/get-started), [read and aggregate data](https://developer.android.com/health-and-fitness/health-connect/read-data), [feature availability](https://developer.android.com/health-and-fitness/health-connect/features/availability), [Android Google authorization](https://developer.android.com/identity/authorization), [Sheets scopes](https://developers.google.com/workspace/sheets/api/scopes), and [Sheets REST API](https://developers.google.com/workspace/sheets/api/reference/rest).
