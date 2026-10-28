import { describe, expect, it, jest } from '@jest/globals';
import { HealthConnectError, toHealthConnectError } from '../errors';
import { HealthConnect } from '../HealthConnect.android';

const mockNative = {
  readRecords: jest.fn<(...args: unknown[]) => Promise<unknown>>(),
  readChanges: jest.fn<(...args: unknown[]) => Promise<unknown>>(),
  requestExerciseRoute: jest.fn<(...args: unknown[]) => Promise<unknown>>(),
  getStatus: jest.fn<() => Promise<unknown>>(),
};

jest.mock('react-native-nitro-modules', () => ({
  NitroModules: { createHybridObject: () => mockNative },
}));

describe('toHealthConnectError', () => {
  it.each([
    {
      id: 'known code prefix',
      input: new Error(
        "[permission] Caller doesn't have android.permission.health.READ_EXERCISE"
      ),
      code: 'permission',
      message: "Caller doesn't have android.permission.health.READ_EXERCISE",
    },
    {
      id: 'unknown code prefix kept in the message',
      input: new Error('[foo] bar'),
      code: undefined,
      message: '[foo] bar',
    },
    {
      id: 'no prefix',
      input: new Error('boom'),
      code: undefined,
      message: 'boom',
    },
    {
      id: 'non-error rejection',
      input: 'boom',
      code: undefined,
      message: 'boom',
    },
  ])('$id', ({ input, code, message }) => {
    const error = toHealthConnectError(input);
    expect(error).toBeInstanceOf(HealthConnectError);
    expect(error.code).toBe(code);
    expect(error.message).toBe(message);
  });
});

describe('HealthConnect (android)', () => {
  it('decodes points and passes the default page size', async () => {
    mockNative.readRecords.mockResolvedValueOnce({
      pointsJson: '[{"type":"weight","uid":"a","start_time":1,"weight":70}]',
      nextPageToken: 'next',
    });
    const page = await HealthConnect.readRecords('weight', {
      startMs: 0,
      endMs: 10,
    });
    expect(mockNative.readRecords).toHaveBeenCalledWith(
      'weight',
      0,
      10,
      100,
      undefined
    );
    expect(page).toEqual({
      points: [{ type: 'weight', uid: 'a', start_time: 1, weight: 70 }],
      nextPageToken: 'next',
    });
  });

  it('decodes upserts and keeps tombstones and token state', async () => {
    mockNative.readChanges.mockResolvedValueOnce({
      upsertsJson: '[]',
      deletedUids: ['gone'],
      nextChangesToken: 't2',
      hasMore: false,
      changesTokenExpired: false,
    });
    await expect(HealthConnect.readChanges('t1')).resolves.toEqual({
      upserts: [],
      deletedUids: ['gone'],
      nextChangesToken: 't2',
      hasMore: false,
      changesTokenExpired: false,
    });
  });

  it.each([
    {
      id: 'route granted',
      native: '[{"t":1,"lat":2,"lng":3}]',
      expected: [{ t: 1, lat: 2, lng: 3 }],
    },
    { id: 'route declined', native: undefined, expected: undefined },
  ])('requestExerciseRoute: $id', async ({ native, expected }) => {
    mockNative.requestExerciseRoute.mockResolvedValueOnce(native);
    await expect(HealthConnect.requestExerciseRoute('uid')).resolves.toEqual(
      expected
    );
  });

  it('maps native rejections to HealthConnectError', async () => {
    mockNative.getStatus.mockRejectedValueOnce(
      new Error('[rateLimited] quota')
    );
    await expect(HealthConnect.getStatus()).rejects.toMatchObject({
      name: 'HealthConnectError',
      code: 'rateLimited',
    });
  });
});
