import { normalizeUnifiedActivity, type UnifiedActivityV2 } from '../core/activity-model.js';
import type { MediaState } from '../core/activity.js';

export interface IdlixAdapterOptions {
  mediaState?: MediaState;
  now?: number;
  durationSeconds?: number;
  currentTimeSeconds?: number;
}

export interface IdlixExtractedMetadata {
  title?: string;
  seriesTitle?: string;
  seasonNumber?: number;
  episodeNumber?: number;
  episodeTitle?: string;
  artworkUrl?: string;
  routeType: 'home' | 'movie' | 'series' | 'episode' | 'search' | 'browse';
}

function textOf(node: any): string {
  return typeof node?.textContent === 'string' ? node.textContent.replace(/\s+/g, ' ').trim() : '';
}

function attrOf(node: any, key: string): string {
  return typeof node?.getAttribute === 'function' ? String(node.getAttribute(key) ?? '').trim() : '';
}

function meta(document: any, key: string): string {
  return attrOf(document.querySelector(`meta[property="${key}"]`), 'content')
    || attrOf(document.querySelector(`meta[name="${key}"]`), 'content');
}

function cleanTitle(value: string): string {
  return value
    .replace(/[\u0000-\u001f\u007f]/g, ' ')
    .replace(/\s+/g, ' ')
    .replace(/^nonton\s+/i, '')
    .replace(/\s*(?:[-|•·:]|—)\s*(?:subtitle indonesia\s*)?idlix(?:plus)?(?:\.[a-z.]+)?\s*$/i, '')
    .replace(/\s*\/\s*idlix(?:plus)?(?:\.[a-z.]+)?\s*$/i, '')
    .trim()
    .slice(0, 120);
}

function jsonLdRecords(document: any): any[] {
  const output: any[] = [];
  const scripts = Array.from(document.querySelectorAll('script[type="application/ld+json"]') ?? []) as any[];
  const walk = (value: any): void => {
    if (Array.isArray(value)) { value.forEach(walk); return; }
    if (!value || typeof value !== 'object') return;
    output.push(value);
    if (Array.isArray(value['@graph'])) walk(value['@graph']);
    if (Array.isArray(value.itemListElement)) walk(value.itemListElement);
  };
  for (const script of scripts) {
    try { walk(JSON.parse(textOf(script))); } catch { /* Invalid JSON-LD is ignored. */ }
  }
  return output;
}

function parseNumber(source: string, patterns: RegExp[]): number | undefined {
  for (const pattern of patterns) {
    const match = source.match(pattern);
    const value = match?.[1] ? Number(match[1]) : NaN;
    if (Number.isInteger(value) && value >= 0 && value <= 999) return value;
  }
  return undefined;
}

function parseEpisodeDetails(value: string): Pick<IdlixExtractedMetadata, 'seasonNumber' | 'episodeNumber' | 'episodeTitle'> {
  const clean = value.replace(/[\[\]()]/g, ' ').replace(/\s+/g, ' ').trim();
  const seasonNumber = parseNumber(clean, [/(?:season|s)\s*0*(\d{1,3})/i]);
  const episodeNumber = parseNumber(clean, [/(?:episode|ep|e)\s*0*(\d{1,3})/i, /\bs\d{1,3}\s*e\s*0*(\d{1,3})\b/i]);
  let episodeTitle = clean
    .replace(/\bs\d{1,3}\s*e\s*\d{1,3}\b/ig, '')
    .replace(/(?:season|s)\s*\d{1,3}/ig, '')
    .replace(/(?:episode|ep|e)\s*\d{1,3}/ig, '')
    .replace(/^[\s\-–—:|·]+|[\s\-–—:|·]+$/g, '')
    .trim();
  if (!episodeTitle || /^(?:season|episode|ep|series)$/i.test(episodeTitle)) episodeTitle = '';
  return {
    ...(seasonNumber !== undefined ? { seasonNumber } : {}),
    ...(episodeNumber !== undefined ? { episodeNumber } : {}),
    ...(episodeTitle ? { episodeTitle: episodeTitle.slice(0, 120) } : {}),
  };
}

