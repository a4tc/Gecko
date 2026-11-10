import test from 'node:test';
import assert from 'node:assert/strict';

let moduleRun = 0;

async function loadBackgroundWithMock(initialTabs = [], mediaStates = {}, initialStorage = {}) {
  const storage = { ...initialStorage };
  const listeners = {};
  const requests = [];
  const injections = [];
  const permissionChecks = [];
  let tabs = initialTabs;
  globalThis.fetch = async (url, options = {}) => {
    requests.push({ url: String(url), options });
    return {
      ok: true,
      status: 202,
      json: async () => ({ ok: true, accepted: true }),
    };
  };

  globalThis.browser = {
    tabs: {
      query: async () => tabs,
      executeScript: async (tabId, details) => { injections.push({ tabId, details }); return []; },
      sendMessage: async (tabId, message, options) => {
        if (message?.type === 'GET_IDLIX_METADATA') {
          const metadata = mediaStates[`idlix:${tabId}`];
          if (metadata instanceof Error) throw metadata;
          return metadata;
        }
        if (message?.type === 'GET_MEDIA_STATE') {
          const state = options?.frameId === 0
            ? (mediaStates[`top:${tabId}`] ?? mediaStates[tabId] ?? 'unknown')
            : (mediaStates[tabId] ?? 'unknown');
          if (state instanceof Error) throw state;
          return state;
        }
        return undefined;
      },
      onActivated: { addListener: (listener) => { listeners.activated = listener; } },
      onUpdated: { addListener: (listener) => { listeners.updated = listener; } },
      onRemoved: { addListener: (listener) => { listeners.removed = listener; } },
    },
    storage: {
      local: {
        get: async (keys) => {
          const list = Array.isArray(keys) ? keys : [keys];
          return Object.fromEntries(list.map((key) => [key, storage[key]]));
        },
        set: async (values) => { Object.assign(storage, values); },
      },
    },
    permissions: {
      contains: async (permission) => { permissionChecks.push(permission); return true; },
      request: async () => true,
      remove: async () => true,
    },
    runtime: {
      onInstalled: { addListener: (listener) => { listeners.installed = listener; } },
      onStartup: { addListener: (listener) => { listeners.startup = listener; } },
      onMessage: { addListener: (listener) => { listeners.message = listener; } },
    },
  };

  await import(`../extension/background.js?test=${++moduleRun}`);
  return { storage, listeners, requests, injections, permissionChecks, setTabs: (nextTabs) => { tabs = nextTabs; } };
}

test('registers event listeners synchronously and stores active-tab activity', async () => {
  const mock = await loadBackgroundWithMock([
    { id: 5, active: true, url: 'https://example.com/private/path?token=x', title: 'Private page' },
  ]);

  assert.equal(typeof mock.listeners.activated, 'function');
  assert.equal(typeof mock.listeners.updated, 'function');
  assert.equal(typeof mock.listeners.message, 'function');
  assert.equal(typeof mock.listeners.startup, 'function');

  mock.listeners.activated({ tabId: 5, windowId: 1 });
  await new Promise((resolve) => setImmediate(resolve));

  assert.equal(mock.storage.currentActivity.source, 'example.com');
  assert.equal(mock.storage.currentActivity.pageTitle, undefined);
  assert.equal(JSON.stringify(mock.storage.currentActivity).includes('token=x'), false);
});

test('ignores media messages from a background tab without clearing foreground activity', async () => {
  const mock = await loadBackgroundWithMock([
    { id: 5, active: true, url: 'https://example.com/', title: 'Current tab' },
    { id: 6, active: false, url: 'https://www.youtube.com/watch?v=background' },
  ]);

  mock.listeners.activated({ tabId: 5, windowId: 1 });
  await new Promise((resolve) => setImmediate(resolve));
  const before = mock.storage.currentActivity;

  await mock.listeners.message(
    { type: 'MEDIA_STATE', state: 'playing' },
    { tab: { id: 6 } },
  );

  assert.deepEqual(mock.storage.currentActivity, before);
});

test('accepts media transitions from the active tab', async () => {
  const mock = await loadBackgroundWithMock([
    { id: 5, active: true, url: 'https://www.youtube.com/watch?v=video' },
  ]);

  await mock.listeners.message(
    { type: 'MEDIA_STATE', state: 'playing' },
    { tab: { id: 5 } },
  );

  assert.equal(mock.storage.currentActivity.mediaState, 'playing');
  assert.equal(mock.storage.currentActivity.details, 'Watching');
});


