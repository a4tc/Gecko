import type { UnifiedActivityV2, ActivitySourceType } from './activity-model.js';

export function arbitrateActivity(
  candidates: UnifiedActivityV2[],
  now = Date.now(),
  preferredSourceType?: ActivitySourceType,
  maxAgeMs = 45_000,
): UnifiedActivityV2 | null {
  const fresh = candidates.filter((candidate) =>
    candidate && candidate.schemaVersion === 2
    && Number.isFinite(candidate.updatedAt)
    && candidate.updatedAt <= now + 5_000
    && now - candidate.updatedAt <= maxAgeMs,
  );
  fresh.sort((left, right) => {
    const leftPreferred = left.sourceType === preferredSourceType ? 1 : 0;
    const rightPreferred = right.sourceType === preferredSourceType ? 1 : 0;
    return right.priority - left.priority
      || rightPreferred - leftPreferred
      || right.confidence - left.confidence
      || right.updatedAt - left.updatedAt;
  });
  return fresh[0] ?? null;
}
