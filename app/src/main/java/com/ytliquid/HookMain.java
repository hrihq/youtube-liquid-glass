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
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.Checkable;
import android.widget.FrameLayout;
import android.widget.TextView;
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
    /** Miuix dims unselected navigation content to 40%. */
    private static final float MIUIX_UNSELECTED_ALPHA = 0.4f;
    /** Miuix squircle corner-tile extension: 1.0 = circle, 1.1 = continuous corner. */
    private static final float SQUIRCLE_EXTENSION = 1.1f;
    /** Miuix squircle cubic-bezier handle ratio. */
    private static final float SQUIRCLE_CONTROL = 0.643f;

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

    private View indicatorView;

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
            installIndicator(bar, s, density);
            installDrag(bar, s, density);
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

        SquircleDrawable background = new SquircleDrawable(
                Color.argb((int) (s.bgAlpha * 2.55f), 20, 26, 36), radius, 0f, 0f);
        background.setStrokeWidth(Math.max(1f, 0.8f * density));
        background.setStrokeColor(Color.argb(0x38, 0xFF, 0xFF, 0xFF));
        bar.setBackground(background);
        bar.setElevation(dpToPx(DP_ELEVATION, density));

        final int r = radius;
        bar.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    outline.setPath(squirclePath(view.getWidth(), view.getHeight(), r));
                } else {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
                }
            }
        });
        bar.setClipToOutline(true);
    }

    /** Appends a Miuix squircle (continuous corner) rounded rectangle. */
    private static void squirclePath(Path path, float left, float top, float right, float bottom,
                                     float radius) {
        float width = right - left;
        float height = bottom - top;
        if (width <= 0f || height <= 0f) return;

        float halfMin = Math.min(width, height) * 0.5f;
        float tile = Math.min(Math.max(radius, 0f) * SQUIRCLE_EXTENSION, halfMin);
        if (tile <= 0f) {
            path.addRect(left, top, right, bottom, Path.Direction.CW);
            return;
        }
        float handle = tile * (1f - SQUIRCLE_CONTROL);
        path.moveTo(left + tile, top);
        path.lineTo(right - tile, top);
        path.cubicTo(right - handle, top, right, top + handle, right, top + tile);
        path.lineTo(right, bottom - tile);
        path.cubicTo(right, bottom - handle, right - handle, bottom, right - tile, bottom);
        path.lineTo(left + tile, bottom);
        path.cubicTo(left + handle, bottom, left, bottom - handle, left, bottom - tile);
        path.lineTo(left, top + tile);
        path.cubicTo(left, top + handle, left + handle, top, left + tile, top);
        path.close();
    }

    private static Path squirclePath(float width, float height, float radius) {
        Path path = new Path();
        squirclePath(path, 0f, 0f, width, height, radius);
        return path;
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
        int selected = selectedIndex(bar);
        updateIndicator(bar, selected);

        ViewGroup menu = findMenu(bar);
        if (menu == null) return;
        for (int j = 0; j < menu.getChildCount(); j++) {
            View tab = menu.getChildAt(j);
            boolean isSelected = j == selected;
            Boolean last = selectionState.get(tab);
            if (last != null && last == isSelected) continue;
            selectionState.put(tab, isSelected);
            tab.setBackground(null);
            if (isSelected && s.animation) {
                tab.setScaleX(0.94f);
                tab.setScaleY(0.94f);
                tab.animate().scaleX(1f).scaleY(1f).setDuration(200L).start();
            }
        }
    }

    /** Adds the Miuix liquid-glass selection capsule behind the tabs. */
    private void installIndicator(ViewGroup bar, Settings s, float density) {
        indicatorView = new View(bar.getContext());
        int alpha = (int) (255 * 0.28f);
        int color = Color.argb(alpha, Color.red(s.selectedColor),
                Color.green(s.selectedColor), Color.blue(s.selectedColor));
        SquircleDrawable drawable = new SquircleDrawable(
                color, dpToPx(s.cornerRadius, density), dpToPx(5, density), dpToPx(4, density));
        drawable.setStrokeWidth(Math.max(1f, 0.8f * density));
        drawable.setStrokeColor(Color.argb(0x40, 0xFF, 0xFF, 0xFF));
        indicatorView.setBackground(drawable);
        try {
            bar.addView(indicatorView, 0,
                    new ViewGroup.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": could not add indicator: " + t);
            indicatorView = null;
        }
    }

    private void updateIndicator(ViewGroup bar, int index) {
        if (indicatorView == null) return;
        int count = tabCount(bar);
        if (count <= 0 || bar.getWidth() <= 0) return;
        float slot = bar.getWidth() / (float) count;
        indicatorView.setTranslationX(index * slot);
        ViewGroup.LayoutParams lp = indicatorView.getLayoutParams();
        if (lp != null) {
            lp.width = (int) slot;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            indicatorView.setLayoutParams(lp);
        }
    }

    /** Lets a horizontal drag slide the glass capsule and switch tabs on release. */
    private void installDrag(final ViewGroup bar, final Settings s, final float density) {
        bar.setClickable(true);
        bar.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        updateIndicator(bar, indexAt(bar, event.getX()));
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        updateIndicator(bar, indexAt(bar, event.getX()));
                        return true;
                    case MotionEvent.ACTION_UP:
                        int index = indexAt(bar, event.getX());
                        updateIndicator(bar, index);
                        View tab = tabAt(bar, index);
                        if (tab != null) tab.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        updateIndicator(bar, selectedIndex(bar));
                        return true;
                }
                return false;
            }
        });
    }

    private int indexAt(ViewGroup bar, float x) {
        int count = tabCount(bar);
        if (count <= 0) return 0;
        int index = (int) (x / (bar.getWidth() / (float) count));
        return Math.max(0, Math.min(count - 1, index));
    }

    private int tabCount(ViewGroup bar) {
        ViewGroup menu = findMenu(bar);
        return menu == null ? 0 : menu.getChildCount();
    }

    private View tabAt(ViewGroup bar, int index) {
        ViewGroup menu = findMenu(bar);
        if (menu == null || index < 0 || index >= menu.getChildCount()) return null;
        return menu.getChildAt(index);
    }

    private int selectedIndex(ViewGroup bar) {
        ViewGroup menu = findMenu(bar);
        if (menu == null) return 0;
        for (int j = 0; j < menu.getChildCount(); j++) {
            if (isSelected(menu.getChildAt(j))) return j;
        }
        return 0;
    }

    private ViewGroup findMenu(ViewGroup bar) {
        for (int i = 0; i < bar.getChildCount(); i++) {
            View child = bar.getChildAt(i);
            if (child == indicatorView) continue;
            if (child instanceof ViewGroup && ((ViewGroup) child).getChildCount() > 0) {
                return (ViewGroup) child;
            }
        }
        return null;
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
            menu.setClickable(false);
            for (int j = 0; j < menu.getChildCount(); j++) {
                View tab = menu.getChildAt(j);
                if (tab instanceof ViewGroup) {
                    tab.setClickable(false);
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

        boolean selected = isSelected(tab);
        float alpha = selected ? 1f : MIUIX_UNSELECTED_ALPHA;
        if (iconContainer != null) iconContainer.setAlpha(alpha);
        if (labelsGroup != null) {
            labelsGroup.setAlpha(alpha);
        }
        applyLabelStyle(labelsGroup, selected);

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

    private static void applyLabelStyle(View labelsGroup, boolean selected) {
        if (labelsGroup instanceof TextView) {
            ((TextView) labelsGroup).setTypeface(null,
                    selected ? Typeface.BOLD : Typeface.NORMAL);
        } else if (labelsGroup instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) labelsGroup;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof TextView) {
                    ((TextView) child).setTypeface(null,
                            selected ? Typeface.BOLD : Typeface.NORMAL);
                }
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

    /** Miuix squircle (continuous corner) drawable used for the bar and the selection pill. */
    public static final class SquircleDrawable extends Drawable {

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final float radius;
        private final float insetX;
        private final float insetY;
        private float strokeWidth;

        public SquircleDrawable(int color, float radius, float insetX, float insetY) {
            this.radius = radius;
            this.insetX = insetX;
            this.insetY = insetY;
            fill.setColor(color);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setColor(Color.argb(0x22, 0xFF, 0xFF, 0xFF));
        }

        public void setStrokeWidth(float width) {
            strokeWidth = width;
            stroke.setStrokeWidth(width);
        }

        public void setStrokeColor(int color) {
            stroke.setColor(color);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            path.reset();
            squirclePath(path, bounds.left + insetX, bounds.top + insetY,
                    bounds.right - insetX, bounds.bottom - insetY, radius);
            canvas.drawPath(path, fill);
            if (strokeWidth > 0f) canvas.drawPath(path, stroke);
        }

        @Override
        public void setAlpha(int alpha) {
            fill.setAlpha(alpha);
            stroke.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            fill.setColorFilter(colorFilter);
            stroke.setColorFilter(colorFilter);
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
