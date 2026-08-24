# Agent setup and installation guide

This file is the operational runbook for coding agents installing **Samsung Health Bridge** for a user. Read `README.md` and this file before changing source, building, installing, or troubleshooting the app.

## Mission

Set up this source-first Android app so that the user's own device performs:

```text
Samsung Health → Health Connect → one user-owned Google Sheet
```

A successful setup has four separate proofs:

1. the source passes unit tests, Android lint, and assembly;
2. the intended APK is installed on the intended Android device;
3. a foreground sync writes and reads back real `Daily` rows;
4. a later natural WorkManager run updates the sheet without opening the app or tapping a button.

Do not collapse these into one claim. A successful build does not prove installation, a successful install does not prove sync, and a manual sync does not prove unattended operation.

## Safety boundaries

- Never commit or print OAuth credentials, API keys, tokens, cookies, spreadsheet IDs, personal email addresses, health rows, screenshots containing health data, signing keys, APKs, AABs, or local diagnostic dumps.
- Never add `local.properties`, `google-services.json`, `secrets.properties`, keystores, generated `build/` files, `outputs/`, `work/`, or local agent state to Git.
- This project needs no client secret, service-account JSON, API key, or embedded spreadsheet ID. Do not invent any of them.
- Never change the OAuth scope beyond `https://www.googleapis.com/auth/drive.file` without an explicit security review.
- Never add Health Connect write, heart-rate, route, location, or medical-record permissions as a setup workaround.
- Do not use `adb shell pm clear` or uninstall the app without explicit user approval. Clearing app data removes the stored spreadsheet ID; a later connection can create another spreadsheet.
- Do not force-grant Health Connect or Google consent through shell hacks. These are user-controlled permission surfaces. Ask the user to review and approve them on the phone.
- Do not read, export, or retain more health data than the user requested. The complete raw export is a troubleshooting action, not part of normal installation.
- Do not publish a prebuilt APK from a local debug build. Android OAuth binds the package name to the signing-certificate SHA-1, so users must build with an OAuth client matching their own signing key.

## Prerequisites

Confirm all of the following before building:

- JDK 21 is available. The project compiles Kotlin/Java to JVM 17 bytecode but the Gradle build runs on JDK 21.
- Android SDK 36 is installed.
- `ANDROID_HOME` or `ANDROID_SDK_ROOT` points to the Android SDK.
- `adb` exists under that SDK's `platform-tools` directory.
- A physical Android device with Health Connect support is connected and authorized for USB debugging.
- Samsung Health is installed, has already collected or imported data, and is configured to write the required types to Health Connect.
- The user can access Google Cloud Console and the Google account that will own the sheet.

On macOS or Linux, define one SDK/ADB path for the current shell and check the environment without changing it:

```bash
SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK_ROOT" ]; then echo "Set ANDROID_HOME or ANDROID_SDK_ROOT" >&2; exit 1; fi
ADB="$SDK_ROOT/platform-tools/adb"
java -version
./gradlew --version
./gradlew signingReport
"$ADB" devices -l
```

On Windows, use PowerShell, not cmd.exe syntax:

```powershell
$SdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { throw "Set ANDROID_HOME or ANDROID_SDK_ROOT" }
$Adb = Join-Path $SdkRoot "platform-tools\adb.exe"
java -version
& .\gradlew.bat --version
& .\gradlew.bat signingReport
& $Adb devices -l
```

Stop if the device list is empty or shows `unauthorized`. Ask the user to identify and confirm the intended serial and model even when only one device is connected. Never infer that the only connected device is the intended target.

After confirmation, bind every ADB command to that serial. On macOS/Linux:

```bash
DEVICE_SERIAL='<user-confirmed-serial>'
"$ADB" -s "$DEVICE_SERIAL" get-serialno
"$ADB" -s "$DEVICE_SERIAL" shell getprop ro.product.manufacturer
"$ADB" -s "$DEVICE_SERIAL" shell getprop ro.product.model
```

On PowerShell:

```powershell
$DeviceSerial = '<user-confirmed-serial>'
& $Adb -s $DeviceSerial get-serialno
& $Adb -s $DeviceSerial shell getprop ro.product.manufacturer
& $Adb -s $DeviceSerial shell getprop ro.product.model
```

Require the serial readback and manufacturer/model to match the user's confirmation. Keep `DEVICE_SERIAL`/`$DeviceSerial` and `ADB`/`$Adb` defined for every later command block.

## One-time Google configuration

Google authorization is based on the Android package and signing certificate. No Google configuration file is embedded in the app.

