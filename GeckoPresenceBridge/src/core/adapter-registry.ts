import { isSensitiveHost } from './activity.js';

export type AdapterKind = 'native' | 'premid';
export interface AdapterDescriptor {
  id: string;
  kind: AdapterKind;
  enabled: boolean;
  priority: number;
  hosts: string[];
  version?: string;
  runtimePath?: string;
  iframePath?: string;
  configPath?: string;
}

function normalizeHost(host: string): string | null {
  const normalized = host.trim().toLowerCase().replace(/^www\./, '').replace(/\.$/, '');
  if (!normalized || normalized.length > 253 || !/^[a-z0-9.-]+$/.test(normalized) || normalized.includes('..')) return null;
  return normalized;
}

function matchHost(host: string, pattern: string): boolean {
  if (pattern === '*') return true;
  const normalizedPattern = pattern.trim().toLowerCase().replace(/^www\./, '').replace(/\.$/, '');
  if (normalizedPattern.startsWith('*.')) {
    const suffix = normalizedPattern.slice(2);
    return host === suffix || host.endsWith(`.${suffix}`);
  }
  return host === normalizedPattern;
}

export class AdapterRegistry {
  private readonly adapters: AdapterDescriptor[];

  constructor(adapters: AdapterDescriptor[]) {
    this.adapters = adapters.map((adapter) => ({
      ...adapter,
      hosts: [...adapter.hosts],
      priority: Math.max(0, Math.min(100, Number(adapter.priority) || 0)),
    }));
  }

  resolveAll(rawHost: string): AdapterDescriptor[] {
    const host = normalizeHost(rawHost);
    if (!host || isSensitiveHost(host)) return [];
    return this.adapters
      .filter((adapter) => adapter.enabled && adapter.hosts.some((pattern) => matchHost(host, pattern)))
      .sort((left, right) => right.priority - left.priority || (left.kind === 'native' ? -1 : 1));
  }

  resolve(rawHost: string): AdapterDescriptor | null {
    return this.resolveAll(rawHost)[0] ?? null;
  }

  list(): AdapterDescriptor[] {
    return this.adapters.map((adapter) => ({ ...adapter, hosts: [...adapter.hosts] }));
  }
}

export const BUILTIN_ADAPTERS: AdapterDescriptor[] = [
  { id: 'generic-media', kind: 'native', enabled: true, priority: 10, hosts: ['*'] },
  { id: 'idlix-native', kind: 'native', enabled: true, priority: 90, hosts: ['idlixku.com', '*.idlixku.com', '*.idlixvip.asia', '*.idlix.*'] },
];
