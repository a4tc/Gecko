import { isOriginPattern, normalizeOriginPattern } from './core/site-permissions.js';

const HOST_URL = 'http://127.0.0.1:32145';
const enabledInput = document.querySelector<HTMLInputElement>('#bridge-enabled');
const keyInput = document.querySelector<HTMLInputElement>('#pairing-key');
const revealButton = document.querySelector<HTMLButtonElement>('#reveal-key');
const saveButton = document.querySelector<HTMLButtonElement>('#save-settings');
const healthButton = document.querySelector<HTMLButtonElement>('#check-health');
const statusElement = document.querySelector<HTMLElement>('#status');
const siteHostInput = document.querySelector<HTMLInputElement>('#site-host');
const addSiteButton = document.querySelector<HTMLButtonElement>('#add-site');
const siteOriginList = document.querySelector<HTMLElement>('#site-origin-list');
const siteCount = document.querySelector<HTMLElement>('#site-count');

type BridgeSettings = {
  bridgeEnabled?: boolean;
  bridgeKey?: string;
  bridgeStatus?: { message?: string; ok?: boolean; state?: string };
  siteOrigins?: string[];
};

function showStatus(message: string, kind: 'neutral' | 'success' | 'warning' | 'error' = 'neutral'): void {
  if (!statusElement) return;
  statusElement.textContent = message;
  statusElement.dataset.kind = kind;
}

async function loadSettings(): Promise<void> {
  const settings = await browser.storage.local.get([
    'bridgeEnabled', 'bridgeKey', 'bridgeStatus', 'siteOrigins',
  ]) as BridgeSettings;
  if (enabledInput) enabledInput.checked = settings.bridgeEnabled === true;
  if (keyInput) keyInput.value = typeof settings.bridgeKey === 'string' ? settings.bridgeKey : '';
  await renderSiteOrigins(Array.isArray(settings.siteOrigins) ? settings.siteOrigins : []);
  const currentStatus = settings.bridgeStatus;
  if (currentStatus?.message) {
    showStatus(currentStatus.message, currentStatus.ok ? 'success' : currentStatus.state === 'disabled' ? 'neutral' : 'warning');
  }
}

async function saveSettings(): Promise<void> {
  if (!enabledInput || !keyInput) return;
  const bridgeKey = keyInput.value.trim();
  await browser.storage.local.set({ bridgeEnabled: enabledInput.checked, bridgeKey });
  if (enabledInput.checked && !bridgeKey) {
    showStatus('Settings saved, but the bridge is not paired. Copy the pairing key from the Android app.', 'warning');
    await browser.runtime.sendMessage({ type: 'BRIDGE_SETTINGS_UPDATED' });
    return;
  }
  const result = await browser.runtime.sendMessage({ type: 'BRIDGE_SETTINGS_UPDATED' }) as { message?: string; state?: string; ok?: boolean };
  showStatus(result?.message ?? 'Settings saved. Only the active browser tab can publish activity.', result?.ok ? 'success' : 'neutral');
}

async function renderSiteOrigins(origins: string[]): Promise<void> {
  if (!siteOriginList || !siteCount) return;
  const valid = [...new Set(origins.filter(isOriginPattern))].sort();
  const checks = await Promise.all(valid.map(async (origin) => {
    try {
      const granted = await browser.permissions.contains({ origins: [origin] });
      return { origin, granted };
    } catch {
      return { origin, granted: false };
    }
  }));
  siteOriginList.replaceChildren();
  const active = checks.filter((item) => item.granted);
  siteCount.textContent = `${active.length} ACTIVE`;
  if (checks.length === 0) {
    const empty = document.createElement('p');
    empty.className = 'empty';
    empty.textContent = 'No extra website permissions. YouTube’s built-in detector remains available.';
    siteOriginList.append(empty);
    return;
  }
  for (const item of checks) {
    const row = document.createElement('div');
    row.className = 'site-row';
    const info = document.createElement('div');
    info.className = 'site-domain';
    const originText = document.createElement('span');
    originText.textContent = item.origin.replace(/\/\*$/, '');
    const state = document.createElement('small');
    state.className = 'site-state';
    state.textContent = item.granted ? 'PERMISSION ACTIVE' : 'REVOKED IN FIREFOX';
    info.append(originText, state);
    const remove = document.createElement('button');
    remove.className = 'site-remove';
    remove.type = 'button';
    remove.textContent = 'REVOKE';
    remove.disabled = !item.granted;
    remove.setAttribute('aria-label', `Revoke website permission for ${item.origin}`);
    remove.addEventListener('click', () => { void removeSiteOrigin(item.origin); });
    row.append(info, remove);
    siteOriginList.append(row);
  }
}

