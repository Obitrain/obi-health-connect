import { NitroModules } from 'react-native-nitro-modules';
import { DEFAULT_PAGE_SIZE, type HealthConnectApi } from './api';
import { toHealthConnectError } from './errors';
import type { HealthConnect as NativeHealthConnect } from './HealthConnect.nitro';

let _native: NativeHealthConnect | undefined;
const native = (): NativeHealthConnect => {
  _native ??=
    NitroModules.createHybridObject<NativeHealthConnect>('HealthConnect');
  return _native;
};

const call = async <T>(run: () => Promise<T>): Promise<T> => {
  try {
    return await run();
  } catch (error) {
    throw toHealthConnectError(error);
  }
};

export const HealthConnect: HealthConnectApi = {
  isSupportedPlatform: true,
  getStatus: () => call(() => native().getStatus()),
  resolveStatus: () => call(() => native().resolveStatus()),
  openSettings: () => call(() => native().openSettings()),
  isFeatureAvailable: (feature) =>
    call(() => native().isFeatureAvailable(feature)),
  getGrantedPermissions: (permissions) =>
    call(() => native().getGrantedPermissions(permissions)),
  requestPermissions: (permissions) =>
    call(() => native().requestPermissions(permissions)),
  readRecords: (type, { startMs, endMs, pageSize, pageToken }) =>
    call(async () => {
      const page = await native().readRecords(
        type,
        startMs,
        endMs,
        pageSize ?? DEFAULT_PAGE_SIZE,
        pageToken
      );
      return {
        points: JSON.parse(page.pointsJson),
        nextPageToken: page.nextPageToken,
      };
    }),
  getChangesToken: (types) => call(() => native().getChangesToken(types)),
  readChanges: (changesToken) =>
    call(async () => {
      const page = await native().readChanges(changesToken);
      return {
        upserts: JSON.parse(page.upsertsJson),
        deletedUids: page.deletedUids,
        nextChangesToken: page.nextChangesToken,
        hasMore: page.hasMore,
        changesTokenExpired: page.changesTokenExpired,
      };
    }),
  requestExerciseRoute: (uid) =>
    call(async () => {
      const route = await native().requestExerciseRoute(uid);
      return route === undefined ? undefined : JSON.parse(route);
    }),
  aggregateDaily: (metric, startDate, endDate) =>
    call(() => native().aggregateDaily(metric, startDate, endDate)),
};