test('preserves playing state when the active tab title updates', async () => {
  const mock = await loadBackgroundWithMock([
    { id: 5, active: true, url: 'https://www.youtube.com/watch?v=video', title: 'Old title' },
  ]);

  mock.listeners.activated({ tabId: 5, windowId: 1 });
  await new Promise((resolve) => setImmediate(resolve));
  await mock.listeners.message(
    { type: 'MEDIA_STATE', state: 'playing' },
    { tab: { id: 5 } },
  );

  mock.listeners.updated(5, { title: 'New title' }, {
    id: 5, active: true, url: 'https://www.youtube.com/watch?v=video', title: 'New title',
  });
  await new Promise((resolve) => setImmediate(resolve));

  assert.equal(mock.storage.currentActivity.mediaState, 'playing');
});


test('restores the existing media state when returning to a playing tab', async () => {
  const mock = await loadBackgroundWithMock([
    { id: 5, active: true, url: 'https://www.youtube.com/watch?v=video' },
  ], { 5: 'playing' });

  mock.listeners.activated({ tabId: 5, windowId: 1 });
  await new Promise((resolve) => setImmediate(resolve));

  assert.equal(mock.storage.currentActivity.mediaState, 'playing');
  assert.equal(mock.storage.currentActivity.details, 'Watching');
});


test('sends the active-site activity to the paired Android host', async () => {
  const mock = await loadBackgroundWithMock(
    [{ id: 5, active: true, url: 'https://www.youtube.com/watch?v=private', title: 'Private video' }],
    { 5: 'playing' },
    { bridgeEnabled: true, bridgeKey: 'pairing-secret' },
  );

  mock.listeners.activated({ tabId: 5, windowId: 1 });
  await new Promise((resolve) => setTimeout(resolve, 0));

  assert.equal(mock.requests.length, 1);
  assert.equal(mock.requests[0].url, 'http://127.0.0.1:32145/v2/activity');
  assert.equal(mock.requests[0].options.method, 'POST');
  assert.equal(mock.requests[0].options.headers['X-GPB-Key'], 'pairing-secret');
  const payload = JSON.parse(mock.requests[0].options.body);
  assert.equal(payload.source, 'youtube.com');
  assert.equal(payload.name, 'YouTube');
  assert.equal(payload.details, 'Private video');
  assert.equal(payload.mediaState, 'playing');
  assert.equal(payload.state, 'Watching');
  assert.equal('url' in payload, false);
  assert.equal(JSON.stringify(payload).includes('private'), false);
  assert.equal(JSON.stringify(payload).includes('pairing-secret'), false);
  assert.equal(Number.isInteger(payload.startTimestamp), true);
});

test('clears the Android activity when the active tab is not a web page', async () => {
  const mock = await loadBackgroundWithMock(
    [{ id: 8, active: true, url: 'about:config' }],
    {},
    { bridgeEnabled: true, bridgeKey: 'pairing-secret' },
  );

  mock.listeners.activated({ tabId: 8, windowId: 1 });
  await new Promise((resolve) => setTimeout(resolve, 0));

  assert.equal(mock.requests.length, 1);
  assert.equal(mock.requests[0].url, 'http://127.0.0.1:32145/v2/clear');
  assert.equal(mock.requests[0].options.headers['X-GPB-Key'], 'pairing-secret');
  assert.equal(mock.requests[0].options.body, '{}');
});

test('does not contact the Android host when bridge pairing is not enabled', async () => {
  const mock = await loadBackgroundWithMock([
    { id: 3, active: true, url: 'https://example.com/' },
  ]);

  mock.listeners.activated({ tabId: 3, windowId: 1 });
  await new Promise((resolve) => setTimeout(resolve, 0));

  assert.equal(mock.requests.length, 0);
});

test('resyncs the current activity after bridge settings are saved', async () => {
  const mock = await loadBackgroundWithMock([
    { id: 2, active: true, url: 'https://www.twitch.tv/example' },
  ]);

  mock.listeners.activated({ tabId: 2, windowId: 1 });
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(mock.requests.length, 0);

  mock.storage.bridgeEnabled = true;
  mock.storage.bridgeKey = 'new-pairing-key';
  await mock.listeners.message({ type: 'BRIDGE_SETTINGS_UPDATED' }, {});

  assert.equal(mock.requests.length, 1);
  assert.equal(mock.requests[0].url, 'http://127.0.0.1:32145/v2/activity');
  assert.equal(mock.requests[0].options.headers['X-GPB-Key'], 'new-pairing-key');
  assert.equal(JSON.parse(mock.requests[0].options.body).source, 'twitch.tv');
});


