"use strict";
(() => {
    const globalState = globalThis;
    if (globalState.__gpbIdlixPresenceV1)
        return;
    globalState.__gpbIdlixPresenceV1 = true;
    const clean = (value) => typeof value === 'string'
        ? value.replace(/[\u0000-\u001f\u007f]/g, ' ').replace(/\s+/g, ' ').trim()
            .replace(/^nonton\s+/i, '').replace(/\s*(?:[-|•·:]|—)\s*(?:subtitle indonesia\s*)?idlix(?:plus)?(?:\.[a-z.]+)?\s*$/i, '').slice(0, 120)
        : '';
    const text = (selector) => clean(document.querySelector(selector)?.textContent ?? '');
    const attr = (selector, name) => clean(document.querySelector(selector)?.getAttribute(name) ?? '');
    const meta = (name) => attr(`meta[property="${name}"]`, 'content') || attr(`meta[name="${name}"]`, 'content');
    function extract() {
        const path = location.pathname.toLowerCase();
        const route = path.split('/').filter(Boolean)[0] ?? '';
        const routeType = route === 'movie' ? 'movie'
            : ['series', 'tvseries'].includes(route) ? 'series'
                : ['episode', 'episodes', 'watch'].includes(route) ? 'episode'
                    : route === 'search' ? 'search' : path === '/' ? 'home' : 'browse';
        let episodeRecord;
        let movieRecord;
        const visit = (value) => {
            if (Array.isArray(value)) {
                value.forEach(visit);
                return;
            }
            if (!value || typeof value !== 'object')
                return;
            const type = Array.isArray(value['@type']) ? value['@type'].join(' ') : String(value['@type'] ?? '');
            if (/TVEpisode|Episode/i.test(type))
                episodeRecord ??= value;
            if (/Movie/i.test(type))
                movieRecord ??= value;
            if (Array.isArray(value['@graph']))
                visit(value['@graph']);
        };
        document.querySelectorAll('script[type="application/ld+json"]').forEach((node) => {
            try {
                visit(JSON.parse(node.textContent ?? 'null'));
            }
            catch { /* Ignore malformed JSON-LD. */ }
        });
        const h1 = text('.epih1') || text('.entry-title') || text('article h1') || text('main h1');
        const episodeHeading = text('.epih3') || text('.episode-title') || text('[data-episode-title]') || attr('.pag_episodes a[title]', 'title');
        const seasonText = episodeHeading || h1 || document.title;
        const seasonMatch = seasonText.match(/(?:season|s)\s*0*(\d{1,3})/i);
        const episodeMatch = seasonText.match(/(?:episode|ep|e)\s*0*(\d{1,3})/i) || seasonText.match(/\bs\d{1,3}\s*e\s*0*(\d{1,3})\b/i);
        const seasonNumber = Number(episodeRecord?.partOfSeason?.seasonNumber ?? seasonMatch?.[1]);
        const episodeNumber = Number(episodeRecord?.episodeNumber ?? episodeMatch?.[1]);
        const jsonSeriesTitle = episodeRecord?.partOfSeries?.name ?? episodeRecord?.partOfSeason?.partOfSeries?.name;
        const seriesTitle = clean(jsonSeriesTitle || h1 || meta('og:title'));
        const episodeTitle = clean(episodeRecord?.name || seasonText
            .replace(/\bs\d{1,3}\s*e\s*\d{1,3}\b/ig, '')
            .replace(/(?:season|s|episode|ep|e)\s*\d{1,3}/ig, '')
            .replace(/^[\s\-–—:|·]+|[\s\-–—:|·]+$/g, ''));
        const jsonLdMovieTitle = typeof movieRecord?.name === 'string' ? movieRecord.name : '';
        const ogTitle = meta('og:title');
        const rawTitle = routeType === 'episode' && jsonSeriesTitle ? jsonSeriesTitle
            : jsonLdMovieTitle || (routeType === 'series' || routeType === 'episode' ? (seriesTitle || h1 || ogTitle) : '')
                || h1 || ogTitle || clean(document.title);
        const title = clean(rawTitle);
        const titleSource = jsonSeriesTitle && routeType === 'episode' ? 'jsonld-series'
            : jsonLdMovieTitle ? 'jsonld-movie' : h1 ? 'heading' : ogTitle ? 'og:title' : document.title ? 'document-title' : 'none';
        const ogImage = meta('og:image');
        const twitterImage = meta('twitter:image');
        const posterImage = attr('.poster img', 'src');
        const coverImage = attr('.cover img', 'src');
        const artworkUrl = ogImage || twitterImage || posterImage || coverImage;
        const artworkSource = ogImage ? 'og:image' : twitterImage ? 'twitter:image' : posterImage ? '.poster img' : coverImage ? '.cover img' : 'none';
        const generic = /^(?:idlix|series|tv series|movie|movies|watch online|home)$/i;
        return {
            ...(title && !generic.test(title) ? { title } : {}),
            ...(seriesTitle && !generic.test(seriesTitle) ? { seriesTitle } : {}),
            ...(Number.isInteger(seasonNumber) && seasonNumber >= 0 && seasonNumber <= 999 ? { seasonNumber } : {}),
            ...(Number.isInteger(episodeNumber) && episodeNumber >= 0 && episodeNumber <= 999 ? { episodeNumber } : {}),
            ...(episodeTitle && !generic.test(episodeTitle) ? { episodeTitle } : {}),
            ...(artworkUrl ? { artworkUrl } : {}),
            titleSource,
            artworkSource,
            routeType,
        };
    }
    let lastSignature = '';
    const send = () => {
        if (!/idlix|idflix/i.test(location.hostname))
            return;
        const metadata = extract();
        const signature = JSON.stringify(metadata);
        if (signature === lastSignature)
            return;
        lastSignature = signature;
        void browser.runtime.sendMessage({ type: 'IDLIX_METADATA', metadata }).catch(() => undefined);
    };
    const observer = new MutationObserver(() => send());
    const root = document.documentElement;
    if (root)
        observer.observe(root, { subtree: true, childList: true, characterData: true, attributes: true });
    document.addEventListener('DOMContentLoaded', send, { once: true });
    send();
    browser.runtime.onMessage.addListener((message) => {
        if (message && typeof message === 'object' && message.type === 'GET_IDLIX_METADATA')
            return extract();
        return undefined;
    });
})();
