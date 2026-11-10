import test from 'node:test';
import assert from 'node:assert/strict';
import { PreMiDCompatibilityRuntime } from '../extension/core/premid-compat.js';

test('supports Presence UpdateData listeners and emits normalized presence candidates', async () => {
  const runtime = new PreMiDCompatibilityRuntime({ clientId: '12345' }, { service: 'Example' });
  let observed;
  runtime.presence.on('UpdateData', async () => runtime.presence.setActivity({ details: 'Reading', state: 'Chapter 8', type: 3 }));
  runtime.onCandidate((value) => { observed = value; });
  await runtime.tick();
  assert.equal(observed.name, 'Example');
  assert.equal(observed.details, 'Reading');
  assert.equal(observed.state, 'Chapter 8');
  assert.equal(observed.activityType, 'Watching');
});

test('implements settings defaults, strings, clearActivity, and iframe message events', async () => {
  const runtime = new PreMiDCompatibilityRuntime({ clientId: '123' }, {
    service: 'Example', settings: [{ id: 'covers', value: true }], strings: { 'general.browsing': 'Browsing' },
  });
  assert.equal(await runtime.presence.getSetting('covers'), true);
  assert.equal(await runtime.presence.getSetting('missing'), undefined);
  assert.equal(await runtime.presence.getStrings({ browse: 'general.browsing' }).then(x => x.browse), 'Browsing');
  let iframeData;
  runtime.presence.on('iFrameData', (data) => { iframeData = data; });
  runtime.receiveIframeData({ isVideo: true, duration: 100 });
  assert.equal(iframeData.isVideo, true);
  runtime.presence.clearActivity();
  assert.equal(runtime.currentActivity, null);
});
