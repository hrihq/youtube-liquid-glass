package com.ytliquid;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed module: YouTube MIUI Liquid Glass
 * Applies frosted-glass (liquid glass) effect to YouTube's bottom navigation bar.
 */
public class HookMain implements IXposedHookLoadPackage {

    private static final String TAG = "YT_LiquidGlass";
    private static final String TARGET_PACKAGE = "com.google.android.youtube";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(TARGET_PACKAGE)) {
            return;
        }

        XposedBridge.log(TAG + ": Module loaded for " + TARGET_PACKAGE);

        // Hook Activity.onCreate to apply glass effect after UI is ready
        XposedHelpers.findAndHookMethod(
            Activity.class,
            "onCreate",
            Bundle.class,
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Activity activity = (Activity) param.thisObject;
                    if (activity == null) return;
                    if (!activity.getPackageName().equals(TARGET_PACKAGE)) return;

                    // Apply glass effect after layout
                    final View decorView = activity.getWindow().getDecorView();
                    decorView.post(() -> applyLiquidGlass(activity, decorView));
                }
            }
        );

        // Also hook onResume for cases where activity is recreated
        XposedHelpers.findAndHookMethod(
            Activity.class,
            "onResume",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Activity activity = (Activity) param.thisObject;
                    if (activity == null) return;
                    if (!activity.getPackageName().equals(TARGET_PACKAGE)) return;

                    final View decorView = activity.getWindow().getDecorView();
                    decorView.post(() -> applyLiquidGlass(activity, decorView));
                }
            }
        );
    }

    private void applyLiquidGlass(Activity activity, View decorView) {
        try {
            Window window = activity.getWindow();
            if (window == null) return;

            // Enable translucent navigation bar
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);

            // Traverse view hierarchy and find navigation bar containers
            if (decorView instanceof ViewGroup) {
                traverseAndApplyGlass((ViewGroup) decorView, window);
            }

            XposedBridge.log(TAG + ": Liquid glass applied");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Error applying glass: " + t.getMessage());
        }
    }

    private void traverseAndApplyGlass(ViewGroup parent, Window window) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child == null) continue;

            // Check if this view looks like a bottom navigation bar
            if (isNavigationBar(child, parent)) {
                applyGlassEffectToView(child, window);
            }

            // Recurse into child ViewGroups
            if (child instanceof ViewGroup) {
                traverseAndApplyGlass((ViewGroup) child, window);
            }
        }
    }

    private boolean isNavigationBar(View view, ViewGroup parent) {
        try {
            // Navigation bars are typically at the bottom of the screen
            // and have a specific height range
            int parentHeight = parent.getHeight();
            int childTop = view.getTop();
            int childHeight = view.getHeight();

            // Check if view is at bottom of parent
            if (parentHeight > 0 && childTop >= parentHeight - 300 && childHeight > 0 && childHeight < 200) {
                // Check if it's a container type (LinearLayout, FrameLayout, etc.)
                String className = view.getClass().getName().toLowerCase();
                if (className.contains("linearlayout") || 
                    className.contains("framelayout") || 
                    className.contains("relativelayout") ||
                    className.contains("constraintlayout") ||
                    className.contains("coordinatorlayout") ||
                    className.contains("cardview") ||
                    className.contains("toolbar") ||
                    className.contains("bottomnav") ||
                    className.contains("navigation")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void applyGlassEffectToView(View view, Window window) {
        try {
            // Apply RenderEffect blur (Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                RenderEffect blurEffect = RenderEffect.createBlurEffect(
                    20f, 20f, Shader.TileMode.CLAMP
                );
                view.setRenderEffect(blurEffect);
            }

            // Set translucent glass background
            // MIUI liquid glass: semi-transparent white with slight blue tint
            view.setBackgroundColor(Color.argb(140, 240, 240, 255));

            // Add elevation for depth
            view.setElevation(12f);

            // Add subtle shadow/outline
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                view.setOutlineSpotShadowColor(Color.argb(60, 0, 0, 0));
            }

            XposedBridge.log(TAG + ": Applied glass to " + view.getClass().getSimpleName());
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Failed to apply glass: " + t.getMessage());
        }
    }
}
