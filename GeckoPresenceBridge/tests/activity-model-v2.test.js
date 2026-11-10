import test from 'node:test';
import assert from 'node:assert/strict';
import {
  normalizeUnifiedActivity,
  toBridgePayloadV2,
  fromLegacyActivity,
} from '../extension/core/activity-model.js';

const base = {
  schemaVersion: 2,
  id: 'idlix:tab-7',
  source: 'z2.idlixku.com',
  sourceType: 'website',
  activityType: 'Watching',
  name: 'IDLIX',
  details: 'The Bear',
  state: 'Season 2 · Episode 4',
  mediaState: 'playing',
  timestamps: { startMs: 1_800_000_000_000, endMs: 1_800_000_900_000 },
  assets: { large: { url: 'https://cdn.example.test/poster.jpg', text: 'The Bear' } },
  buttons: [{ label: 'View episode', url: 'https://z2.idlixku.com/episode/4' }],
  metadata: { seriesTitle: 'The Bear', seasonNumber: 2, episodeNumber: 4, episodeTitle: 'Honeydew' },
  priority: 80,
  confidence: 0.95,
  updatedAt: 1_800_000_000_000,
};

test('normalizes a complete UAM v2 media activity and retains episode metadata', () => {
  const result = normalizeUnifiedActivity(base);
  assert.equal(result.schemaVersion, 2);
  assert.equal(result.details, 'The Bear');
  assert.equal(result.metadata.episodeTitle, 'Honeydew');
  assert.equal(result.metadata.seasonNumber, 2);
  assert.equal(result.timestamps.endMs, 1_800_000_900_000);
});

test('rejects invalid host names and non-web source URLs', () => {
  assert.throws(() => normalizeUnifiedActivity({ ...base, source: 'https://user:pw@example.test/path' }), /source/i);
  assert.throws(() => normalizeUnifiedActivity({ ...base, assets: { large: { url: 'javascript:alert(1)' } } }), /image/i);
});

test('removes untrusted button URLs and bounds user-facing fields', () => {
  const result = normalizeUnifiedActivity({ ...base, details: 'x'.repeat(500), buttons: [{ label: 'bad', url: 'javascript:alert(1)' }] });
  assert.equal(result.details.length, 120);
  assert.deepEqual(result.buttons, []);
});

test('maps UAM episode title and series title into legacy host-compatible fields', () => {
  const payload = toBridgePayloadV2(normalizeUnifiedActivity(base));
  assert.equal(payload.schemaVersion, 2);
  assert.equal(payload.details, 'The Bear');
  assert.equal(payload.contentTitle, 'The Bear');
  assert.equal(payload.sourceType, 'website');
  assert.equal(payload.priority, 80);
  assert.equal(payload.state, 'Season 2 · Episode 4 — Honeydew');
  assert.equal(payload.source, 'z2.idlixku.com');
  assert.equal(payload.mediaState, 'playing');
});

test('converts the existing v1 browser activity into UAM v2', () => {
  const result = fromLegacyActivity({ source: 'youtube.com', details: 'Watching', pageTitle: 'Example video', mediaState: 'paused', occurredAt: 1_800_000_000_000 });
  assert.equal(result.schemaVersion, 2);
  assert.equal(result.activityType, 'Watching');
  assert.equal(result.details, 'Example video');
  assert.equal(result.mediaState, 'paused');
});
