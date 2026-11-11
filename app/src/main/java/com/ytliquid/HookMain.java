package com.ytliquid;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.Checkable;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed module that turns YouTube's bottom navigation bar into a floating
 * MIUI/Miuix capsule with a blurred glass backdrop.
 *
 * <p>Detection is timing-safe and version-proof: instead of guessing from view
 * geometry at {@code onResume} (when nothing is laid out yet), it waits for the
 * decor view to be laid out, retries while YouTube adds its fragment views, and
 * locates the bar through a resource-id lookup followed by content-description /
 * text matching of the five real tabs. A wrong guess is never applied.
 */
public class HookMain implements IXposedHookLoadPackage {

    private static final String TAG = "YT_MiuixNav";
    /** Packages whose bottom navigation bar should be restyled. */
    private static final Set<String> TARGET_PACKAGES = new HashSet<String>(Arrays.asList(
            "com.whatsapp",
            "com.google.android.youtube"));
    private static final String MODULE_PACKAGE = "com.ytliquid";

    private static final int MAX_RETRIES = 25;
    private static final long RETRY_DELAY_MS = 320L;
    private static final int DP_MIN_HEIGHT = 36;
    private static final int DP_MAX_HEIGHT = 140;
    private static final int DP_MAX_BOTTOM_DISTANCE = 160;