export function extractIdlixMetadata(document: any): IdlixExtractedMetadata {
  const pathname = String(document.location?.pathname ?? '/').toLowerCase();
  const segments = pathname.split('/').filter(Boolean);
  const route = segments[0] ?? '';
  const routeType: IdlixExtractedMetadata['routeType'] =
    route === 'movie' ? 'movie'
      : ['series', 'tvseries'].includes(route) ? 'series'
        : ['episode', 'episodes', 'watch'].includes(route) ? 'episode'
          : route === 'search' ? 'search'
            : pathname === '/' ? 'home' : 'browse';

  const records = jsonLdRecords(document);
  const episode = records.find((record) => {
    const rawType = record['@type'];
    return [rawType, ...(Array.isArray(rawType) ? rawType : [])].some((type) => typeof type === 'string' && /TVEpisode|Episode/i.test(type));
  });
  const series = records.find((record) => {
    const rawType = record['@type'];
    return [rawType, ...(Array.isArray(rawType) ? rawType : [])].some((type) => typeof type === 'string' && /TVSeries|Movie/i.test(type));
  });
  const movie = records.find((record) => {
    const rawType = record['@type'];
    return [rawType, ...(Array.isArray(rawType) ? rawType : [])].some((type) => typeof type === 'string' && /Movie/i.test(type));
  });

  const h1 = textOf(document.querySelector('.epih1'))
    || textOf(document.querySelector('.entry-title'))
    || textOf(document.querySelector('article h1'))
    || textOf(document.querySelector('main h1'));
  const episodeHeading = textOf(document.querySelector('.epih3'))
    || textOf(document.querySelector('.episode-title'))
    || textOf(document.querySelector('[data-episode-title]'))
    || textOf(document.querySelector('.pag_episodes a[title]'));
  const ogTitle = meta(document, 'og:title');
  const documentTitle = cleanTitle(String(document.title ?? ''));
  const jsonSeriesTitle = typeof episode?.partOfSeries?.name === 'string'
    ? episode.partOfSeries.name
    : typeof episode?.partOfSeason?.partOfSeries?.name === 'string' ? episode.partOfSeason.partOfSeries.name : '';
  const seasonValue = Number(episode?.partOfSeason?.seasonNumber ?? episode?.partOfSeason?.name?.match(/\d+/)?.[0]);
  const episodeValue = Number(episode?.episodeNumber ?? episode?.position);
  const seasonFromHeading = parseEpisodeDetails(episodeHeading || h1 || documentTitle);
  const seasonNumber = Number.isInteger(seasonValue) && seasonValue >= 0 && seasonValue <= 999 ? seasonValue : seasonFromHeading.seasonNumber;
  const episodeNumber = Number.isInteger(episodeValue) && episodeValue >= 0 && episodeValue <= 999 ? episodeValue : seasonFromHeading.episodeNumber;
  const seriesTitle = cleanTitle(jsonSeriesTitle || h1 || (routeType === 'series' ? ogTitle : '') || (routeType === 'episode' ? ogTitle : ''));
  const episodeTitle = cleanTitle(typeof episode?.name === 'string' ? episode.name : seasonFromHeading.episodeTitle ?? '');
  const isGeneric = /^(?:idlix|series|tv series|movie|movies|watch online|subtitle indonesia|home)$/i;
  const rawTitle = routeType === 'episode' && jsonSeriesTitle
    ? jsonSeriesTitle
    : (typeof movie?.name === 'string' ? movie.name : '')
      || (routeType === 'series' || routeType === 'episode' ? (seriesTitle || ogTitle || h1) : '')
      || h1 || ogTitle || documentTitle;
  const title = cleanTitle(rawTitle);
  const artwork = meta(document, 'og:image')
    || meta(document, 'twitter:image')
    || attrOf(document.querySelector('.poster img'), 'src')
    || attrOf(document.querySelector('.cover img'), 'src');
  return {
    ...(title && !isGeneric.test(title) ? { title } : {}),
    ...(seriesTitle && !isGeneric.test(seriesTitle) ? { seriesTitle } : {}),
    ...(seasonNumber !== undefined ? { seasonNumber } : {}),
    ...(episodeNumber !== undefined ? { episodeNumber } : {}),
    ...(episodeTitle && !isGeneric.test(episodeTitle) ? { episodeTitle } : {}),
    ...(artwork ? { artworkUrl: artwork } : {}),
    routeType,
  };
}

export function parseIdlixActivity(document: any, options: IdlixAdapterOptions = {}): UnifiedActivityV2 {
  const now = Number.isFinite(options.now) ? Number(options.now) : Date.now();
  const mediaState = options.mediaState ?? 'unknown';
  const metadata = extractIdlixMetadata(document);
  const source = String(document.location?.hostname ?? '').toLowerCase().replace(/^www\./, '');
  const isEpisode = metadata.routeType === 'episode' || metadata.routeType === 'series' && metadata.episodeNumber !== undefined;
  const title = metadata.seriesTitle || metadata.title;
  const details = title || (metadata.routeType === 'series' ? 'Browsing series' : metadata.routeType === 'movie' ? 'Browsing movies' : metadata.routeType === 'search' ? 'Searching IDLIX' : metadata.routeType === 'home' ? 'Viewing IDLIX home' : 'Browsing IDLIX');
  const episodeParts = [
    metadata.seasonNumber !== undefined ? `Season ${metadata.seasonNumber}` : '',
    metadata.episodeNumber !== undefined ? `Episode ${metadata.episodeNumber}` : '',
  ].filter(Boolean);
  const state = isEpisode
    ? [episodeParts.join(' · '), metadata.episodeTitle].filter(Boolean).join(' — ')
    : metadata.routeType === 'movie' ? 'Movie' : undefined;
  const startMs = mediaState === 'playing' && Number.isFinite(options.durationSeconds) && Number.isFinite(options.currentTimeSeconds)
    ? now - Number(options.currentTimeSeconds) * 1000 : undefined;
  const endMs = startMs !== undefined ? startMs + Number(options.durationSeconds) * 1000 : undefined;
  return normalizeUnifiedActivity({
    schemaVersion: 2,
    id: `idlix:${source}:${metadata.routeType}`,
    source,
    sourceType: 'website',
    activityType: 'Watching',
    name: 'IDLIX',
    details: mediaState === 'unknown' ? details : (metadata.episodeTitle && isEpisode ? (metadata.seriesTitle || details) : details),
    state,
    mediaState,
    timestamps: { startMs, endMs },
    assets: { large: { url: metadata.artworkUrl, text: title || 'IDLIX' } },
    metadata: {
      routeType: metadata.routeType,
      ...(metadata.seriesTitle ? { seriesTitle: metadata.seriesTitle } : {}),
      ...(metadata.seasonNumber !== undefined ? { seasonNumber: metadata.seasonNumber } : {}),
      ...(metadata.episodeNumber !== undefined ? { episodeNumber: metadata.episodeNumber } : {}),
      ...(metadata.episodeTitle ? { episodeTitle: metadata.episodeTitle } : {}),
    },
    priority: title ? 90 : 20,
    confidence: title ? 0.92 : 0.3,
    updatedAt: now,
  });
}
