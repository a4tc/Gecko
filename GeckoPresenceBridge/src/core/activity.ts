export type MediaState = 'playing' | 'paused' | 'ended' | 'unknown';

export type ActivityEvent = {
  kind: 'activity.update';
  source: string;
  details: string;
  state: string;
  mediaState: MediaState;
  occurredAt: number;
  pageTitle?: string;
};

const MEDIA_STATES = new Set<MediaState>(['playing', 'paused', 'ended', 'unknown']);
const SENSITIVE_HOSTS = [
  'bca.co.id', 'klikbca.com', 'mybca.bca.co.id', 'bankmandiri.co.id', 'bmri.co.id',
  'bni.co.id', 'bri.co.id', 'btn.co.id', 'danamon.co.id', 'cimbniaga.co.id',
  'ocbc.id', 'permatabank.com', 'dbs.id', 'seabank.co.id', 'jago.com', 'bsi.co.id',
  'paypal.com', 'wise.com', 'stripe.com', 'dana.id', 'ovo.id', 'gopay.co.id', 'gmail.com', 'outlook.com', 'proton.me', 'web.whatsapp.com', 'web.telegram.org', 'signal.org', 'discord.com',
  'linkaja.id', 'shopeepay.co.id', 'doku.com', 'midtrans.com', 'jenius.com', 'bankjago.com', 'blubybcadigital.id', 'bankbsi.co.id', 'bankaltimtara.co.id', 'bankbjb.co.id', 'livin.id', 'bibit.id', 'ajaib.co.id', 'bareksa.com', 'stockbit.com', 'pluang.com', 'indodax.com', 'pintu.co.id', 'binance.com', 'bybit.com', 'coinbase.com',
];
const SENSITIVE_HOST_LABELS = /(^|[.-])(bank|banking|finance|fintech|wallet|payment|pay|invest|trading|broker|crypto|exchange|auth|authenticator|otp|password|passkey|health|medical|clinic|hospital|insurance|government|gov|tax|identity|mail|email|webmail|messaging|messages|whatsapp|telegram|signal|discord|slack|teams|contacts|sms|phone|account|accounts|login|signin|checkout|billing|security|dukcapil|passport|imigrasi|ktp|pharmacy|apotek|medicine|bca|bni|bri|mandiri|seabank|jago|livin|dana|ovo|gopay|bibit|ajaib|bareksa|stockbit|pluang|indodax|pintu|binance|bybit|coinbase|bpjs|jkn|satusehat|credit|loan)([.-]|$)/i;

export function isSensitiveHost(host: string): boolean {
  const normalized = host.toLowerCase().replace(/^www\./, '').replace(/\.$/, '');
  if (SENSITIVE_HOSTS.some((blocked) => normalized === blocked || normalized.endsWith(`.${blocked}`))) return true;
  return SENSITIVE_HOST_LABELS.test(normalized);
}

export function selectActiveTab<T extends { id?: number; active?: boolean }>(tabs: T[]): T | null {
  const activeTabs = tabs.filter((tab) => tab && tab.active === true && Number.isInteger(tab.id));
  return activeTabs.length === 1 ? activeTabs[0] : null;
}

export function isMessageFromActiveTab(activeTabId: number | undefined, senderTabId: number): boolean {
  return typeof activeTabId === 'number' && activeTabId === senderTabId;
}

export function inferMediaState(ended: boolean, paused: boolean, hasPlayed: boolean): MediaState {
  if (ended) return 'ended';
  if (!paused) return 'playing';
  return hasPlayed ? 'paused' : 'unknown';
}

function normalizeMediaState(value: unknown): MediaState {
  return MEDIA_STATES.has(value as MediaState) ? value as MediaState : 'unknown';
}

function normalizeHost(rawUrl?: string): string | null {
  if (!rawUrl) return null;
  try {
    const parsed = new URL(rawUrl);
    if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') return null;
    const hostname = parsed.hostname.toLowerCase().replace(/\.$/, '').replace(/^www\./, '');
    return hostname || null;
  } catch {
    return null;
  }
}