test('injects generic media detection in all permitted frames of the active tab only', async () => {
  const mock = await loadBackgroundWithMock(
    [{ id: 31, active: true, url: 'https://www.idlixplus.net/watch/123' }],
    {},
    {},
  );
  mock.listeners.activated({ tabId: 31, windowId: 1 });
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.deepEqual(mock.permissionChecks[0], { origins: ['https://*.idlixplus.net/*'] });
  assert.equal(mock.injections.length, 1);
  assert.equal(mock.injections[0].tabId, 31);
  assert.equal(mock.injections[0].details.allFrames, true);
  assert.equal(mock.storage.currentActivity.source, 'idlixplus.net');
});


test('restores browsing presence when the active browser page becomes visible again', async () => {
  const mock = await loadBackgroundWithMock([
    { id: 42, active: true, url: 'https://www.idlixplus.net/watch/123' },
  ]);

  await mock.listeners.message(
    { type: 'MEDIA_STATE', state: 'unknown' },
    { tab: { id: 42 }, url: 'https://www.idlixplus.net/watch/123' },
  );

  assert.equal(mock.storage.currentActivity.source, 'idlixplus.net');
  assert.equal(mock.storage.currentActivity.mediaState, 'unknown');
  assert.equal(mock.storage.currentActivity.details, 'Browsing');
});

test('ignores media messages originating inside a sensitive embedded frame', async () => {
  const mock = await loadBackgroundWithMock([
    { id: 32, active: true, url: 'https://example-streaming-site.net/watch' },
  ]);
  mock.listeners.activated({ tabId: 32, windowId: 1 });
  await new Promise((resolve) => setImmediate(resolve));
  const before = mock.storage.currentActivity;
  await mock.listeners.message(
    { type: 'MEDIA_STATE', state: 'playing' },
    { tab: { id: 32 }, url: 'https://mybca.bca.co.id/login', frameId: 4 },
  );
  assert.deepEqual(mock.storage.currentActivity, before);
});


test('accepts a title extracted by the top-level IDLix document and forwards it while watching', async () => {
  const mock = await loadBackgroundWithMock(
    [{ id: 74, active: true, url: 'https://www.idlixplus.net/movie/slug' }],
    {},
    { bridgeEnabled: true, bridgeKey: 'pairing-secret' },
  );

  await mock.listeners.message(
    { type: 'MEDIA_STATE', state: 'playing', pageTitle: 'Sample Film (2026) - IDLix' },
    { tab: { id: 74 }, url: 'https://www.idlixplus.net/movie/slug', frameId: 0 },
  );

  assert.equal(mock.storage.currentActivity.details, 'Watching');
  assert.equal(mock.storage.currentActivity.pageTitle, 'Sample Film (2026)');
  const payload = JSON.parse(mock.requests.at(-1).options.body);
  assert.equal(payload.details, 'Sample Film (2026)');
  assert.equal(payload.state, 'Watching');
  assert.equal(payload.source, 'idlixplus.net');
  assert.equal('url' in payload, false);
});


test('recovers the title from the top-level page when playback is reported by an embedded frame', async () => {
  const mock = await loadBackgroundWithMock(
    [{ id: 75, active: true, url: 'https://z2.idlixku.com/watch/123' }],
    { 'top:75': { state: 'unknown', pageTitle: 'The Example Film (2026)' } },
    { bridgeEnabled: true, bridgeKey: 'pairing-secret' },
  );

  await mock.listeners.message(
    { type: 'MEDIA_STATE', state: 'playing' },
    { tab: { id: 75 }, url: 'https://player-cdn.example/embed/123', frameId: 4 },
  );

  assert.equal(mock.storage.currentActivity.details, 'Watching');
  assert.equal(mock.storage.currentActivity.pageTitle, 'The Example Film (2026)');
  const payload = JSON.parse(mock.requests.at(-1).options.body);
  assert.equal(payload.details, 'The Example Film (2026)');
  assert.equal(payload.source, 'z2.idlixku.com');
});

