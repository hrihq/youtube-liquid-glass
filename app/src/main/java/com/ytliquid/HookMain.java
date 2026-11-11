package com.ytliquid;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.Checkable;
import android.widget.FrameLayout;
import android.widget.Toast;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed module that turns WhatsApp's bottom navigation bar into a floating
 * MIUI/Miuix glass capsule.
 *
 * <p>The bar is located the same way WaEnhancer does it: by WhatsApp's own
 * resource ids ({@code bottom_nav}, {@code navigation_bar}, {@code tab_layout})
 * instead of view-tree guessing, so it survives WhatsApp updates and obfuscation.
 * The frosted-glass backdrop is a real {@code RenderNode} backdrop blur.
 */
public class HookMain implements IXposedHookLoadPackage {

    private static final String TAG = "MiuixNav";
    private static final Set<String> TARGET_PACKAGES = new HashSet<String>(Arrays.asList(
            "com.whatsapp",
            "com.whatsapp.w4b"));
    private static final String MODULE_PACKAGE = "com.ytliquid";

    private static final int DP_PILL_HEIGHT = 56;
    private static final int DP_MIN_PILL_HEIGHT = 48;
    private static final int DP_ELEVATION = 12;

    private static int bottomNavId = -1;
    private static int navigationBarId = -1;
    private static int tabLayoutId = -1;
    private static int activeIndicatorId = -1;
    private static int dividerId = -1;

    private static final Set<ViewGroup> processed =
            Collections.newSetFromMap(new WeakHashMap<ViewGroup, Boolean>());
    private static final WeakHashMap<ViewGroup, ViewTreeObserver.OnPreDrawListener> guards =
            new WeakHashMap<ViewGroup, ViewTreeObserver.OnPreDrawListener>();
    private static final WeakHashMap<View, Boolean> selectionState =
            new WeakHashMap<View, Boolean>();

    private static boolean announcedLoad;
    private static boolean announcedResult;

