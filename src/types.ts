// Mirrors android/.../PointJson.kt. Times are epoch ms, offsets seconds.

export type HealthConnectDeviceType =
  | 'unknown'
  | 'watch'
  | 'phone'
  | 'scale'
  | 'ring'
  | 'headMounted'
  | 'fitnessBand'
  | 'chestStrap'
  | 'smartDisplay';

export type RecordingMethod =
  'unknown' | 'activelyRecorded' | 'automaticallyRecorded' | 'manualEntry';

export interface HealthConnectPointBase {
  uid: string;
  start_time: number;
  end_time?: number;
  zone_offset_s?: number;
  end_zone_offset_s?: number;
  update_time: number;
  /** Writer app package, e.g. `com.garmin.android.apps.connectmobile`. */
  app_id: string;
  client_record_id?: string;
  recording_method: RecordingMethod;
  device?: {
    type: HealthConnectDeviceType;
    manufacturer?: string;
    model?: string;
  };
}

export interface RoutePoint {
  t: number;
  lat: number;
  lng: number;
  /** m */
  alt?: number;
  horizontal_accuracy?: number;
  vertical_accuracy?: number;
}

/** Series samples: `t` epoch ms, `v` in the series' unit. */
export interface Sample {
  t: number;
  v: number;
}

export interface ExercisePoint extends HealthConnectPointBase {
  type: 'exercise';
  end_time: number;
  /** `ExerciseSessionRecord.EXERCISE_TYPE_*` suffix, e.g. `RUNNING`, `SWIMMING_POOL`. */
  exercise_type: string;
  exercise_type_id: number;
  title?: string;
  notes?: string;
  /** `consentRequired`: another app's route, see `requestExerciseRoute`. */
  route_status: 'data' | 'noData' | 'consentRequired';
  route?: RoutePoint[];
  laps?: { start_time: number; end_time: number; length?: number }[];
  segments?: {
    start_time: number;
    end_time: number;
    /** `ExerciseSegment.EXERCISE_SEGMENT_TYPE_*` id. */
    segment_type: number;
    repetitions: number;
  }[];
  /** Totals from the writer app's records over the session (absent without the permission). */
  distance?: number;
  elevation_gained?: number;
  active_calories?: number;
  total_calories?: number;
  steps?: number;
  /** bpm */
  heart_rate?: Sample[];
  /** m/s */
  speed?: Sample[];
  /** W */
  power?: Sample[];
  /** rpm */
  cycling_cadence?: Sample[];
  /** steps/min */
  steps_cadence?: Sample[];
}

export type SleepStage =
  | 'UNKNOWN'
  | 'AWAKE'
  | 'SLEEPING'
  | 'OUT_OF_BED'
  | 'LIGHT'
  | 'DEEP'
  | 'REM'
  | 'AWAKE_IN_BED';

export interface SleepPoint extends HealthConnectPointBase {
  type: 'sleep';
  end_time: number;
  title?: string;
  notes?: string;
  stages: { start_time: number; end_time: number; stage: SleepStage }[];
}

export interface WeightPoint extends HealthConnectPointBase {
  type: 'weight';
  /** kg */
  weight: number;
}

export interface BodyFatPoint extends HealthConnectPointBase {
  type: 'bodyFat';
  /** % */
  body_fat: number;
}

export interface HeightPoint extends HealthConnectPointBase {
  type: 'height';
  /** m */
  height: number;
}

export interface RestingHeartRatePoint extends HealthConnectPointBase {
  type: 'restingHeartRate';
  heart_rate: number;
}

export interface HeartRateVariabilityPoint extends HealthConnectPointBase {
  type: 'heartRateVariability';
  rmssd_ms: number;
}

export interface Vo2MaxPoint extends HealthConnectPointBase {
  type: 'vo2Max';
  /** mL/(min·kg) */
  vo2max: number;
  /** `Vo2MaxRecord.MEASUREMENT_METHOD_*` id. */
  measurement_method: number;
}

export interface HealthConnectPointMap {
  exercise: ExercisePoint;
  sleep: SleepPoint;
  weight: WeightPoint;
  bodyFat: BodyFatPoint;
  height: HeightPoint;
  restingHeartRate: RestingHeartRatePoint;
  heartRateVariability: HeartRateVariabilityPoint;
  vo2Max: Vo2MaxPoint;
}

export type HealthConnectPoint =
  HealthConnectPointMap[keyof HealthConnectPointMap];
