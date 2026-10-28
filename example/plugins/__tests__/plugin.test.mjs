import assert from 'node:assert/strict';
import { test } from 'node:test';
import plugin from '../with-health-connect.js';

const { addPermissions, setPrivacyPolicy, setMinSdk } = plugin;

test('adds each health permission once', () => {
  const manifest = addPermissions(addPermissions({}));
  const names = manifest['uses-permission'].map((p) => p.$['android:name']);
  assert.equal(new Set(names).size, names.length);
  assert.ok(names.includes('android.permission.health.READ_EXERCISE'));
});

test('replaces the privacy policy meta-data', () => {
  const application = setPrivacyPolicy(
    setPrivacyPolicy({}, 'https://old.example'),
    'https://new.example'
  );
  assert.deepEqual(
    application['meta-data'].map((m) => m.$['android:value']),
    ['https://new.example']
  );
});

for (const [name, properties] of [
  ['missing', []],
  ['lower', [{ type: 'property', key: 'android.minSdkVersion', value: '24' }]],
]) {
  test(`raises minSdk to 26 when ${name}`, () => {
    const result = setMinSdk(properties);
    assert.deepEqual(
      result
        .filter((p) => p.key === 'android.minSdkVersion')
        .map((p) => p.value),
      ['26']
    );
  });
}
