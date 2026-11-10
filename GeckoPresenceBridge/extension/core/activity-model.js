import { displayNameForHost, isSensitiveHost } from './activity.js';
const CONTROL = /[\u0000-\u001f\u007f]/g;
const SPACE = /\s+/g;
const MEDIA_STATES = new Set(['playing', 'paused', 'ended', 'unknown']);
const ACTIVITY_TYPES = new Set(['Playing', 'Watching', 'Listening', 'Streaming', 'Competing']);
const SOURCE_TYPES = new Set(['website', 'android-app', 'manual']);
function cleanText(value, maximum) {
    if (typeof value !== 'string')
        return '';
    return value.replace(CONTROL, ' ').replace(SPACE, ' ').trim().slice(0, maximum);
}
function normalizeSource(value, sourceType) {
    const raw = cleanText(value, 300).toLowerCase();
    if (sourceType === 'android-app') {
        if (!/^[a-z][a-z0-9_]*(?:\.[a-z0-9_]+)+$/.test(raw))
            throw new Error('Invalid source package name');
        return raw;
    }
    const host = raw.replace(/^www\./, '').replace(/\.$/, '');
    if (!/^[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?$/.test(host) || host.includes('..')) {
        throw new Error('Invalid source host');
    }
    if (isSensitiveHost(host))
        throw new Error('Sensitive sources cannot publish activity');
    return host;
}
function safeImage(value) {
    if (!value || typeof value !== 'object')
        return undefined;
    const asset = value;
    const url = cleanText(asset.url, 1000);
    let safeUrl;
    if (url) {
        try {
            const parsed = new URL(url);
            if (parsed.protocol !== 'https:')
                throw new Error('Unsafe image URL');
            safeUrl = parsed.toString();
        }
        catch {
            throw new Error('Invalid image URL');
        }
    }
    const text = cleanText(asset.text, 120);
    return safeUrl || text ? { ...(safeUrl ? { url: safeUrl } : {}), ...(text ? { text } : {}) } : undefined;
}
export function normalizeUnifiedActivity(input) {
    if (!input || typeof input !== 'object')
        throw new Error('Activity must be an object');
    const raw = input;
    if (raw.schemaVersion !== 2)
        throw new Error('Unsupported activity schema version');
    const sourceType = SOURCE_TYPES.has(raw.sourceType) ? raw.sourceType : 'website';
    const source = normalizeSource(raw.source, sourceType);
    const activityType = ACTIVITY_TYPES.has(raw.activityType) ? raw.activityType : 'Playing';
    const mediaState = MEDIA_STATES.has(raw.mediaState) ? raw.mediaState : 'unknown';
    const details = cleanText(raw.details, 120) || (mediaState === 'playing' ? 'Watching' : 'Browsing');
    const state = cleanText(raw.state, 120);
    const name = cleanText(raw.name, 80) || (sourceType === 'website' ? displayNameForHost(source) : source);
    const id = cleanText(raw.id, 140) || `${sourceType}:${source}`;
    const now = Number.isFinite(raw.updatedAt) ? Number(raw.updatedAt) : Date.now();
    let timestamps;
    if (raw.timestamps && typeof raw.timestamps === 'object') {
        const ts = raw.timestamps;
        const startMs = Number.isFinite(ts.startMs) && Number(ts.startMs) > 0 ? Number(ts.startMs) : undefined;
        const endMs = Number.isFinite(ts.endMs) && Number(ts.endMs) > 0 ? Number(ts.endMs) : undefined;
        timestamps = startMs || endMs ? { ...(startMs ? { startMs } : {}), ...(endMs ? { endMs } : {}) } : undefined;
    }
    let assets;
    if (raw.assets && typeof raw.assets === 'object') {
        const a = raw.assets;
        const large = safeImage(a.large);
        const small = safeImage(a.small);
        assets = large || small ? { ...(large ? { large } : {}), ...(small ? { small } : {}) } : undefined;
    }
    const buttons = [];
    if (Array.isArray(raw.buttons)) {
        for (const value of raw.buttons.slice(0, 2)) {
            if (!value || typeof value !== 'object')
                continue;
            const button = value;
            const label = cleanText(button.label, 32);
            const url = cleanText(button.url, 1000);
            if (!label || !url)
                continue;
            try {
                const parsed = new URL(url);
                if (parsed.protocol === 'https:')
                    buttons.push({ label, url: parsed.toString() });
            }
            catch { /* Ignore malformed and non-HTTPS links. */ }
        }
    }
    const metadata = {};
    if (raw.metadata && typeof raw.metadata === 'object') {
        for (const [key, value] of Object.entries(raw.metadata).slice(0, 24)) {
            if (!/^[a-zA-Z][a-zA-Z0-9_]{0,39}$/.test(key))
                continue;
            if (typeof value === 'string')
                metadata[key] = cleanText(value, 120);
            else if (typeof value === 'number' && Number.isFinite(value))
                metadata[key] = value;
            else if (typeof value === 'boolean' || value === null)
                metadata[key] = value;
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
export function fromLegacyActivity(input) {
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
export function toBridgePayloadV2(activity) {
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
