import test from 'node:test';
import assert from 'node:assert/strict';
import { arbitrateActivity } from '../extension/core/activity-arbiter.js';

const now = 1_800_000_000_000;
const candidate = (id, priority, updatedAt = now, sourceType = 'website') => ({
  schemaVersion: 2, id, source: 'example.com', sourceType, activityType: 'Watching', name: id,
  details: id, mediaState: 'playing', priority, confidence: 1, updatedAt,
});

test('prefers a high priority native adapter over a compatibility adapter', () => {
  const result = arbitrateActivity([candidate('premid', 50), candidate('native', 90)], now);
  assert.equal(result.id, 'native');
});

test('drops stale adapter candidates', () => {
  const result = arbitrateActivity([candidate('stale', 100, now - 120_000), candidate('fresh', 10)], now);
  assert.equal(result.id, 'fresh');
});

test('prefers the explicit app or website foreground source when priority ties', () => {
  const result = arbitrateActivity([candidate('app', 50, now, 'android-app'), candidate('web', 50)], now, 'android-app');
  assert.equal(result.id, 'app');
});
