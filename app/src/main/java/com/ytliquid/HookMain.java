package com.ytliquid;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed module: YouTube MIUI Liquid Glass.
 *
 * Makes YouTube's bottom navigation bar a floating pill with a real backdrop
 * blur: the content behind the bar is captured, blurred, and set as the bar's
 * background. Icons stay sharp because the blur lives on the background only,
 * never on the bar's children.
 */
public class HookMain implements IXposedHookLoadPackage {

    private static final String TAG = "YT_LiquidGlass";
    private static final String TARGET_PACKAGE = "com.google.android.youtube";

    private static final float BLUR_RADIUS = 24f;
    private static final int SIDE_MARGIN_DP = 12;
    private static final int BOTTOM_MARGIN_DP = 10;
    private static final int ELEVATION_DP = 10;
    private static final int TINT_ALPHA = 50;
    private static final int MIN_SCORE = 12;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Activity activity;
    private View navBar;
    private boolean scrollListenerAdded = false;

    private final Runnable reapplyRunnable = new Runnable() {
        @Override
        public void run() {
            if (activity != null && navBar != null) {
                applyBackdropBlur(activity, navBar);
            }
        }
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(TARGET_PACKAGE)) return;

        XposedBridge.log(TAG + ": Module loaded for " + TARGET_PACKAGE);

        XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Activity a = (Activity) param.thisObject;
                if (a == null || !a.getPackageName().equals(TARGET_PACKAGE)) return;
                final View decor = a.getWindow().getDecorView();
                decor.post(() -> setup(a, decor));
            }
        });

        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Activity a = (Activity) param.thisObject;
                if (a == null || !a.getPackageName().equals(TARGET_PACKAGE)) return;
                final View decor = a.getWindow().getDecorView();
                decor.post(() -> setup(a, decor));
            }
        });
    }

    private void setup(Activity a, View decor) {
        this.activity = a;

        Window window = a.getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        }

        View bar = findNavBar(decor);
        if (bar == null) {
            XposedBridge.log(TAG + ": Nav bar not found");
            return;
        }
        this.navBar = bar;
        XposedBridge.log(TAG + ": Nav bar = " + bar.getClass().getName()
                + " (" + bar.getWidth() + "x" + bar.getHeight() + ")");

        makeFloating(bar, a);
        applyBackdropBlur(a, bar);

        if (!scrollListenerAdded) {
            scrollListenerAdded = true;
            decor.getViewTreeObserver().addOnScrollChangedListener(() -> {
                handler.removeCallbacks(reapplyRunnable);
                handler.postDelayed(reapplyRunnable, 80);
            });
        }
    }

    private View findNavBar(View root) {
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) root;
        View best = null;
        int bestScore = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == null) continue;

            int score = scoreNavBar(child, group);
            if (score > bestScore) {
                bestScore = score;
                best = child;
            }

            View nested = findNavBar(child);
            if (nested != null && nested.getParent() instanceof ViewGroup) {
                int ns = scoreNavBar(nested, (ViewGroup) nested.getParent());
                if (ns > bestScore) {
                    bestScore = ns;
                    best = nested;
                }
            }
        }
        return bestScore >= MIN_SCORE ? best : null;
    }

    private int scoreNavBar(View view, ViewGroup parent) {
        int score = 0;
        try {
            int h = view.getHeight();
            int w = view.getWidth();
            int parentH = parent.getHeight();
            int parentW = parent.getWidth();
            int top = view.getTop();
            String name = view.getClass().getName().toLowerCase();

            // Sits at the bottom of its parent.
            if (parentH > 0 && top >= parentH - h - dpToPx(view, 40)) score += 10;

            // Height in the typical bottom-bar range.
            if (h >= dpToPx(view, 40) && h <= dpToPx(view, 100)) score += 5;

            // Spans most of the width.
            if (parentW > 0 && w >= parentW * 0.7f) score += 3;

            // Class-name hints.
            if (name.contains("bottomnavigation") || name.contains("bottombar")
                    || name.contains("navigationbar") || name.contains("navbar")
                    || name.contains("tabbar") || name.contains("bottomnav")) score += 4;
            if (name.contains("linearlayout") || name.contains("framelayout")
                    || name.contains("relativelayout") || name.contains("constraintlayout")) score += 1;

            // Holds several children (the tab icons).
            if (view instanceof ViewGroup && ((ViewGroup) view).getChildCount() >= 3) score += 3;
        } catch (Throwable ignored) {
        }
        return score;
    }

    /** Turns the bar into a floating pill: side margins + elevation. */
    private void makeFloating(View bar, Activity a) {
        try {
            ViewGroup.LayoutParams lp = bar.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                int side = dpToPx(a, SIDE_MARGIN_DP);
                int bottom = dpToPx(a, BOTTOM_MARGIN_DP);
                mlp.setMargins(side, mlp.topMargin, side, bottom);
                bar.setLayoutParams(lp);
            }
            bar.setElevation(dpToPx(a, ELEVATION_DP));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": makeFloating failed: " + t);
        }
    }

    /**
     * Captures what is behind the bar, blurs it, and sets it as the bar's
     * background. The bar's own children (icons) are never blurred.
     */
    private void applyBackdropBlur(Activity a, View bar) {
        try {
            int w = bar.getWidth();
            int h = bar.getHeight();
            if (w <= 0 || h <= 0) return;

            View decor = a.getWindow().getDecorView();

            // Hide the bar while capturing so it does not sample itself.
            int oldVisibility = bar.getVisibility();
            bar.setVisibility(View.INVISIBLE);
            Bitmap snapshot = captureView(decor);
            bar.setVisibility(oldVisibility);

            if (snapshot == null) return;

            int[] barLoc = new int[2];
            bar.getLocationOnScreen(barLoc);
            int[] decorLoc = new int[2];
            decor.getLocationOnScreen(decorLoc);

            int cropX = Math.max(0, barLoc[0] - decorLoc[0]);
            int cropY = Math.max(0, barLoc[1] - decorLoc[1]);
            int cropW = Math.min(w, snapshot.getWidth() - cropX);
            int cropH = Math.min(h, snapshot.getHeight() - cropY);
            if (cropW <= 0 || cropH <= 0) return;

            Bitmap cropped = Bitmap.createBitmap(snapshot, cropX, cropY, cropW, cropH);
            Bitmap blurred = blurBitmap(cropped);
            if (blurred == null) return;

            // Subtle white tint so the glass reads on any background.
            Canvas c = new Canvas(blurred);
            c.drawColor(Color.argb(TINT_ALPHA, 255, 255, 255));

            bar.setBackground(new BitmapDrawable(a.getResources(), blurred));

            // Pill outline.
            final float radius = h / 2f;
            bar.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            bar.setClipToOutline(true);

            XposedBridge.log(TAG + ": Backdrop blur applied");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": applyBackdropBlur failed: " + t);
        }
    }

    private Bitmap captureView(View view) {
        try {
            Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            view.draw(canvas);
            return bitmap;
        } catch (Throwable t) {
            return null;
        }
    }

    private Bitmap blurBitmap(Bitmap source) {
        if (source == null) return null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                Bitmap result = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(result);
                Paint paint = new Paint();
                paint.setRenderEffect(RenderEffect.createBlurEffect(BLUR_RADIUS, BLUR_RADIUS, Shader.TileMode.CLAMP));
                canvas.drawBitmap(source, 0, 0, paint);
                return result;
            } catch (Throwable ignored) {
            }
        }

        // Fallback: cheap downscale + upscale blur.
        int scale = 8;
        int w = Math.max(1, source.getWidth() / scale);
        int h = Math.max(1, source.getHeight() / scale);
        Bitmap small = Bitmap.createScaledBitmap(source, w, h, false);
        return Bitmap.createScaledBitmap(small, source.getWidth(), source.getHeight(), false);
    }

    private int dpToPx(android.content.Context context, int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics());
    }

    private int dpToPx(View view, int dp) {
        return dpToPx(view.getContext(), dp);
    }
}
