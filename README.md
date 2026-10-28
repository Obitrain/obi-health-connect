# @obitrain/react-native-health-connect

React Native wrapper (Nitro module, Kotlin) around [Android Health Connect](https://developer.android.com/health-and-fitness/health-connect) (`androidx.health.connect:connect-client` 1.1.0): permissions, record reads, change-token reads, exercise route consent and daily aggregates. Android only; other platforms get a stub reporting `unsupportedDevice`.

Health Connect gathers what other apps write (Garmin Connect, Polar Flow, Fitbit, Samsung Health, Coros…). Every point carries its writer's package in `app_id`.

## Install

```sh
yarn add @obitrain/react-native-health-connect react-native-nitro-modules
```

In the app `AndroidManifest.xml`:

1. Declare the read permissions the app requests (Health Connect only grants declared ones), e.g. `<uses-permission android:name="android.permission.health.READ_EXERCISE" />`. Full list in [`example/plugins/with-health-connect.js`](example/plugins/with-health-connect.js).
2. Set the privacy policy Health Connect links to from its permission screen. The library ships the rationale activity (Android 13−) and the `VIEW_PERMISSION_USAGE` alias (Android 14+); they open this URL:
   ```xml
   <meta-data android:name="com.obitrain.healthconnect.PRIVACY_POLICY_URL" android:value="https://example.com/privacy" />
   ```
3. The client needs Android 8 (API 26). With a lower `minSdkVersion`, add (`xmlns:tools`) `<uses-sdk tools:overrideLibrary="androidx.health.connect.client" />`. The wrapper checks for Android 9+ at runtime.

Play requires a [health apps declaration](https://support.google.com/googleplay/android-developer/answer/12991134) for every requested permission.

## Usage

```ts
import { HealthConnect } from '@obitrain/react-native-health-connect';

const status = await HealthConnect.getStatus(); // 'available' | 'notInstalled' | 'updateRequired' | 'unsupportedDevice'
if (status === 'notInstalled' || status === 'updateRequired') await HealthConnect.resolveStatus();

await HealthConnect.requestPermissions(['exercise', 'heartRate', 'speed', 'distance', 'sleep']);

// first sync: by record time
const { points, nextPageToken } = await HealthConnect.readRecords('exercise', { startMs, endMs });
// later syncs: by change token (upserts + deleted ids), valid 30 days
let token = await HealthConnect.getChangesToken(['exercise', 'sleep']);
const changes = await HealthConnect.readChanges(token);
token = changes.nextChangesToken; // loop while changes.hasMore; re-read by time if changesTokenExpired

const steps = await HealthConnect.aggregateDaily('steps', '2026-10-20', '2026-10-26');
```

Point shapes are in [`src/types.ts`](src/types.ts) (epoch ms, seconds, m, m/s, kg, kcal, W). An exercise point embeds the writer app's heart rate, speed, power and cadence series and its distance / calories / elevation / steps totals over the session, for the granted permissions only. Errors are `HealthConnectError` with a `code` (see `HealthConnectErrorCode`).

## Limits

- Reads are capped at the 30 days before the first grant unless `history` is granted (`isFeatureAvailable('history')`).
- Reading while the app is in the background needs `backgroundRead` (`isFeatureAvailable('backgroundRead')`).
- Another app's exercise route needs per-session consent: the point has `route_status: 'consentRequired'`; `requestExerciseRoute(uid)` shows the consent sheet (foreground only).
- Changes are tracked per point type; a series record edited after its session is not reported.
- Health Connect rate-limits reads; a quota error rejects with code `rateLimited`.

## Example app

```sh
yarn install
yarn example prebuild --clean
yarn example android
```

On Android 9–13 install the Health Connect app from the Play Store first; emulators work with a Play Store image.