async function addSiteOrigin(): Promise<void> {
  if (!siteHostInput || !addSiteButton) return;
  let origin: string;
  try {
    origin = normalizeOriginPattern(siteHostInput.value);
  } catch (error) {
    showStatus(error instanceof Error ? error.message : 'Invalid website domain.', 'error');
    return;
  }
  if (addSiteButton.disabled) return;
  addSiteButton.disabled = true;
  try {
    // Keep this permission request directly in the button gesture; do not request both wildcard schemes.
    const granted = await browser.permissions.request({ origins: [origin] });
    if (!granted) {
      showStatus(`Firefox did not grant access to ${origin.replace(/\/\*$/, '')}. No other sites were changed.`, 'warning');
      return;
    }
    const settings = await browser.storage.local.get('siteOrigins') as { siteOrigins?: unknown };
    const existing = Array.isArray(settings.siteOrigins) ? settings.siteOrigins.filter(isOriginPattern) : [];
    const updated = [...new Set([...existing, origin])];
    await browser.storage.local.set({ siteOrigins: updated });
    await browser.runtime.sendMessage({ type: 'BRIDGE_SETTINGS_UPDATED' });
    siteHostInput.value = '';
    await renderSiteOrigins(updated);
    showStatus(`Website access enabled for ${origin.replace(/\/\*$/, '')}. The detector will run only on this active, non-sensitive site.`, 'success');
  } catch (error) {
    const reason = error instanceof Error && error.message ? ` (${error.message})` : '';
    showStatus(`Firefox could not grant ${origin.replace(/\/\*$/, '')}${reason}. Check the add-on's site access in Firefox Settings.`, 'error');
  } finally {
    addSiteButton.disabled = false;
  }
}

async function removeSiteOrigin(origin: string): Promise<void> {
  try {
    await browser.permissions.remove({ origins: [origin] });
    const settings = await browser.storage.local.get('siteOrigins') as { siteOrigins?: unknown };
    const existing = Array.isArray(settings.siteOrigins) ? settings.siteOrigins.filter(isOriginPattern) : [];
    const updated = existing.filter((item) => item !== origin);
    await browser.storage.local.set({ siteOrigins: updated });
    await browser.runtime.sendMessage({ type: 'BRIDGE_SETTINGS_UPDATED' });
    await renderSiteOrigins(updated);
    showStatus(`Website access revoked for ${origin.replace(/\/\*$/, '')}.`, 'success');
  } catch (error) {
    const reason = error instanceof Error && error.message ? ` (${error.message})` : '';
    showStatus(`Could not revoke website access${reason}.`, 'error');
  }
}

