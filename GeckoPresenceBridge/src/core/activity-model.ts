import { displayNameForHost, isSensitiveHost, type MediaState } from './activity.js';

export type UnifiedActivityType = 'Playing' | 'Watching' | 'Listening' | 'Streaming' | 'Competing';
export type ActivitySourceType = 'website' | 'android-app' | 'manual';

export interface ActivityAsset {
  url?: string;
  text?: string;
}

export interface UnifiedActivityV2 {
  schemaVersion: 2;
  id: string;
  source: string;
  sourceType: ActivitySourceType;
  activityType: UnifiedActivityType;
  name: string;
  details: string;
  state?: string;
  mediaState: MediaState;
  timestamps?: { startMs?: number; endMs?: number };
  assets?: { large?: ActivityAsset; small?: ActivityAsset };
  buttons?: Array<{ label: string; url: string }>;
  metadata?: Record<string, string | number | boolean | null>;
  priority: number;
  confidence: number;
  updatedAt: number;
}

const CONTROL = /[\u0000-\u001f\u007f]/g;
const SPACE = /\s+/g;
const MEDIA_STATES = new Set<MediaState>(['playing', 'paused', 'ended', 'unknown']);
const ACTIVITY_TYPES = new Set<UnifiedActivityType>(['Playing', 'Watching', 'Listening', 'Streaming', 'Competing']);
const SOURCE_TYPES = new Set<ActivitySourceType>(['website', 'android-app', 'manual']);

function cleanText(value: unknown, maximum: number): string {
  if (typeof value !== 'string') return '';
  return value.replace(CONTROL, ' ').replace(SPACE, ' ').trim().slice(0, maximum);
}

