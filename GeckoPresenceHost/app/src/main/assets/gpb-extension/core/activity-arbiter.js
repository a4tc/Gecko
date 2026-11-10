export function arbitrateActivity(candidates, now = Date.now(), preferredSourceType, maxAgeMs = 45_000) {
    const fresh = candidates.filter((candidate) => candidate && candidate.schemaVersion === 2
        && Number.isFinite(candidate.updatedAt)
        && candidate.updatedAt <= now + 5_000
        && now - candidate.updatedAt <= maxAgeMs);
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