test('manual presence override takes priority over the active website', async () => {
  const mock = await loadBackgroundWithMock(
    [{ id: 92, active: true, url: 'https://www.youtube.com/watch?v=video' }],
    { 92: 'playing' },
    { bridgeEnabled: true, bridgeKey: 'pairing-secret', manualPresence: { enabled: true, name: 'Custom Game', details: 'Building GPB', state: 'Adapter work', activityType: 'Playing' } },
  );
  mock.listeners.activated({ tabId: 92, windowId: 1 });
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.equal(mock.storage.currentActivityV2.sourceType, 'manual');
  assert.equal(mock.storage.currentActivityV2.name, 'Custom Game');
  assert.equal(mock.storage.currentActivityV2.details, 'Building GPB');
  const payload = JSON.parse(mock.requests.at(-1).options.body);
  assert.equal(payload.name, 'Custom Game');
  assert.equal(payload.details, 'Building GPB');
});

test('returns a redacted developer lab snapshot with adapter and event diagnostics', async () => {
  const mock = await loadBackgroundWithMock([
    { id: 93, active: true, url: 'https://z2.idlixku.com/series' },
  ]);
  const snapshot = await mock.listeners.message({ type: 'GPB_GET_LAB_SNAPSHOT' }, {});
  assert.ok(Array.isArray(snapshot.adapters));
  assert.ok(snapshot.adapters.some((adapter) => adapter.id === 'idlix-native'));
  assert.ok(Array.isArray(snapshot.diagnostics));
  assert.equal('bridgeKey' in snapshot, false);
});

test('injects both native and imported IDLIX adapters and accepts imported data as fallback', async () => {
  const mock = await loadBackgroundWithMock(
    [{ id: 105, active: true, url: 'https://vip2.idlixvip.asia/movie/sample' }],
    {},
    {},
  );
  mock.listeners.activated({ tabId: 105, windowId: 1 });
  await new Promise((resolve) => setTimeout(resolve, 0));
  const files = mock.injections.map((entry) => entry.details.file);
  assert.ok(files.includes('content/idlix-presence.js'));
  assert.ok(files.includes('content/premid-compat-runtime.js'));
  assert.ok(files.includes('adapters/premid/idlix-legacy/presence.js'));
  assert.ok(mock.injections.some((entry) => entry.details.file === 'adapters/premid/idlix-legacy/config.js' && entry.details.allFrames === true));
  assert.ok(mock.injections.some((entry) => entry.details.file === 'adapters/premid/idlix-legacy/iframe.js' && entry.details.allFrames === true));
  await mock.listeners.message({
    type: 'PREMID_ACTIVITY_UPDATE', service: 'IDLIX',
    activity: { type: 3, details: 'Imported Film Title', state: 'Season 1 · Episode 2' },
  }, { tab: { id: 105 }, frameId: 0 });
  assert.equal(mock.storage.currentActivityV2.details, 'Imported Film Title');
  assert.equal(mock.storage.currentActivityV2.priority, 40);
});

test('does not let an imported compatibility candidate replace a stronger native adapter result', async () => {
  const mock = await loadBackgroundWithMock(
    [{ id: 106, active: true, url: 'https://vip2.idlixvip.asia/movie/sample' }],
  );
  mock.storage.currentActivityV2 = {
    schemaVersion: 2, id: 'idlix-native', source: 'vip2.idlixvip.asia', sourceType: 'website',
    activityType: 'Watching', name: 'IDLIX', details: 'Native Film Title', mediaState: 'playing',
    priority: 90, confidence: 0.92, updatedAt: Date.now(),
  };
  await mock.listeners.message({
    type: 'PREMID_ACTIVITY_UPDATE', service: 'IDLIX',
    activity: { type: 3, details: 'Older Imported Title', state: 'Episode 2' },
  }, { tab: { id: 106 }, frameId: 0 });
  assert.equal(mock.storage.currentActivityV2.details, 'Native Film Title');
});

