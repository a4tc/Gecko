import { normalizeUnifiedActivity, type UnifiedActivityV2, type UnifiedActivityType } from './activity-model.js';

export interface ManualPresenceInput {
  enabled?: boolean;
  name?: string;
  details?: string;
  state?: string;
  activityType?: string;
}

const TYPES = new Set<UnifiedActivityType>(['Playing', 'Watching', 'Listening', 'Streaming', 'Competing']);

export function buildManualPresence(input: ManualPresenceInput, now = Date.now()): UnifiedActivityV2 | null {
  if (input.enabled !== true) return null;
  const name = typeof input.name === 'string' ? input.name.trim().slice(0, 80) : '';
  const details = typeof input.details === 'string' ? input.details.trim().slice(0, 120) : '';
  if (!name || !details) throw new Error('Manual presence requires both a name and details.');
  const activityType = TYPES.has(input.activityType as UnifiedActivityType)
    ? input.activityType as UnifiedActivityType : 'Playing';
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
