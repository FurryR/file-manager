# File Manager

Android Kotlin + Gradle workspace for a Jetpack Compose file manager MVP.

Package name: `io.furryr.file`.

## Features

- Defaults to `/storage/emulated/0`.
- Lists files in rows with an icon, name, and last modified time.
- Supports multiple tabs with add and close actions.
- Shows the current path and folder/file/storage stats in the title bar.
- Provides bottom toolbar actions for back, forward, new file, info, and parent folder.
- Opens files through the Android system app chooser.
- Provides a left navigation drawer with Home, optional Root `/`, and Settings entries.
- Detects `su`; when available, Root `/` browsing is enabled.
- Settings screen stores a configurable `su` path.

## Build

Use Android Studio or run:

```bash
./gradlew :app:assembleDebug
```

The app intentionally targets SDK 28 to avoid newer data execution restrictions needed by planned root features.