1. Run `./gradlew signingReport` on macOS/Linux or `.\gradlew.bat signingReport` in PowerShell.
2. Identify the SHA-1 for the exact variant that will be installed. For the normal local flow this is `debug`.
3. In Google Cloud Console, create or select a project.
4. Enable **Google Sheets API**.
5. Configure **Google Auth Platform** branding and audience.
6. If the OAuth app is in Testing, add the user's Google account as a test user.
7. Create an **Android OAuth client** with:
   - package name: `com.roktober.samsunghealthbridge`;
   - SHA-1: the value from the installed build's signing report.
8. If `applicationId` was deliberately changed in a fork, use that exact new package name in both the build and OAuth client.

Human checkpoint: the user may need to sign in, choose a Cloud project, accept Google terms, or add a test user. Do not request or type their password, recovery code, API key, or payment information.

A debug keystore differs between development machines. If a build moves to another machine, re-run `signingReport` and add an OAuth client for the new SHA-1 or deliberately reuse the original signing key. Do not diagnose an OAuth mismatch by adding secrets to the repository.

## Build gate

From the repository root, run the full local gate:

```bash
./gradlew clean testDebugUnitTest lintDebug assembleDebug
```

PowerShell equivalent:

```powershell
& .\gradlew.bat clean testDebugUnitTest lintDebug assembleDebug
```

Expected artifact:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Do not install if any test, lint task, checksum verification, or assembly task fails. Fix the root cause and repeat the complete gate.

Before installation, verify repository hygiene:

```bash
git status --short
git diff --check
```

Only intentional source/documentation changes may be present. The APK and all generated build files must remain ignored.

## Install without destroying app state

Confirm the package currently installed, if any:

```bash
"$ADB" -s "$DEVICE_SERIAL" shell dumpsys package com.roktober.samsunghealthbridge
```

PowerShell equivalent:

```powershell
& $Adb -s $DeviceSerial shell dumpsys package com.roktober.samsunghealthbridge
```

Install or upgrade in place:

```bash
"$ADB" -s "$DEVICE_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
"$ADB" -s "$DEVICE_SERIAL" shell am start -n com.roktober.samsunghealthbridge/.MainActivity
```

PowerShell equivalent:

```powershell
& $Adb -s $DeviceSerial install -r app/build/outputs/apk/debug/app-debug.apk
& $Adb -s $DeviceSerial shell am start -n com.roktober.samsunghealthbridge/.MainActivity
```

If `adb install -r` reports a signature mismatch, stop. Preferred recovery is to build with the original signing key. Uninstalling would delete the app's stored spreadsheet ID and other operational state; do it only after explaining this consequence and receiving explicit approval.

## Phone setup

These steps require the user's review on the phone. An agent may navigate to the relevant screen when asked, but must not silently accept permission or account-consent dialogs.

1. Open Samsung Health settings and enable its Health Connect integration.
2. In Health Connect, allow Samsung Health to write the data types the user wants bridged.
3. Open **Samsung Health Bridge**.
4. Tap **Grant health permissions** and approve only:
   - Steps;
   - Exercise sessions;
   - Sleep;
   - Weight;
   - Body fat.
5. If available, tap **Allow background sync** and approve background health reads.
6. Tap **Connect Google Sheet**.
7. Choose the intended Google account and approve the `drive.file` request. This scope covers Drive files created by the app or explicitly opened with it; it is not intrinsically limited to one file.

This implementation creates and reuses one spreadsheet named **Samsung Health Bridge**, creates a `Daily` tab, and stores only that spreadsheet ID in private app preferences. It does not persist Google access or refresh tokens.

History access is separate and optional. Request it only when the user explicitly chooses **Import 90 days once** or **Export all raw data + aggregates**.

## Foreground sync verification

1. Tap **Sync now**.
2. Wait for the app to report success. The implementation writes rows and reads the same dates back before reporting success.
3. Open the spreadsheet using **Open Google Sheet**.
4. Confirm the `Daily` tab has the exact header:

```text
date, timezone, weight_kg, body_fat_percent, steps, active_minutes, workout_count, sleep_minutes, synced_at, source_status
```

5. Confirm at least one expected date exists and that its `synced_at` belongs to this run.
6. Confirm missing metrics are blank rather than fabricated zeroes.
7. Confirm repeated manual sync updates the matching ISO `date` instead of creating a duplicate date row.

If the agent has an already-authorized Google Sheets tool, it may perform a read-only readback after the user identifies the created spreadsheet. Do not expose the spreadsheet ID or health values in logs, commits, issues, screenshots, or public chat. If no authorized readback tool exists, ask the user to confirm the row in Google Sheets; do not pretend it was verified.

## Background registration verification

The app registers unique periodic WorkManager work named:

```text
daily-health-sheet-sync
```

Its contract is:

- interval: 24 hours;
- flex window: 2 hours;
- constraint: connected network;
- no charging requirement.

Verify the unique-work name, interval, flex window, and constraints using Android Studio **App Inspection → Background Task Inspector** for package `com.roktober.samsunghealthbridge`. A secondary shell inspection can prove only scheduler integration for the selected package:

