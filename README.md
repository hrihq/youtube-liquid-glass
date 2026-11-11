# Miuix Nav Bar (LSPosed Module)

Turns the bottom navigation bar of **WhatsApp** (and WhatsApp Business) into a
floating MIUI/Miuix capsule with a blurred glass backdrop, a blue selection
pill, and vertical icon + label tabs. All original navigation keeps working —
the module restyles the real bar instead of replacing it.

## Requirements

- Android device with **LSPosed** installed (root required)
- LSPosed with API level 82 (legacy rovo89 API) or 101+
- WhatsApp (`com.whatsapp`) and/or WhatsApp Business (`com.whatsapp.w4b`) installed

## Install

1. Download the release APK from [Releases](../../releases)
2. Install the APK
3. Open **LSPosed Manager** → Modules
4. Enable **Miuix Nav Bar**
5. Set scope to **WhatsApp** (and/or WhatsApp Business)
6. Force-stop the app and reopen it
7. Open the module app to tune blur, margins, colors, height and label visibility

## Build

Built automatically via GitHub Actions on every push to `main` and on `v*` tags.
The workflow runs unit tests, builds debug and release APKs, validates that the
APKs contain a manifest, `classes.dex` and `assets/xposed_init`, uploads both as
artifacts, and attaches the release APK to a GitHub Release when a tag is pushed.

## How it works

1. **Exact bar lookup** — the bar is found by WhatsApp's own resource ids
   (`bottom_nav`, `navigation_bar`, `tab_layout`), the same approach WaEnhancer
   uses, so it is not defeated by obfuscation or view-tree changes.
2. **Floating capsule** — the bar's wrapper is turned into a bottom overlay with
   side/bottom margins, a fixed pill height, elevation, a rounded
   `GradientDrawable` background, and a rounded `OutlineProvider` clip.
4. **Glass backdrop** — a real backdrop blur through
   `RenderNode.setBackdropRenderEffect`, so the icons and labels stay sharp
   while the content behind the pill is frosted. A translucent tint is the
   fallback on older Android versions.
5. **Selection pill** — the native active indicator is hidden and a MIUI-style
   rounded capsule is drawn behind the selected tab, kept in sync with an
   `OnPreDrawListener`.
6. **Live settings** — blur, margins, colors, height and label visibility are
   read from the module's preferences.

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
