package com.lanpower.standalone;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** A separate, scrollable history screen; opening it leaves device selection intact. */
public final class DeviceHistoryActivity extends Activity {
    private TextView entries;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(244, 246, 248));
        root.setFitsSystemWindows(true);
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
                view.setPadding(padding + bars.left, padding + bars.top,
                        padding + bars.right, padding + bars.bottom);
            } else {
                view.setPadding(padding + insets.getSystemWindowInsetLeft(),
                        padding + insets.getSystemWindowInsetTop(),
                        padding + insets.getSystemWindowInsetRight(),
                        padding + insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        Button back = new Button(this);
        back.setText("‹ Quay lại");
        back.setOnClickListener(v -> finish());
        root.addView(back);
        TextView title = new TextView(this);
        title.setText("Nhật ký máy • " + getIntent().getStringExtra("hostname"));
        title.setTextSize(22);
        title.setTextColor(Color.rgb(16, 42, 67));
        root.addView(title);
        TextView address = new TextView(this);
        address.setText(getIntent().getStringExtra("ip") + "\nNhật ký lệnh được lưu trên APK này • Mới nhất ở trên");
        address.setPadding(0, padding / 2, 0, padding);
        root.addView(address);
        ScrollView scroll = new ScrollView(this);
        entries = new TextView(this);
        entries.setTextSize(15);
        entries.setTextColor(Color.rgb(16, 42, 67));
        entries.setTextIsSelectable(true);
        entries.setLineSpacing(padding / 4f, 1.1f);
        entries.setPadding(padding, padding, padding, padding);
        entries.setBackgroundColor(Color.WHITE);
        scroll.addView(entries);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        root.requestApplyInsets();
    }

    @Override protected void onResume() {
        super.onResume();
        entries.setText(new DeviceHistory(this).read(getIntent().getStringExtra("roomId"),
                getIntent().getStringExtra("deviceId")));
    }
}
