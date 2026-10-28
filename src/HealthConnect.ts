import type { HealthConnectApi } from './api';
import { HealthConnectError } from './errors';

const unsupported = () =>
  Promise.reject(
    new HealthConnectError(
      'Health Connect is only available on Android',
      'unavailable'
    )
  );

export const HealthConnect: HealthConnectApi = {
  isSupportedPlatform: false,
  getStatus: () => Promise.resolve('unsupportedDevice'),
  resolveStatus: unsupported,
  openSettings: unsupported,
  isFeatureAvailable: () => Promise.resolve(false),
  getGrantedPermissions: () => Promise.resolve([]),
  requestPermissions: unsupported,
  readRecords: unsupported,
  getChangesToken: unsupported,
  readChanges: unsupported,
  requestExerciseRoute: unsupported,
  aggregateDaily: unsupported,
};
