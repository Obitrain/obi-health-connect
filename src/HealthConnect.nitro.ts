import type { HybridObject } from 'react-native-nitro-modules';

/** Read permissions this wrapper can request; `backgroundRead` and `history` are the feature permissions. */
export type HealthConnectPermission =
  | 'exercise'
  | 'heartRate'
  | 'speed'
  | 'power'
  | 'cyclingPedalingCadence'
  | 'stepsCadence'
  | 'distance'
  | 'elevationGained'
  | 'activeCalories'
  | 'totalCalories'
  | 'steps'
  | 'floorsClimbed'
  | 'sleep'
  | 'weight'
  | 'bodyFat'
  | 'height'
  | 'restingHeartRate'
  | 'heartRateVariability'
  | 'vo2Max'
  | 'backgroundRead'
  | 'history';

/** Record types returned as points (`readRecords` / `readChanges`). */
export type HealthConnectPointType =
  | 'exercise'
  | 'sleep'
  | 'weight'
  | 'bodyFat'
  | 'height'
  | 'restingHeartRate'
  | 'heartRateVariability'
  | 'vo2Max';

export type HealthConnectDailyMetric =
  | 'steps'
  | 'activeCalories'
  | 'totalCalories'
  | 'distance'
  | 'activeTime'
  | 'floorsClimbed';

export type HealthConnectFeature = 'backgroundRead' | 'history';

/**
 * `notInstalled` (Android 9–13) and `updateRequired` are resolvable with `resolveStatus()`, which
 * opens the Play Store. `unsupportedDevice`: below Android 9, or no provider on the device.
 */
export type HealthConnectStatus =
  'available' | 'unsupportedDevice' | 'notInstalled' | 'updateRequired';

export interface DataPage {
  /** JSON array of points (`HealthConnectPoint` in `types.ts`). */
  pointsJson: string;
  nextPageToken?: string;
}

export interface ChangesPage {
  /** JSON array of upserted points, each with its `type`. */
  upsertsJson: string;
  /** Health Connect only returns the id of a deleted record, not its type. */
  deletedUids: string[];
  nextChangesToken: string;
  hasMore: boolean;
  /** Tokens expire after 30 days: get a new one and re-read by time range. */
  changesTokenExpired: boolean;
}

export interface DailyValue {
  /** Local date, YYYY-MM-DD. */
  date: string;
  /** Canonical unit: count (steps, floors), kcal, m, s (activeTime). */
  value: number;
}

/**
 * Promises reject with an Error whose message starts with `[<code>]` (see `HealthConnectErrorCode`);
 * `index.tsx` parses it into a `HealthConnectError`.
 */
export interface HealthConnect extends HybridObject<{ android: 'kotlin' }> {
  getStatus(): Promise<HealthConnectStatus>;

  /** Opens the Play Store page of the Health Connect app (install / update). */
  resolveStatus(): Promise<void>;

  /** Opens the Health Connect screen managing this app's permissions and data. */
  openSettings(): Promise<void>;

  isFeatureAvailable(feature: HealthConnectFeature): Promise<boolean>;

  getGrantedPermissions(
    permissions: HealthConnectPermission[]
  ): Promise<HealthConnectPermission[]>;

  /**
   * Shows the Health Connect consent screen; resolves with the granted subset. Only permissions
   * declared in the app manifest can be granted.
   */
  requestPermissions(
    permissions: HealthConnectPermission[]
  ): Promise<HealthConnectPermission[]>;

  /** Records whose time is in [startMs, endMs), oldest first. */
  readRecords(
    type: HealthConnectPointType,
    startMs: number,
    endMs: number,
    pageSize: number,
    pageToken?: string
  ): Promise<DataPage>;

  /** Token for `readChanges` covering the given types, valid 30 days. */
  getChangesToken(types: HealthConnectPointType[]): Promise<string>;

  readChanges(changesToken: string): Promise<ChangesPage>;

  /**
   * Route of another app's exercise session (`route_status: 'consentRequired'`): asks the user
   * for this session's route, resolves with the JSON route or undefined when declined.
   */
  requestExerciseRoute(uid: string): Promise<string | undefined>;

  /** One value per local day in [startDate, endDate] (YYYY-MM-DD, inclusive), deduplicated by Health Connect. */
  aggregateDaily(
    metric: HealthConnectDailyMetric,
    startDate: string,
    endDate: string
  ): Promise<DailyValue[]>;
}
