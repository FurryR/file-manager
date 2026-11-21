# DevTools frontend

The app ships the **official Chrome DevTools frontend** instead of a hand-written
JavaScript console. This little npm project pins the built frontend bundle and
copies it into `app/src/main/assets/devtools/`.

The runtime side is `app/src/main/java/io/furryr/file/devtools/`:

- `DevToolsServer.kt` serves the generated assets from `127.0.0.1` and
  reverse-proxies the DevTools WebSocket to the WebView's remote-debugging
  socket (`webview_devtools_remote_<pid>`). Each DevTools tab owns one server.
- `DevToolsTargets.kt` finds the page target that belongs to the WebView the
  DevTools tab was opened from.
- `terminal/WebDevToolsTab.kt` owns the frontend WebView and the tab's server
  lifecycle.

## Install / sync

```bash
cd tools/devtools
npm ci
npm run sync
```

`./gradlew assembleDebug` runs the same two steps before packaging, unless
`-PskipDevToolsBuild=true` is passed. The generated asset directory is ignored
by git.

The npm package bundles `en-US` and `zh` locale data only. The sync derives the
app's languages from `app/src/main/res/values-*` (e.g. `values-ja`,
`values-zh-rTW`) and fetches the matching DevTools locale from the same pinned
frontend revision; downloads are cached under `.locale-cache/`. Adding an app
language therefore needs no change in this project — the next sync stages its
DevTools locale and the runtime matches the app locale against the staged set.

## Compression

`npm run sync` brotli-compresses text assets (`*.js`, `*.css`, `*.json`, `*.md`,
`*.svg`, `*.html`) into `<name>.br` and deletes the originals. Files that would
not shrink stay plain, and `inspector.html`/`BUILD_INFO.json` are always kept
plain (`inspector.html` is patched at serve time). `DevToolsServer` resolves a
request for `<name>` to `<name>.br` and sends it with `Content-Encoding: br`;
`androidResources.noCompress` and the release post-processor keep those streams
stored. The generated tree drops from about 16 MiB to about 3 MiB.

The pinned package ships only the prebuilt `dist`, not the DevTools TypeScript
sources. That bundle is already produced with esbuild/rollup, including
tree-shaking and minification. Running another tree-shake over the bundled
output is not useful; a further size cut would need a build from the official
sources or removing panel chunks (and that removes UI features).

## Updating

```bash
cd tools/devtools
npm install @devtools-anywhere/devtools-frontend@latest
npm run sync
```

`BUILD_INFO.json` in the generated assets records the package version, the
Chrome version and the frontend revision. The package is a build of the
[official DevTools sources](https://github.com/ChromeDevTools/devtools-frontend)
published for hosted DevTools use; it is not a fork of the UI code.

The frontend is an ES-module app and uses module workers, so the device needs a
reasonably recent Android System WebView (Chrome 100+ recommended). Android 8
devices with an old, non-updated WebView are not a supported target for the
in-app frontend.
