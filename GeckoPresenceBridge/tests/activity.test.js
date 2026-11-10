import test from 'node:test';
import assert from 'node:assert/strict';
import { selectActiveTab, createActivityEvent, isMessageFromActiveTab, inferMediaState, isSensitiveHost, displayNameForHost } from '../extension/core/activity.js';

test('selects the single active tab and ignores inactive tabs', () => {
  const active = { id: 7, active: true, url: 'https://www.youtube.com/watch?v=secret' };
  const tabs = [
    { id: 2, active: false, url: 'https://example.com/' },
    active,
  ];

  assert.equal(selectActiveTab(tabs), active);
});

test('returns null when there is no unambiguous active tab', () => {
  assert.equal(selectActiveTab([]), null);
  assert.equal(selectActiveTab([
    { id: 1, active: true },
    { id: 2, active: true },
  ]), null);
});

test('creates a privacy-minimal event without URL, query, fragment, or title', () => {
  const event = createActivityEvent({
    id: 1,
    active: true,
    url: 'https://www.youtube.com/watch?v=private-video#comments',
    title: 'Private video title',
  }, 'playing', { now: 1234 });

  assert.deepEqual(event, {
    kind: 'activity.update',
    source: 'youtube.com',
    details: 'Watching',
    state: 'youtube.com',
    mediaState: 'playing',
    occurredAt: 1234,
  });
  assert.equal('url' in event, false);
  assert.equal('title' in event, false);
});

test('includes a sanitized page title only when explicitly enabled', () => {
  const event = createActivityEvent({
    id: 3,
    active: true,
    url: 'https://www.example.com/path?token=secret',
    title: '  Example\nPage  ',
  }, 'playing', { includeTitle: true, now: 99 });

  assert.equal(event.pageTitle, 'Example Page');
  assert.equal(event.details, 'Watching');
  assert.equal(JSON.stringify(event).includes('token=secret'), false);
});

test('rejects non-web pages and malformed URLs', () => {
  assert.equal(createActivityEvent({ id: 1, active: true, url: 'about:config' }), null);
  assert.equal(createActivityEvent({ id: 1, active: true, url: 'file:///sdcard/secret.txt' }), null);
  assert.equal(createActivityEvent({ id: 1, active: true, url: 'not a url' }), null);
});

test('normalizes unsupported media states to browsing', () => {
  const event = createActivityEvent({
    id: 4,
    active: true,
    url: 'https://example.org/',
  }, 'unexpected');

  assert.equal(event.mediaState, 'unknown');
  assert.equal(event.details, 'Browsing');
});

test('accepts media messages only from the currently active tab', () => {
  assert.equal(isMessageFromActiveTab(9, 9), true);
  assert.equal(isMessageFromActiveTab(9, 4), false);
  assert.equal(isMessageFromActiveTab(undefined, 4), false);
});

test('does not publish page titles by default and truncates explicit titles', () => {
  const tab = {
    id: 8,
    active: true,
    url: 'http://www.Example.org./watch?private=1',
    title: `  ${'A'.repeat(140)}\nsecret  `,
  };
  const redacted = createActivityEvent(tab, 'unknown', { now: 1 });
  const optedIn = createActivityEvent(tab, 'playing', { includeTitle: true, now: 1 });

  assert.equal(redacted.source, 'example.org');
  assert.equal(redacted.pageTitle, undefined);
  assert.equal(optedIn.pageTitle.length, 120);
  assert.equal(JSON.stringify(optedIn).includes('private=1'), false);
});

test('infers media state without marking a never-played video as paused', () => {
  assert.equal(inferMediaState(false, true, false), 'unknown');
  assert.equal(inferMediaState(false, false, false), 'playing');
  assert.equal(inferMediaState(false, true, true), 'paused');
  assert.equal(inferMediaState(true, true, true), 'ended');
});


test('blocks sensitive finance, authentication and health hosts', () => {
  for (const host of ['mybca.bca.co.id', 'ib.bankmandiri.co.id', 'auth.example.com', 'health.example.org', 'mail.example.com', 'account.google.com', 'checkout.example.com', 'web.whatsapp.com']) {
    assert.equal(isSensitiveHost(host), true, `${host} should be blocked`);
  }
  assert.equal(createActivityEvent({ id: 10, active: true, url: 'https://mybca.bca.co.id/login' }), null);
});

test('recognizes mobile YouTube and IDLix without sending full URLs', () => {
  assert.equal(displayNameForHost('m.youtube.com'), 'YouTube');
  assert.equal(displayNameForHost('music.youtube.com'), 'YouTube Music');
  assert.equal(displayNameForHost('www.idlixplus.net'), 'IDLix');
  const event = createActivityEvent({ id: 11, active: true, url: 'https://www.idlixplus.net/movie/episode?id=private' }, 'playing');
  assert.equal(event.source, 'idlixplus.net');
  assert.equal(event.details, 'Watching');
  assert.equal(JSON.stringify(event).includes('private'), false);
});


test('labels common websites and app-specific watching states', () => {
  const tikTok = createActivityEvent({ id: 20, active: true, url: 'https://www.tiktok.com/@demo/video/123' }, 'playing');
  const instagram = createActivityEvent({ id: 21, active: true, url: 'https://www.instagram.com/reels/' }, 'playing');
  const chess = createActivityEvent({ id: 22, active: true, url: 'https://www.chess.com/play/computer' }, 'playing');
  assert.equal(displayNameForHost('m.youtube.com'), 'YouTube');
  assert.equal(displayNameForHost('www.idlixplus.net'), 'IDLix');
  assert.equal(tikTok.details, 'Watching TikTok');
  assert.equal(instagram.details, 'Watching Reels');
  assert.equal(chess.details, 'Playing chess');
  const chessLive = createActivityEvent({ id: 23, active: true, url: 'https://www.chess.com/game/live/123' });
  assert.equal(chessLive.details, 'Playing chess');
});

test('includes a sanitized media title for active playback and strips the IDLix suffix', async () => {
  const activity = createActivityEvent(
    { id: 90, active: true, url: 'https://www.idlixplus.net/movie/example' },
    'playing',
    { includeTitle: true, mediaTitle: 'The Example Film (2026) - IDLix' },
  );

  assert.equal(activity.details, 'Watching');
  assert.equal(activity.pageTitle, 'The Example Film (2026)');
  assert.equal(activity.state, 'idlixplus.net');
  assert.equal(JSON.stringify(activity).includes('/movie/example'), false);
});

test('does not include page titles while the browser is only browsing', () => {
  const activity = createActivityEvent(
    { id: 91, active: true, url: 'https://example.com/', title: 'Private account dashboard' },
    'unknown',
    { includeTitle: true },
  );
  assert.equal(activity.pageTitle, undefined);
});


test("removes the active media site suffix from YouTube titles", () => {
  const activity = createActivityEvent(
    { id: 92, active: true, url: 'https://www.youtube.com/watch?v=private', title: 'Example Video - YouTube' },
    'playing',
    { includeTitle: true },
  );
  assert.equal(activity.pageTitle, 'Example Video');
});