function normalizeSource(value: unknown, sourceType: ActivitySourceType): string {
  const raw = cleanText(value, 300).toLowerCase();
  if (sourceType === 'android-app') {
    if (!/^[a-z][a-z0-9_]*(?:\.[a-z0-9_]+)+$/.test(raw)) throw new Error('Invalid source package name');
    return raw;
  }
  const host = raw.replace(/^www\./, '').replace(/\.$/, '');
  if (!/^[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?$/.test(host) || host.includes('..')) {
    throw new Error('Invalid source host');
  }
  if (isSensitiveHost(host)) throw new Error('Sensitive sources cannot publish activity');
  return host;
}

function safeImage(value: unknown): ActivityAsset | undefined {
  if (!value || typeof value !== 'object') return undefined;
  const asset = value as Record<string, unknown>;
  const url = cleanText(asset.url, 1000);
  let safeUrl: string | undefined;
  if (url) {
    try {
      const parsed = new URL(url);
      if (parsed.protocol !== 'https:') throw new Error('Unsafe image URL');
      safeUrl = parsed.toString();
    } catch {
      throw new Error('Invalid image URL');
    }
  }
  const text = cleanText(asset.text, 120);
  return safeUrl || text ? { ...(safeUrl ? { url: safeUrl } : {}), ...(text ? { text } : {}) } : undefined;
}

export function normalizeUnifiedActivity(input: unknown): UnifiedActivityV2 {
  if (!input || typeof input !== 'object') throw new Error('Activity must be an object');
  const raw = input as Record<string, unknown>;
  if (raw.schemaVersion !== 2) throw new Error('Unsupported activity schema version');
  const sourceType = SOURCE_TYPES.has(raw.sourceType as ActivitySourceType) ? raw.sourceType as ActivitySourceType : 'website';
  const source = normalizeSource(raw.source, sourceType);
  const activityType = ACTIVITY_TYPES.has(raw.activityType as UnifiedActivityType) ? raw.activityType as UnifiedActivityType : 'Playing';
  const mediaState = MEDIA_STATES.has(raw.mediaState as MediaState) ? raw.mediaState as MediaState : 'unknown';
  const details = cleanText(raw.details, 120) || (mediaState === 'playing' ? 'Watching' : 'Browsing');
  const state = cleanText(raw.state, 120);
  const name = cleanText(raw.name, 80) || (sourceType === 'website' ? displayNameForHost(source) : source);
  const id = cleanText(raw.id, 140) || `${sourceType}:${source}`;
  const now = Number.isFinite(raw.updatedAt) ? Number(raw.updatedAt) : Date.now();

  let timestamps: UnifiedActivityV2['timestamps'];
  if (raw.timestamps && typeof raw.timestamps === 'object') {
    const ts = raw.timestamps as Record<string, unknown>;
    const startMs = Number.isFinite(ts.startMs) && Number(ts.startMs) > 0 ? Number(ts.startMs) : undefined;
    const endMs = Number.isFinite(ts.endMs) && Number(ts.endMs) > 0 ? Number(ts.endMs) : undefined;
    timestamps = startMs || endMs ? { ...(startMs ? { startMs } : {}), ...(endMs ? { endMs } : {}) } : undefined;
  }

  let assets: UnifiedActivityV2['assets'];
  if (raw.assets && typeof raw.assets === 'object') {
    const a = raw.assets as Record<string, unknown>;
    const large = safeImage(a.large);
    const small = safeImage(a.small);
    assets = large || small ? { ...(large ? { large } : {}), ...(small ? { small } : {}) } : undefined;
  }

  const buttons: Array<{ label: string; url: string }> = [];
  if (Array.isArray(raw.buttons)) {
    for (const value of raw.buttons.slice(0, 2)) {
      if (!value || typeof value !== 'object') continue;
      const button = value as Record<string, unknown>;
      const label = cleanText(button.label, 32);
      const url = cleanText(button.url, 1000);
      if (!label || !url) continue;
      try {
        const parsed = new URL(url);
        if (parsed.protocol === 'https:') buttons.push({ label, url: parsed.toString() });
      } catch { /* Ignore malformed and non-HTTPS links. */ }
    }
  }

  const metadata: Record<string, string | number | boolean | null> = {};
  if (raw.metadata && typeof raw.metadata === 'object') {
    for (const [key, value] of Object.entries(raw.metadata as Record<string, unknown>).slice(0, 24)) {
      if (!/^[a-zA-Z][a-zA-Z0-9_]{0,39}$/.test(key)) continue;
      if (typeof value === 'string') metadata[key] = cleanText(value, 120);
      else if (typeof value === 'number' && Number.isFinite(value)) metadata[key] = value;
      else if (typeof value === 'boolean' || value === null) metadata[key] = value;
    }
  }

  const priority = Number.isFinite(raw.priority) ? Math.max(0, Math.min(100, Number(raw.priority))) : 10;
  const confidence = Number.isFinite(raw.confidence) ? Math.max(0, Math.min(1, Number(raw.confidence))) : 0.5;
  return {
    schemaVersion: 2,
    id,
    source,
    sourceType,
    activityType,
    name,
    details,
    ...(state ? { state } : {}),
    mediaState,
    ...(timestamps ? { timestamps } : {}),
    ...(assets ? { assets } : {}),
    ...(Array.isArray(raw.buttons) ? { buttons } : buttons.length ? { buttons } : {}),
    ...(Object.keys(metadata).length ? { metadata } : {}),
    priority,
    confidence,
    updatedAt: now,
  };
}

export function fromLegacyActivity(input: {
  source: string;
  details?: string;
  pageTitle?: string;
  mediaState?: MediaState;
  occurredAt?: number;
}): UnifiedActivityV2 {
  const source = input.source.toLowerCase().replace(/^www\./, '');
  const mediaState = input.mediaState ?? 'unknown';
  const knownName = displayNameForHost(source);
  const isMusic = /spotify|music\.youtube/i.test(source);
  const details = mediaState === 'unknown'
    ? (input.details || 'Browsing')
    : (input.pageTitle || (mediaState === 'playing' ? (isMusic ? 'Listening' : 'Watching') : mediaState === 'paused' ? 'Paused' : 'Finished watching'));
  return normalizeUnifiedActivity({
    schemaVersion: 2,
    id: `website:${source}`,
    source,
    sourceType: 'website',
    activityType: isMusic ? 'Listening' : 'Watching',
    name: knownName,
    details,
    state: mediaState === 'unknown' ? undefined : (input.pageTitle ? input.details : undefined),
    mediaState,
    priority: 10,
    confidence: input.pageTitle ? 0.65 : 0.4,
    updatedAt: input.occurredAt ?? Date.now(),
  });
}

export function toBridgePayloadV2(activity: UnifiedActivityV2): Record<string, unknown> {
  const episodeState = [
    typeof activity.metadata?.seasonNumber === 'number' ? `Season ${activity.metadata.seasonNumber}` : '',
    typeof activity.metadata?.episodeNumber === 'number' ? `Episode ${activity.metadata.episodeNumber}` : '',
  ].filter(Boolean).join(' · ');
  const episodeTitle = typeof activity.metadata?.episodeTitle === 'string' ? activity.metadata.episodeTitle : '';
  const episodeLine = [episodeState, episodeTitle].filter(Boolean).join(' — ');
  const state = episodeLine || activity.state || (activity.mediaState === 'playing' ? 'Playing' : activity.mediaState === 'paused' ? 'Paused' : activity.mediaState === 'ended' ? 'Finished' : 'Browsing');
  const startTimestamp = activity.timestamps?.startMs
    ? Math.floor(activity.timestamps.startMs / 1000)
    : Math.floor(activity.updatedAt / 1000);
  const action = activity.mediaState === 'playing' ? (activity.activityType === 'Listening' ? 'Listening' : activity.activityType === 'Playing' ? 'Playing' : 'Watching')
    : activity.mediaState === 'paused' ? 'Paused'
      : activity.mediaState === 'ended' ? 'Finished' : 'Browsing';
  return {
    schemaVersion: 2,
    activityId: activity.id,
    source: activity.source,
    sourceType: activity.sourceType,
    priority: activity.priority,
    name: activity.name,
    details: activity.details,
    contentTitle: activity.details,
    state: episodeLine || activity.state || action,
    mediaState: activity.mediaState,
    startTimestamp,
    activityType: activity.activityType,
    largeImageUrl: activity.assets?.large?.url,
    largeImageText: activity.assets?.large?.text,
    smallImageUrl: activity.assets?.small?.url,
    smallImageText: activity.assets?.small?.text,
  };
}
