const {
  withAndroidManifest,
  withGradleProperties,
} = require('expo/config-plugins');

// Health Connect client requires Android 8 (API 26)
const MIN_SDK = '26';
const PRIVACY_POLICY_KEY = 'com.obitrain.healthconnect.PRIVACY_POLICY_URL';
const PERMISSIONS = [
  'READ_EXERCISE',
  'READ_HEART_RATE',
  'READ_SPEED',
  'READ_POWER',
  'READ_STEPS',
  'READ_DISTANCE',
  'READ_ELEVATION_GAINED',
  'READ_ACTIVE_CALORIES_BURNED',
  'READ_TOTAL_CALORIES_BURNED',
  'READ_FLOORS_CLIMBED',
  'READ_SLEEP',
  'READ_WEIGHT',
  'READ_BODY_FAT',
  'READ_HEIGHT',
  'READ_RESTING_HEART_RATE',
  'READ_HEART_RATE_VARIABILITY',
  'READ_VO2_MAX',
  'READ_HEALTH_DATA_IN_BACKGROUND',
  'READ_HEALTH_DATA_HISTORY',
].map((name) => `android.permission.health.${name}`);

const addPermissions = (manifest) => {
  manifest['uses-permission'] ??= [];
  const existing = new Set(
    manifest['uses-permission'].map((p) => p.$['android:name'])
  );
  for (const name of PERMISSIONS) {
    if (!existing.has(name))
      manifest['uses-permission'].push({ $: { 'android:name': name } });
  }
  return manifest;
};

const setPrivacyPolicy = (application, url) => {
  application['meta-data'] = (application['meta-data'] ?? []).filter(
    (m) => m.$['android:name'] !== PRIVACY_POLICY_KEY
  );
  application['meta-data'].push({
    $: { 'android:name': PRIVACY_POLICY_KEY, 'android:value': url },
  });
  return application;
};

const setMinSdk = (properties) => {
  const existing = properties.find(
    (p) => p.type === 'property' && p.key === 'android.minSdkVersion'
  );
  if (existing) existing.value = MIN_SDK;
  else
    properties.push({
      type: 'property',
      key: 'android.minSdkVersion',
      value: MIN_SDK,
    });
  return properties;
};

module.exports = (
  config,
  { privacyPolicyUrl = 'https://obitrain.com/privacy' } = {}
) => {
  config = withAndroidManifest(config, (mod) => {
    addPermissions(mod.modResults.manifest);
    setPrivacyPolicy(mod.modResults.manifest.application[0], privacyPolicyUrl);
    return mod;
  });
  config = withGradleProperties(config, (mod) => {
    mod.modResults = setMinSdk(mod.modResults);
    return mod;
  });
  return config;
};

module.exports.addPermissions = addPermissions;
module.exports.setPrivacyPolicy = setPrivacyPolicy;
module.exports.setMinSdk = setMinSdk;
