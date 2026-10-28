import type {
  DailyValue,
  HealthConnectDailyMetric,
  HealthConnectFeature,
  HealthConnectPermission,
  HealthConnectPointType,
  HealthConnectStatus,
} from './HealthConnect.nitro';
import type {
  HealthConnectPoint,
  HealthConnectPointMap,
  RoutePoint,
} from './types';

export interface ReadOptions {
  startMs: number;
  endMs: number;
  /** Default 100 (exercise pages also read each session's series). */
  pageSize?: number;
  pageToken?: string;
}

export interface PointsPage<T extends HealthConnectPointType> {
  points: HealthConnectPointMap[T][];
  nextPageToken?: string;
}

export interface ChangesResult {
  upserts: HealthConnectPoint[];
  deletedUids: string[];
  nextChangesToken: string;
  hasMore: boolean;
  changesTokenExpired: boolean;
}

/** Same surface on every platform; off Android it reports `unsupportedDevice`. */
export interface HealthConnectApi {
  readonly isSupportedPlatform: boolean;
  getStatus(): Promise<HealthConnectStatus>;
  resolveStatus(): Promise<void>;
  openSettings(): Promise<void>;
  isFeatureAvailable(feature: HealthConnectFeature): Promise<boolean>;
  getGrantedPermissions(
    permissions: HealthConnectPermission[]
  ): Promise<HealthConnectPermission[]>;
  requestPermissions(
    permissions: HealthConnectPermission[]
  ): Promise<HealthConnectPermission[]>;
  readRecords<T extends HealthConnectPointType>(
    type: T,
    options: ReadOptions
  ): Promise<PointsPage<T>>;
  getChangesToken(types: HealthConnectPointType[]): Promise<string>;
  readChanges(changesToken: string): Promise<ChangesResult>;
  requestExerciseRoute(uid: string): Promise<RoutePoint[] | undefined>;
  aggregateDaily(
    metric: HealthConnectDailyMetric,
    startDate: string,
    endDate: string
  ): Promise<DailyValue[]>;
}

export const DEFAULT_PAGE_SIZE = 100;
