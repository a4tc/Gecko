import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, access } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const manifest = JSON.parse(await readFile(path.join(root, 'extension/manifest.json'), 'utf8'));

test('targets Gecko MV2 with a non-persistent event page', () => {
  assert.equal(manifest.manifest_version, 2);
  assert.equal(manifest.background.persistent, false);
  assert.equal(manifest.background.type, 'module');
  assert.ok(manifest.browser_specific_settings.gecko.strict_min_version);
  assert.ok(manifest.browser_specific_settings.gecko_android.strict_min_version);
});

test('keeps broad website access optional and retains the local bridge permission', async () => {
  assert.equal(manifest.permissions.includes('<all_urls>'), false);
  assert.ok(manifest.permissions.includes('http://127.0.0.1:32145/*'));
  assert.deepEqual(manifest.content_scripts[0].matches, [
    'https://www.youtube.com/*',
    'https://m.youtube.com/*',
  ]);
  assert.deepEqual(manifest.optional_permissions, ['http://*/*', 'https://*/*']);
  await access(path.join(root, 'extension', 'content/generic-media.js'));
});

test('exposes an extension-owned options page for local pairing', async () => {
  assert.equal(manifest.options_ui.page, 'options.html');
  assert.equal(manifest.options_ui.open_in_tab, true);
  await access(path.join(root, 'extension', manifest.options_ui.page));
  await access(path.join(root, 'extension', 'options.css'));
  await access(path.join(root, 'extension', 'options.js'));
});

test('all manifest-referenced scripts exist in the built extension', async () => {
  const scripts = [
    ...manifest.background.scripts,
    ...manifest.content_scripts.flatMap((entry) => entry.js),
  ];

  for (const script of scripts) {
    await access(path.join(root, 'extension', script));
  }
});

test('options page exposes separate Activity Preview, Developer Lab and Custom Presence Studio panels', async () => {
  const html = await readFile(new URL('../extension/options.html', import.meta.url), 'utf8');
  for (const id of ['preview-heading', 'lab-heading', 'studio-heading', 'preview-artwork', 'lab-export', 'manual-preset-store', 'manual-preset-apply']) {
    assert.match(html, new RegExp(`id=["']${id}["']`));
  }
});
