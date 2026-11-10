#!/usr/bin/env node
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { readFile, mkdir, rm, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';
import { createHash } from 'node:crypto';
import { validateImportManifest, scanAdapterSource } from '../extension/core/adapter-import-policy.js';

const execFileAsync = promisify(execFile);
const require = createRequire(import.meta.url);
let ts;
try { ts = require('typescript'); } catch {
  const { stdout } = await execFileAsync('npm', ['root', '-g']);
  ts = await import(pathToFileURL(join(stdout.trim(), 'typescript/lib/typescript.js'))).then((module) => module.default ?? module);
}
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

function parseArgs(argv) {
  const result = {};
  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index];
    if (token === '--source-repo') result.sourceRepo = argv[++index];
    else if (token === '--source-dir') result.sourceDir = argv[++index];
    else if (token === '--manifest') result.manifest = argv[++index];
    else if (token === '--output') result.output = argv[++index];
    else if (token === '--help') result.help = true;
    else throw new Error(`Unknown argument: ${token}`);
  }
  return result;
}

function extractPreMiDImports(source) {
  const importPattern = /import\s+(type\s+)?\{([\s\S]*?)\}\s+from\s+['"]premid['"]\s*;?/g;
  const bindings = [];
  const transformed = source.replace(importPattern, (_statement, typeOnly, names) => {
    if (typeOnly) return '';
    for (const raw of names.split(',')) {
      const item = raw.trim();
      if (!item) continue;
      const [imported, local] = item.split(/\s+as\s+/).map((part) => part.trim());
      if (!/^[A-Za-z_$][\w$]*$/.test(imported) || (local && !/^[A-Za-z_$][\w$]*$/.test(local))) {
        throw new Error(`Unsupported PreMiD named import: ${item}`);
      }
      bindings.push(local ? `${imported}: ${local}` : imported);
    }
    return '';
  });
  return { source: transformed, bindings: [...new Set(bindings)] };
}

export function compilePreMiDSource(source, filename = 'presence.ts') {
  const findings = scanAdapterSource(source);
  if (findings.length) throw new Error(`${filename} failed the import security scan: ${findings.join('; ')}`);
  if (/import\s+(?:\*|[A-Za-z_$][\w$]*\s*(?:,|from))/m.test(source)) {
    throw new Error(`${filename} uses an unsupported PreMiD import form`);
  }
  const { source: withoutImports, bindings } = extractPreMiDImports(source);
  const transpiled = ts.transpileModule(withoutImports, {
    fileName: filename,
    reportDiagnostics: true,
    compilerOptions: {
      target: ts.ScriptTarget.ES2022,
      module: ts.ModuleKind.None,
      removeComments: false,
      sourceMap: false,
      inlineSourceMap: false,
    },
  });
  const errors = (transpiled.diagnostics ?? []).filter((diagnostic) => diagnostic.category === ts.DiagnosticCategory.Error);
  if (errors.length) throw new Error(ts.formatDiagnostics(errors, {
    getCurrentDirectory: () => root,
    getCanonicalFileName: (name) => name,
    getNewLine: () => '\n',
  }));
  const prelude = [
    '(function () {',
    '  const Presence = globalThis.Presence;',
    '  const iFrame = globalThis.iFrame;',
    bindings.length ? `  const { ${bindings.join(', ')} } = globalThis.__gpbPreMiDCompatV1.presence;` : '',
  ].filter(Boolean).join('\n');
  return `${prelude}\n${transpiled.outputText}\n})();\n`;
}

async function gitShow(sourceRepo, commit, filePath) {
  const { stdout } = await execFileAsync('git', ['-C', sourceRepo, 'show', `${commit}:${filePath}`], { maxBuffer: 10 * 1024 * 1024 });
  return stdout;
}

async function readPinnedSource({ sourceRepo, sourceDir, commit, filePath }) {
  if (sourceRepo) return gitShow(sourceRepo, commit, filePath);
  const target = resolve(sourceDir, filePath);
  if (target !== sourceDir && !target.startsWith(`${sourceDir}/`)) throw new Error(`Source path escapes snapshot: ${filePath}`);
  return readFile(target, 'utf8');
}

function hostPatterns(metadata, overrides = []) {
  if (overrides.length) return [...new Set(overrides)];
  const urls = Array.isArray(metadata.url) ? metadata.url : [metadata.url];
  return [...new Set(urls.map((host) => String(host).toLowerCase().replace(/^www\./, '')).filter(Boolean))];
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) {
    process.stdout.write('Usage: node scripts/import-premid-activities.mjs (--source-repo PATH | --source-dir PATH) [--manifest premid-imports.json] [--output extension/adapters/premid]\n');
    return;
  }
  const sourceRepo = args.sourceRepo || process.env.PREMID_ACTIVITIES_REPO ? resolve(args.sourceRepo ?? process.env.PREMID_ACTIVITIES_REPO) : '';
  const sourceDir = args.sourceDir ? resolve(args.sourceDir) : '';
  if (!sourceRepo && !sourceDir) throw new Error('--source-repo or --source-dir is required');
  const manifestPath = resolve(root, args.manifest ?? 'premid-imports.json');
  const outputRoot = resolve(root, args.output ?? 'extension/adapters/premid');
  if (outputRoot !== root && !outputRoot.startsWith(`${root}/`)) throw new Error('Import output must remain inside the bridge project.');
  const manifest = validateImportManifest(JSON.parse(await readFile(manifestPath, 'utf8')));
  const generated = [];
  const importLock = { schemaVersion: 1, upstream: 'PreMiD/Activities', license: manifest.license, adapters: [] };
  await mkdir(outputRoot, { recursive: true });

  for (const entry of manifest.adapters) {
    const target = join(outputRoot, entry.id);
    await rm(target, { recursive: true, force: true });
    await mkdir(target, { recursive: true });
    const fileContents = {};
    for (const file of entry.files) {
      const gitPath = `${entry.path}/${file}`;
      fileContents[file] = await readPinnedSource({ sourceRepo, sourceDir, commit: entry.commit, filePath: gitPath });
    }
    const hashes = Object.fromEntries(Object.entries(fileContents).map(([name, content]) => [name, createHash('sha256').update(content).digest('hex')]));
    for (const [name, hash] of Object.entries(hashes)) {
      if (entry.hashes[name] !== hash) throw new Error(`SHA-256 mismatch for ${entry.id}/${name}; source snapshot does not match the pinned manifest.`);
    }
    const metadata = JSON.parse(fileContents['metadata.json']);
    if (metadata.apiVersion !== 1) throw new Error(`Unsupported PreMiD API version for ${entry.id}: ${metadata.apiVersion}`);
    if (metadata.service && metadata.service.toLowerCase().replace(/[^a-z0-9-]/g, '-') !== entry.id.replace(/-legacy$/, '')) {
      // IDLIX archive intentionally carries its upstream service name; adapter id may differ.
      if (!(entry.id === 'idlix-legacy' && metadata.service === 'IDLIX')) throw new Error(`Metadata service mismatch for ${entry.id}`);
    }
    await writeFile(join(target, 'metadata.json'), `${JSON.stringify(metadata, null, 2)}\n`);
    await writeFile(join(target, 'presence.js'), compilePreMiDSource(fileContents['presence.ts'], `${entry.id}/presence.ts`));
    if (fileContents['iframe.ts']) await writeFile(join(target, 'iframe.js'), compilePreMiDSource(fileContents['iframe.ts'], `${entry.id}/iframe.ts`));
    const config = {
      service: metadata.service,
      settings: Array.isArray(metadata.settings) ? metadata.settings : [],
      description: metadata.description ?? {},
      source: 'PreMiD/Activities',
      commit: entry.commit,
      license: manifest.license,
    };
    await writeFile(join(target, 'config.js'), `globalThis.__gpbPreMiDCompatV1?.configure(${JSON.stringify(config)});\n`);
    await writeFile(join(target, 'NOTICE.md'), [
      `Imported from PreMiD/Activities at commit ${entry.commit}.`,
      `Source path: ${entry.path}`,
      'License: Mozilla Public License 2.0 (MPL-2.0).',
      'Preserve this notice and the upstream license when redistributing the imported activity source.',
      'The compatibility runtime and Gecko Presence adapter glue are separate GPB code.',
    ].join('\n') + '\n');
    if (entry.mode === 'compat') {
      generated.push({
        id: entry.id,
        kind: 'premid',
        version: metadata.version,
        sourceCommit: entry.commit,
        sourceHashes: hashes,
        license: manifest.license,
        enabled: true,
        priority: 40,
        hosts: hostPatterns(metadata, entry.hosts),
        runtimePath: `adapters/premid/${entry.id}/presence.js`,
        iframePath: fileContents['iframe.ts'] ? `adapters/premid/${entry.id}/iframe.js` : undefined,
        configPath: `adapters/premid/${entry.id}/config.js`,
      });
    }
    importLock.adapters.push({ id: entry.id, path: entry.path, commit: entry.commit, mode: entry.mode ?? 'compat', files: hashes });
    process.stdout.write(`[import] ${entry.id} @ ${entry.commit.slice(0, 12)} (${entry.mode ?? 'compat'})\n`);
  }

  await writeFile(resolve(root, 'PREMiD_IMPORT_LOCK.json'), `${JSON.stringify(importLock, null, 2)}\n`);
  const notices = [
    '# Third-party notices', '',
    '## PreMiD Activities imports', '',
    'The following activity sources are imported from PreMiD/Activities under MPL-2.0.',
    ...importLock.adapters.map((item) => `- ${item.id}: ${item.path} @ ${item.commit} (${Object.entries(item.files).map(([name, hash]) => `${name} SHA-256 ${hash}`).join('; ')})`),
    '', 'Retain upstream copyright notices and comply with MPL-2.0 source-availability requirements for covered files.', '',
  ].join('\n');
  await writeFile(resolve(root, 'THIRD_PARTY_NOTICES.md'), `${notices}\n`);

  const registryPath = resolve(root, 'src/adapters/imported-registry.ts');
  const registrySource = `// Generated by scripts/import-premid-activities.mjs. Do not edit by hand.\nexport const IMPORTED_ADAPTERS = ${JSON.stringify(generated, null, 2)};\n`;
  await writeFile(registryPath, registrySource);
  const sourceIndex = resolve(root, 'SOURCES.md');
  const sourceNote = [
    '',
    '## PreMiD activity imports',
    '',
    '- Upstream: https://github.com/PreMiD/Activities',
    '- Import manifest: `premid-imports.json`',
    '- Importer: `scripts/import-premid-activities.mjs`',
    '- Imported activities are commit-pinned and statically scanned before compilation.',
    '- Imported source is MPL-2.0; per-adapter `NOTICE.md` files preserve provenance.',
    '',
  ].join('\n');
  const existing = await readFile(sourceIndex, 'utf8').catch(() => '# Sources\n');
  await writeFile(sourceIndex, `${existing.replace(/\n## PreMiD activity imports[\s\S]*$/m, '')}${sourceNote}`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    process.stderr.write(`[import] ${error instanceof Error ? error.stack ?? error.message : String(error)}\n`);
    process.exitCode = 1;
  });
}
