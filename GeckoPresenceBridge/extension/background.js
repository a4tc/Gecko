import { createActivityEvent, displayNameForHost, isMessageFromActiveTab, isSensitiveHost, selectActiveTab, } from './core/activity.js';
import { permissionPatternForUrl } from './core/site-permissions.js';
import { AdapterRegistry, BUILTIN_ADAPTERS } from './core/adapter-registry.js';
import { IMPORTED_ADAPTERS } from './adapters/imported-registry.js';
import { fromLegacyActivity, normalizeUnifiedActivity, toBridgePayloadV2 } from './core/activity-model.js';
import { arbitrateActivity } from './core/activity-arbiter.js';
import { buildManualPresence } from './core/manual-presence.js';
const BRIDGE_BASE_URL = 'http://127.0.0.1:32145';
const BRIDGE_TIMEOUT_MS = 5000;
const adapterRegistry = new AdapterRegistry([...BUILTIN_ADAPTERS, ...IMPORTED_ADAPTERS]);
const diagnosticEvents = [];
function recordDiagnostic(category, message, frameId) {
    diagnosticEvents.push({ at: Date.now(), category, message: message.slice(0, 180), ...(Number.isInteger(frameId) ? { frameId } : {}) });
    if (diagnosticEvents.length > 80)
        diagnosticEvents.splice(0, diagnosticEvents.length - 80);
    void browser.storage.local.set({ gpbDiagnostics: diagnosticEvents });
}
async function getLabSnapshot() {
    const stored = await browser.storage.local.get(['currentActivityV2', 'bridgeStatus', 'gpbDiagnostics', 'manualPresence']);
    return {
        activity: stored.currentActivityV2 ?? null,
        bridgeStatus: stored.bridgeStatus ?? { state: 'unknown', message: 'No bridge status yet.' },
        diagnostics: Array.isArray(stored.gpbDiagnostics) ? stored.gpbDiagnostics.slice(-80) : diagnosticEvents.slice(-80),
        adapters: adapterRegistry.list().map(({ id, kind, enabled, priority, hosts, version }) => ({ id, kind, enabled, priority, hosts, version })),
        manualPresenceEnabled: stored.manualPresence?.enabled === true,
    };
}
let bridgeQueue = Promise.resolve({
    ok: true,
    state: 'idle',
    message: 'No bridge request has been made.',
});
function isMediaState(value) {
    return ['playing', 'paused', 'ended', 'unknown'].includes(String(value));
}
function isMediaMessage(value) {
    if (!value || typeof value !== 'object')
        return false;
    const message = value;
    return message.type === 'MEDIA_STATE'
        && ['playing', 'paused', 'ended', 'unknown'].includes(String(message.state))
        && (message.pageTitle === undefined || typeof message.pageTitle === 'string')
        && (message.duration === undefined || (typeof message.duration === 'number' && Number.isFinite(message.duration)))
        && (message.currentTime === undefined || (typeof message.currentTime === 'number' && Number.isFinite(message.currentTime)));
}
function isBridgeSettingsMessage(value) {
    if (!value || typeof value !== 'object')
        return false;
    const message = value;
    return message.type === 'BRIDGE_SETTINGS_UPDATED' || message.type === 'BRIDGE_SYNC_NOW';
}
function sameActivity(left, right) {
    if (left === null || right === null)
        return left === right;
    return left.source === right.source
        && left.details === right.details
        && left.state === right.state
        && left.mediaState === right.mediaState
        && left.pageTitle === right.pageTitle;
}
function toBridgePayload(activity, unified) {
    if (unified)
        return toBridgePayloadV2(unified);
    return {
        source: activity.source,
        name: displayNameForHost(activity.source),
        details: activity.details,
        state: activity.pageTitle,
        mediaState: activity.mediaState === 'unknown' ? 'browsing' : activity.mediaState,
        startTimestamp: Math.floor(activity.occurredAt / 1000),
    };
}
async function saveBridgeStatus(result) {
    await browser.storage.local.set({
        bridgeStatus: {
            ...result,
            checkedAt: Date.now(),
        },
    });
    return result;
}
async function syncToAndroidHost(activity, unified) {
    const settings = await browser.storage.local.get(['bridgeEnabled', 'bridgeKey']);
    if (settings.bridgeEnabled !== true) {
        recordDiagnostic('bridge', 'Bridge forwarding disabled by user setting.');
        return saveBridgeStatus({
            ok: false,
            state: 'disabled',
            message: 'Android bridge is disabled in extension settings.',
        });
    }
    const bridgeKey = typeof settings.bridgeKey === 'string' ? settings.bridgeKey.trim() : '';
    if (!bridgeKey) {
        recordDiagnostic('bridge', 'Pairing key missing; host forwarding not attempted.');
        return saveBridgeStatus({
            ok: false,
            state: 'pairing-required',
            message: 'Copy the pairing key from GeckoPresenceHost and save it in this extension.',
        });
    }
    const isClear = activity === null;
    const endpoint = isClear ? '/v2/clear' : '/v2/activity';
    const body = isClear ? '{}' : JSON.stringify(toBridgePayload(activity, unified));
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), BRIDGE_TIMEOUT_MS);
    try {
        const response = await fetch(`${BRIDGE_BASE_URL}${endpoint}`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-GPB-Key': bridgeKey,
            },
            body,
            cache: 'no-store',
            credentials: 'omit',
            referrerPolicy: 'no-referrer',
            signal: controller.signal,
        });
        if (response.ok) {
            recordDiagnostic('bridge', isClear ? 'Activity cleared on Android host.' : 'UAM v2 activity accepted by Android host.');
            return saveBridgeStatus({
                ok: true,
                state: 'connected',
                message: isClear ? 'Activity cleared on Android host.' : 'Activity delivered to Android host.',
                httpStatus: response.status,
            });
        }
        if (response.status === 401) {
            recordDiagnostic('bridge', 'Android host rejected the pairing key.');
            return saveBridgeStatus({
                ok: false,
                state: 'pairing-invalid',
                message: 'Android host rejected the pairing key. Copy it again from the host app.',
                httpStatus: response.status,
            });
        }
        recordDiagnostic('bridge', `Android host returned HTTP ${response.status}.`);
        return saveBridgeStatus({
            ok: false,
            state: 'host-error',
            message: `Android host rejected the request (HTTP ${response.status}).`,
            httpStatus: response.status,
        });
    }
    catch (error) {
        const timedOut = error instanceof Error && error.name === 'AbortError';
        recordDiagnostic('bridge', timedOut ? 'Android host request timed out.' : 'Android host is unreachable.');
        return saveBridgeStatus({
            ok: false,
            state: 'unreachable',
            message: timedOut
                ? 'Android host did not respond within five seconds.'
                : 'Cannot reach Android host. Keep GeckoPresenceHost running with the bridge listening.',
        });
    }
    finally {
        clearTimeout(timeoutId);
    }
}
async function sendManualOverrideClear() {
    const settings = await browser.storage.local.get(['bridgeEnabled', 'bridgeKey']);
    const bridgeKey = typeof settings.bridgeKey === 'string' ? settings.bridgeKey.trim() : '';
    if (!bridgeKey) {
        recordDiagnostic('manual', 'Manual override disabled locally; Android host restore requires a pairing key.');
        return { ok: false, state: 'pairing-required', message: 'Manual override disabled locally; Android host restore requires pairing.' };
    }
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), BRIDGE_TIMEOUT_MS);
    try {
        const response = await fetch(`${BRIDGE_BASE_URL}/v2/manual/clear`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'X-GPB-Key': bridgeKey },
            body: '{}', cache: 'no-store', credentials: 'omit', referrerPolicy: 'no-referrer', signal: controller.signal,
        });
        if (!response.ok)
            throw new Error(`Android host returned HTTP ${response.status}.`);
        recordDiagnostic('manual', 'Manual override cleared on Android host; previous automatic source restored.');
        if (settings.bridgeEnabled === true)
            return saveBridgeStatus({ ok: true, state: 'connected', message: 'Manual override cleared on Android host.', httpStatus: response.status });
        return { ok: true, state: 'disabled', message: 'Manual override cleared; normal bridge forwarding remains disabled.', httpStatus: response.status };
    }
    catch (error) {
        const message = error instanceof Error ? error.message : 'Could not clear the manual override on Android host.';
        recordDiagnostic('manual', message);
        return saveBridgeStatus({ ok: false, state: 'unreachable', message });
    }
    finally {
        clearTimeout(timeoutId);
    }
}
function queueManualOverrideClear() {
    const operation = bridgeQueue.then(sendManualOverrideClear, sendManualOverrideClear);
    bridgeQueue = operation.catch(() => ({ ok: false, state: 'error', message: 'Manual override clear failed.' }));
    return operation;
}
function queueBridgeSync(activity, unified) {
    const operation = bridgeQueue.then(() => syncToAndroidHost(activity, unified), () => syncToAndroidHost(activity, unified));
    bridgeQueue = operation.catch(() => ({
        ok: false,
        state: 'error',
        message: 'Bridge synchronization failed.',
    }));
    return operation;
}
async function syncStoredActivity() {
    const stored = await browser.storage.local.get(['currentActivity', 'currentActivityV2']);
    const activity = (stored.currentActivity ?? null);
    const unified = (stored.currentActivityV2 ?? undefined);
    return queueBridgeSync(activity, unified);
}
async function storeIfChanged(next, tabId, unified) {
    const stored = await browser.storage.local.get(['currentActivity', 'currentTabId', 'currentActivityV2']);
    const current = (stored.currentActivity ?? null);
    const currentUnified = (stored.currentActivityV2 ?? null);
    const stable = (value) => {
        if (!value)
            return null;
        const { updatedAt: _updatedAt, ...rest } = value;
        return rest;
    };
    const unifiedSame = JSON.stringify(stable(currentUnified)) === JSON.stringify(stable(unified));
    if (sameActivity(current, next) && stored.currentTabId === tabId && unifiedSame)
        return;
    await browser.storage.local.set({ currentActivity: next, currentTabId: tabId ?? null, currentActivityV2: unified ?? null });
    await queueBridgeSync(next, unified);
}
async function getCurrentActiveTab() {
    const tabs = await browser.tabs.query({ active: true, currentWindow: true });
    return selectActiveTab(tabs);
}
async function injectGenericMediaDetector(tab) {
    if (!tab.url)
        return;
    let parsed;
    try {
        parsed = new URL(tab.url);
    }
    catch {
        return;
    }
    if (isSensitiveHost(parsed.hostname))
        return;
    const originPattern = permissionPatternForUrl(tab.url);
    if (!originPattern)
        return;
    try {
        const hasPermission = await browser.permissions.contains({ origins: [originPattern] });
        if (!hasPermission)
            return;
        await browser.tabs.executeScript(tab.id, {
            file: 'content/generic-media.js',
            runAt: 'document_idle',
            allFrames: true,
        });
        const adapters = adapterRegistry.resolveAll(parsed.hostname);
        recordDiagnostic('adapter', adapters.length ? `Matched ${adapters.map((item) => item.id).join(', ')}.` : 'No site adapter matched this host.');
        for (const adapter of adapters) {
            if (adapter.id === 'idlix-native') {
                await browser.tabs.executeScript(tab.id, { file: 'content/idlix-presence.js', runAt: 'document_idle' });
            }
            if (adapter.kind === 'premid' && adapter.runtimePath && adapter.configPath) {
                await browser.tabs.executeScript(tab.id, { file: 'content/premid-compat-runtime.js', runAt: 'document_idle', allFrames: true });
                await browser.tabs.executeScript(tab.id, { file: adapter.configPath, runAt: 'document_idle', allFrames: true });
                if (adapter.iframePath)
                    await browser.tabs.executeScript(tab.id, { file: adapter.iframePath, runAt: 'document_idle', allFrames: true });
                await browser.tabs.executeScript(tab.id, { file: adapter.runtimePath, runAt: 'document_idle' });
            }
        }
    }
    catch {
        // Restricted pages, permission removal and unsupported injection targets are ignored.
    }
}
async function refreshActiveActivity() {
    const manualSettings = await browser.storage.local.get('manualPresence');
    const manualInput = manualSettings.manualPresence && typeof manualSettings.manualPresence === 'object'
        ? manualSettings.manualPresence
        : { enabled: false };
    if (manualInput.enabled === true) {
        try {
            const unified = buildManualPresence(manualInput);
            if (unified) {
                const event = { kind: 'activity.update', source: 'gecko-presence.local', details: unified.details, state: unified.state ?? '', mediaState: 'unknown', occurredAt: Date.now() };
                recordDiagnostic('manual', 'Custom Presence override is active.');
                await storeIfChanged(event, undefined, unified);
                return;
            }
        }
        catch (error) {
            recordDiagnostic('manual', error instanceof Error ? error.message : 'Invalid manual presence.');
        }
    }
    const tab = await getCurrentActiveTab();
    if (!tab) {
        await browser.storage.local.set({ currentActivity: null, currentTabId: null, currentActivityV2: null });
        await queueBridgeSync(null);
        return;
    }
    await injectGenericMediaDetector(tab);
    let mediaState = 'unknown';
    let mediaTitle;
    let durationSeconds;
    let currentTimeSeconds;
    try {
        const response = await browser.tabs.sendMessage(tab.id, { type: 'GET_MEDIA_STATE' });
        if (isMediaState(response)) {
            mediaState = response;
        }
        else if (response && typeof response === 'object') {
            const mediaResponse = response;
            if (isMediaState(mediaResponse.state))
                mediaState = mediaResponse.state;
            if (typeof mediaResponse.pageTitle === 'string')
                mediaTitle = mediaResponse.pageTitle;
            if (typeof mediaResponse.duration === 'number')
                durationSeconds = mediaResponse.duration;
            if (typeof mediaResponse.currentTime === 'number')
                currentTimeSeconds = mediaResponse.currentTime;
        }
    }
    catch {
        // No content script is expected on unsupported sites or before a page has loaded.
    }
    await updateFromActiveTab(mediaState, tab.id, mediaTitle, { durationSeconds, currentTimeSeconds });
}
async function updateFromActiveTab(mediaState = 'unknown', senderTabId, mediaTitle, timeline = {}) {
    const tab = await getCurrentActiveTab();
    if (!tab) {
        await storeIfChanged(null);
        return;
    }
    // Ignore late messages from background tabs; they must not clear foreground activity.
    if (senderTabId !== undefined && !isMessageFromActiveTab(tab.id, senderTabId))
        return;
    let effectiveMediaState = mediaState === 'preserve' ? 'unknown' : mediaState;
    if (mediaState === 'preserve') {
        const previous = await browser.storage.local.get(['currentActivity', 'currentTabId']);
        const previousActivity = (previous.currentActivity ?? null);
        const currentSource = createActivityEvent(tab)?.source;
        if (previous.currentTabId === tab.id && previousActivity && previousActivity.source === currentSource) {
            effectiveMediaState = previousActivity.mediaState;
        }
    }
    const event = createActivityEvent(tab, effectiveMediaState, {
        includeTitle: true,
        mediaTitle,
    });
    if (!event) {
        await browser.storage.local.set({ currentActivity: null, currentTabId: null, currentActivityV2: null });
        await queueBridgeSync(null);
        return;
    }
    const unifiedCandidate = await createUnifiedActivity(tab, event, timeline);
    const unified = arbitrateActivity([unifiedCandidate], Date.now()) ?? unifiedCandidate;
    await storeIfChanged(event, tab.id, unified);
}
async function createUnifiedActivity(tab, event, timeline = {}) {
    const adapter = adapterRegistry.resolve(event.source);
    if (adapter?.id === 'idlix-native') {
        let metadata = {};
        try {
            const response = await browser.tabs.sendMessage(tab.id, { type: 'GET_IDLIX_METADATA' }, { frameId: 0 });
            if (response && typeof response === 'object')
                metadata = response;
        }
        catch { /* Adapter is absent on restricted pages or before content readiness. */ }
        if (Object.keys(metadata).length === 0) {
            recordDiagnostic('idlix', 'Metadata unavailable; generic media fallback is active.');
            return fromLegacyActivity({ source: event.source, details: event.details, pageTitle: event.pageTitle, mediaState: event.mediaState, occurredAt: event.occurredAt });
        }
        const routeType = typeof metadata.routeType === 'string' ? metadata.routeType : 'browse';
        const title = typeof metadata.seriesTitle === 'string' ? metadata.seriesTitle : typeof metadata.title === 'string' ? metadata.title : event.pageTitle ?? '';
        const episodeParts = [
            typeof metadata.seasonNumber === 'number' ? `Season ${metadata.seasonNumber}` : '',
            typeof metadata.episodeNumber === 'number' ? `Episode ${metadata.episodeNumber}` : '',
        ].filter(Boolean);
        const episodeTitle = typeof metadata.episodeTitle === 'string' ? metadata.episodeTitle : '';
        recordDiagnostic('idlix', `route=${routeType}; title=${title ? 'present' : 'missing'}; titleSource=${String(metadata.titleSource ?? 'unknown')}; artwork=${String(metadata.artworkSource ?? 'unknown')}; episode=${episodeParts.length > 0 || Boolean(episodeTitle) ? 'present' : 'missing'}.`);
        const episodeState = [episodeParts.join(' · '), episodeTitle].filter(Boolean).join(' — ');
        const details = title || (routeType === 'series' ? 'Browsing series' : routeType === 'movie' ? 'Browsing movies' : routeType === 'search' ? 'Searching IDLIX' : 'Browsing IDLIX');
        const now = Date.now();
        const startMs = event.mediaState === 'playing' && Number.isFinite(timeline.durationSeconds) && Number.isFinite(timeline.currentTimeSeconds)
            ? now - Number(timeline.currentTimeSeconds) * 1000 : undefined;
        const endMs = startMs !== undefined ? startMs + Number(timeline.durationSeconds) * 1000 : undefined;
        return normalizeUnifiedActivity({
            schemaVersion: 2, id: `idlix:${event.source}:${routeType}`, source: event.source, sourceType: 'website',
            activityType: 'Watching', name: 'IDLIX', details, state: episodeState || (routeType === 'movie' ? 'Movie' : undefined),
            mediaState: event.mediaState, timestamps: { startMs, endMs },
            assets: { large: { url: metadata.artworkUrl, text: title || 'IDLIX' } },
            metadata: { routeType, ...(title ? { seriesTitle: title } : {}),
                ...(typeof metadata.seasonNumber === 'number' ? { seasonNumber: metadata.seasonNumber } : {}),
                ...(typeof metadata.episodeNumber === 'number' ? { episodeNumber: metadata.episodeNumber } : {}),
                ...(episodeTitle ? { episodeTitle } : {}) },
            priority: title ? 90 : 20, confidence: title ? 0.92 : 0.3, updatedAt: now,
        });
    }
    return fromLegacyActivity({
        source: event.source, details: event.details, pageTitle: event.pageTitle,
        mediaState: event.mediaState, occurredAt: event.occurredAt,
    });
}
function valueText(value) {
    if (typeof value === 'string')
        return value.replace(/[\u0000-\u001f\u007f]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 120);
    if (value && typeof value === 'object' && 'textContent' in value)
        return valueText(value.textContent);
    return '';
}
function buildPreMiDActivity(tab, service, data) {
    if (!tab.url)
        return null;
    let parsed;
    try {
        parsed = new URL(tab.url);
    }
    catch {
        return null;
    }
    const typeMap = { 0: 'Playing', 1: 'Streaming', 2: 'Listening', 3: 'Watching', 5: 'Competing' };
    const activityType = typeMap[Number(data.type)] ?? 'Playing';
    const mediaState = ['Watching', 'Listening', 'Streaming'].includes(activityType) ? 'playing' : 'unknown';
    const details = valueText(data.details) || service || displayNameForHost(parsed.hostname);
    const state = valueText(data.state);
    const genericDetails = /^(?:browsing|website|home|searching|watching|playing|listening|viewing)$/i.test(details);
    if (genericDetails && !state)
        return null;
    const timestamp = (value) => {
        if (value instanceof Date)
            return value.getTime();
        if (!Number.isFinite(value))
            return undefined;
        const numeric = Number(value);
        return numeric > 0 ? (numeric < 10_000_000_000 ? numeric * 1000 : numeric) : undefined;
    };
    const safeAsset = (value, text) => {
        const raw = valueText(value);
        if (!raw)
            return undefined;
        try {
            const parsedAsset = new URL(raw);
            if (parsedAsset.protocol !== 'https:' || parsedAsset.username || parsedAsset.password
                || (parsedAsset.hostname !== parsed.hostname && parsedAsset.hostname !== 'cdn.rcd.gg'))
                return undefined;
            return { url: parsedAsset.toString(), ...(valueText(text) ? { text: valueText(text) } : {}) };
        }
        catch {
            return undefined;
        }
    };
    const safeButtonUrl = (raw) => {
        try {
            const target = new URL(valueText(raw));
            return target.protocol === 'https:' && !target.username && !target.password
                && (target.hostname === parsed.hostname || target.hostname.endsWith(`.${parsed.hostname}`)) ? target.toString() : '';
        }
        catch {
            return '';
        }
    };
    const large = safeAsset(data.largeImageKey, data.largeImageText);
    const small = safeAsset(data.smallImageKey, data.smallImageText);
    const buttons = Array.isArray(data.buttons) ? data.buttons.slice(0, 2).map((button) => ({ label: valueText(button?.label), url: safeButtonUrl(button?.url) })).filter((button) => button.label && button.url) : [];
    return normalizeUnifiedActivity({
        schemaVersion: 2, id: `premid:${service.toLowerCase()}`, source: parsed.hostname, sourceType: 'website',
        activityType, name: service, details, state, mediaState,
        timestamps: { startMs: timestamp(data.startTimestamp), endMs: timestamp(data.endTimestamp) },
        assets: { ...(large ? { large } : {}), ...(small ? { small } : {}) },
        buttons, priority: 40, confidence: 0.76, updatedAt: Date.now(),
    });
}
async function handlePreMiDMessage(message, sender) {
    const tabId = sender.tab?.id;
    if (typeof tabId !== 'number')
        return undefined;
    const activeTab = await getCurrentActiveTab();
    if (!activeTab || activeTab.id !== tabId)
        return undefined;
    if (message.type === 'PREMID_IFRAME_DATA') {
        recordDiagnostic('frame', 'Received PreMiD iframe data.', sender.frameId);
        if (sender.frameId === 0)
            return undefined;
        try {
            await browser.tabs.sendMessage(tabId, { type: 'PREMID_IFRAME_DATA', data: message.data }, { frameId: 0 });
        }
        catch { /* Top frame may have navigated away. */ }
        return undefined;
    }
    if (sender.frameId !== 0)
        return undefined;
    if (message.type === 'PREMID_RUNTIME_READY') {
        recordDiagnostic('adapter', 'PreMiD compatibility runtime initialized.', sender.frameId);
        return undefined;
    }
    if (message.type === 'PREMID_ACTIVITY_CLEAR') {
        const manual = await browser.storage.local.get('manualPresence');
        if (manual.manualPresence?.enabled === true) {
            recordDiagnostic('adapter', 'Ignored PreMiD clear while manual override is active.', sender.frameId);
            return undefined;
        }
        recordDiagnostic('adapter', 'PreMiD activity cleared.', sender.frameId);
        await storeIfChanged(null);
        return queueBridgeSync(null);
    }
    if (message.type === 'PREMID_ACTIVITY_UPDATE' && message.activity && typeof message.activity === 'object') {
        const service = valueText(message.service) || 'Website';
        const unified = buildPreMiDActivity(activeTab, service, message.activity);
        if (!unified)
            return undefined;
        const existing = await browser.storage.local.get('currentActivityV2');
        const current = existing.currentActivityV2 && typeof existing.currentActivityV2 === 'object'
            ? existing.currentActivityV2 : null;
        const winner = arbitrateActivity([...(current ? [current] : []), unified], Date.now());
        if (winner !== unified) {
            recordDiagnostic('adapter', `Ignored lower-priority PreMiD candidate for ${service}.`, sender.frameId);
            return undefined;
        }
        recordDiagnostic('adapter', `PreMiD activity update: ${service}.`, sender.frameId);
        const event = createActivityEvent(activeTab, unified.mediaState, { includeTitle: true, mediaTitle: unified.details });
        if (!event)
            return undefined;
        await storeIfChanged(event, tabId, unified);
        return undefined;
    }
    return undefined;
}
// Event listeners are registered synchronously at module startup so the event page
// can be suspended and restarted without losing its wake-up registrations.
browser.runtime.onInstalled.addListener(() => {
    void browser.storage.local.get(['bridgeEnabled', 'bridgeKey']).then(async (settings) => {
        const defaults = {};
        if (typeof settings.bridgeEnabled !== 'boolean')
            defaults.bridgeEnabled = false;
        if (typeof settings.bridgeKey !== 'string')
            defaults.bridgeKey = '';
        if (Object.keys(defaults).length > 0)
            await browser.storage.local.set(defaults);
        await refreshActiveActivity();
        await syncStoredActivity();
    });
});
browser.runtime.onStartup.addListener(() => {
    void refreshActiveActivity().then(syncStoredActivity);
});
browser.tabs.onActivated.addListener(() => {
    void refreshActiveActivity();
});
browser.tabs.onUpdated.addListener((_tabId, changeInfo, tab) => {
    if (tab.active && (changeInfo.status === 'complete' || typeof changeInfo.url === 'string' || typeof changeInfo.title === 'string')) {
        void injectGenericMediaDetector(tab);
        void updateFromActiveTab('preserve');
    }
});
browser.tabs.onRemoved.addListener(() => {
    void refreshActiveActivity();
});
browser.runtime.onMessage.addListener((message, sender) => {
    if (message && typeof message === 'object' && message.type === 'IDLIX_METADATA' && typeof sender.tab?.id === 'number' && sender.frameId === 0) {
        recordDiagnostic('idlix', 'IDLIX metadata mutation observed; refreshing active activity.', sender.frameId);
        return refreshActiveActivity();
    }
    if (message && typeof message === 'object' && message.type === 'GPB_GET_LAB_SNAPSHOT')
        return getLabSnapshot();
    if (message && typeof message === 'object' && message.type === 'GPB_REFRESH_ACTIVITY')
        return refreshActiveActivity();
    if (message && typeof message === 'object' && message.type === 'GPB_MANUAL_OVERRIDE_DISABLED') {
        return queueManualOverrideClear().then(() => refreshActiveActivity());
    }
    if (message && typeof message === 'object' && ['PREMID_IFRAME_DATA', 'PREMID_ACTIVITY_UPDATE', 'PREMID_ACTIVITY_CLEAR', 'PREMID_RUNTIME_READY'].includes(String(message.type))) {
        return handlePreMiDMessage(message, sender);
    }
    if (isMediaMessage(message) && typeof sender.tab?.id === 'number') {
        if (sender.url) {
            try {
                if (isSensitiveHost(new URL(sender.url).hostname))
                    return undefined;
            }
            catch {
                return undefined;
            }
        }
        const tabId = sender.tab.id;
        const resolvePageTitle = async () => {
            if (sender.frameId === 0) {
                return typeof message.pageTitle === 'string' ? message.pageTitle : undefined;
            }
            // Playback can originate in a cross-origin iframe. Ask the top-level
            // document for its page title instead of trying to read across frames.
            try {
                const topFrame = await browser.tabs.sendMessage(tabId, { type: 'GET_MEDIA_STATE' }, { frameId: 0 });
                if (topFrame && typeof topFrame === 'object') {
                    const response = topFrame;
                    if (typeof response.pageTitle === 'string')
                        return response.pageTitle;
                }
            }
            catch {
                // The top frame may not have a content script on restricted pages.
            }
            return undefined;
        };
        return resolvePageTitle().then((mediaTitle) => updateFromActiveTab(message.state, tabId, mediaTitle, {
            durationSeconds: message.duration, currentTimeSeconds: message.currentTime,
        }));
    }
    if (isBridgeSettingsMessage(message)) {
        if (message.type === 'BRIDGE_SETTINGS_UPDATED') {
            return refreshActiveActivity().then(syncStoredActivity);
        }
        return syncStoredActivity();
    }
    return undefined;
});