    private Settings settings;
    private XSharedPreferences modulePrefs;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGES.contains(lpparam.packageName)) return;

        XposedBridge.log(TAG + ": module loaded for " + lpparam.packageName);

        try {
            XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            onViewAttached((View) param.thisObject);
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook onAttachedToWindow failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof Activity) {
                                onActivity((Activity) param.thisObject);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook onCreate failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof Activity) {
                                onActivity((Activity) param.thisObject);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook onResume failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onDestroy",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject instanceof Activity) {
                                cleanup((Activity) param.thisObject);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook onDestroy failed: " + t);
        }
    }

    // ------------------------------------------------------------------
    // Locating the bar
    // ------------------------------------------------------------------

    private void onViewAttached(View view) {
        if (view == null || bottomNavId == -1) return;
        int id = view.getId();
        if (id <= 0) return;
        if (id != bottomNavId && id != navigationBarId && id != tabLayoutId) return;
        if (!(view instanceof ViewGroup)) return;

        Activity activity = getActivity(view);
        if (activity == null || !isHomeActivity(activity)) return;

        scheduleSetup((ViewGroup) view);
    }

    private void onActivity(Activity activity) {
        if (activity == null || !isHomeActivity(activity)) return;

        announceLoad(activity);
        resolveIds(activity);

        final View decor = activity.getWindow().getDecorView();
        if (decor == null) return;

        final WeakReference<Activity> ref = new WeakReference<Activity>(activity);
        decor.post(new Runnable() {
            @Override
            public void run() {
                Activity a = ref.get();
                if (a == null || a.isDestroyed()) return;
                findAndSetup(a);
            }
        });
    }

    private void findAndSetup(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        if (decor == null) return;

        resolveIds(activity);

        View bar = null;
        if (bottomNavId > 0) bar = decor.findViewById(bottomNavId);
        if (bar == null && navigationBarId > 0) bar = decor.findViewById(navigationBarId);
        if (bar == null && tabLayoutId > 0) bar = decor.findViewById(tabLayoutId);

        if (bar instanceof ViewGroup) {
            scheduleSetup((ViewGroup) bar);
        } else {
            XposedBridge.log(TAG + ": bottom nav not found yet (ids "
                    + bottomNavId + "/" + navigationBarId + "/" + tabLayoutId + ")");
        }
    }

    private void resolveIds(Context context) {
        if (bottomNavId != -1) return;
        try {
            String pkg = context.getPackageName();
            bottomNavId = context.getResources().getIdentifier("bottom_nav", "id", pkg);
            navigationBarId = context.getResources().getIdentifier("navigation_bar", "id", pkg);
            tabLayoutId = context.getResources().getIdentifier("tab_layout", "id", pkg);
            activeIndicatorId = context.getResources().getIdentifier("active_indicator", "id", pkg);
            dividerId = context.getResources().getIdentifier("bottom_nav_divider", "id", pkg);
            XposedBridge.log(TAG + ": ids bottom_nav=" + bottomNavId
                    + " navigation_bar=" + navigationBarId
                    + " tab_layout=" + tabLayoutId
                    + " indicator=" + activeIndicatorId
                    + " divider=" + dividerId);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": resolveIds failed: " + t);
        }
    }

    private static boolean isHomeActivity(Activity activity) {
        String name = activity.getClass().getName();
        return name.endsWith("HomeActivity");
    }

    private static Activity getActivity(View view) {
        Context ctx = view.getContext();
        while (ctx instanceof ContextWrapper) {
            if (ctx instanceof Activity) return (Activity) ctx;
            ctx = ((ContextWrapper) ctx).getBaseContext();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------

    private void scheduleSetup(final ViewGroup bar) {
        if (bar == null) return;
        if (processed.contains(bar)) return;

        bar.post(new Runnable() {
            @Override
            public void run() {
                if (processed.contains(bar)) return;
                if (setupFloatingBar(bar)) {
                    processed.add(bar);
                } else {
                    bar.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (processed.contains(bar)) return;
                            if (setupFloatingBar(bar)) processed.add(bar);
                        }
                    }, 300L);
                }
            }
        });
    }

    private boolean setupFloatingBar(ViewGroup bar) {
        try {
            Settings s = readSettings();
            if (!s.enabled) {
                XposedBridge.log(TAG + ": disabled by settings");
                return true;
            }

            ViewParent parent = bar.getParent();
            if (!(parent instanceof ViewGroup)) return false;
            ViewGroup container = (ViewGroup) parent;

            FrameLayout root = findRoot(bar);
            if (root == null) return false;

            float density = bar.getResources().getDisplayMetrics().density;
            int sideMargin = dpToPx(s.sideMargin, density);
            int bottomMargin = navigationBarInset(bar) + dpToPx(s.bottomMargin, density);
            int pillHeight = s.barHeight > 0
                    ? dpToPx(s.barHeight, density)
                    : dpToPx(DP_PILL_HEIGHT + 12, density);
            if (pillHeight < dpToPx(DP_MIN_PILL_HEIGHT, density)) {
                pillHeight = dpToPx(DP_MIN_PILL_HEIGHT, density);
            }

            if (container.getParent() == root) {
                ViewGroup.LayoutParams lp = container.getLayoutParams();
                if (lp instanceof FrameLayout.LayoutParams) {
                    FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) lp;
                    flp.gravity = Gravity.BOTTOM;
                    flp.leftMargin = sideMargin;
                    flp.rightMargin = sideMargin;
                    flp.bottomMargin = bottomMargin;
                    flp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                    flp.height = pillHeight;
                    container.setLayoutParams(flp);
                }
            } else {
                if (container.getParent() instanceof ViewGroup) {
                    ((ViewGroup) container.getParent()).removeView(container);
                }
                FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, pillHeight);
                flp.gravity = Gravity.BOTTOM;
                flp.leftMargin = sideMargin;
                flp.rightMargin = sideMargin;
                flp.bottomMargin = bottomMargin;
                root.addView(container, flp);
            }

            container.setBackgroundColor(Color.TRANSPARENT);
            container.setClipChildren(false);
            container.setClipToPadding(false);

            bar.setClipChildren(false);
            bar.setClipToPadding(false);
            bar.setPadding(0, 0, 0, 0);

            if (dividerId > 0) {
                View divider = bar.findViewById(dividerId);
                if (divider != null) divider.setVisibility(View.GONE);
            }

            ViewGroup.LayoutParams barLp = bar.getLayoutParams();
            if (barLp != null) {
                barLp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                barLp.height = pillHeight;
                bar.setLayoutParams(barLp);
            }

            for (int i = 0; i < bar.getChildCount(); i++) {
                View child = bar.getChildAt(i);
                if (child instanceof ViewGroup) {
                    ViewGroup.LayoutParams clp = child.getLayoutParams();
                    if (clp != null) {
                        clp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                        child.setLayoutParams(clp);
                    }
                }
            }

            applyPillStyle(bar, s, density);
            applyBackdropBlur(bar, s.blurRadius, density);
            makeChildrenTransparent(bar);
            hideNativeIndicator(bar);
            installSelectionGuard(bar, s, density);

            selectionState.clear();
            syncSelection(bar, s, density);

            XposedBridge.log(TAG + ": floating bar applied to "
                    + bar.getClass().getName() + " " + bar.getWidth() + "x" + bar.getHeight());
            announceResult(bar.getContext(), true, bar.getClass().getName());
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": setup failed: " + t);
            return false;
        }
    }

    private void applyPillStyle(ViewGroup bar, Settings s, float density) {
        int radius = dpToPx(s.cornerRadius, density);

        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setCornerRadius(radius);
        background.setColor(Color.argb((int) (s.bgAlpha * 2.55f), 16, 20, 28));
        background.setStroke(Math.max(1, (int) (0.6f * density)),
                Color.argb(0x22, 0xFF, 0xFF, 0xFF));
        bar.setBackground(background);
        bar.setElevation(dpToPx(DP_ELEVATION, density));

        final int r = radius;
        bar.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        bar.setClipToOutline(true);
    }

    /**
     * Real backdrop blur through {@code RenderNode.setBackdropRenderEffect}, so the
     * icons and labels stay sharp while the content behind the pill is frosted.
     */
    private void applyBackdropBlur(View view, int radiusDp, float density) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        try {
            Object renderNode = XposedHelpers.getObjectField(view, "mRenderNode");
            if (renderNode == null) return;
            RenderEffect effect = RenderEffect.createBlurEffect(
                    radiusDp * density, radiusDp * density, Shader.TileMode.CLAMP);
            XposedHelpers.callMethod(renderNode, "setBackdropRenderEffect", effect);
            XposedHelpers.callMethod(renderNode, "setClipToOutline", true);
            view.invalidate();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": backdrop blur unavailable, using tint only: " + t);
        }
    }

    private void makeChildrenTransparent(View view) {
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (isIndicator(child)) continue;
            child.setBackground(null);
            if (child instanceof ViewGroup) makeChildrenTransparent(child);
        }
    }

    private void hideNativeIndicator(View view) {
        if (view.getId() != View.NO_ID) {
            try {
                String name = view.getResources().getResourceEntryName(view.getId());
                if (name != null && name.contains("active_indicator")) {
                    view.setBackground(null);
                    view.setAlpha(0f);
                    view.setVisibility(View.INVISIBLE);
                    return;
                }
            } catch (Throwable ignored) {
                // Not resolvable.
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                hideNativeIndicator(group.getChildAt(i));
            }
        }
    }

    private boolean isIndicator(View view) {
        if (view.getId() == View.NO_ID) return false;
        try {
            String name = view.getResources().getResourceEntryName(view.getId());
            return name != null && name.contains("active_indicator");
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Selection pill
    // ------------------------------------------------------------------

    private void installSelectionGuard(final ViewGroup bar, final Settings s, final float density) {
        if (guards.containsKey(bar)) return;
        ViewTreeObserver.OnPreDrawListener guard =
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        layoutTabItems(bar, s, density);
                        syncSelection(bar, s, density);
                        return true;
                    }
                };
        guards.put(bar, guard);
        try {
            bar.getViewTreeObserver().addOnPreDrawListener(guard);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": could not attach selection guard: " + t);
        }
    }

    private void syncSelection(ViewGroup bar, Settings s, float density) {
        for (int i = 0; i < bar.getChildCount(); i++) {
            View child = bar.getChildAt(i);
            if (!(child instanceof ViewGroup)) continue;
            ViewGroup menu = (ViewGroup) child;
            for (int j = 0; j < menu.getChildCount(); j++) {
                View tab = menu.getChildAt(j);
                boolean selected = isSelected(tab);
                Boolean last = selectionState.get(tab);
                if (last != null && last == selected) continue;
                selectionState.put(tab, selected);
                if (selected) {
                    tab.setBackground(new SelectorDrawable(
                            dpToPx(20, density), s.selectedColor,
                            dpToPx(6, density), dpToPx(5, density)));
                    if (s.animation) {
                        tab.setScaleX(0.94f);
                        tab.setScaleY(0.94f);
                        tab.animate().scaleX(1f).scaleY(1f).setDuration(200L).start();
                    }
                } else {
                    tab.setBackground(new ColorDrawable(Color.TRANSPARENT));
                }
            }
        }
    }

    private static boolean isSelected(View view) {
        if (view instanceof Checkable && ((Checkable) view).isChecked()) return true;
        return view.isSelected() || view.isActivated();
    }

    /**
     * Stacks each tab's icon above its label and applies the configured icon size
     * and label visibility. WhatsApp's own tab layout collapses once the bar is
     * given a fixed pill height, so the two are re-positioned explicitly.
     */
    private void layoutTabItems(ViewGroup bar, Settings s, float density) {
        for (int i = 0; i < bar.getChildCount(); i++) {
            View child = bar.getChildAt(i);
            if (!(child instanceof ViewGroup)) continue;
            ViewGroup menu = (ViewGroup) child;
            for (int j = 0; j < menu.getChildCount(); j++) {
                View tab = menu.getChildAt(j);
                if (tab instanceof ViewGroup) {
                    layoutTabItem((ViewGroup) tab, s, density);
                }
            }
        }
    }

    private void layoutTabItem(ViewGroup tab, Settings s, float density) {
        View iconContainer = null;
        View labelsGroup = null;

        for (int i = 0; i < tab.getChildCount(); i++) {
            View child = tab.getChildAt(i);
            if (child.getId() != View.NO_ID) {
                try {
                    String name = child.getResources().getResourceEntryName(child.getId());
                    if (name != null) {
                        if (name.contains("icon_container") || name.contains("icon")) {
                            iconContainer = child;
                        } else if (name.contains("labels_group") || name.contains("label")) {
                            labelsGroup = child;
                        }
                    }
                } catch (Throwable ignored) {
                    // Id not resolvable.
                }
            }
            if (iconContainer == null && child instanceof FrameLayout) iconContainer = child;
            if (labelsGroup == null && (child.getClass().getName().contains("BaselineLayout")
                    || (child instanceof ViewGroup && !(child instanceof FrameLayout)))) {
                labelsGroup = child;
            }
        }
        if (iconContainer == null && tab.getChildCount() >= 1) iconContainer = tab.getChildAt(0);
        if (labelsGroup == null && tab.getChildCount() >= 2) labelsGroup = tab.getChildAt(1);

        if (labelsGroup != null) {
            labelsGroup.setVisibility(s.showLabels ? View.VISIBLE : View.GONE);
        }

        int itemHeight = tab.getHeight();
        if (itemHeight <= 0) itemHeight = dpToPx(DP_PILL_HEIGHT + 12, density);

        int iconSize = dpToPx(s.iconSize, density);
        View iconView = findIconView(iconContainer);
        if (iconView != null) {
            ViewGroup.LayoutParams lp = iconView.getLayoutParams();
            if (lp != null && (lp.width != iconSize || lp.height != iconSize)) {
                lp.width = iconSize;
                lp.height = iconSize;
                iconView.setLayoutParams(lp);
            }
        }

        int iconHeight = (iconContainer != null && iconContainer.getHeight() > 0)
                ? iconContainer.getHeight() : iconSize;

        int labelHeight = 0;
        if (s.showLabels && labelsGroup != null && labelsGroup.getVisibility() != View.GONE) {
            labelHeight = labelsGroup.getHeight();
            if (labelHeight <= 0) labelHeight = dpToPx(16, density);
        }

        int spacing = dpToPx(2, density);
        int total = labelHeight > 0 ? iconHeight + spacing + labelHeight : iconHeight;
        int targetIconTop = Math.max(dpToPx(2, density), (itemHeight - total) / 2);

        if (iconContainer != null) {
            float offset = targetIconTop - iconContainer.getTop();
            if (Math.abs(iconContainer.getTranslationY() - offset) > 0.5f) {
                iconContainer.setTranslationY(offset);
            }
        }
        if (s.showLabels && labelsGroup != null && labelHeight > 0) {
            int targetLabelTop = targetIconTop + iconHeight + spacing;
            float offset = targetLabelTop - labelsGroup.getTop();
            if (Math.abs(labelsGroup.getTranslationY() - offset) > 0.5f) {
                labelsGroup.setTranslationY(offset);
            }
        }
    }

    private static View findIconView(View container) {
        if (container == null) return null;
        if (container instanceof android.widget.ImageView) return container;
        if (container instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) container;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (group.getChildAt(i) instanceof android.widget.ImageView) {
                    return group.getChildAt(i);
                }
            }
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findIconView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Rounded MIUI-style selection capsule drawn behind a tab's icon and label. */
    public static final class SelectorDrawable extends Drawable {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float radius;
        private final float insetX;
        private final float insetY;

        public SelectorDrawable(float radius, int color, float insetX, float insetY) {
            this.radius = radius;
            this.insetX = insetX;
            this.insetY = insetY;
            paint.setColor(color);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            rect.set(bounds.left + insetX, bounds.top + insetY,
                    bounds.right - insetX, bounds.bottom - insetY);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static FrameLayout findRoot(View start) {
        View current = start;
        FrameLayout last = null;
        while (current != null) {
            if (current instanceof FrameLayout) last = (FrameLayout) current;
            ViewParent parent = current.getParent();
            current = (parent instanceof View) ? (View) parent : null;
        }
        return last;
    }

    private static int navigationBarInset(View view) {
        try {
            android.view.WindowInsets insets = view.getRootWindowInsets();
            if (insets != null) return insets.getSystemWindowInsetBottom();
        } catch (Throwable ignored) {
            // Not available on this platform level.
        }
        return 0;
    }

    private static int dpToPx(int dp, float density) {
        return (int) (dp * density + 0.5f);
    }

    private void announceLoad(Context context) {
        if (announcedLoad) return;
        announcedLoad = true;
        toast(context, "Miuix Nav Bar: hook aktif");
    }

    private void announceResult(Context context, boolean found, String info) {
        if (announcedResult) return;
        announcedResult = true;
        toast(context, found
                ? "Miuix Nav Bar: nav bar ditemukan\n" + info
                : "Miuix Nav Bar: nav bar TIDAK ditemukan");
    }

    private void toast(Context context, String message) {
        try {
            Toast.makeText(context.getApplicationContext(), message, Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": toast failed: " + t);
        }
    }

    private void cleanup(Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            if (decor instanceof ViewGroup) clearState((ViewGroup) decor);
        } catch (Throwable ignored) {
            // Activity already detached.
        }
    }

    private void clearState(ViewGroup group) {
        processed.remove(group);
        ViewTreeObserver.OnPreDrawListener guard = guards.remove(group);
        if (guard != null) {
            try {
                group.getViewTreeObserver().removeOnPreDrawListener(guard);
            } catch (Throwable ignored) {
                // Already detached.
            }
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            processed.remove(child);
            if (child instanceof ViewGroup) clearState((ViewGroup) child);
        }
    }

    private Settings readSettings() {
        try {
            if (modulePrefs == null) {
                modulePrefs = new XSharedPreferences(MODULE_PACKAGE, Settings.PREF_NAME);
            }
            modulePrefs.reload();
            if (settings == null) settings = Settings.read(modulePrefs);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": could not read settings: " + t);
            if (settings == null) settings = new Settings();
        }
        return settings;
    }
}
