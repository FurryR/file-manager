#!/usr/bin/env node
/*
 * Copies the built Chrome DevTools frontend into the app assets.
 *
 * The runtime never talks to npm: Gradle runs this once (and again only when
 * the package pin, this script or the app's `values-*` locales change), then
 * the app serves the generated tree from its own loopback DevTools bridge.
 */
import {cpSync, existsSync, mkdirSync, readdirSync, readFileSync, renameSync, rmSync, writeFileSync} from 'node:fs';
import {dirname, extname, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {brotliCompressSync, constants as zlibConstants} from 'node:zlib';

const here = dirname(fileURLToPath(import.meta.url));
const project = resolve(here, '..');
const repo = resolve(project, '..', '..');
const packageDir = join(project, 'node_modules', '@devtools-anywhere', 'devtools-frontend');
const dist = join(packageDir, 'dist');
const output = join(repo, 'app', 'src', 'main', 'assets', 'devtools');
// Stage outside the assets tree so an interrupted sync can never be packaged.
const staging = join(project, '.dist');

if (!existsSync(dist)) {
  console.error('devtools frontend is not installed; run `npm ci` in tools/devtools first.');
  process.exit(1);
}

const manifest = JSON.parse(readFileSync(join(packageDir, 'package.json'), 'utf8'));
const revision = readFileSync(join(dist, 'REVISION'), 'utf8').trim();

rmSync(staging, {recursive: true, force: true});
mkdirSync(staging, {recursive: true});
cpSync(dist, staging, {recursive: true});

// The package ships only en-US and zh. Stage the DevTools locale matching
// every app translation instead of keeping a locale list here: adding
// `values-xx` (or `values-xx-rYY`) to the app stages its DevTools data on the
// next sync, and the runtime matches the app locale against what is staged.
const stagedLocales = await stageLocales(
  appLocales(join(repo, 'app', 'src', 'main', 'res')),
  revision,
  staging,
);

// The package ships one built entry point. Give it the name the bridge uses;
// every relative asset reference stays valid because only the file name moves.
renameSync(join(staging, 'anywhere_app.html'), join(staging, 'inspector.html'));

// Keep the upstream license files next to the generated assets. The app's
// license screen reads its own report, so these are the attribution copy for
// the bundled frontend itself.
for (const name of ['LICENSE', 'THIRD_PARTY_LICENSES.md']) {
  const source = join(packageDir, name);
  if (existsSync(source)) cpSync(source, join(staging, name));
}

writeFileSync(
  join(staging, 'BUILD_INFO.json'),
  JSON.stringify(
    {
      package: manifest.name,
      version: manifest.version,
      chrome: manifest.chrome,
      revision,
      compression: 'brotli',
      locales: [...stagedLocales].sort(),
    },
    null,
    2,
  ) + '\n',
);

// Text assets brotli-compress well; PNG/AVIF assets are already compressed.
const COMPRESSIBLE = new Set(['.css', '.html', '.js', '.json', '.md', '.svg', '.txt', '.xml']);
// The bridge patches inspector.html before serving it, and BUILD_INFO is a
// human-readable manifest; keep both plain.
const KEEP_PLAIN = new Set(['inspector.html', 'BUILD_INFO.json']);
// Even the small issue-description Markdown files add up; the encoder keeps
// the original whenever Brotli would not actually shrink it.
const MIN_COMPRESS_BYTES = 0;

// The loopback bridge sends the stored .br bytes with Content-Encoding: br,
// so the WebView never downloads the uncompressed 16 MiB tree. Only text
// assets are worth compressing; images are already compressed.
compressTree(staging);

mkdirSync(dirname(output), {recursive: true});
rmSync(output, {recursive: true, force: true});
renameSync(staging, output);
console.log(`synced ${manifest.name}@${manifest.version} (Chrome ${manifest.chrome}) -> ${output}`);

/** App languages are the Android `values-*` translation resource folders. */
function appLocales(resDir) {
  const tags = new Set();
  for (const entry of readdirSync(resDir)) {
    const tag = localeTagOfResourceDir(entry);
    if (tag) tags.add(tag);
  }
  return [...tags];
}

// values-ja -> ja, values-zh-rTW -> zh-TW, values-b+zh+Hant -> zh-Hant.
function localeTagOfResourceDir(name) {
  const match = /^values-(.+)$/.exec(name);
  if (!match) return null;
  const qualifier = match[1];
  if (qualifier.startsWith('b+')) return bcp47(qualifier.slice(2).split('+'));
  const parts = /^([a-z]{2,3})(?:-r([A-Z]{2}))?$/.exec(qualifier);
  if (!parts) return null;
  return bcp47([parts[1], parts[2]].filter(Boolean));
}

function bcp47(parts) {
  return parts
    .map((part, index) => {
      if (index === 0) return part.toLowerCase();
      if (/^[A-Za-z]{4}$/.test(part)) return part[0].toUpperCase() + part.slice(1).toLowerCase();
      if (/^[A-Za-z]{2}$/.test(part)) return part.toUpperCase();
      return part;
    })
    .join('-');
}

/** Add the best available DevTools locale for every app language. */
async function stageLocales(tags, revision, staging) {
  const dir = join(staging, 'core', 'i18n', 'locales');
  mkdirSync(dir, {recursive: true});
  const staged = new Set(
    readdirSync(dir)
      .filter((name) => name.endsWith('.json'))
      .map((name) => name.slice(0, -'.json'.length)),
  );
  for (const tag of tags) {
    // en-US is the frontend default and already in the package.
    if (/^en(?:-|$)/i.test(tag)) continue;
    for (const candidate of new Set([tag, tag.split('-')[0]])) {
      if (staged.has(candidate)) break;
      const data = await readLocale(candidate, revision);
      if (!data) continue;
      writeFileSync(join(dir, `${candidate}.json`), data);
      staged.add(candidate);
      break;
    }
  }
  return staged;
}

async function readLocale(locale, revision) {
  if (!/^[A-Za-z0-9-]+$/.test(locale)) return null;
  const cached = join(project, '.locale-cache', revision, `${locale}.json`);
  if (existsSync(cached)) return readFileSync(cached);
  try {
    const response = await fetch(
      `https://chrome-devtools-frontend.appspot.com/serve_file/@${revision}/core/i18n/locales/${locale}.json`,
    );
    if (!response.ok) return null;
    const data = Buffer.from(await response.arrayBuffer());
    mkdirSync(dirname(cached), {recursive: true});
    writeFileSync(cached, data);
    return data;
  } catch (error) {
    console.error(`cannot fetch DevTools locale ${locale}: ${error.message}`);
    return null;
  }
}

/**
 * Replace text assets with `<name>.br` and delete the originals.
 *
 * `DevToolsServer` resolves a request for `<name>` to `<name>.br`, sends the
 * stored bytes with `Content-Encoding: br`, and keeps the original MIME type.
 * Files that do not shrink stay plain.
 */
function compressTree(root) {
  let source = 0;
  let packed = 0;
  let count = 0;

  const walk = (dir) => {
    for (const entry of readdirSync(dir, {withFileTypes: true})) {
      const path = join(dir, entry.name);
      if (entry.isDirectory()) {
        walk(path);
        continue;
      }
      if (!entry.isFile() || KEEP_PLAIN.has(entry.name)) continue;
      if (!COMPRESSIBLE.has(extname(entry.name).toLowerCase())) continue;
      const data = readFileSync(path);
      if (data.length < MIN_COMPRESS_BYTES) continue;
      const encoded = brotliCompressSync(data, {
        params: {
          [zlibConstants.BROTLI_PARAM_QUALITY]: 11,
          [zlibConstants.BROTLI_PARAM_LGWIN]: 24,
          [zlibConstants.BROTLI_PARAM_MODE]: zlibConstants.BROTLI_MODE_TEXT,
          [zlibConstants.BROTLI_PARAM_SIZE_HINT]: data.length,
        },
      });
      if (encoded.length >= data.length) continue;
      writeFileSync(`${path}.br`, encoded);
      rmSync(path);
      source += data.length;
      packed += encoded.length;
      count++;
    }
  };

  walk(root);
  const mib = (value) => (value / (1024 * 1024)).toFixed(2);
  console.log(`brotli: ${count} files, ${mib(source)} MiB -> ${mib(packed)} MiB`);
}
