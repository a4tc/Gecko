function permissionPattern(protocol: string, hostname: string): string {
  const normalized = hostname.toLowerCase().replace(/^www\./, '').replace(/\.$/, '');
  const isIpLiteral = /^\d{1,3}(?:\.\d{1,3}){3}$/.test(normalized) || normalized.startsWith('[');
  const wildcardHost = isIpLiteral || normalized === 'localhost' || normalized.endsWith('.localhost')
    ? normalized
    : `*.${normalized}`;
  return `${protocol}//${wildcardHost}/*`;
}

export function normalizeOriginPattern(raw: string): string {
  const trimmed = raw.trim();
  if (!trimmed) throw new Error('Enter a website domain first.');
  const parsed = new URL(/^[a-z][a-z0-9+.-]*:\/\//i.test(trimmed) ? trimmed : `https://${trimmed}`);
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    throw new Error('Only HTTP and HTTPS websites are supported.');
  }
  if (!parsed.hostname || parsed.username || parsed.password) {
    throw new Error('Enter a valid domain, without login credentials.');
  }
  const hostname = parsed.hostname.toLowerCase().replace(/^www\./, '').replace(/\.$/, '');
  if (!hostname || hostname === 'localhost' || hostname.endsWith('.localhost')) {
    throw new Error('Localhost websites do not need browser media permissions.');
  }
  return permissionPattern(parsed.protocol, hostname);
}

export function permissionPatternForUrl(rawUrl: string): string | null {
  try {
    const parsed = new URL(rawUrl);
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return null;
    if (!parsed.hostname) return null;
    return permissionPattern(parsed.protocol, parsed.hostname);
  } catch {
    return null;
  }
}

export function isOriginPattern(value: unknown): value is string {
  if (typeof value !== 'string' || !value.endsWith('/*')) return false;
  const match = /^(https?):\/\/(\*\.)?([a-z0-9.-]+|\[[0-9a-f:]+\])\/\*$/i.exec(value);
  if (!match) return false;
  const host = match[3].toLowerCase();
  if (host.startsWith('.') || host.endsWith('.') || host.includes('..')) return false;
  try {
    const parsed = new URL(`${match[1]}://${host}/`);
    return Boolean(parsed.hostname);
  } catch {
    return false;
  }
}
