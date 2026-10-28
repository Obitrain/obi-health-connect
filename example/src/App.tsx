import { useCallback, useState } from 'react';
import {
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import {
  HealthConnect,
  toHealthConnectError,
  type HealthConnectPermission,
} from '@obitrain/react-native-health-connect';

const ALL_PERMISSIONS: HealthConnectPermission[] = [
  'exercise',
  'heartRate',
  'speed',
  'power',
  'cyclingPedalingCadence',
  'stepsCadence',
  'distance',
  'elevationGained',
  'activeCalories',
  'totalCalories',
  'steps',
  'floorsClimbed',
  'sleep',
  'weight',
  'bodyFat',
  'height',
  'restingHeartRate',
  'heartRateVariability',
  'vo2Max',
  'backgroundRead',
  'history',
];

const DAY_MS = 24 * 60 * 60 * 1000;
const isoDate = (date: Date) => date.toISOString().slice(0, 10);

export default function App() {
  const [log, setLog] = useState<string[]>([]);

  const run = useCallback(
    async (label: string, action: () => Promise<unknown>) => {
      try {
        const result = await action();
        const line = `${label}: ${JSON.stringify(result, null, 1).slice(0, 2000)}`;
        console.log(`[obi-health-connect] ${line}`);
        setLog((prev) => [line, ...prev]);
      } catch (e) {
        const error = toHealthConnectError(e);
        setLog((prev) => [
          `${label} failed [${error.code}]: ${error.message}`,
          ...prev,
        ]);
      }
    },
    []
  );

  const now = Date.now();
  const actions: [string, () => Promise<unknown>][] = [
    ['getStatus', () => HealthConnect.getStatus()],
    ['resolveStatus', () => HealthConnect.resolveStatus()],
    ['openSettings', () => HealthConnect.openSettings()],
    [
      'requestPermissions',
      () => HealthConnect.requestPermissions(ALL_PERMISSIONS),
    ],
    [
      'getGrantedPermissions',
      () => HealthConnect.getGrantedPermissions(ALL_PERMISSIONS),
    ],
    [
      'background read available',
      () => HealthConnect.isFeatureAvailable('backgroundRead'),
    ],
    [
      'exercises (7 days)',
      () =>
        HealthConnect.readRecords('exercise', {
          startMs: now - 7 * DAY_MS,
          endMs: now,
        }),
    ],
    [
      'sleep (7 days)',
      () =>
        HealthConnect.readRecords('sleep', {
          startMs: now - 7 * DAY_MS,
          endMs: now,
        }),
    ],
    [
      'exercise + sleep changes (new token)',
      async () => {
        const token = await HealthConnect.getChangesToken([
          'exercise',
          'sleep',
        ]);
        return HealthConnect.readChanges(token);
      },
    ],
    [
      'daily steps (7 days)',
      () =>
        HealthConnect.aggregateDaily(
          'steps',
          isoDate(new Date(now - 6 * DAY_MS)),
          isoDate(new Date(now))
        ),
    ],
    [
      'daily active calories (7 days)',
      () =>
        HealthConnect.aggregateDaily(
          'activeCalories',
          isoDate(new Date(now - 6 * DAY_MS)),
          isoDate(new Date(now))
        ),
    ],
  ];

  return (
    <View style={styles.container}>
      <StatusBar barStyle="dark-content" />
      <Text style={styles.title}>obi-health-connect</Text>
      <View style={styles.buttons}>
        {actions.map(([label, action]) => (
          <TouchableOpacity
            key={label}
            style={styles.button}
            onPress={() => run(label, action)}
          >
            <Text style={styles.buttonText}>{label}</Text>
          </TouchableOpacity>
        ))}
      </View>
      <ScrollView style={styles.log}>
        {log.map((line, i) => (
          <Text key={i} style={styles.logLine} selectable>
            {line}
          </Text>
        ))}
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    paddingTop: 48,
    paddingHorizontal: 16,
    backgroundColor: '#fff',
  },
  title: { fontSize: 20, fontWeight: '600', marginBottom: 12 },
  buttons: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  button: {
    backgroundColor: '#1259c3',
    borderRadius: 6,
    paddingVertical: 8,
    paddingHorizontal: 10,
  },
  buttonText: { color: '#fff', fontSize: 13 },
  log: { marginTop: 16, flex: 1 },
  logLine: {
    fontFamily: 'monospace',
    fontSize: 11,
    marginBottom: 8,
    color: '#222',
  },
});
