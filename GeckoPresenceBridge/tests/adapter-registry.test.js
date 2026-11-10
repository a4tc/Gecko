import test from 'node:test';
import assert from 'node:assert/strict';
import { AdapterRegistry } from '../extension/core/adapter-registry.js';

const adapters = [
  { id: 'generic-media', kind: 'native', enabled: true, priority: 10, hosts: ['*'] },
  { id: 'premid-youtube', kind: 'premid', enabled: true, priority: 50, hosts: ['youtube.com', '*.youtube.com'] },
  { id: 'idlix-native', kind: 'native', enabled: true, priority: 90, hosts: ['idlixku.com', '*.idlixku.com', '*.idlixvip.asia'] },
  { id: 'disabled', kind: 'native', enabled: false, priority: 100, hosts: ['example.com'] },
];

test('resolves the highest-priority adapter for a host', () => {
  const registry = new AdapterRegistry(adapters);
  assert.equal(registry.resolve('z2.idlixku.com').id, 'idlix-native');
  assert.equal(registry.resolve('m.youtube.com').id, 'premid-youtube');
  assert.equal(registry.resolve('unrelated.example').id, 'generic-media');
});

test('does not select disabled adapters or match suffix lookalikes', () => {
  const registry = new AdapterRegistry(adapters);
  assert.equal(registry.resolve('example.com').id, 'generic-media');
  assert.equal(registry.resolve('notidlixku.com').id, 'generic-media');
});

test('rejects sensitive hosts even when an adapter claims them', () => {
  const registry = new AdapterRegistry([{ id: 'bad', kind: 'native', enabled: true, priority: 999, hosts: ['*'] }]);
  assert.equal(registry.resolve('bank.example'), null);
  assert.equal(registry.resolve('login.microsoftonline.com'), null);
});

test('returns all matching adapters so native logic can fall back to imported compatibility logic', () => {
  const registry = new AdapterRegistry([
    { id: 'native-idlix', kind: 'native', enabled: true, priority: 90, hosts: ['*.idlixvip.asia'] },
    { id: 'premid-idlix', kind: 'premid', enabled: true, priority: 40, hosts: ['*.idlixvip.asia'] },
  ]);
  assert.deepEqual(registry.resolveAll('vip2.idlixvip.asia').map((item) => item.id), ['native-idlix', 'premid-idlix']);
});
