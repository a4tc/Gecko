import test from 'node:test';
import assert from 'node:assert/strict';
import { buildManualPresence } from '../extension/core/manual-presence.js';

test('creates a normalized manual override with highest priority', () => {
  const value = buildManualPresence({ enabled: true, name: 'Custom Game', details: 'Building a project', state: 'Focus mode', activityType: 'Playing' }, 123456);
  assert.equal(value.sourceType, 'manual');
  assert.equal(value.priority, 100);
  assert.equal(value.name, 'Custom Game');
  assert.equal(value.details, 'Building a project');
  assert.equal(value.state, 'Focus mode');
  assert.equal(value.updatedAt, 123456);
});

test('returns null when manual override is disabled', () => {
  assert.equal(buildManualPresence({ enabled: false, name: 'x', details: 'y' }), null);
});

test('requires a name and details and bounds the user-facing fields', () => {
  assert.throws(() => buildManualPresence({ enabled: true, name: 'Only name' }), /requires both/i);
  const value = buildManualPresence({ enabled: true, name: 'N'.repeat(100), details: 'D'.repeat(200) });
  assert.equal(value.name.length, 80);
  assert.equal(value.details.length, 120);
});