test('refreshes IDLIX activity when the page metadata observer reports a title change', async () => {
  const mock = await loadBackgroundWithMock(
    [{ id: 107, active: true, url: 'https://z2.idlixku.com/series/sample' }],
    { 'idlix:107': { routeType: 'episode', title: 'Sample Series', seriesTitle: 'Sample Series', seasonNumber: 2, episodeNumber: 3, episodeTitle: 'The Test', titleSource: 'jsonld-series', artworkSource: 'og:image' } },
  );
  await mock.listeners.message({ type: 'IDLIX_METADATA', metadata: { routeType: 'episode' } }, { tab: { id: 107 }, frameId: 0 });
  assert.equal(mock.storage.currentActivityV2.name, 'IDLIX');
  assert.equal(mock.storage.currentActivityV2.details, 'Sample Series');
  assert.equal(mock.storage.currentActivityV2.metadata.seasonNumber, 2);
  assert.ok(mock.storage.gpbDiagnostics.some((event) => event.category === 'idlix' && event.message.includes('titleSource=jsonld-series')));
});

test('ignores compatibility clear events while manual presence override is active', async () => {
  const mock = await loadBackgroundWithMock(
    [{ id: 108, active: true, url: 'https://vip2.idlixvip.asia/movie/sample' }],
    {},
    { manualPresence: { enabled: true, name: 'Manual', details: 'Custom status' } },
  );
  mock.storage.currentActivityV2 = {
    schemaVersion: 2, id: 'manual:custom-presence', source: 'gecko-presence.local', sourceType: 'manual',
    activityType: 'Playing', name: 'Manual', details: 'Custom status', mediaState: 'unknown',
    priority: 100, confidence: 1, updatedAt: Date.now(),
  };
  await mock.listeners.message({ type: 'PREMID_ACTIVITY_CLEAR', service: 'IDLIX' }, { tab: { id: 108 }, frameId: 0 });
  assert.equal(mock.storage.currentActivityV2.name, 'Manual');
});

test('manual override disable clears the host override before restoring automatic activity', async () => {
  const mock = await loadBackgroundWithMock(
    [{ id: 109, active: true, url: 'https://www.youtube.com/watch?v=sample' }],
    { 109: 'playing' },
    { bridgeEnabled: true, bridgeKey: 'pairing-secret', manualPresence: { enabled: true, name: 'Manual', details: 'Custom status' } },
  );
  mock.storage.manualPresence = { enabled: false };
  await mock.listeners.message({ type: 'GPB_MANUAL_OVERRIDE_DISABLED' }, {});
  assert.equal(mock.requests[0].url, 'http://127.0.0.1:32145/v2/manual/clear');
  assert.equal(mock.requests[1].url, 'http://127.0.0.1:32145/v2/activity');
  assert.equal(JSON.parse(mock.requests[1].options.body).sourceType, 'website');
});

test('allows imported compatibility metadata to fill a low-confidence native IDLIX candidate', async () => {
  const mock = await loadBackgroundWithMock(
    [{ id: 110, active: true, url: 'https://vip2.idlixvip.asia/movie/sample' }],
  );
  mock.storage.currentActivityV2 = {
    schemaVersion: 2, id: 'idlix:vip2.idlixvip.asia:movie', source: 'vip2.idlixvip.asia', sourceType: 'website',
    activityType: 'Watching', name: 'IDLIX', details: 'Browsing movies', mediaState: 'playing',
    priority: 20, confidence: 0.3, updatedAt: Date.now(),
  };
  await mock.listeners.message({
    type: 'PREMID_ACTIVITY_UPDATE', service: 'IDLIX',
    activity: { type: 3, details: 'Recovered Film Title', state: 'Movie' },
  }, { tab: { id: 110 }, frameId: 0 });
  assert.equal(mock.storage.currentActivityV2.details, 'Recovered Film Title');
  assert.equal(mock.storage.currentActivityV2.priority, 40);
});

test('does not let a generic imported browsing status replace a useful native fallback', async () => {
  const mock = await loadBackgroundWithMock(
    [{ id: 111, active: true, url: 'https://vip2.idlixvip.asia/series' }],
  );
  mock.storage.currentActivityV2 = {
    schemaVersion: 2, id: 'idlix-native', source: 'vip2.idlixvip.asia', sourceType: 'website',
    activityType: 'Watching', name: 'IDLIX', details: 'Browsing series', mediaState: 'unknown',
    priority: 20, confidence: 0.3, updatedAt: Date.now(),
  };
  await mock.listeners.message({ type: 'PREMID_ACTIVITY_UPDATE', service: 'IDLIX', activity: { type: 3, details: 'Browsing', state: '' } }, { tab: { id: 111 }, frameId: 0 });
  assert.equal(mock.storage.currentActivityV2.details, 'Browsing series');
});
