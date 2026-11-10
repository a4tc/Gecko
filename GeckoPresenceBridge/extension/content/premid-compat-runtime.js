"use strict";
(() => {
    const globalState = globalThis;
    if (globalState.__gpbPreMiDCompatV1)
        return;
    const listeners = new Map();
    let metadata = { service: 'Unknown', settings: [] };
    let timer = null;
    let latestData = null;
    let active = true;
    const settings = new Map();
    const defaults = {
        'general.browsing': 'Browsing', 'general.playing': 'Playing', 'general.paused': 'Paused',
        'general.searchFor': 'Searching', 'general.viewHome': 'Viewing home', 'general.live': 'Live',
        'general.watchVideo': 'Watch video', 'general.buttonWatchVideo': 'Watch video',
        'general.buttonViewPage': 'View page', 'general.viewMovie': 'Viewing movie',
        'general.viewShow': 'Viewing show', 'general.buttonViewEpisode': 'View episode',
        'general.buttonViewMovie': 'View movie', 'general.search': 'Searching',
    };
    function emit(event, ...args) {
        for (const listener of [...(listeners.get(event) ?? [])]) {
            try {
                const result = listener(...args);
                if (result && typeof result.catch === 'function')
                    result.catch((error) => log('error', error));
            }
            catch (error) {
                log('error', error);
            }
        }
    }
    function log(level, value) {
        const text = typeof value === 'string' ? value : String(value);
        const method = level === 'error' ? console.error : level === 'warn' ? console.warn : console.info;
        method(`[GPB/PreMiD ${metadata.service ?? 'Activity'}] ${text}`);
    }
    function milliseconds(value) {
        if (value instanceof Date)
            return value.getTime();
        if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0)
            return undefined;
        return value < 10_000_000_000 ? value * 1000 : value;
    }
    function getTimestamps(current, duration) {
        const start = Math.floor(Date.now() / 1000 - current);
        return [start, Math.floor(start + duration)];
    }
    class Presence {
        clientId;
        constructor(options = {}) { this.clientId = String(options.clientId ?? ''); }
        on(event, listener) {
            const bucket = listeners.get(event) ?? new Set();
            bucket.add(listener);
            listeners.set(event, bucket);
        }
        setActivity(data) {
            if (!data)
                return this.clearActivity();
            latestData = data;
            void browser.runtime.sendMessage({ type: 'PREMID_ACTIVITY_UPDATE', service: metadata.service, activity: data }).catch(() => undefined);
        }
        clearActivity() {
            latestData = null;
            void browser.runtime.sendMessage({ type: 'PREMID_ACTIVITY_CLEAR', service: metadata.service }).catch(() => undefined);
        }
        async getSetting(id) {
            return settings.get(id);
        }
        async getStrings(input) {
            const result = {};
            for (const [key, value] of Object.entries(input))
                result[key] = defaults[value] ?? value.replace(/^general\./, '').replace(/([A-Z])/g, ' $1').toLowerCase();
            return result;
        }
        getTimestamps(current, duration) { return getTimestamps(current, duration); }
        getTimestampsfromMedia(media) { return getTimestamps(media.currentTime, media.duration); }
        getExtensionVersion() { return '0.5.0-gpb-compat'; }
        async getPageVariable(...paths) { return {}; }
        async getPageletiable() { return undefined; }
        async execInPage() { return undefined; }
        async getLogs() { return []; }
        async hideSetting() { return undefined; }
        async showSetting() { return undefined; }
        info(value) { log('info', value); }
        success(value) { log('info', value); }
        error(value) { log('error', value); }
        createSlideshow() {
            const slides = new Map();
            return {
                addSlide: (id, data, interval) => { slides.set(id, { data, interval }); return slides.get(id); },
                updateSlide: (id, data, interval) => { const old = slides.get(id); if (!old)
                    return undefined; const next = { data: data ?? old.data, interval: interval ?? old.interval }; slides.set(id, next); return next; },
                deleteSlide: (id) => slides.delete(id),
                deleteAllSlides: () => slides.clear(),
                hasSlide: (id) => slides.has(id),
                getSlides: () => [...slides.entries()].map(([id, item]) => ({ id, ...item })),
            };
        }
        onRequest() { return () => undefined; }
    }
    function iframeAllowed() {
        if (window.top === window)
            return true;
        if (metadata.iframe !== true)
            return false;
        const pattern = typeof metadata.iFrameRegExp === 'string' ? metadata.iFrameRegExp : '';
        if (!pattern)
            return true;
        try {
            return new RegExp(pattern).test(location.href);
        }
        catch {
            return false;
        }
    }
    class IFrame {
        on(event, listener) {
            const bucket = listeners.get(event) ?? new Set();
            bucket.add(listener);
            listeners.set(event, bucket);
        }
        send(data) {
            if (!iframeAllowed())
                return;
            void browser.runtime.sendMessage({ type: 'PREMID_IFRAME_DATA', data }).catch(() => undefined);
        }
        async getUrl() { return location.href; }
    }
    const compat = {
        configure(value) {
            metadata = value ?? metadata;
            for (const setting of Array.isArray(metadata.settings) ? metadata.settings : []) {
                if (setting && typeof setting.id === 'string')
                    settings.set(setting.id, setting.value ?? false);
            }
            if (!settings.has('lang'))
                settings.set('lang', 'en');
            if (timer)
                clearInterval(timer);
            active = true;
            timer = setInterval(() => { if (active)
                emit('UpdateData'); }, 10_000);
            setTimeout(() => { if (active)
                emit('UpdateData'); }, 250);
        },
        receiveIframeData(data) { emit('iFrameData', data); },
        stop() { active = false; if (timer)
            clearInterval(timer); timer = null; },
        presence: {
            Presence,
            iFrame: IFrame,
            ActivityType: { Playing: 0, Streaming: 1, Listening: 2, Watching: 3, Competing: 5 },
            StatusDisplayType: { Name: 0, State: 1, Details: 2 },
            Assets: {
                Play: 'https://cdn.rcd.gg/PreMiD/resources/play.png', Pause: 'https://cdn.rcd.gg/PreMiD/resources/pause.png',
                Stop: 'https://cdn.rcd.gg/PreMiD/resources/stop.png', Search: 'https://cdn.rcd.gg/PreMiD/resources/search.png',
                Live: 'https://cdn.rcd.gg/PreMiD/resources/live.png', Viewing: 'https://cdn.rcd.gg/PreMiD/resources/viewing.png',
            },
            getTimestamps,
            getTimestampsFromMedia: (media) => getTimestamps(media.currentTime, media.duration),
        },
        Presence,
        iFrame: IFrame,
    };
    globalState.__gpbPreMiDCompatV1 = compat;
    globalThis.Presence = Presence;
    globalThis.iFrame = IFrame;
    browser.runtime.onMessage.addListener((message) => {
        if (!message || typeof message !== 'object')
            return undefined;
        const item = message;
        if (item.type === 'GPB_PREMID_CONFIG')
            compat.configure(item.metadata);
        if (item.type === 'PREMID_IFRAME_DATA' && window.top === window)
            compat.receiveIframeData(item.data);
        if (item.type === 'GPB_PREMID_STOP')
            compat.stop();
        return undefined;
    });
    if (window.top === window)
        void browser.runtime.sendMessage({ type: 'PREMID_RUNTIME_READY' }).catch(() => undefined);
})();
