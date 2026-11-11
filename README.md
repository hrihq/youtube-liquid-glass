# YouTube Liquid Glass (LSPosed Module)

Apply MIUI Liquid Glass (frosted glass) effect to YouTube's bottom navigation bar.

## Requirements

- Android device with **LSPosed** installed (root required)
- YouTube app installed

## Install

1. Download the APK from [Releases](../../releases)
2. Install the APK
3. Open **LSPosed Manager** → Modules
4. Enable **YouTube Liquid Glass**
5. Set scope to **YouTube** (`com.google.android.youtube`)
6. Reboot or force-stop YouTube

## Build

Automatically built via GitHub Actions on every push to `main`.

Manual build:
```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

## How it works

The module hooks YouTube's `Activity.onCreate` and `onResume`, traverses the view hierarchy to find the bottom navigation bar, and applies:
- **RenderEffect blur** (Android 12+) — frosted glass blur
- **Translucent background** — semi-transparent white with blue tint
- **Elevation** — depth shadow

## Notes

- Tested on Android 12+ for best blur effect
- Older Android versions get translucent background only (no blur)
- YouTube updates may change view structure; adjust `isNavigationBar()` if needed
