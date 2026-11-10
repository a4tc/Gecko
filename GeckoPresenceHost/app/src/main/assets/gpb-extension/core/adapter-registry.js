import { isSensitiveHost } from './activity.js';
function normalizeHost(host) {
    const normalized = host.trim().toLowerCase().replace(/^www\./, '').replace(/\.$/, '');
    if (!normalized || normalized.length > 253 || !/^[a-z0-9.-]+$/.test(normalized) || normalized.includes('..'))
        return null;
    return normalized;
}
function matchHost(host, pattern) {
    if (pattern === '*')
        return true;
    const normalizedPattern = pattern.trim().toLowerCase().replace(/^www\./, '').replace(/\.$/, '');
    if (normalizedPattern.startsWith('*.')) {
        const suffix = normalizedPattern.slice(2);
        return host === suffix || host.endsWith(`.${suffix}`);
    }
    return host === normalizedPattern;
}
export class AdapterRegistry {
    adapters;
    constructor(adapters) {
        this.adapters = adapters.map((adapter) => ({
            ...adapter,
            hosts: [...adapter.hosts],
            priority: Math.max(0, Math.min(100, Number(adapter.priority) || 0)),
        }));
    }
    resolveAll(rawHost) {
        const host = normalizeHost(rawHost);
        if (!host || isSensitiveHost(host))
            return [];
        return this.adapters
            .filter((adapter) => adapter.enabled && adapter.hosts.some((pattern) => matchHost(host, pattern)))
            .sort((left, right) => right.priority - left.priority || (left.kind === 'native' ? -1 : 1));
    }
    resolve(rawHost) {
        return this.resolveAll(rawHost)[0] ?? null;
    }
    list() {
        return this.adapters.map((adapter) => ({ ...adapter, hosts: [...adapter.hosts] }));
    }
}
export const BUILTIN_ADAPTERS = [
    { id: 'generic-media', kind: 'native', enabled: true, priority: 10, hosts: ['*'] },
    { id: 'idlix-native', kind: 'native', enabled: true, priority: 90, hosts: ['idlixku.com', '*.idlixku.com', '*.idlixvip.asia', '*.idlix.*'] },
];