async function checkHealth(): Promise<void> {
  if (healthButton) healthButton.disabled = true;
  showStatus('Checking 127.0.0.1:32145…');
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 5000);
  try {
    const response = await fetch(`${HOST_URL}/v1/health`, {
      method: 'GET', cache: 'no-store', credentials: 'omit', referrerPolicy: 'no-referrer', signal: controller.signal,
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const health = await response.json() as { ok?: boolean; apiVersion?: number; service?: string; bridgeState?: string; discordSdkEnabled?: boolean };
    if (health.ok !== true || health.apiVersion !== 1) throw new Error('Unexpected local bridge response');
    const sdk = health.discordSdkEnabled ? 'Discord SDK enabled' : 'Preview build: Discord SDK not configured';
    showStatus(`${health.service}: ${health.bridgeState}. ${sdk}. Health check does not verify the pairing key.`, 'success');
  } catch (error) {
    const message = error instanceof Error && error.name === 'AbortError'
      ? 'Android host did not respond within five seconds.'
      : 'Cannot reach Android host. Open Gecko Presence and make sure Local Bridge is listening.';
    showStatus(message, 'error');
  } finally {
    clearTimeout(timeoutId);
    if (healthButton) healthButton.disabled = false;
  }
}

revealButton?.addEventListener('click', () => {
  if (!keyInput || !revealButton) return;
  const reveal = keyInput.type === 'password';
  keyInput.type = reveal ? 'text' : 'password';
  revealButton.textContent = reveal ? 'HIDE' : 'SHOW';
});
saveButton?.addEventListener('click', () => { void saveSettings().catch(() => showStatus('Could not save extension settings.', 'error')); });
healthButton?.addEventListener('click', () => { void checkHealth(); });
addSiteButton?.addEventListener('click', () => { void addSiteOrigin(); });
void loadSettings().catch(() => showStatus('Could not load extension settings.', 'error'));

const previewName = document.querySelector<HTMLElement>('#preview-name');
const previewDetails = document.querySelector<HTMLElement>('#preview-details');
const previewState = document.querySelector<HTMLElement>('#preview-state');
const previewSource = document.querySelector<HTMLElement>('#preview-source');
const previewType = document.querySelector<HTMLElement>('#preview-type');
const previewArtwork = document.querySelector<HTMLImageElement>('#preview-artwork');
const previewTransport = document.querySelector<HTMLElement>('#preview-transport');
const manualEnabled = document.querySelector<HTMLInputElement>('#manual-enabled');
const manualType = document.querySelector<HTMLSelectElement>('#manual-type');
const manualName = document.querySelector<HTMLInputElement>('#manual-name');
const manualDetails = document.querySelector<HTMLInputElement>('#manual-details');
const manualState = document.querySelector<HTMLInputElement>('#manual-state');
const manualStatus = document.querySelector<HTMLElement>('#manual-status');
const manualPresetName = document.querySelector<HTMLInputElement>('#manual-preset-name');
const manualPresetSelect = document.querySelector<HTMLSelectElement>('#manual-preset-select');
const labAdapter = document.querySelector<HTMLElement>('#lab-adapter');
const labFrame = document.querySelector<HTMLElement>('#lab-frame');
const labBridge = document.querySelector<HTMLElement>('#lab-bridge');
const labCount = document.querySelector<HTMLElement>('#lab-count');
const labEvents = document.querySelector<HTMLOListElement>('#lab-events');
const labEventCount = document.querySelector<HTMLElement>('#lab-event-count');
const labStatus = document.querySelector<HTMLElement>('#lab-status');
let lastLabSnapshot: Record<string, any> | null = null;
let manualFieldsInitialized = false;
let lastPresetSignature = '';

function setText(element: HTMLElement | null, value: unknown, fallback = '—'): void {
  if (element) element.textContent = typeof value === 'string' && value ? value : fallback;
}

async function refreshRuntimePanels(): Promise<void> {
  try {
    const snapshot = await browser.runtime.sendMessage({ type: 'GPB_GET_LAB_SNAPSHOT' }) as Record<string, any>;
    if (!snapshot || typeof snapshot !== 'object') throw new Error('Runtime did not return a diagnostic snapshot.');
    lastLabSnapshot = snapshot;
    const activity = snapshot.activity && typeof snapshot.activity === 'object' ? snapshot.activity : null;
    setText(previewName, activity?.name, 'Waiting for activity');
    setText(previewDetails, activity?.details, 'No active browser signal.');
    setText(previewState, activity?.state, activity?.mediaState ?? '—');
    setText(previewSource, activity?.source ? `Source: ${activity.source}` : '', 'Source: none');
    setText(previewType, activity?.activityType ? `Activity type: ${activity.activityType} · Discord transport: Playing` : '', 'Activity type: —');
    const imageUrl = typeof activity?.assets?.large?.url === 'string' ? activity.assets.large.url : '';
    if (previewArtwork) {
      let safeImage = '';
      try {
        const parsedImage = new URL(imageUrl);
        if (parsedImage.protocol === 'https:' && !parsedImage.username && !parsedImage.password
          && (parsedImage.hostname === activity?.source || parsedImage.hostname === 'cdn.rcd.gg')) safeImage = parsedImage.toString();
      } catch { /* Invalid artwork is omitted. */ }
      if (safeImage) { previewArtwork.src = safeImage; previewArtwork.hidden = false; }
      else { previewArtwork.removeAttribute('src'); previewArtwork.hidden = true; }
    }
    const bridge = snapshot.bridgeStatus ?? {};
    setText(previewTransport, `Transport: ${bridge.state ?? 'unknown'} — ${bridge.message ?? 'No bridge status yet.'}`);
    setText(labBridge, bridge.state ?? 'unknown');
    const adapters = Array.isArray(snapshot.adapters) ? snapshot.adapters : [];
    const matching = activity ? adapters.find((adapter: any) => Array.isArray(adapter.hosts) && adapter.hosts.some((host: string) => host === activity.source || host === '*' || (host.startsWith('*.') && activity.source.endsWith(host.slice(1))))) : null;
    setText(labAdapter, matching ? `${matching.id} / ${matching.kind}` : activity ? 'generic / fallback' : 'none');
    setText(labFrame, 'Top frame + approved helpers');
    setText(labCount, String(adapters.length));
    const events = Array.isArray(snapshot.diagnostics) ? snapshot.diagnostics.slice(-30).reverse() : [];
    setText(labEventCount, `${events.length} EVENTS`);
    if (labEvents) {
      labEvents.replaceChildren();
      if (!events.length) {
        const empty = document.createElement('li');
        empty.textContent = 'No runtime events yet. Open an approved website and refresh.';
        labEvents.append(empty);
      }
      for (const event of events) {
        const item = document.createElement('li');
        const time = Number.isFinite(event.at) ? new Date(event.at).toLocaleTimeString() : '--:--:--';
        item.textContent = `${time} [${String(event.category ?? 'event').toUpperCase()}] ${String(event.message ?? '')}${Number.isInteger(event.frameId) ? ` (frame ${event.frameId})` : ''}`;
        labEvents.append(item);
      }
    }
    const manual = await browser.storage.local.get(['manualPresence', 'manualPresencePresets']) as { manualPresence?: Record<string, unknown>; manualPresencePresets?: Array<Record<string, unknown>> };
    const presets = Array.isArray(manual.manualPresencePresets) ? manual.manualPresencePresets : [];
    if (manualPresetSelect) {
      const signature = JSON.stringify(presets.map((preset) => [preset.id, preset.label]));
      if (signature !== lastPresetSignature) {
        const selected = manualPresetSelect.value;
        manualPresetSelect.replaceChildren();
        if (!presets.length) {
          const empty = document.createElement('option'); empty.value = ''; empty.textContent = 'No saved presets'; manualPresetSelect.append(empty);
        } else {
          for (const preset of presets) {
            const option = document.createElement('option');
            option.value = String(preset.id ?? ''); option.textContent = String(preset.label ?? 'Unnamed preset');
            manualPresetSelect.append(option);
          }
          if (presets.some((preset) => String(preset.id) === selected)) manualPresetSelect.value = selected;
        }
        lastPresetSignature = signature;
      }
    }
    const config = manual.manualPresence;
    if (!manualFieldsInitialized) {
      if (config && manualEnabled) {
        manualEnabled.checked = config.enabled === true;
        if (manualName && typeof config.name === 'string') manualName.value = config.name;
        if (manualDetails && typeof config.details === 'string') manualDetails.value = config.details;
        if (manualState && typeof config.state === 'string') manualState.value = config.state;
        if (manualType && typeof config.activityType === 'string') manualType.value = config.activityType;
      }
      manualFieldsInitialized = true;
    }
    if (manualStatus) manualStatus.textContent = snapshot.manualPresenceEnabled ? 'Manual override is active. Automatic source switching is paused.' : 'Automatic activity is active.';
    if (labStatus) labStatus.textContent = 'Runtime snapshot updated. Diagnostic data is stored locally and excludes full page URLs.';
  } catch (error) {
    if (labStatus) labStatus.textContent = error instanceof Error ? error.message : 'Could not load runtime diagnostics.';
  }
}

async function saveManualPresence(): Promise<void> {
  if (!manualName || !manualDetails || !manualType || !manualEnabled) return;
  const name = manualName.value.trim();
  const details = manualDetails.value.trim();
  if (!name || !details) {
    manualEnabled.checked = false;
    if (manualStatus) manualStatus.textContent = 'Activity name and details are required.';
    return;
  }
  const manualPresence = {
    enabled: true,
    name: name.slice(0, 80),
    details: details.slice(0, 120),
    state: manualState?.value.trim().slice(0, 120) ?? '',
    activityType: manualType.value,
  };
  await browser.storage.local.set({ manualPresence });
  manualEnabled.checked = true;
  await browser.runtime.sendMessage({ type: 'BRIDGE_SETTINGS_UPDATED' });
  if (manualStatus) manualStatus.textContent = 'Manual override activated. Automatic source switching is paused.';
  await refreshRuntimePanels();
}

async function disableManualPresence(): Promise<void> {
  await browser.storage.local.set({ manualPresence: { enabled: false } });
  if (manualEnabled) manualEnabled.checked = false;
  await browser.runtime.sendMessage({ type: 'GPB_MANUAL_OVERRIDE_DISABLED' });
  if (manualStatus) manualStatus.textContent = 'Manual override disabled. Automatic activity is restored.';
  await refreshRuntimePanels();
}


async function storeManualPreset(): Promise<void> {
  const label = manualPresetName?.value.trim().slice(0, 40) ?? '';
  if (!label || !manualName?.value.trim() || !manualDetails?.value.trim()) {
    if (manualStatus) manualStatus.textContent = 'Enter a preset name, activity name, and details before storing.';
    return;
  }
  const stored = await browser.storage.local.get('manualPresencePresets') as { manualPresencePresets?: Array<Record<string, unknown>> };
  const presets = Array.isArray(stored.manualPresencePresets) ? stored.manualPresencePresets : [];
  const preset = {
    id: `preset-${Date.now()}`,
    label,
    name: manualName.value.trim().slice(0, 80),
    details: manualDetails.value.trim().slice(0, 120),
    state: manualState?.value.trim().slice(0, 120) ?? '',
    activityType: manualType?.value ?? 'Playing',
  };
  await browser.storage.local.set({ manualPresencePresets: [...presets.slice(-19), preset] });
  if (manualPresetName) manualPresetName.value = '';
  await refreshRuntimePanels();
  if (manualPresetSelect) manualPresetSelect.value = preset.id;
  if (manualStatus) manualStatus.textContent = `Preset “${label}” stored locally.`;
}

async function applyManualPreset(): Promise<void> {
  const selected = manualPresetSelect?.value ?? '';
  const stored = await browser.storage.local.get('manualPresencePresets') as { manualPresencePresets?: Array<Record<string, unknown>> };
  const preset = (stored.manualPresencePresets ?? []).find((item) => item.id === selected);
  if (!preset) { if (manualStatus) manualStatus.textContent = 'Choose a saved preset first.'; return; }
  if (manualName) manualName.value = String(preset.name ?? '');
  if (manualDetails) manualDetails.value = String(preset.details ?? '');
  if (manualState) manualState.value = String(preset.state ?? '');
  if (manualType) manualType.value = String(preset.activityType ?? 'Playing');
  await saveManualPresence();
}

async function deleteManualPreset(): Promise<void> {
  const selected = manualPresetSelect?.value ?? '';
  const stored = await browser.storage.local.get('manualPresencePresets') as { manualPresencePresets?: Array<Record<string, unknown>> };
  const presets = (stored.manualPresencePresets ?? []).filter((item) => item.id !== selected);
  await browser.storage.local.set({ manualPresencePresets: presets });
  await refreshRuntimePanels();
  if (manualStatus) manualStatus.textContent = selected ? 'Preset deleted.' : 'Choose a saved preset first.';
}

async function exportRedactedDiagnostics(): Promise<void> {
  if (!lastLabSnapshot) await refreshRuntimePanels();
  if (!lastLabSnapshot) return;
  const activity = lastLabSnapshot.activity;
  const report = {
    product: 'Gecko Presence',
    version: '0.5.0',
    exportedAt: new Date().toISOString(),
    activity: activity ? {
      source: activity.source,
      sourceType: activity.sourceType,
      activityType: activity.activityType,
      mediaState: activity.mediaState,
      confidence: activity.confidence,
    } : null,
    bridgeStatus: lastLabSnapshot.bridgeStatus,
    adapters: lastLabSnapshot.adapters,
    diagnostics: lastLabSnapshot.diagnostics,
    redactions: ['media title', 'episode metadata', 'image URLs', 'buttons', 'full URL', 'path', 'query', 'fragment', 'pairing key'],
  };
  try {
    await navigator.clipboard.writeText(JSON.stringify(report, null, 2));
    if (labStatus) labStatus.textContent = 'Redacted diagnostic report copied. No media title, page path, query, or pairing key was included.';
  } catch {
    if (labStatus) labStatus.textContent = 'Clipboard access was blocked by this browser. Use the event trace above; no report was exported.';
  }
}

manualEnabled?.addEventListener('change', () => {
  if (manualEnabled.checked) void saveManualPresence().catch(() => { if (manualStatus) manualStatus.textContent = 'Could not activate the manual override.'; });
  else void disableManualPresence().catch(() => { if (manualStatus) manualStatus.textContent = 'Could not disable the manual override.'; });
});
document.querySelector<HTMLButtonElement>('#manual-save')?.addEventListener('click', () => { void saveManualPresence().catch(() => { if (manualStatus) manualStatus.textContent = 'Could not save the manual override.'; }); });
document.querySelector<HTMLButtonElement>('#manual-disable')?.addEventListener('click', () => { void disableManualPresence().catch(() => { if (manualStatus) manualStatus.textContent = 'Could not disable the manual override.'; }); });
document.querySelector<HTMLButtonElement>('#manual-preset-store')?.addEventListener('click', () => { void storeManualPreset(); });
document.querySelector<HTMLButtonElement>('#manual-preset-apply')?.addEventListener('click', () => { void applyManualPreset(); });
document.querySelector<HTMLButtonElement>('#manual-preset-delete')?.addEventListener('click', () => { void deleteManualPreset(); });
document.querySelector<HTMLButtonElement>('#lab-refresh')?.addEventListener('click', () => { void refreshRuntimePanels(); });
document.querySelector<HTMLButtonElement>('#lab-export')?.addEventListener('click', () => { void exportRedactedDiagnostics(); });
void refreshRuntimePanels();
window.setInterval(() => { void refreshRuntimePanels(); }, 3000);
