import test from 'node:test';
import assert from 'node:assert/strict';
import { isOriginPattern, normalizeOriginPattern, permissionPatternForUrl } from '../extension/core/site-permissions.js';

test('normalizes one explicit domain into a subdomain-aware permission', () => {
  assert.equal(normalizeOriginPattern('https://www.idlixplus.net/movie/demo'), 'https://*.idlixplus.net/*');
  assert.equal(normalizeOriginPattern('example.net/path?x=secret'), 'https://*.example.net/*');
});

test('preserves HTTP scheme and does not wildcard IP addresses', () => {
  assert.equal(normalizeOriginPattern('http://192.0.2.12:8080/watch'), 'http://192.0.2.12/*');
  assert.equal(permissionPatternForUrl('https://www.idlixplus.net/movie/secret'), 'https://*.idlixplus.net/*');
});

test('rejects unsupported schemes, credentials, and local hosts', () => {
  assert.throws(() => normalizeOriginPattern('ftp://example.com'));
  assert.throws(() => normalizeOriginPattern('https://user:pass@example.com'));
  assert.throws(() => normalizeOriginPattern('localhost:8000'));
  assert.equal(permissionPatternForUrl('file:///storage/private.txt'), null);
});

test('validates permission patterns before storing or revoking them', () => {
  assert.equal(isOriginPattern('https://*.example.net/*'), true);
  assert.equal(isOriginPattern('https://example.net/*'), true);
  assert.equal(isOriginPattern('https://*/*'), false);
  assert.equal(isOriginPattern('file:///private/*'), false);
  assert.equal(isOriginPattern('https://example..net/*'), false);
});
