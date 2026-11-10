(() => {
  const globalState = globalThis as typeof globalThis & { __gpbPreMiDCompatV1?: any };
  if (globalState.__gpbPreMiDCompatV1) return;

  type Listener = (...args: any[]) => void | Promise<void>;
  type RuntimeEvent = 'UpdateData' | 'iFrameData';
  const listeners = new Map<RuntimeEvent, Set<Listener>>();
  let metadata: Record<string, any> = { service: 'Unknown', settings: [] };
  let timer: ReturnType<typeof setInterval> | null = null;
  let latestData: any = null;
  let active = true;
  const settings = new Map<string, string | number | boolean>();
  const defaults: Record<string, string> = {
    'general.browsing': 'Browsing', 'general.playing': 'Playing', 'general.paused': 'Paused',
    'general.searchFor': 'Searching', 'general.viewHome': 'Viewing home', 'general.live': 'Live',
    'general.watchVideo': 'Watch video', 'general.buttonWatchVideo': 'Watch video',
    'general.buttonViewPage': 'View page', 'general.viewMovie': 'Viewing movie',
    'general.viewShow': 'Viewing show', 'general.buttonViewEpisode': 'View episode',
    'general.buttonViewMovie': 'View movie', 'general.search': 'Searching',
  };

  function emit(event: RuntimeEvent, ...args: any[]): void {
    for (const listener of [...(listeners.get(event) ?? [])]) {
      try {
        const result = listener(...args);
        if (result && typeof result.catch === 'function') result.catch((error: unknown) => log('error', error));
      } catch (error) { log('error', error); }
    }
  }

  function log(level: string, value: unknown): void {
    const text = typeof value === 'string' ? value : String(value);
    const method = level === 'error' ? console.error : level === 'warn' ? console.warn : console.info;
    method(`[GPB/PreMiD ${metadata.service ?? 'Activity'}] ${text}`);
  }

  function milliseconds(value: unknown): number | undefined {
    if (value instanceof Date) return value.getTime();
    if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0) return undefined;
    return value < 10_000_000_000 ? value * 1000 : value;
  }

  function getTimestamps(current: number, duration: number): [number, number] {
    const start = Math.floor(Date.now() / 1000 - current);
    return [start, Math.floor(start + duration)];
  }

  class Presence {
    clientId: string;
    constructor(options: { clientId?: string } = {}) { this.clientId = String(options.clientId ?? ''); }
    on(event: RuntimeEvent, listener: Listener): void {
      const bucket = listeners.get(event) ?? new Set<Listener>();
      bucket.add(listener);
      listeners.set(event, bucket);
    }
    setActivity(data?: any): void {
      if (!data) return this.clearActivity();
      latestData = data;
      void browser.runtime.sendMessage({ type: 'PREMID_ACTIVITY_UPDATE', service: metadata.service, activity: data }).catch(() => undefined);
    }
    clearActivity(): void {
      latestData = null;
      void browser.runtime.sendMessage({ type: 'PREMID_ACTIVITY_CLEAR', service: metadata.service }).catch(() => undefined);
    }
    async getSetting<T extends string | number | boolean>(id: string): Promise<T> {
      return settings.get(id) as T;
    }
    async getStrings<T extends Record<string, string>>(input: T): Promise<T> {
      const result: Record<string, string> = {};
      for (const [key, value] of Object.entries(input)) result[key] = defaults[value] ?? value.replace(/^general\./, '').replace(/([A-Z])/g, ' $1').toLowerCase();
      return result as T;
    }
    getTimestamps(current: number, duration: number): [number, number] { return getTimestamps(current, duration); }
    getTimestampsfromMedia(media: HTMLMediaElement): [number, number] { return getTimestamps(media.currentTime, media.duration); }
    getExtensionVersion(): string { return '0.5.0-gpb-compat'; }
    async getPageVariable<T = unknown>(...paths: string[]): Promise<T> { return {} as T; }
    async getPageletiable<T = unknown>(): Promise<T> { return undefined as T; }
    async execInPage<T = unknown>(): Promise<T> { return undefined as T; }
    async getLogs<T = unknown>(): Promise<T[]> { return []; }
    async hideSetting(): Promise<void> { return undefined; }
    async showSetting(): Promise<void> { return undefined; }
    info(value: string): void { log('info', value); }
    success(value: string): void { log('info', value); }
    error(value: string): void { log('error', value); }
    createSlideshow(): any {
      const slides = new Map<string, { data: any; interval: number }>();
      return {
        addSlide: (id: string, data: any, interval: number) => { slides.set(id, { data, interval }); return slides.get(id); },
        updateSlide: (id: string, data?: any, interval?: number) => { const old = slides.get(id); if (!old) return undefined; const next = { data: data ?? old.data, interval: interval ?? old.interval }; slides.set(id, next); return next; },
        deleteSlide: (id: string) => slides.delete(id),
        deleteAllSlides: () => slides.clear(),
        hasSlide: (id: string) => slides.has(id),
        getSlides: () => [...slides.entries()].map(([id, item]) => ({ id, ...item })),
      };
    }
    onRequest(): () => void { return () => undefined; }
  }

  function iframeAllowed(): boolean {
    if (window.top === window) return true;
    if (metadata.iframe !== true) return false;
    const pattern = typeof metadata.iFrameRegExp === 'string' ? metadata.iFrameRegExp : '';
    if (!pattern) return true;
    try { return new RegExp(pattern).test(location.href); } catch { return false; }
  }

  class IFrame {
    on(event: RuntimeEvent, listener: Listener): void {
      const bucket = listeners.get(event) ?? new Set<Listener>();
      bucket.add(listener);
      listeners.set(event, bucket);
    }
    send(data: unknown): void {
      if (!iframeAllowed()) return;
      void browser.runtime.sendMessage({ type: 'PREMID_IFRAME_DATA', data }).catch(() => undefined);
    }
    async getUrl(): Promise<string> { return location.href; }
  }

  const compat = {
    configure(value: Record<string, any>) {
      metadata = value ?? metadata;
      for (const setting of Array.isArray(metadata.settings) ? metadata.settings : []) {
        if (setting && typeof setting.id === 'string') settings.set(setting.id, setting.value ?? false);
      }
      if (!settings.has('lang')) settings.set('lang', 'en');
      if (timer) clearInterval(timer);
      active = true;
      timer = setInterval(() => { if (active) emit('UpdateData'); }, 10_000);
      setTimeout(() => { if (active) emit('UpdateData'); }, 250);
    },
    receiveIframeData(data: unknown) { emit('iFrameData', data); },
    stop() { active = false; if (timer) clearInterval(timer); timer = null; },
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
      getTimestampsFromMedia: (media: HTMLMediaElement) => getTimestamps(media.currentTime, media.duration),
    },
    Presence,
    iFrame: IFrame,
  };
  globalState.__gpbPreMiDCompatV1 = compat;
  (globalThis as any).Presence = Presence;
  (globalThis as any).iFrame = IFrame;
  browser.runtime.onMessage.addListener((message) => {
    if (!message || typeof message !== 'object') return undefined;
    const item = message as Record<string, unknown>;
    if (item.type === 'GPB_PREMID_CONFIG') compat.configure(item.metadata as Record<string, any>);
    if (item.type === 'PREMID_IFRAME_DATA' && window.top === window) compat.receiveIframeData(item.data);
    if (item.type === 'GPB_PREMID_STOP') compat.stop();
    return undefined;
  });
  if (window.top === window) void browser.runtime.sendMessage({ type: 'PREMID_RUNTIME_READY' }).catch(() => undefined);
})();
