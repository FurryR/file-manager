#!/usr/bin/env node
/*
 * Copies the built Chrome DevTools frontend into the app assets.
 *
 * The runtime never talks to npm: Gradle runs this once (and again only when
 * the package pin, this script or the app's `values-*` locales change), then
 * the app serves the generated tree from its own loopback DevTools bridge.
 */
import {cpSync, existsSync, mkdirSync, readdirSync, readFileSync, renameSync, rmSync, writeFileSync} from 'node:fs';
import {dirname, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

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
      locales: [...stagedLocales].sort(),
    },
    null,
    2,
  ) + '\n',
);

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
