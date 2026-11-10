export function validateImportManifest(value) {
    if (!value || typeof value !== 'object')
        throw new Error('Import manifest must be an object');
    const manifest = value;
    if (manifest.schemaVersion !== 1 || manifest.upstream !== 'PreMiD/Activities' || manifest.license !== 'MPL-2.0') {
        throw new Error('Unsupported import manifest or license');
    }
    if (!Array.isArray(manifest.adapters))
        throw new Error('Import adapters must be an array');
    const ids = new Set();
    const adapters = manifest.adapters.map((item) => {
        if (!item || typeof item !== 'object')
            throw new Error('Invalid adapter import entry');
        const entry = item;
        if (typeof entry.id !== 'string' || !/^[a-z0-9][a-z0-9-]{1,63}$/.test(entry.id) || ids.has(entry.id))
            throw new Error('Invalid or duplicate adapter id');
        ids.add(entry.id);
        if (typeof entry.path !== 'string' || entry.path.startsWith('/') || entry.path.includes('\\') || entry.path.split('/').some((part) => !part || part === '.' || part === '..'))
            throw new Error('Invalid adapter source path');
        if (typeof entry.commit !== 'string' || !/^[a-f0-9]{40}$/i.test(entry.commit))
            throw new Error('Adapter source must be pinned to a full commit SHA');
        if (!Array.isArray(entry.files) || entry.files.length < 2 || !entry.files.every((file) => ['metadata.json', 'presence.ts', 'iframe.ts'].includes(String(file))))
            throw new Error('Invalid adapter source files');
        if (!entry.files.includes('metadata.json') || !entry.files.includes('presence.ts'))
            throw new Error('metadata.json and presence.ts are required');
        if (!entry.hashes || typeof entry.hashes !== 'object')
            throw new Error('Every import must pin SHA-256 hashes for its source files');
        const hashes = entry.hashes;
        for (const file of entry.files) {
            if (typeof hashes[file] !== 'string' || !/^[a-f0-9]{64}$/i.test(hashes[file]))
                throw new Error(`Missing or invalid SHA-256 for ${file}`);
        }
        const mode = entry.mode === 'reference' ? 'reference' : 'compat';
        const hosts = Array.isArray(entry.hosts) ? entry.hosts.filter((host) => typeof host === 'string' && host.length <= 253) : [];
        return { id: entry.id, path: entry.path, commit: entry.commit.toLowerCase(), files: entry.files, mode, hashes: Object.fromEntries(entry.files.map((file) => [file, String(hashes[file]).toLowerCase()])), ...(hosts.length ? { hosts } : {}) };
    });
    return { schemaVersion: 1, upstream: 'PreMiD/Activities', license: 'MPL-2.0', adapters };
}
export function scanAdapterSource(source) {
    const findings = [];
    const checks = [
        [/\bfetch\s*\(/, 'Direct network requests are not permitted in imported activities'],
        [/\bXMLHttpRequest\b|\bWebSocket\b|\bEventSource\b/, 'Network transport APIs are not permitted'],
        [/\bdocument\s*\.\s*cookie\b|\blocalStorage\b|\bsessionStorage\b|\bindexedDB\b/, 'Cookie and persistent storage APIs are not permitted'],
        [/\b(?:browser|chrome)\s*\.\s*(?:tabs|cookies|history|downloads|webRequest|runtime)\b/, 'Browser extension privilege APIs are not permitted'],
        [/\beval\s*\(|\bnew\s+Function\s*\(/, 'Dynamic code evaluation is not permitted'],
        [/\bnavigator\s*\.\s*sendBeacon\s*\(/, 'Beacon exfiltration is not permitted'],
        [/\bimportScripts\s*\(/, 'External script loading is not permitted'],
        [/\bimport\s*\(/, 'Dynamic module loading is not permitted'],
        [/\bnew\s+(?:SharedWorker|Worker|ServiceWorker)\b/, 'Worker creation is not permitted in imported activities'],
        [/\bdocument\s*\.\s*createElement\s*\(\s*['"]script['"]/, 'Dynamic script injection is not permitted'],
        [/\b(?:window|globalThis|self)\s*\.\s*(?:fetch|XMLHttpRequest|WebSocket|EventSource)\b/, 'Indirect network APIs are not permitted'],
        [/\b(?:presence|iframe)\s*\.\s*(?:getPageVariable|getPageletiable|execInPage|onRequest|createSlideshow|hideSetting|showSetting)\s*\(/, 'Activity uses a PreMiD API that is not supported by the GPB compatibility profile'],
    ];
    for (const [pattern, message] of checks)
        if (pattern.test(source))
            findings.push(message);
    const imports = source.matchAll(/import\s+(?:[\s\S]*?from\s*)?['"]([^'"]+)['"]/g);
    for (const match of imports)
        if (match[1] !== 'premid')
            findings.push(`Unsupported module import: ${match[1]}`);
    return [...new Set(findings)];
}
