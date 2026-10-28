/** Codes set by HealthConnect.kt in the `[code]` message prefix. */
export const HealthConnectErrorCode = {
  /** Missing permission (`SecurityException`). */
  PERMISSION: 'permission',
  /** API quota exceeded; retry later. */
  RATE_LIMITED: 'rateLimited',
  /** Health Connect service failure (`RemoteException`). */
  REMOTE: 'remote',
  /** Storage failure or data sync in progress (`IOException`). */
  IO: 'io',
  INVALID_ARGUMENT: 'invalidArgument',
  /** Health Connect missing or below the required version. */
  UNAVAILABLE: 'unavailable',
  /** Needs a foreground activity (permission or route consent screen). */
  NO_ACTIVITY: 'noActivity',
} as const;

export type HealthConnectErrorCode =
  (typeof HealthConnectErrorCode)[keyof typeof HealthConnectErrorCode];

export class HealthConnectError extends Error {
  /** Undefined for failures without a code prefix. */
  readonly code: HealthConnectErrorCode | undefined;

  constructor(message: string, code: HealthConnectErrorCode | undefined) {
    super(message);
    this.name = 'HealthConnectError';
    this.code = code;
  }
}

const CODE_PREFIX = /^\[([A-Za-z]+)\]\s*/;
const CODES = new Set<string>(Object.values(HealthConnectErrorCode));

/** Turns a native rejection (`[<code>] message`, see HealthConnect.kt) into a HealthConnectError. */
export const toHealthConnectError = (error: unknown): HealthConnectError => {
  if (error instanceof HealthConnectError) return error;
  const message = error instanceof Error ? error.message : String(error);
  const match = CODE_PREFIX.exec(message);
  if (!match || !CODES.has(match[1]!))
    return new HealthConnectError(message, undefined);
  return new HealthConnectError(
    message.slice(match[0].length),
    match[1] as HealthConnectErrorCode
  );
};
