import test from 'node:test';
import assert from 'node:assert/strict';
import { parseIdlixActivity } from '../extension/adapters/idlix.js';

function fakeDocument({ pathname='/movie/example', title='Example Movie - Subtitle Indonesia - IDLIX', meta={}, selectors={}, jsonLd=[] } = {}) {
  const make = (entry) => entry ? ({ textContent: entry.text ?? '', getAttribute: (key) => entry.attrs?.[key] ?? null, querySelectorAll: () => entry.children ?? [], firstElementChild: entry.firstChild ?? null }) : null;
  const scriptNodes = jsonLd.map((item) => ({ textContent: JSON.stringify(item) }));
  return {
    title,
    location: { hostname: 'z2.idlixku.com', pathname, href: `https://z2.idlixku.com${pathname}` },
    querySelector(selector) {
      if (selector === 'script[type="application/ld+json"]') return null;
      if (selector.startsWith('meta[')) {
        const key = selector.match(/(?:property|name)="([^"]+)"/)?.[1];
        return meta[key] ? { getAttribute: () => meta[key] } : null;
      }
      if (selector === 'script[type="application/ld+json"]') return scriptNodes[0] ?? null;
      return make(selectors[selector]);
    },
    querySelectorAll(selector) {
      if (selector === 'script[type="application/ld+json"]') return scriptNodes;
      return selectors[selector]?.all ?? [];
    },
  };
}

test('recognizes IDLIX /series routes and returns a non-empty series browse activity', () => {
  const document = fakeDocument({ pathname: '/series', title: 'Series - IDLIX' });
  const result = parseIdlixActivity(document, { mediaState: 'unknown', now: 1_800_000_000_000 });
  assert.equal(result.name, 'IDLIX');
  assert.equal(result.details, 'Browsing series');
  assert.equal(result.metadata.routeType, 'series');
});

test('extracts TV series title, season, episode number and player state from modern series detail routes', () => {
  const document = fakeDocument({
    pathname: '/series/the-bear-season-2-episode-4',
    title: 'The Bear Season 2 Episode 4 - IDLIX',
    meta: { 'og:title': 'The Bear', 'og:image': 'https://cdn.idlix.test/the-bear.jpg' },
    selectors: {
      '.epih1': { text: 'The Bear' },
      '.epih3': { text: 'Season 2 Episode 4 - Honeydew' },
    },
  });
  const result = parseIdlixActivity(document, { mediaState: 'playing', now: 1_800_000_000_000, durationSeconds: 2700, currentTimeSeconds: 300 });
  assert.equal(result.details, 'The Bear');
  assert.equal(result.state, 'Season 2 · Episode 4 — Honeydew');
  assert.equal(result.metadata.seasonNumber, 2);
  assert.equal(result.metadata.episodeNumber, 4);
  assert.equal(result.mediaState, 'playing');
  assert.equal(result.assets.large.url, 'https://cdn.idlix.test/the-bear.jpg');
  assert.equal(result.timestamps.endMs - result.timestamps.startMs, 2_700_000);
});

test('prefers JSON-LD TV episode metadata to generic document title and page chrome', () => {
  const document = fakeDocument({
    pathname: '/tvseries/the-show',
    title: 'Watch online - IDLIX',
    jsonLd: [{ '@type': 'TVEpisode', name: 'The Arrival', partOfSeason: { seasonNumber: 3 }, episodeNumber: 8, partOfSeries: { name: 'The Expanse' } }],
  });
  const result = parseIdlixActivity(document, { mediaState: 'paused', now: 1_800_000_000_000 });
  assert.equal(result.details, 'The Expanse');
  assert.equal(result.metadata.episodeTitle, 'The Arrival');
  assert.equal(result.metadata.seasonNumber, 3);
  assert.equal(result.metadata.episodeNumber, 8);
  assert.equal(result.mediaState, 'paused');
});