function sanitizeTitle(value?: string, host?: string): string | undefined {
  if (!value) return undefined;
  let cleaned = value.replace(/[\u0000-\u001f\u007f]/g, ' ').replace(/\s+/g, ' ').trim();
  const brandByHost: Array<[RegExp, string]> = [
    [/idlix/i, 'idlix(?:plus)?(?:\\.[a-z.]+)?'],
    [/(?:^|\.)youtube\.com$/i, '(?:youtube|youtube music)'],
    [/(?:^|\.)netflix\.com$/i, 'netflix'],
    [/(?:^|\.)spotify\.com$/i, 'spotify'],
    [/(?:^|\.)twitch\.tv$/i, 'twitch'],
    [/(?:^|\.)tiktok\.com$/i, 'tiktok'],
    [/(?:^|\.)instagram\.com$/i, 'instagram'],
  ];
  const brandedSuffix = brandByHost.find(([pattern]) => pattern.test(host ?? ''))?.[1]
    ?? 'idlix(?:plus)?(?:\\.[a-z.]+)?|watch online|streaming online';
  cleaned = cleaned.replace(new RegExp(`\\s*(?:[-|•·:]|—)\\s*(?:${brandedSuffix})\\s*$`, 'i'), '').trim();
  if (!cleaned || /^(?:idlix|home|watch online|movie|movies|video)$/i.test(cleaned)) return undefined;
  return cleaned.slice(0, 120);
}

export function displayNameForHost(source: string): string {
  const host = source.toLowerCase().replace(/^www\./, '');
  const known: Array<[RegExp, string]> = [
    [/^music\.youtube\.com$/, 'YouTube Music'],
    [/^(?:m\.|www\.)?youtube\.com$|^youtu\.be$/, 'YouTube'],
    [/(^|\.)twitch\.tv$/, 'Twitch'],
    [/(^|\.)netflix\.com$/, 'Netflix'],
    [/(^|\.)spotify\.com$/, 'Spotify'],
    [/(^|\.)instagram\.com$/, 'Instagram'],
    [/(^|\.)tiktok\.com$/, 'TikTok'],
    [/(^|\.)chess\.com$/, 'Chess.com'],
    [/(^|\.)reddit\.com$/, 'Reddit'],
    [/(^|\.)github\.com$/, 'GitHub'],
    [/(^|\.)x\.com$/, 'X'],
    [/idlix/i, 'IDLix'],
  ];
  for (const [pattern, name] of known) if (pattern.test(host)) return name;
  const firstLabel = host.split('.')[0] ?? host;
  return firstLabel ? firstLabel.charAt(0).toUpperCase() + firstLabel.slice(1) : host;
}

function activityDetails(source: string, mediaState: MediaState, rawUrl?: string): string {
  const host = source.toLowerCase();
  if (host === 'chess.com' || host.endsWith('.chess.com')) {
    try {
      const path = new URL(rawUrl ?? '').pathname;
      if (/\/(?:live|play|game)\//i.test(path)) return 'Playing chess';
    } catch { /* Keep the safe generic label. */ }
  }
  if (mediaState === 'paused') return 'Paused';
  if (mediaState === 'ended') return 'Finished watching';
  if (mediaState !== 'playing') return 'Browsing';
  if (host === 'spotify.com' || host.endsWith('.spotify.com') || host === 'music.youtube.com') return 'Listening';
  if (host === 'tiktok.com' || host.endsWith('.tiktok.com')) return 'Watching TikTok';
  if (host === 'instagram.com' || host.endsWith('.instagram.com')) return 'Watching Reels';
  return 'Watching';
}

export function createActivityEvent(
  tab: { id?: number; active?: boolean; url?: string; title?: string } | null,
  rawMediaState: unknown = 'unknown',
  options: { includeTitle?: boolean; mediaTitle?: string; now?: number } = {},
): ActivityEvent | null {
  if (!tab || tab.active !== true) return null;
  const source = normalizeHost(tab.url);
  if (!source || isSensitiveHost(source)) return null;
  const mediaState = normalizeMediaState(rawMediaState);
  const occurredAt = Number.isFinite(options.now) && (options.now ?? -1) >= 0 ? options.now! : Date.now();
  const event: ActivityEvent = {
    kind: 'activity.update',
    source,
    details: activityDetails(source, mediaState, tab.url),
    state: source,
    mediaState,
    occurredAt,
  };
  // Titles are emitted only while real media is playing, paused or just ended.
  // The Android host remains the final policy gate for sharing the title to Discord.
  if (options.includeTitle && mediaState !== 'unknown') {
    const pageTitle = sanitizeTitle(options.mediaTitle ?? tab.title, source);
    if (pageTitle) event.pageTitle = pageTitle;
  }
  return event;
}
