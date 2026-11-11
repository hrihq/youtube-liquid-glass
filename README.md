# Miuix Nav Bar (LSPosed Module)

Turns the bottom navigation bar of **WhatsApp** and **YouTube** into a floating
MIUI/Miuix capsule with a blurred glass backdrop, a blue selection pill, and
vertical icon + label tabs. All original navigation keeps working — the module
restyles the real bar instead of replacing it.

## Requirements

- Android device with **LSPosed** installed (root required)
- LSPosed with API level 82 (legacy rovo89 API) or 101+
- WhatsApp (`com.whatsapp`) and/or YouTube (`com.google.android.youtube`) installed

## Install

1. Download the release APK from [Releases](../../releases)
2. Install the APK
3. Open **LSPosed Manager** → Modules
4. Enable **Miuix Nav Bar**
5. Set scope to **WhatsApp** and/or **YouTube**
6. Force-stop the app and reopen it
7. Open the module app to tune blur, margins, colors, height and label visibility

## Build

Built automatically via GitHub Actions on every push to `main` and on `v*` tags.
The workflow runs unit tests, builds debug and release APKs, validates that the
APKs contain a manifest, `classes.dex` and `assets/xposed_init`, uploads both as
artifacts, and attaches the release APK to a GitHub Release when a tag is pushed.

## How it works

1. **Timing-safe detection** — the module hooks `Activity.onCreate`/`onResume`
   but waits for the decor view to be laid out, then retries for ~8 seconds so
   the app's fragment views have time to be added.
2. **Three detection signals** — a resource-id lookup
   (`bottom_navigation_bar` and friends), then matching of the real tab
   labels/content descriptions (WhatsApp: `Chats`/`Chat`, `Updates`/`Status`,
   `Communities`/`Komunitas`, `Calls`/`Panggilan`; YouTube: `Home`/`Beranda`,
   `Shorts`, `Subscriptions`/`Langganan`, `You`/`Anda`, `Create`/`Buat`), then a
   strict structural profile (wide, 36–140dp tall, bottom-anchored, 3–8
   children). A view that does not match is never touched.
3. **Floating capsule** — side/bottom margins, elevation, a rounded
   `OutlineProvider` and `clipToOutline`.
4. **Glass backdrop** — the decor view is drawn off-screen with the bar hidden,
   the bar region is cropped, blurred with `RenderScript` (downscale/upscale
   fallback), tinted, and installed as the first layer of a `LayerDrawable`.
5. **Selection pill** — a custom `PillDrawable` in the background draws the MIUI
   blue capsule behind the active tab, tracked through
   `getSelectedItemId()`/`findViewById` or `isSelected`/`isActivated`/`isChecked`.
6. **Live settings** — the bar is re-styled when settings change; the backdrop
   is refreshed (throttled) while the feed scrolls.

## Notes / limitations

- Runtime behaviour was verified by code inspection and CI build only — it was
  not exercised on a physical device.
- `SurfaceView`/`TextureView` content (the video player surface) cannot be
  captured by `View.draw()`, so the blurred backdrop behind a fullscreen video
  may be blank. The feed and other native views capture correctly.
- YouTube updates can change view structure; if detection misses, logcat lines
  tagged `YT_MiuixNav` print every candidate with its score, bounds and label
  count so the matcher can be extended.
- The module only activates for `com.google.android.youtube`.
