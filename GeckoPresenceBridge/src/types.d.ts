type GeckoTab = {
  id: number;
  active: boolean;
  url?: string;
  title?: string;
};

declare const browser: {
  tabs: {
    query(query: { active?: boolean; currentWindow?: boolean }): Promise<GeckoTab[]>;
    sendMessage(tabId: number, message: unknown, options?: { frameId?: number }): Promise<unknown>;
    executeScript(tabId: number, details: { file: string; runAt?: 'document_start' | 'document_end' | 'document_idle'; allFrames?: boolean }): Promise<unknown[]>;
    onActivated: { addListener(listener: (info: { tabId: number; windowId: number }) => void): void };
    onUpdated: {
      addListener(listener: (tabId: number, changeInfo: { status?: string; url?: string; title?: string }, tab: GeckoTab) => void): void;
    };
    onRemoved: { addListener(listener: (tabId: number) => void): void };
  };
  storage: {
    local: {
      get(keys: string | string[]): Promise<Record<string, unknown>>;
      set(items: Record<string, unknown>): Promise<void>;
    };
  };
  permissions: {
    request(details: { origins?: string[] }): Promise<boolean>;
    contains(details: { origins?: string[] }): Promise<boolean>;
    remove(details: { origins?: string[] }): Promise<boolean>;
  };
  runtime: {
    onInstalled: { addListener(listener: () => void): void };
    onStartup: { addListener(listener: () => void): void };
    onMessage: {
      addListener(listener: (message: unknown, sender: { tab?: { id?: number }; url?: string; frameId?: number }) => unknown): void;
    };
    sendMessage(message: unknown): Promise<unknown>;
  };
};
