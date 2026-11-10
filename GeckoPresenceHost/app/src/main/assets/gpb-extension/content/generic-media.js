"use strict";
(() => {
    const globalState = globalThis;
    if (globalState.__gpbGenericMediaV1)
        return;
    globalState.__gpbGenericMediaV1 = true;
    const MESSAGE_TYPE = 'MEDIA_STATE';
    const tracked = new Set();
    const hasPlayed = new WeakSet();
    let observer = null;
    let lastTouched = null;
    let lastSentSignature = '';
    function cleanTitle(value) {
        if (!value)
            return undefined;
        let cleaned = value
            .replace(/[\u0000-\u001f\u007f]/g, ' ')
            .replace(/\s+/g, ' ')
            .trim();
        const suffixPattern = /idlix/i.test(location.hostname)
            ? 'idlix(?:plus)?(?:\\.[a-z.]+)?'
            : /youtube/i.test(location.hostname)
                ? '(?:youtube|youtube music)'
                : /netflix/i.test(location.hostname)
                    ? 'netflix'
                    : /spotify/i.test(location.hostname)
                        ? 'spotify'
                        : /twitch/i.test(location.hostname)
                            ? 'twitch'
                            : /tiktok/i.test(location.hostname)
                                ? 'tiktok'
                                : /instagram/i.test(location.hostname)
                                    ? 'instagram'
                                    : 'watch online|streaming online';
        cleaned = cleaned.replace(new RegExp(`\\s*(?:[-|•·:]|—)\\s*(?:${suffixPattern})\\s*$`, 'i'), '').trim();
        if (/idlix/i.test(location.hostname)) {
            cleaned = cleaned
                .replace(/\s*\/\s*idlix(?:plus)?(?:\.[a-z.]+)?\s*$/i, '')
                .replace(/^nonton\s+/i, '')
                .trim();
        }
        if (!cleaned || /^(?:idlix|home|watch online|movie|movies|video)$/i.test(cleaned))
            return undefined;
        return cleaned.slice(0, 120);
    }
    function extractJsonLdMovieTitle() {
        const scripts = Array.from(document.querySelectorAll('script[type="application/ld+json"]'));
        const findMovieName = (value) => {
            if (Array.isArray(value)) {
                for (const item of value) {
                    const found = findMovieName(item);
                    if (found)
                        return found;
                }
                return undefined;
            }
            if (!value || typeof value !== 'object')
                return undefined;
            const record = value;
            const rawType = record['@type'];
            const types = Array.isArray(rawType) ? rawType : [rawType];
            const isMovie = types.some((type) => typeof type === 'string' && (type.toLowerCase() === 'movie' || type.toLowerCase().endsWith('/movie')));
            if (isMovie && typeof record.name === 'string') {
                const candidate = cleanTitle(record.name);
                if (candidate)
                    return candidate;
            }
            if (Array.isArray(record['@graph']))
                return findMovieName(record['@graph']);
            return undefined;
        };
        for (const script of scripts) {
            const raw = script.textContent?.trim();
            if (!raw)
                continue;
            try {
                const title = findMovieName(JSON.parse(raw));
                if (title)
                    return title;
            }
            catch {
                // Ignore malformed or unrelated JSON-LD blocks and continue to DOM metadata.
            }
        }
        return undefined;
    }
    function extractPageTitle() {
        const isIdlix = /idlix/i.test(location.hostname);
        if (isIdlix) {
            const structuredTitle = extractJsonLdMovieTitle();
            if (structuredTitle)
                return structuredTitle;
        }
        const selectors = isIdlix
            ? [
                'video.shaka-video[aria-label]', 'video[aria-label]',
                '[data-player-container] [aria-label]', '.hero-player-titlebar',
                'article.sr-only h1', 'article h1', '.entry-title', '.movie-title', '.film-name',
                '.data h1', '.info h1', '.mvic-desc h3', '.single-title', '#mv-info h1',
                'h1[itemprop="name"]', 'meta[property="og:title"]',
                'meta[name="twitter:title"]', 'title',
            ]
            : [
                'h1[itemprop="name"]', '.entry-title', '.movie-title', '.film-title',
                'meta[property="og:title"]', 'meta[name="twitter:title"]', 'title',
            ];
        for (const selector of selectors) {
            const element = document.querySelector(selector);
            if (!element)
                continue;
            if (isIdlix && element.closest?.('#browser-support-gate, #browser-asset-gate, .browser-support-gate, .error-page-card, .error-page-title'))
                continue;
            const raw = element instanceof HTMLMetaElement
                ? element.content
                : element instanceof HTMLTitleElement
                    ? element.textContent
                    : element.getAttribute('aria-label') || element.getAttribute('content') || element.textContent;
            const candidate = cleanTitle(raw);
            if (candidate)
                return candidate;
        }
        return cleanTitle(document.title);
    }
    function currentState() {
        for (const media of tracked) {
            if (!media.paused && !media.ended)
                return 'playing';
        }
        if (lastTouched?.ended)
            return 'ended';
        if (lastTouched?.paused && hasPlayed.has(lastTouched))
            return 'paused';
        return 'unknown';
    }
    function activeMedia() {
        for (const media of tracked)
            if (!media.paused && !media.ended)
                return media;
        return lastTouched;
    }
    function sendState(state) {
        const pageTitle = window.top === window ? extractPageTitle() : undefined;
        const media = activeMedia();
        const duration = media && Number.isFinite(media.duration) ? media.duration : undefined;
        const currentTime = media && Number.isFinite(media.currentTime) ? media.currentTime : undefined;
        const signature = `${state}|${pageTitle ?? ''}|${duration ?? ''}|${currentTime ?? ''}`;
        if (signature === lastSentSignature)
            return;
        lastSentSignature = signature;
        void browser.runtime.sendMessage({ type: MESSAGE_TYPE, state, pageTitle, duration, currentTime }).catch(() => undefined);
    }
    function emitState() {
        sendState(currentState());
    }
    function onMediaEvent(event) {
        const media = event.currentTarget;
        if (!(media instanceof HTMLMediaElement))
            return;
        lastTouched = media;
        if (event.type === 'play')
            hasPlayed.add(media);
        emitState();
    }
    function attach(media) {
        if (tracked.has(media))
            return;
        tracked.add(media);
        media.addEventListener('play', onMediaEvent);
        media.addEventListener('pause', onMediaEvent);
        media.addEventListener('ended', onMediaEvent);
        if (!media.paused && !media.ended) {
            lastTouched = media;
            hasPlayed.add(media);
        }
    }
    function inspectNode(node) {
        if (!(node instanceof Element))
            return;
        if (node instanceof HTMLMediaElement)
            attach(node);
        node.querySelectorAll('video, audio').forEach((item) => attach(item));
    }
    function detachNode(node) {
        if (!(node instanceof Element))
            return;
        const mediaItems = [];
        if (node instanceof HTMLMediaElement)
            mediaItems.push(node);
        node.querySelectorAll('video, audio').forEach((item) => mediaItems.push(item));
        for (const media of mediaItems) {
            media.removeEventListener('play', onMediaEvent);
            media.removeEventListener('pause', onMediaEvent);
            media.removeEventListener('ended', onMediaEvent);
            tracked.delete(media);
            if (lastTouched === media)
                lastTouched = null;
        }
    }
    function observeMedia() {
        if (document.hidden || observer)
            return;
        document.querySelectorAll('video, audio').forEach((item) => attach(item));
        const root = document.body ?? document.documentElement;
        if (!root)
            return;
        observer = new MutationObserver((records) => {
            for (const record of records) {
                record.removedNodes.forEach(detachNode);
                record.addedNodes.forEach(inspectNode);
            }
            // A site may render its title after the player shell; publish only when it changed.
            emitState();
        });
        observer.observe(root, { childList: true, subtree: true, characterData: true });
        const titleElement = document.querySelector('title');
        if (titleElement)
            observer.observe(titleElement, { childList: true, subtree: true, characterData: true });
    }
    function pauseObservation() {
        observer?.disconnect();
        observer = null;
        for (const media of [...tracked])
            detachNode(media);
    }
    browser.runtime.onMessage.addListener((message) => {
        if (message && typeof message === 'object' && message.type === 'GET_MEDIA_STATE') {
            const media = activeMedia();
            return {
                state: currentState(),
                pageTitle: window.top === window ? extractPageTitle() : undefined,
                duration: media && Number.isFinite(media.duration) ? media.duration : undefined,
                currentTime: media && Number.isFinite(media.currentTime) ? media.currentTime : undefined,
            };
        }
        return undefined;
    });
    document.addEventListener('visibilitychange', () => {
        if (document.hidden) {
            pauseObservation();
        }
        else {
            observeMedia();
            if (window.top === window || currentState() !== 'unknown')
                emitState();
        }
    });
    observeMedia();
})();
