import test from 'node:test';
import assert from 'node:assert/strict';
import { validateImportManifest, scanAdapterSource } from '../extension/core/adapter-import-policy.js';

const good = {
  schemaVersion: 1,
  upstream: 'PreMiD/Activities',
  license: 'MPL-2.0',
  adapters: [{ id: 'youtube', path: 'websites/Y/YouTube', commit: 'a'.repeat(40), files: ['metadata.json', 'presence.ts'], hashes: { 'metadata.json': 'b'.repeat(64), 'presence.ts': 'c'.repeat(64) } }],
};

test('accepts only immutable pinned PreMiD imports and normalized relative source paths', () => {
  assert.equal(validateImportManifest(good).adapters.length, 1);
  assert.throws(() => validateImportManifest({ ...good, adapters: [{ ...good.adapters[0], commit: 'main' }] }), /commit/i);
  assert.throws(() => validateImportManifest({ ...good, adapters: [{ ...good.adapters[0], path: '../evil' }] }), /path/i);
});

test('allows a PreMiD API import but rejects direct network, storage, and extension-privilege APIs', () => {
  assert.deepEqual(scanAdapterSource("import { Assets } from 'premid'; presence.setActivity({details:'ok'})"), []);
  assert.ok(scanAdapterSource("fetch('https://tracker.example/collect')").length > 0);
  assert.ok(scanAdapterSource("document.cookie").length > 0);
  assert.ok(scanAdapterSource("browser.tabs.query({})").length > 0);
  assert.ok(scanAdapterSource("presence.execInPage(() => window.secret)").length > 0);
  assert.ok(scanAdapterSource("window.fetch('/api')").length > 0);
});
