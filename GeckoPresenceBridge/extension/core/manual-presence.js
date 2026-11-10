import { normalizeUnifiedActivity } from './activity-model.js';
const TYPES = new Set(['Playing', 'Watching', 'Listening', 'Streaming', 'Competing']);
export function buildManualPresence(input, now = Date.now()) {
    if (input.enabled !== true)
        return null;
    const name = typeof input.name === 'string' ? input.name.trim().slice(0, 80) : '';
    const details = typeof input.details === 'string' ? input.details.trim().slice(0, 120) : '';
    if (!name || !details)
        throw new Error('Manual presence requires both a name and details.');
    const activityType = TYPES.has(input.activityType)
        ? input.activityType : 'Playing';
    const state = typeof input.state === 'string' ? input.state.trim().slice(0, 120) : '';
    return normalizeUnifiedActivity({
        schemaVersion: 2,
        id: 'manual:custom-presence',
        source: 'gecko-presence.local',
        sourceType: 'manual',
        activityType,
        name,
        details,
        ...(state ? { state } : {}),
        mediaState: 'unknown',
        priority: 100,
        confidence: 1,
        updatedAt: now,
    });
}