    private static final String[] BAR_ID_NAMES = {
            "bottom_navigation_bar",
            "bottom_navigation",
            "navigation_bar",
            "bottom_bar",
            "main_bottom_navigation",
            "bottom_nav",
            "navbar",
            "bottom_navigation_view",
            "navigation_bar_container",
            "bottom_navigation_container",
            "main_navigation_bar",
            "bottom_navigation_layout",
            "main_bottom_navigation_bar",
            "bottom_navigation_bar_container",
            "bottom_navigation_outer",
            "bottom_navigation_menu",
            "design_bottom_navigation_view",
            "bottom_nav_view",
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<View> appliedDecors =
            Collections.newSetFromMap(new WeakHashMap<View, Boolean>());
    private final Map<View, Integer> retryCounts = new WeakHashMap<View, Integer>();
    private final Map<View, ViewTreeObserver.OnScrollChangedListener> scrollListeners =
            new WeakHashMap<View, ViewTreeObserver.OnScrollChangedListener>();
    private final ExecutorService blurExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "yt-miuix-blur");
        t.setDaemon(true);
        return t;
    });

    private WeakReference<Activity> currentActivity;
    private WeakReference<View> currentBar;
    private PillDrawable pillLayer;
    private BitmapDrawable blurLayer;
    private Settings lastSettings;
    private XSharedPreferences modulePrefs;
    private String targetPackage;

    private RenderScript renderScript;
    private ScriptIntrinsicBlur blurScript;
    private boolean polling;
    private boolean scrollRefreshScheduled;
    private static boolean announcedLoad;
    private static boolean announcedResult;
    private final Runnable scrollRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            scrollRefreshScheduled = false;
            Activity a = currentActivity == null ? null : currentActivity.get();
            View b = currentBar == null ? null : currentBar.get();
            if (a != null && b != null && !a.isDestroyed()) {
                refreshBackdrop(a, b, lastSettings);
            }
        }
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGES.contains(lpparam.packageName)) return;

        targetPackage = lpparam.packageName;
        XposedBridge.log(TAG + ": module loaded for " + lpparam.packageName);

        XC_MethodHook createHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object obj = param.thisObject;
                if (!(obj instanceof Activity)) return;
                Activity a = (Activity) obj;
                if (!TARGET_PACKAGES.contains(a.getPackageName())) return;
                try {
                    setup(a, a.getWindow().getDecorView());
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": setup failed: " + t);
                }
            }
        };

        XC_MethodHook resumeHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object obj = param.thisObject;
                if (!(obj instanceof Activity)) return;
                Activity a = (Activity) obj;
                if (!TARGET_PACKAGES.contains(a.getPackageName())) return;
                try {
                    setup(a, a.getWindow().getDecorView());
                } catch (Throwable t) {
                    XposedBridge.log(TAG + ": setup failed: " + t);
                }
            }
        };

        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, createHook);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook onCreate failed: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", resumeHook);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook onResume failed: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onDestroy", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object obj = param.thisObject;
                    if (obj instanceof Activity) cleanup((Activity) obj);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook onDestroy failed: " + t);
        }
    }

    // ------------------------------------------------------------------
    // Detection
    // ------------------------------------------------------------------

    private void setup(Activity a, View decor) {
        if (a == null || decor == null) return;
        if (appliedDecors.contains(decor)) return;

        announceLoad(a);

        if (decor.getWidth() <= 0 || decor.getHeight() <= 0) {
            scheduleSetup(a, decor, 200L);
            return;
        }

        View bar = findNavBar(a, decor);
        if (bar == null) {
            int retries = retryCounts.containsKey(decor) ? retryCounts.get(decor) : 0;
            if (retries < MAX_RETRIES) {
                retryCounts.put(decor, retries + 1);
                scheduleSetup(a, decor, RETRY_DELAY_MS);
            } else {
                XposedBridge.log(TAG + ": no navigation bar found after " + MAX_RETRIES + " attempts");
                dumpBottomHierarchy(a, decor);
                announceResult(a, false, null);
            }
            return;
        }

        appliedDecors.add(decor);
        retryCounts.remove(decor);
        XposedBridge.log(TAG + ": navigation bar = " + bar.getClass().getName()
                + " " + bar.getWidth() + "x" + bar.getHeight());
        announceResult(a, true, bar.getClass().getName());
        applyNavBar(a, bar);
    }

    private void announceLoad(Activity a) {
        if (announcedLoad) return;
        announcedLoad = true;
        toast(a, "Miuix Nav Bar: hook aktif");
    }

    private void announceResult(Activity a, boolean found, String info) {
        if (announcedResult) return;
        announcedResult = true;
        String message = found
                ? "Miuix Nav Bar: nav bar ditemukan\n" + info
                : "Miuix Nav Bar: nav bar TIDAK ditemukan";
        toast(a, message);
    }

    private void toast(Activity a, String message) {
        try {
            Toast.makeText(a.getApplicationContext(), message, Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": toast failed: " + t);
        }
    }

    /**
     * Logs every view in the bottom 220dp with its resource-entry name, bounds,
     * child count and content description so a missed detection can be fixed
     * from logcat alone.
     */
    private void dumpBottomHierarchy(Activity a, View decor) {
        StringBuilder sb = new StringBuilder();
        try {
            int[] decorLoc = new int[2];
            decor.getLocationOnScreen(decorLoc);
            int screenBottom = decorLoc[1] + decor.getHeight();
            float density = a.getResources().getDisplayMetrics().density;
            dumpBottomHierarchy(decor, screenBottom, dp(220, density), 0, sb);
        } catch (Throwable t) {
            sb.append("dump failed: ").append(t);
        }
        String dump = sb.toString();
        XposedBridge.log(TAG + ": hierarchy dump:\n" + dump);
        showDumpDialog(a, dump);
    }

    private void dumpBottomHierarchy(View v, int screenBottom, int maxDistance, int depth, StringBuilder sb) {
        if (v == null || depth > 24) return;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        int bottom = loc[1] + v.getHeight();
        if (v.getHeight() > 0 && screenBottom - bottom <= maxDistance) {
            String idName = "";
            try {
                if (v.getId() != View.NO_ID) {
                    idName = v.getResources().getResourceEntryName(v.getId());
                }
            } catch (Throwable ignored) {
                // Id not resolvable.
            }
            int children = v instanceof ViewGroup ? ((ViewGroup) v).getChildCount() : 0;
            sb.append(v.getClass().getName())
                    .append(" id=").append(idName)
                    .append(" bounds=[").append(loc[0]).append(',').append(loc[1]).append(',')
                    .append(loc[0] + v.getWidth()).append(',').append(bottom).append(']')
                    .append(" children=").append(children)
                    .append(" cd=").append(v.getContentDescription())
                    .append(" bg=").append(v.getBackground() != null)
                    .append('\n');
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                dumpBottomHierarchy(g.getChildAt(i), screenBottom, maxDistance, depth + 1, sb);
            }
        }
    }

    private void showDumpDialog(Activity a, String dump) {
        try {
            ScrollView scroll = new ScrollView(a);
            TextView tv = new TextView(a);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
            tv.setText(dump);
            int pad = dpToPx(a, 12);
            tv.setPadding(pad, pad, pad, pad);
            scroll.addView(tv);
            new AlertDialog.Builder(a)
                    .setTitle("Miuix Nav Bar - Diagnosa")
                    .setView(scroll)
                    .setPositiveButton("Tutup", null)
                    .show();
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": dump dialog failed: " + t);
        }
    }

    private void scheduleSetup(Activity a, View decor, long delay) {
        final WeakReference<Activity> actRef = new WeakReference<Activity>(a);
        final WeakReference<View> decorRef = new WeakReference<View>(decor);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Activity act = actRef.get();
                View d = decorRef.get();
                if (act == null || d == null || act.isDestroyed()) return;
                setup(act, d);
            }
        }, delay);
    }

    private View findNavBar(Activity a, View decor) {
        int rootW = decor.getWidth();
        if (rootW <= 0) return null;
        int[] decorLoc = new int[2];
        decor.getLocationOnScreen(decorLoc);
        int screenBottom = decorLoc[1] + decor.getHeight();
        float density = a.getResources().getDisplayMetrics().density;

        // 1. Resource-id lookup: the strongest signal when the name matches.
        View byId = findById(a, decor);
        if (byId != null) return byId;

        // 2. Label-based and structural traversal.
        List<View> all = new ArrayList<View>();
        collectAll(decor, all, 0);

        Map<View, Set<String>> labelSets = new HashMap<View, Set<String>>();
        for (View v : all) {
            if (!isNavLabelView(v)) continue;
            String label = navLabelText(v);
            if (label == null) continue;
            String key = label.trim().toLowerCase(Locale.ROOT);
            ViewParent p = v.getParent();
            int depth = 0;
            while (p instanceof View && depth < 6) {
                View anc = (View) p;
                Set<String> set = labelSets.get(anc);
                if (set == null) {
                    set = new HashSet<String>();
                    labelSets.put(anc, set);
                }
                set.add(key);
                p = anc.getParent();
                depth++;
            }
        }

        List<Candidate> candidates = new ArrayList<Candidate>();
        for (View v : all) {
            if (!(v instanceof ViewGroup)) continue;

            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            int left = loc[0];
            int top = loc[1];
            int right = left + v.getWidth();
            int bottom = top + v.getHeight();
            int w = right - left;
            int h = bottom - top;
            if (w <= 0 || h <= 0) continue;

            float widthRatio = (float) w / (float) rootW;
            int bottomDist = screenBottom - bottom;
            int dpHeight = (int) (h / density);

            // Hard geometry filter: the bar is a wide, short, bottom-anchored view.
            if (dpHeight < DP_MIN_HEIGHT || dpHeight > DP_MAX_HEIGHT) continue;
            if (widthRatio < 0.55f) continue;
            if (bottomDist < -dp(20, density) || bottomDist > dp(DP_MAX_BOTTOM_DISTANCE, density)) continue;

            int childCount = ((ViewGroup) v).getChildCount();
            int clickableChildren = 0;
            for (int i = 0; i < childCount; i++) {
                if (((ViewGroup) v).getChildAt(i).isClickable()) clickableChildren++;
            }
            Set<String> labels = labelSets.get(v);
            int labelCount = labels == null ? 0 : labels.size();
            boolean classHint = hasClassHint(v);

            int score = scoreNavBar(widthRatio, bottomDist, dpHeight, childCount, labelCount, classHint, density);
            Candidate c = new Candidate(v, score, labelCount, classHint, clickableChildren, w * h,
                    v.getClass().getName(), left, top, right, bottom, childCount);
            candidates.add(c);

            if (score >= 20) {
                XposedBridge.log(TAG + ": candidate " + c.name + " score=" + score
                        + " labels=" + labelCount
                        + " bounds=[" + left + "," + top + "," + right + "," + bottom + "]"
                        + " children=" + childCount);
            }
        }

        Candidate best = pickBest(candidates);
        if (best == null) return null;

        if (best.labelCount >= 3 && best.score >= 60) {
            XposedBridge.log(TAG + ": matched by labels: " + best.name + " labels=" + best.labelCount);
            clearDescendantBars(best.view, candidates);
            return best.view;
        }
        if (best.labelCount == 2 && best.score >= 55) {
            XposedBridge.log(TAG + ": matched by partial labels: " + best.name + " labels=" + best.labelCount);
            clearDescendantBars(best.view, candidates);
            return best.view;
        }
        if (best.labelCount == 1 && best.score >= 60) {
            XposedBridge.log(TAG + ": matched by single label: " + best.name + " labels=" + best.labelCount);
            clearDescendantBars(best.view, candidates);
            return best.view;
        }
        if (best.labelCount == 0 && best.classHint && best.score >= 45) {
            XposedBridge.log(TAG + ": matched by class hint: " + best.name + " score=" + best.score);
            clearDescendantBars(best.view, candidates);
            return best.view;
        }
        if (best.labelCount == 0 && best.clickableChildren >= 3 && best.score >= 40) {
            XposedBridge.log(TAG + ": matched structurally: " + best.name
                    + " score=" + best.score + " clickable=" + best.clickableChildren);
            clearDescendantBars(best.view, candidates);
            return best.view;
        }

        XposedBridge.log(TAG + ": best candidate rejected: " + best.name
                + " labels=" + best.labelCount + " score=" + best.score
                + " classHint=" + best.classHint);
        return null;
    }

    /**
     * When the matched view is a wrapper around the real bar, the inner bar's
     * own opaque background would hide the capsule. Make every nested
     * label-bearing container transparent so only the capsule shows.
     */
    private void clearDescendantBars(View target, List<Candidate> candidates) {
        for (Candidate c : candidates) {
            if (c.view == target || c.labelCount < 3) continue;
            if (isDescendantOf(c.view, target)) {
                try {
                    c.view.setBackground(new ColorDrawable(Color.TRANSPARENT));
                } catch (Throwable ignored) {
                    // Not fatal.
                }
            }
        }
    }

    private boolean isDescendantOf(View v, View ancestor) {
        ViewParent p = v.getParent();
        while (p instanceof View) {
            if (p == ancestor) return true;
            p = ((View) p).getParent();
        }
        return false;
    }

    private View findById(Activity a, View decor) {
        String pkg = targetPackage != null ? targetPackage : a.getPackageName();
        for (String name : BAR_ID_NAMES) {
            try {
                int id = a.getResources().getIdentifier(name, "id", pkg);
                if (id == 0) continue;
                View v = decor.findViewById(id);
                if (v != null && v.getVisibility() == View.VISIBLE) {
                    XposedBridge.log(TAG + ": found by resource id '" + name + "' -> " + v.getClass().getName());
                    return v;
                }
            } catch (Throwable ignored) {
                // Resource lookup can fail for obfuscated/dynamic packages.
            }
        }
        return null;
    }

    private void collectAll(View v, List<View> out, int depth) {
        if (v == null || depth > 32) return;
        out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectAll(g.getChildAt(i), out, depth + 1);
            }
        }
    }

    private boolean isNavLabelView(View v) {
        CharSequence cd = v.getContentDescription();
        if (cd != null && NavLabels.isNavLabel(cd.toString())) return true;
        if (v instanceof TextView) {
            CharSequence text = ((TextView) v).getText();
            return text != null && NavLabels.isNavLabel(text.toString());
        }
        return false;
    }

    private String navLabelText(View v) {
        CharSequence cd = v.getContentDescription();
        if (cd != null) return cd.toString();
        if (v instanceof TextView) {
            CharSequence text = ((TextView) v).getText();
            if (text != null) return text.toString();
        }
        return null;
    }

    private int scoreNavBar(float widthRatio, int bottomDist, int dpHeight,
                            int childCount, int labelCount, boolean classHint, float density) {
        int score = 0;

        if (labelCount >= 3) score += 100 + labelCount * 10;
        else if (labelCount == 2) score += 30;
        else if (labelCount == 1) score += 5;

        if (widthRatio >= 0.6f) score += 12;
        else if (widthRatio >= 0.4f) score += 6;

        if (bottomDist >= -dp(20, density) && bottomDist <= dp(120, density)) score += 12;
        else if (bottomDist > dp(120, density) && bottomDist <= dp(240, density)) score += 4;

        if (dpHeight >= 36 && dpHeight <= 130) score += 8;
        else if (dpHeight > 130 && dpHeight <= 200) score += 2;

        if (childCount >= 3 && childCount <= 8) score += 6;

        if (classHint) score += 10;

        return score;
    }

    private boolean hasClassHint(View v) {
        String cls = v.getClass().getName().toLowerCase(Locale.ROOT);
        return cls.contains("bottomnavigation") || cls.contains("bottombar")
                || cls.contains("navigationbar") || cls.contains("navbar")
                || cls.contains("tabbar") || cls.contains("bottomnav")
                || cls.contains("bottom_navigation") || cls.contains("navigation_bar")
                || cls.contains("bottom_nav");
    }

    private Candidate pickBest(List<Candidate> candidates) {
        Candidate best = null;
        for (Candidate c : candidates) {
            if (best == null) {
                best = c;
            } else if (c.labelCount != best.labelCount) {
                if (c.labelCount > best.labelCount) best = c;
            } else if (c.score != best.score) {
                if (c.score > best.score) best = c;
            } else if (c.area > best.area) {
                // Outermost view that still passes the geometry filter: that is
                // the bar, not the inner menu row.
                best = c;
            }
        }
        return best;
    }

    private static final class Candidate {
        final View view;
        final int score;
        final int labelCount;
        final boolean classHint;
        final int clickableChildren;
        final int area;
        final String name;
        final int left, top, right, bottom;
        final int childCount;

        Candidate(View view, int score, int labelCount, boolean classHint, int clickableChildren,
                  int area, String name, int left, int top, int right, int bottom, int childCount) {
            this.view = view;
            this.score = score;
            this.labelCount = labelCount;
            this.classHint = classHint;
            this.clickableChildren = clickableChildren;
            this.area = area;
            this.name = name;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.childCount = childCount;
        }
    }

    // ------------------------------------------------------------------
    // Application
    // ------------------------------------------------------------------

    private void applyNavBar(Activity a, View bar) {
        if (a == null || bar == null || !(bar instanceof ViewGroup)) return;

        Settings s = readSettings();
        lastSettings = s;
        currentActivity = new WeakReference<Activity>(a);
        currentBar = new WeakReference<View>(bar);

        XposedBridge.log(TAG + ": applying style enabled=" + s.enabled
                + " blur=" + s.blurRadius + " alpha=" + s.bgAlpha
                + " corner=" + s.cornerRadius + " height=" + s.barHeight
                + " side=" + s.sideMargin + " bottom=" + s.bottomMargin
                + " icon=" + s.iconSize + " labels=" + s.showLabels);

        if (!s.enabled) {
            XposedBridge.log(TAG + ": disabled by settings, leaving YouTube untouched");
            return;
        }

        ensureRenderScript(a);

        // Float the bar and optionally pin its height.
        ViewGroup.LayoutParams lp = bar.getLayoutParams();
        if (lp != null) {
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
                int side = dpToPx(a, s.sideMargin);
                int bottom = dpToPx(a, s.bottomMargin);
                mlp.setMargins(side, mlp.topMargin, side, bottom);
                if (s.barHeight > 0) mlp.height = dpToPx(a, s.barHeight);
                bar.setLayoutParams(lp);
            } else {
                XposedBridge.log(TAG + ": LayoutParams " + lp.getClass().getName()
                        + " does not support margins");
            }
        }

        bar.setElevation(dpToPx(a, 12));

        final float corner = s.cornerRadius > 0 ? dpToPx(a, s.cornerRadius) : 0f;
        bar.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                float r = corner > 0 ? corner : (v.getHeight() / 2f);
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            }
        });
        bar.setClipToOutline(true);

        applyItemTints(bar, s);
        installBackground(a, bar, s);
        attachScrollListener(a, bar);
        startPolling(a, bar);
    }

    private void applyItemTints(View bar, Settings s) {
        int[][] states = new int[][]{{android.R.attr.state_checked}, {}};
        int[] colors = new int[]{s.selectedColor, s.inactiveColor};
        ColorStateList csl = new ColorStateList(states, colors);

        try {
            XposedHelpers.callMethod(bar, "setItemIconTintList", csl);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": setItemIconTintList unsupported: " + t);
        }
        try {
            XposedHelpers.callMethod(bar, "setItemTextColor", csl);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": setItemTextColor unsupported: " + t);
        }
        try {
            XposedHelpers.callMethod(bar, "setItemIconSize", dpToPx(bar.getContext(), s.iconSize));
        } catch (Throwable ignored) {
            // Not a Material NavigationBarView.
        }
        try {
            // LABEL_VISIBILITY_LABELED = 1, LABEL_VISIBILITY_UNLABELED = 0
            XposedHelpers.callMethod(bar, "setLabelVisibilityMode", s.showLabels ? 1 : 0);
        } catch (Throwable ignored) {
            // Not a Material NavigationBarView.
        }
    }

    private void installBackground(Activity a, View bar, Settings s) {
        pillLayer = new PillDrawable();
        pillLayer.setColor(s.selectedColor);

        Bitmap placeholder = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        blurLayer = new BitmapDrawable(a.getResources(), placeholder);

        Drawable base = new ColorDrawable(Color.argb((int) (s.bgAlpha * 2.55f), 12, 12, 18));
        LayerDrawable layer = new LayerDrawable(new Drawable[]{base, blurLayer, pillLayer});
        bar.setBackground(layer);

        refreshBackdrop(a, bar, s);
    }

    private void attachScrollListener(Activity a, View bar) {
        View decor = a.getWindow().getDecorView();
        if (scrollListeners.containsKey(decor)) return;

        ViewTreeObserver.OnScrollChangedListener listener =
                new ViewTreeObserver.OnScrollChangedListener() {
                    @Override
                    public void onScrollChanged() {
                        if (scrollRefreshScheduled) return;
                        scrollRefreshScheduled = true;
                        handler.postDelayed(scrollRefreshRunnable, 160L);
                    }
                };
        try {
            decor.getViewTreeObserver().addOnScrollChangedListener(listener);
            scrollListeners.put(decor, listener);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": could not attach scroll listener: " + t);
        }
    }

    private void startPolling(Activity a, View bar) {
        if (polling) return;
        polling = true;

        final WeakReference<Activity> actRef = new WeakReference<Activity>(a);
        final WeakReference<View> barRef = new WeakReference<View>(bar);
        final Runnable poll = new Runnable() {
            private int tick;

            @Override
            public void run() {
                Activity act = actRef.get();
                View b = barRef.get();
                if (act == null || b == null || act.isDestroyed()) {
                    polling = false;
                    return;
                }

                updatePill(b);

                tick++;
                if (tick % 20 == 0) {
                    Settings fresh = readSettings();
                    if (fresh.changedFrom(lastSettings)) {
                        lastSettings = fresh;
                        XposedBridge.log(TAG + ": settings changed, re-applying");
                        applyNavBar(act, b);
                    }
                }

                handler.postDelayed(this, 250L);
            }
        };
        handler.postDelayed(poll, 250L);
    }

    private void updatePill(View bar) {
        if (bar == null || pillLayer == null) return;

        View item = findSelectedItem(bar);
        if (item == null) {
            pillLayer.setItemBounds(new Rect(0, 0, 0, 0), 0f);
            return;
        }
        int[] barLoc = new int[2];
        int[] itemLoc = new int[2];
        bar.getLocationInWindow(barLoc);
        item.getLocationInWindow(itemLoc);

        int pad = dpToPx(bar.getContext(), 3);
        Rect bounds = new Rect(
                itemLoc[0] - barLoc[0] - pad,
                itemLoc[1] - barLoc[1] - pad,
                itemLoc[0] - barLoc[0] + item.getWidth() + pad,
                itemLoc[1] - barLoc[1] + item.getHeight() + pad);
        float radius = Math.min(bounds.width(), bounds.height()) / 2f;
        if (pillLayer.setItemBounds(bounds, radius)) {
            bar.invalidate();
        }
    }

    private View findSelectedItem(View bar) {
        // Material NavigationBarView: resolve the selected menu item id.
        try {
            Object id = XposedHelpers.callMethod(bar, "getSelectedItemId");
            if (id instanceof Integer && ((Integer) id) != 0) {
                View item = bar.findViewById((Integer) id);
                if (item != null) return item;
            }
        } catch (Throwable ignored) {
            // Not a Material bar.
        }

        if (!(bar instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) bar;
        View nestedFallback = null;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.isSelected() || child.isActivated() || isChecked(child)) return child;
            if (nestedFallback == null && child instanceof ViewGroup) {
                View inner = findSelectedItem(child);
                if (inner != null) nestedFallback = inner;
            }
        }
        return nestedFallback;
    }

    private boolean isChecked(View v) {
        return (v instanceof Checkable) && ((Checkable) v).isChecked();
    }

    // ------------------------------------------------------------------
    // Backdrop blur
    // ------------------------------------------------------------------

    private void refreshBackdrop(Activity a, View bar, Settings s) {
        if (a == null || bar == null || s == null) return;
        if (bar.getWidth() <= 0 || bar.getHeight() <= 0 || blurLayer == null) return;

        final int w = bar.getWidth();
        final int h = bar.getHeight();
        final View decor = a.getWindow().getDecorView();
        if (decor.getWidth() <= 0 || decor.getHeight() <= 0) return;

        final float scale = Math.min(0.5f, 480f / (float) Math.max(1, decor.getWidth()));

        Bitmap snapshot = null;
        try {
            snapshot = Bitmap.createBitmap(
                    Math.max(1, (int) (decor.getWidth() * scale)),
                    Math.max(1, (int) (decor.getHeight() * scale)),
                    Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(snapshot);
            canvas.scale(scale, scale);

            int oldVisibility = bar.getVisibility();
            bar.setVisibility(View.INVISIBLE);
            try {
                decor.draw(canvas);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": decor draw failed: " + t);
            }
            bar.setVisibility(oldVisibility);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": snapshot failed: " + t);
        }
        if (snapshot == null) return;

        int[] barLoc = new int[2];
        int[] decorLoc = new int[2];
        bar.getLocationOnScreen(barLoc);
        decor.getLocationOnScreen(decorLoc);

        int cropX = Math.max(0, (int) ((barLoc[0] - decorLoc[0]) * scale));
        int cropY = Math.max(0, (int) ((barLoc[1] - decorLoc[1]) * scale));
        int cropW = Math.min((int) (w * scale), snapshot.getWidth() - cropX);
        int cropH = Math.min((int) (h * scale), snapshot.getHeight() - cropY);
        if (cropW <= 0 || cropH <= 0) {
            snapshot.recycle();
            return;
        }

        Bitmap crop = Bitmap.createBitmap(snapshot, cropX, cropY, cropW, cropH);
        snapshot.recycle();

        float fit = Math.min(1f, 480f / (float) Math.max(crop.getWidth(), crop.getHeight()));
        if (fit < 1f) {
            Bitmap scaled = Bitmap.createScaledBitmap(crop,
                    Math.max(1, (int) (crop.getWidth() * fit)),
                    Math.max(1, (int) (crop.getHeight() * fit)), true);
            crop.recycle();
            crop = scaled;
        }

        final Bitmap toBlur = crop;
        final int radius = Math.max(0, Math.min(25, Math.round(s.blurRadius * scale)));
        final int tintAlpha = (int) (s.bgAlpha * 2.55f);

        blurExecutor.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap blurred = radius > 0 ? blurBitmap(toBlur, radius) : toBlur;
                if (blurred == null) return;

                Canvas c = new Canvas(blurred);
                c.drawColor(Color.argb(tintAlpha, 12, 12, 18));

                final Bitmap result = blurred;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        Activity act = currentActivity == null ? null : currentActivity.get();
                        View b = currentBar == null ? null : currentBar.get();
                        if (act == null || b == null || act.isDestroyed()) return;
                        if (blurLayer != null) {
                            blurLayer.setBitmap(result);
                            b.invalidate();
                        }
                    }
                });
            }
        });
    }

    private void ensureRenderScript(Context context) {
        if (renderScript != null) return;
        try {
            RenderScript rs = RenderScript.create(context.getApplicationContext());
            ScriptIntrinsicBlur blur = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs));
            renderScript = rs;
            blurScript = blur;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": RenderScript unavailable: " + t);
            renderScript = null;
            blurScript = null;
        }
    }

    private Bitmap blurBitmap(Bitmap source, float radius) {
        if (source == null) return null;

        if (renderScript != null && blurScript != null) {
            try {
                Allocation input = Allocation.createFromBitmap(renderScript, source);
                Allocation output = Allocation.createTyped(renderScript, input.getType());
                blurScript.setRadius(Math.max(0f, Math.min(25f, radius)));
                blurScript.setInput(input);
                blurScript.forEach(output);
                output.copyTo(source);
                input.destroy();
                output.destroy();
                return source;
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": RenderScript blur failed: " + t);
            }
        }

        // Fallback: cheap downscale + upscale blur.
        try {
            int scale = 8;
            int w = Math.max(1, source.getWidth() / scale);
            int h = Math.max(1, source.getHeight() / scale);
            Bitmap small = Bitmap.createScaledBitmap(source, w, h, false);
            return Bitmap.createScaledBitmap(small, source.getWidth(), source.getHeight(), false);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Settings / lifecycle
    // ------------------------------------------------------------------

    private Settings readSettings() {
        try {
            if (modulePrefs == null) {
                modulePrefs = new XSharedPreferences(MODULE_PACKAGE, Settings.PREF_NAME);
            }
            modulePrefs.reload();
            return Settings.read(modulePrefs);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": could not read module settings: " + t);
            return new Settings();
        }
    }

    private void cleanup(Activity a) {
        try {
            View decor = a.getWindow().getDecorView();
            ViewTreeObserver.OnScrollChangedListener listener = scrollListeners.remove(decor);
            if (listener != null) {
                try {
                    decor.getViewTreeObserver().removeOnScrollChangedListener(listener);
                } catch (Throwable ignored) {
                    // View already detached.
                }
            }
            appliedDecors.remove(decor);
            retryCounts.remove(decor);
        } catch (Throwable ignored) {
            // Activity already detached.
        }
    }

    private int dpToPx(Context context, int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                context.getResources().getDisplayMetrics());
    }

    private static int dp(int value, float density) {
        return (int) (value * density + 0.5f);
    }
}
