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

The module hooks YouTube's `Activity.onCreate` and `onResume`, finds the bottom
navigation bar, and turns it into a floating pill:

1. **Backdrop capture** — the decor view is drawn to a bitmap with the bar
   hidden, so the bar never samples itself.
2. **Blur** — the captured region behind the bar is blurred
   (`RenderEffect.createBlurEffect`, Android 12+) or downscaled/upscaled as a
   fallback.
3. **Background** — the blurred bitmap becomes the bar's background, with a
   subtle white tint. The tab icons are children, so they stay sharp.
4. **Pill shape** — side margins, `OutlineProvider` round rect, elevation.
5. **Scroll refresh** — the capture is refreshed (throttled) as the feed scrolls.

## Notes

- Tested on Android 12+ for best blur effect
- Older Android versions get translucent background only (no blur)
- YouTube updates may change view structure; adjust `isNavigationBar()` if needed
