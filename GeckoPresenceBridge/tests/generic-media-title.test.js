import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const source = await readFile(path.join(root, 'extension/content/generic-media.js'), 'utf8');

class FakeElement {
  constructor({ textContent = '', attributes = {}, children = [] } = {}) {
    this.textContent = textContent;
    this.attributes = attributes;
    this.children = children;
  }
  getAttribute(name) { return this.attributes[name] ?? null; }
  closest() { return null; }
  querySelectorAll() { return []; }
  addEventListener() {}
  removeEventListener() {}
}
class FakeMediaElement extends FakeElement {
  constructor(options = {}) { super(options); this.paused = true; this.ended = false; }
}
class FakeMetaElement extends FakeElement {
  constructor(content) { super(); this.content = content; }
}
class FakeTitleElement extends FakeElement {}

function readDetectedTitle({ selectors = {}, jsonLd = [], documentTitle = 'Nonton The Odyssey (2026) / IDLIX' } = {}) {
  let messageListener;
  const document = {
    hidden: false,
    title: documentTitle,
    body: new FakeElement(),
    documentElement: new FakeElement(),
    querySelector(selector) { return selectors[selector] ?? null; },
    querySelectorAll(selector) {
      if (selector === 'script[type="application/ld+json"]') return jsonLd;
      return [];
    },
    addEventListener() {},
  };
  const window = {};
  window.top = window;
  const browser = {
    runtime: {
      onMessage: { addListener(listener) { messageListener = listener; } },
      sendMessage: async () => undefined,
    },
  };
  const context = {
    document,
    window,
    browser,
    location: { hostname: 'z2.idlixku.com' },
    Element: FakeElement,
    HTMLMediaElement: FakeMediaElement,
    HTMLMetaElement: FakeMetaElement,
    HTMLTitleElement: FakeTitleElement,
    MutationObserver: class { observe() {} disconnect() {} },
    Set,
    WeakSet,
    URL,
    console,
  };
  vm.runInNewContext(source, context, { filename: 'generic-media.js' });
  return messageListener({ type: 'GET_MEDIA_STATE' }).pageTitle;
}

test('prefers JSON-LD Movie.name over IDLix browser-gate headings', () => {
  const title = readDetectedTitle({
    selectors: {
      h1: new FakeElement({ textContent: 'Perbarui iOS untuk melanjutkan Update iOS to continue' }),
      'meta[property="og:title"]': new FakeMetaElement('Nonton The Odyssey (2026) / IDLIX'),
    },
    jsonLd: [new FakeElement({ textContent: JSON.stringify({ '@type': 'Movie', name: 'The Odyssey' }) })],
  });
  assert.equal(title, 'The Odyssey');
});

test('uses the Shaka video accessible title instead of IDLix error-gate headings', () => {
  const title = readDetectedTitle({
    selectors: {
      'video.shaka-video[aria-label]': new FakeMediaElement({ attributes: { 'aria-label': 'The Odyssey' } }),
      'article.sr-only h1': new FakeElement({ textContent: 'The Odyssey (2026)' }),
      h1: new FakeElement({ textContent: 'Halaman belum bisa dimuat sepenuhnya This page couldn’t load completely' }),
    },
  });
  assert.equal(title, 'The Odyssey');
});

test('uses the IDLix player title when JSON-LD and video labels are unavailable', () => {
  const title = readDetectedTitle({
    selectors: {
      '.hero-player-titlebar': new FakeElement({ textContent: 'The Odyssey (2026)' }),
      h1: new FakeElement({ textContent: 'Perbarui iOS untuk melanjutkan' }),
    },
  });
  assert.equal(title, 'The Odyssey (2026)');
});

test('cleans the IDLix document-title fallback into a film title', () => {
  const title = readDetectedTitle({ selectors: {}, jsonLd: [], documentTitle: 'Nonton The Odyssey (2026) / IDLIX' });
  assert.equal(title, 'The Odyssey (2026)');
});
