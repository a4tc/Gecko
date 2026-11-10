import type { UnifiedActivityV2, UnifiedActivityType } from './activity-model.js';
import { normalizeUnifiedActivity } from './activity-model.js';

export type PreMiDEvent = 'UpdateData' | 'iFrameData';
export type PreMiDListener = (...args: unknown[]) => void | Promise<void>;
export interface PreMiDMetadata {
  service: string;
  settings?: Array<{ id: string; value?: string | number | boolean }>;
  strings?: Record<string, string>;
}

const TYPE_MAP: Record<number, UnifiedActivityType> = {
  0: 'Playing', 1: 'Streaming', 2: 'Listening', 3: 'Watching', 5: 'Competing',
};

export class PreMiDCompatibilityRuntime {
  private readonly listeners = new Map<PreMiDEvent, Set<PreMiDListener>>();
  private readonly settings: Record<string, string | number | boolean>;
  private candidateListeners = new Set<(candidate: UnifiedActivityV2 | null) => void>();
  private activity: UnifiedActivityV2 | null = null;
  private updateRunning = false;
  private readonly metadata: PreMiDMetadata;
  readonly presence: Record<string, (...args: any[]) => any>;
  readonly iframe: Record<string, (...args: any[]) => any>;

  constructor(options: { clientId?: string; injectOnComplete?: boolean }, metadata: PreMiDMetadata) {
    void options;
    this.metadata = metadata;
    this.settings = Object.fromEntries((metadata.settings ?? []).map((item) => [item.id, item.value ?? false]));
    this.presence = {
      on: (event: PreMiDEvent, listener: PreMiDListener) => this.on(event, listener),
      setActivity: (data?: Record<string, unknown>) => this.setActivity(data),
      clearActivity: () => this.clearActivity(),
      getSetting: async (key: string) => this.settings[key],
      getStrings: async (strings: Record<string, string>) => Object.fromEntries(Object.entries(strings).map(([key, value]) => [key, this.metadata.strings?.[value] ?? this.defaultString(value)])),
      getExtensionVersion: () => '0.5.0-gpb-compat',
      getPageVariable: async (...paths: string[]) => Object.fromEntries(paths.map((path) => [path, undefined])),
      getPageletiable: async (path: string) => undefined,
      getLogs: async () => [],
      info: (message: string) => this.log('info', message),
      success: (message: string) => this.log('info', message),
      error: (message: string) => this.log('error', message),
      hideSetting: async () => undefined,
      showSetting: async () => undefined,
      getTimestamps: (current: number, duration: number) => this.getTimestamps(current, duration),
      createSlideshow: () => this.createSlideshow(),
      execInPage: async () => undefined,
      onRequest: () => () => undefined,
    };
    this.iframe = {
      on: (event: PreMiDEvent, listener: PreMiDListener) => this.on(event, listener),
      send: (data: unknown) => this.receiveIframeData(data),
      getUrl: async () => '',
    };
  }

  get currentActivity(): UnifiedActivityV2 | null { return this.activity; }

  onCandidate(listener: (candidate: UnifiedActivityV2 | null) => void): () => void {
    this.candidateListeners.add(listener);
    return () => this.candidateListeners.delete(listener);
  }

  async tick(): Promise<void> {
    if (this.updateRunning) return;
    this.updateRunning = true;
    try { await this.emit('UpdateData'); }
    finally { this.updateRunning = false; }
  }

  receiveIframeData(data: unknown): void {
    void this.emit('iFrameData', data);
  }

  setActivity(data?: Record<string, unknown>): void {
    if (!data || typeof data !== 'object') return this.clearActivity();
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
      buttons: Array.isArray(data.buttons) ? data.buttons.map((button: any) => ({ label: this.valueText(button?.label), url: this.valueText(button?.url) })) : [],
      priority: 50,
      confidence: 0.7,
      updatedAt: Date.now(),
    });
    this.activity = candidate;
    this.publish(candidate);
  }

  clearActivity(): void {
    this.activity = null;
    this.publish(null);
  }

  private on(event: PreMiDEvent, listener: PreMiDListener): void {
    const bucket = this.listeners.get(event) ?? new Set<PreMiDListener>();
    bucket.add(listener);
    this.listeners.set(event, bucket);
  }

  private async emit(event: PreMiDEvent, ...args: unknown[]): Promise<void> {
    const listeners = [...(this.listeners.get(event) ?? [])];
    await Promise.all(listeners.map(async (listener) => {
      try { await listener(...args); } catch (error) { this.log('error', error instanceof Error ? error.message : String(error)); }
    }));
  }

  private publish(candidate: UnifiedActivityV2 | null): void {
    for (const listener of this.candidateListeners) listener(candidate);
  }

  private defaultString(key: string): string {
    return key.replace(/^general\./, '').replace(/[A-Z]/g, (letter) => ` ${letter.toLowerCase()}`);
  }

  private valueText(value: unknown): string {
    if (typeof value === 'string') return value.trim();
    if (value && typeof value === 'object' && 'textContent' in value) return String((value as { textContent: unknown }).textContent ?? '').trim();
    if (value && typeof value === 'object' && 'href' in value) return String((value as { href: unknown }).href ?? '').trim();
    return '';
  }

  private toMilliseconds(value: unknown): number | undefined {
    if (value instanceof Date) return value.getTime();
    if (!Number.isFinite(value)) return undefined;
    const n = Number(value);
    if (n <= 0) return undefined;
    return n < 10_000_000_000 ? n * 1000 : n;
  }

  private getTimestamps(current: number, duration: number): [number, number] {
    const start = Date.now() - current * 1000;
    return [Math.floor(start / 1000), Math.floor((start + duration * 1000) / 1000)];
  }

  private createSlideshow(): Record<string, unknown> {
    const slides = new Map<string, { data: Record<string, unknown>; interval: number }>();
    return {
      addSlide: (id: string, data: Record<string, unknown>, interval: number) => { slides.set(id, { data, interval }); return { id, data, interval }; },
      updateSlide: (id: string, data?: Record<string, unknown>, interval?: number) => { const old = slides.get(id); if (old) slides.set(id, { data: data ?? old.data, interval: interval ?? old.interval }); return slides.get(id); },
      deleteSlide: (id: string) => slides.delete(id),
      deleteAllSlides: () => slides.clear(),
      hasSlide: (id: string) => slides.has(id),
      getSlides: () => [...slides.entries()].map(([id, value]) => ({ id, ...value })),
    };
  }

  private log(type: string, message: string): void {
    if (typeof console !== 'undefined') console[type === 'error' ? 'error' : 'info'](`[GPB/PreMiD ${this.metadata.service}] ${message}`);
  }
}
