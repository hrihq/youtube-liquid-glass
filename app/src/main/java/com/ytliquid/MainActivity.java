package com.ytliquid;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("YouTube Liquid Glass Module\nActivate in LSPosed and select YouTube.");
        tv.setPadding(48, 48, 48, 48);
        setContentView(tv);
    }
}