```bash
"$ADB" -s "$DEVICE_SERIAL" shell dumpsys jobscheduler
"$ADB" -s "$DEVICE_SERIAL" shell dumpsys package com.roktober.samsunghealthbridge
```

PowerShell equivalent:

```powershell
& $Adb -s $DeviceSerial shell dumpsys jobscheduler
& $Adb -s $DeviceSerial shell dumpsys package com.roktober.samsunghealthbridge
```

Inspect the shell output for this package and WorkManager's `SystemJobService`. Do not infer the unique-work name, interval, or flex window from `dumpsys`; use Background Task Inspector for those fields. Registration proves that Android accepted the periodic job, not that the job completed a Google Sheet sync.

## Unattended end-to-end proof

Do not trigger the worker manually for this acceptance test.

1. Record the latest verified `synced_at` after the foreground sync.
2. Leave the app UI unopened and keep network access available. Swiping the UI away is acceptable, but do not use force-stop, `adb shell am force-stop`, disable the package, clear app data, or trigger the worker manually.
3. Allow Android to schedule the natural periodic run; exact wall-clock time is not guaranteed.
4. Later, read the `Daily` tab again.
5. Require a row with a newer `synced_at` produced without opening the app or pressing a button.
6. Record only a privacy-safe verdict and timestamp range, not the health values or spreadsheet ID.

Only after step 5 may an agent claim that unattended operation is proven. Before that, the precise status is: **foreground sync verified and periodic work registered; natural background completion not yet proven**.

## Optional history and diagnostics

Use **Import 90 days once** only when the user wants the initial history import. Use **Export all raw data + aggregates** only for deliberate troubleshooting or a user-requested complete export.

The diagnostic export can create or update a `Raw` tab containing detailed health records. Treat it as sensitive. Never use it as a routine installation test, never publish it, and never attach it to a GitHub issue.

## Troubleshooting decision tree

### Google consent is canceled or returns no result

- Confirm Google Sheets API is enabled.
- Confirm the account is an OAuth test user when the app is in Testing.
- Re-run `signingReport` and compare the installed variant's SHA-1 with the Android OAuth client.
- Confirm the OAuth package name exactly matches `applicationId`.
- If the build came from another machine, expect a different debug SHA-1.
- Do not add a client secret, API key, `google-services.json`, or service-account file.

### Health Connect is unavailable

- Confirm the Android version supports Health Connect.
- Install or update the Health Connect provider when the OS requires the separate provider app.
- On newer Android versions, open the system Health Connect settings.
- Confirm Samsung Health integration is enabled before debugging the bridge.

### Sync succeeds but fields are blank

- Inspect Health Connect permissions for the specific data type.
- Confirm Samsung Health wrote that type to Health Connect.
- Confirm records exist for the expected date and timezone.
- Blank means no accessible measurement; do not replace it with zero.

### Background sync needs user action

- Open the app in the foreground.
- Restore revoked Health Connect permissions.
- Tap **Check Google access**, **Connect Google Sheet**, or **Sync now** to complete interactive Google resolution.
- Re-check WorkManager registration after recovery.

### Spreadsheet was renamed or moved

No action is required; the stored file ID remains stable.

### Spreadsheet was deleted

Restore **Samsung Health Bridge** from Google Drive trash. Do not clear app data to create a replacement unless the user explicitly wants a new sheet.

## Source-change verification

For any code change, run:

```bash
./gradlew clean testDebugUnitTest lintDebug assembleDebug
git diff --check
```

For permission, OAuth, storage, Sheets, backup, or scheduler changes, also review:

- `app/src/main/AndroidManifest.xml`;
- `app/src/main/java/com/roktober/samsunghealthbridge/google/GoogleAuthorizationManager.kt`;
- `app/src/main/java/com/roktober/samsunghealthbridge/sheets/SheetsApi.kt`;
- `app/src/main/java/com/roktober/samsunghealthbridge/storage/AppPreferences.kt`;
- `app/src/main/java/com/roktober/samsunghealthbridge/sync/SyncScheduler.kt`;
- `app/src/main/res/xml/backup_rules.xml`;
- `app/src/main/res/xml/data_extraction_rules.xml`.

Before committing source changes, inspect the complete staged diff and do not include credentials, spreadsheet IDs, personal health exports, generated APKs, signing material, or local agent state.

## Completion report

Report each line independently as `passed`, `failed`, `blocked`, or `not yet proven`:

```text
Build/tests/lint:
APK installed on target device:
Health Connect core permissions:
Background health permission:
Google drive.file authorization:
Sheet foreground write and readback:
WorkManager registration:
Natural unattended Sheet update:
Privacy/secret scan:
```

Include exact commands and non-sensitive evidence for failures. Never report the user's email, spreadsheet ID, OAuth identifiers, signing material, or health values.
