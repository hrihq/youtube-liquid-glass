package com.ytliquid;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Module settings screen.
 *
 * <p>Values are written with {@code MODE_WORLD_READABLE} so the hook, running
 * inside YouTube's process, can read them through {@code XSharedPreferences}.
 * LSPosed hooks {@code ContextImpl.checkMode} for modules that declare
 * {@code xposedsharedprefs}; without it we fall back to private mode and warn.
 */
public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private boolean worldReadable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = openPrefs();
        setContentView(buildContent());
    }

    private SharedPreferences openPrefs() {
        try {
            SharedPreferences p = getSharedPreferences(Settings.PREF_NAME, Context.MODE_WORLD_READABLE);
            worldReadable = true;
            return p;
        } catch (SecurityException e) {
            worldReadable = false;
            return getSharedPreferences(Settings.PREF_NAME, Context.MODE_PRIVATE);
        }
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        root.setPadding(padding, padding, padding, padding);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Miuix Nav Bar");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        TextView status = new TextView(this);
        status.setText(worldReadable
                ? "Aktifkan modul di LSPosed, pilih scope WhatsApp/YouTube, lalu mulai ulang app."
                : "Mode world-readable tidak didukung: pengaturan mungkin tidak terbaca modul.\n"
                + "Aktifkan modul di LSPosed, pilih scope WhatsApp/YouTube, lalu mulai ulang app.");
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setTextColor(worldReadable ? Color.parseColor("#666666") : Color.parseColor("#B3261E"));
        status.setPadding(0, 0, 0, dp(16));
        root.addView(status);

        root.addView(switchRow("Aktifkan modul", Settings.KEY_ENABLED, true));
        root.addView(seekRow("Intensitas blur", Settings.KEY_BLUR_RADIUS, 0, 25, 18, ""));
        root.addView(seekRow("Transparansi latar", Settings.KEY_BG_ALPHA, 0, 100, 45, "%"));
        root.addView(seekRow("Radius sudut", Settings.KEY_CORNER_RADIUS, 0, 40, 28, "dp"));
        root.addView(seekRow("Tinggi bar (0 = otomatis)", Settings.KEY_BAR_HEIGHT, 0, 80, 0, "dp"));
        root.addView(seekRow("Margin samping", Settings.KEY_SIDE_MARGIN, 0, 40, 14, "dp"));
        root.addView(seekRow("Margin bawah", Settings.KEY_BOTTOM_MARGIN, 0, 40, 12, "dp"));
        root.addView(seekRow("Ukuran ikon", Settings.KEY_ICON_SIZE, 16, 32, 24, "dp"));
        root.addView(switchRow("Tampilkan label", Settings.KEY_SHOW_LABELS, true));
        root.addView(switchRow("Animasi", Settings.KEY_ANIMATION, true));
        root.addView(colorRow());

        Button reset = new Button(this);
        reset.setText("Reset ke bawaan");
        reset.setOnClickListener(v -> {
            prefs.edit().clear().apply();
            recreate();
        });
        root.addView(reset);

        return scroll;
    }

    private View switchRow(String label, final String key, boolean defaultValue) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView text = new TextView(this);
        text.setText(label);
        text.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(text);

        Switch sw = new Switch(this);
        sw.setChecked(prefs.getBoolean(key, defaultValue));
        sw.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean(key, isChecked).apply());
        row.addView(sw);

        return row;
    }

    private View seekRow(String label, final String key, final int min, final int max,
                         int defaultValue, final String suffix) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView text = new TextView(this);
        text.setText(label);
        text.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(text);

        final TextView value = new TextView(this);
        value.setMinWidth(dp(48));
        value.setGravity(Gravity.END);
        header.addView(value);

        container.addView(header);

        final int current = prefs.getInt(key, defaultValue);
        value.setText(current + suffix);

        SeekBar seek = new SeekBar(this);
        seek.setMax(max - min);
        seek.setProgress(current - min);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int valueInt = progress + min;
                value.setText(valueInt + suffix);
                if (fromUser) {
                    prefs.edit().putInt(key, valueInt).apply();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        container.addView(seek);

        return container;
    }

    private View colorRow() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);

        TextView text = new TextView(this);
        text.setText("Warna pilihan");
        container.addView(text);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        int[] colors = {
                0xFF2F7BFF, // MIUI blue
                0xFF4FC3F7,
                0xFF7C4DFF,
                0xFF00BCD4,
                0xFFFF7043,
                0xFFFFFFFF,
        };
        for (final int color : colors) {
            Button swatch = new Button(this);
            swatch.setBackgroundColor(color);
            swatch.setText("");
            swatch.setMinimumWidth(dp(36));
            swatch.setMinimumHeight(dp(36));
            swatch.setOnClickListener(v ->
                    prefs.edit().putInt(Settings.KEY_SELECTED_COLOR, color).apply());
            row.addView(swatch);
        }

        container.addView(row);
        return container;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }
}
