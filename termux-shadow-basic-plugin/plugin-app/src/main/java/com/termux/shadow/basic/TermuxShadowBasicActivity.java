package com.termux.shadow.basic;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tencent.shadow.core.runtime.ShadowActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class TermuxShadowBasicActivity extends ShadowActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(28));
        root.setBackgroundColor(Color.rgb(246, 248, 250));

        TextView title = new TextView(this);
        title.setText("Termux Shadow Plugin");
        title.setTextSize(24);
        title.setTextColor(Color.rgb(24, 29, 39));
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView status = new TextView(this);
        status.setText(
                "运行中\n\n"
                        + "版本：" + BuildConfig.VERSION_NAME + "\n"
                        + "pluginId：" + BuildConfig.SHADOW_PLUGIN_ID + "\n"
                        + "partKey：" + BuildConfig.SHADOW_PART_KEY + "\n"
                        + "启动时间：" + now()
        );
        status.setTextSize(15);
        status.setTextColor(Color.rgb(56, 65, 80));
        status.setPadding(0, dp(18), 0, 0);
        root.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        setContentView(root);
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
