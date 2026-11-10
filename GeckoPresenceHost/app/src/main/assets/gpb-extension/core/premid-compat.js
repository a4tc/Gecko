import { normalizeUnifiedActivity } from './activity-model.js';
const TYPE_MAP = {
    0: 'Playing', 1: 'Streaming', 2: 'Listening', 3: 'Watching', 5: 'Competing',
};
export class PreMiDCompatibilityRuntime {
    listeners = new Map();
    settings;
    candidateListeners = new Set();
    activity = null;
    updateRunning = false;
    metadata;
    presence;
    iframe;
    constructor(options, metadata) {
        void options;
        this.metadata = metadata;
        this.settings = Object.fromEntries((metadata.settings ?? []).map((item) => [item.id, item.value ?? false]));
        this.presence = {
            on: (event, listener) => this.on(event, listener),
            setActivity: (data) => this.setActivity(data),
            clearActivity: () => this.clearActivity(),
            getSetting: async (key) => this.settings[key],
            getStrings: async (strings) => Object.fromEntries(Object.entries(strings).map(([key, value]) => [key, this.metadata.strings?.[value] ?? this.defaultString(value)])),
            getExtensionVersion: () => '0.5.0-gpb-compat',
            getPageVariable: async (...paths) => Object.fromEntries(paths.map((path) => [path, undefined])),
            getPageletiable: async (path) => undefined,
            getLogs: async () => [],
            info: (message) => this.log('info', message),
            success: (message) => this.log('info', message),
            error: (message) => this.log('error', message),
            hideSetting: async () => undefined,
            showSetting: async () => undefined,
            getTimestamps: (current, duration) => this.getTimestamps(current, duration),
            createSlideshow: () => this.createSlideshow(),
            execInPage: async () => undefined,
            onRequest: () => () => undefined,
        };
        this.iframe = {
            on: (event, listener) => this.on(event, listener),
            send: (data) => this.receiveIframeData(data),
            getUrl: async () => '',
        };
    }
    get currentActivity() { return this.activity; }
    onCandidate(listener) {
        this.candidateListeners.add(listener);
        return () => this.candidateListeners.delete(listener);
    }
    async tick() {
        if (this.updateRunning)
            return;
        this.updateRunning = true;
        try {
            await this.emit('UpdateData');
        }
        finally {
            this.updateRunning = false;
        }
    }
    receiveIframeData(data) {
        void this.emit('iFrameData', data);
    }
    setActivity(data) {
        if (!data || typeof data !== 'object')
            return this.clearActivity();
        const details = this.valueText(data.details) || this.metadata.service;
        const state = this.valueText(data.state);
        const activityType = TYPE_MAP[Number(data.type)] ?? 'Playing';
        const candidate = normalizeUnifiedActivity({
            schemaVersion: 2,
            id: `premid:${this.metadata.service.toLowerCase()}`,
            source: this.metadata.service.toLowerCase().replace(/[^a-z0-9.-]/g, '-') + '.adapter',
            sourceType: 'manual',
            activityType,
            name: this.metadata.service,
            details,
            state,
            mediaState: activityType === 'Watching' || activityType === 'Listening' || activityType === 'Streaming' ? 'playing' : 'unknown',
            timestamps: {
                startMs: this.toMilliseconds(data.startTimestamp),
                endMs: this.toMilliseconds(data.endTimestamp),
            },
            assets: {
                large: { url: this.valueText(data.largeImageKey), text: this.valueText(data.largeImageText) },
                small: { url: this.valueText(data.smallImageKey), text: this.valueText(data.smallImageText) },
            },
            buttons: Array.isArray(data.buttons) ? data.buttons.map((button) => ({ label: this.valueText(button?.label), url: this.valueText(button?.url) })) : [],
            priority: 50,
            confidence: 0.7,
            updatedAt: Date.now(),
        });
        this.activity = candidate;
        this.publish(candidate);
    }
    clearActivity() {
        this.activity = null;
        this.publish(null);
    }
    on(event, listener) {
        const bucket = this.listeners.get(event) ?? new Set();
        bucket.add(listener);
        this.listeners.set(event, bucket);
    }
    async emit(event, ...args) {
        const listeners = [...(this.listeners.get(event) ?? [])];
        await Promise.all(listeners.map(async (listener) => {
            try {
                await listener(...args);
            }
            catch (error) {
                this.log('error', error instanceof Error ? error.message : String(error));
            }
        }));
    }
    publish(candidate) {
        for (const listener of this.candidateListeners)
            listener(candidate);
    }
    defaultString(key) {
        return key.replace(/^general\./, '').replace(/[A-Z]/g, (letter) => ` ${letter.toLowerCase()}`);
    }
    valueText(value) {
        if (typeof value === 'string')
            return value.trim();
        if (value && typeof value === 'object' && 'textContent' in value)
            return String(value.textContent ?? '').trim();
        if (value && typeof value === 'object' && 'href' in value)
            return String(value.href ?? '').trim();
        return '';
    }
    toMilliseconds(value) {
        if (value instanceof Date)
            return value.getTime();
        if (!Number.isFinite(value))
            return undefined;
        const n = Number(value);
        if (n <= 0)
            return undefined;
        return n < 10_000_000_000 ? n * 1000 : n;
    }
    getTimestamps(current, duration) {
        const start = Date.now() - current * 1000;
        return [Math.floor(start / 1000), Math.floor((start + duration * 1000) / 1000)];
    }
    createSlideshow() {
        const slides = new Map();
        return {
            addSlide: (id, data, interval) => { slides.set(id, { data, interval }); return { id, data, interval }; },
            updateSlide: (id, data, interval) => { const old = slides.get(id); if (old)
                slides.set(id, { data: data ?? old.data, interval: interval ?? old.interval }); return slides.get(id); },
            deleteSlide: (id) => slides.delete(id),
            deleteAllSlides: () => slides.clear(),
            hasSlide: (id) => slides.has(id),
            getSlides: () => [...slides.entries()].map(([id, value]) => ({ id, ...value })),
        };
    }
    log(type, message) {
        if (typeof console !== 'undefined')
            console[type === 'error' ? 'error' : 'info'](`[GPB/PreMiD ${this.metadata.service}] ${message}`);
    }
}
