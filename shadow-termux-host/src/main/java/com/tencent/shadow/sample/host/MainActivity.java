package com.tencent.shadow.sample.host;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.tencent.shadow.sample.constant.Constant;
import com.tencent.shadow.sample.host.platform.ShadowPluginDescriptor;

import java.util.List;

public class MainActivity extends Activity {

    private TextView statusView;
    private LinearLayout pluginList;
    private Button refreshButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(R.style.TestHostTheme);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(24));
        scrollView.addView(root);

        TextView title = new TextView(this);
        title.setText("Shadow 插件平台");
        title.setTextSize(24);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        TextView home = new TextView(this);
        home.setText("数据目录  ~/.termux-shadow");
        home.setTextSize(14);
        home.setTextColor(Color.rgb(65, 65, 65));
        home.setPadding(0, dp(10), 0, dp(10));
        root.addView(home);

        statusView = new TextView(this);
        statusView.setTextColor(Color.rgb(75, 75, 75));
        statusView.setTextSize(14);
        statusView.setPadding(0, dp(6), 0, dp(8));
        root.addView(statusView);

        refreshButton = new Button(this);
        refreshButton.setAllCaps(false);
        refreshButton.setText("刷新插件注册表");
        refreshButton.setOnClickListener(view -> refreshPluginState());
        root.addView(refreshButton);

        pluginList = new LinearLayout(this);
        pluginList.setOrientation(LinearLayout.VERTICAL);
        pluginList.setPadding(0, dp(10), 0, 0);
        root.addView(pluginList);

        setContentView(scrollView);
        refreshPluginState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (statusView != null) {
            refreshPluginState();
        }
    }

    private void refreshPluginState() {
        statusView.setText("正在同步 inbox、注册表和运行快照...");
        refreshButton.setEnabled(false);
        PluginHelper.getInstance().executor().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<ShadowPluginDescriptor> plugins = PluginHelper.getInstance().refresh();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || isDestroyed()) {
                                return;
                            }
                            refreshButton.setEnabled(true);
                            statusView.setText(plugins.isEmpty()
                                    ? "注册表为空。将插件包放入 ~/.termux-shadow/inbox 后刷新。"
                                    : "已注册 " + plugins.size() + " 个插件");
                            renderPlugins(plugins);
                        }
                    });
                } catch (final Throwable throwable) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || isDestroyed()) {
                                return;
                            }
                            refreshButton.setEnabled(true);
                            statusView.setText("Shadow 平台同步失败：" + rootMessage(throwable));
                            Toast.makeText(MainActivity.this, "Shadow 平台同步失败", Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });
    }

    private void renderPlugins(List<ShadowPluginDescriptor> plugins) {
        pluginList.removeAllViews();
        for (final ShadowPluginDescriptor descriptor : plugins) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(10), 0, dp(12));

            TextView name = new TextView(this);
            name.setText(descriptor.displayName + "  " + descriptor.versionName);
            name.setTextSize(17);
            name.setTextColor(Color.BLACK);
            row.addView(name);

            TextView details = new TextView(this);
            details.setText("pluginId = " + descriptor.pluginId
                    + "\ngeneration = " + descriptor.generation
                    + "\nstate = " + descriptor.state
                    + "  trust = " + descriptor.trustLevel
                    + "\nlaunches = " + descriptor.totalLaunchAttempts
                    + "  failures = " + descriptor.totalLaunchFailures
                    + "  consecutive = " + descriptor.consecutiveLaunchFailures
                    + (descriptor.candidate ? "  candidate" : "")
                    + (descriptor.enabled ? "" : "  disabled")
                    + (descriptor.lastError == null
                    ? ""
                    : "\nlastError = " + bounded(descriptor.lastError, 240)));
            details.setTextSize(13);
            details.setTextColor(Color.rgb(75, 75, 75));
            details.setPadding(0, dp(4), 0, dp(6));
            row.addView(details);

            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);

            Button run = actionButton(descriptor.enabled ? "运行" : "已停用");
            run.setEnabled(descriptor.enabled);
            run.setOnClickListener(view -> startPlugin(descriptor.pluginId, false));
            actions.addView(run);

            Button toggle = actionButton(descriptor.enabled ? "停用" : "启用");
            toggle.setOnClickListener(view -> runOperation(
                    descriptor.enabled ? "正在停用插件..." : "正在启用插件...",
                    new PlatformOperation() {
                        @Override
                        public void run() throws Exception {
                            if (descriptor.enabled) {
                                HostApplication.getApp().resetPluginRuntime("Plugin disabled: " + descriptor.pluginId);
                                PluginHelper.getInstance().disable(descriptor.pluginId);
                            } else {
                                PluginHelper.getInstance().enable(descriptor.pluginId);
                            }
                        }
                    }
            ));
            actions.addView(toggle);

            Button rollback = actionButton("回滚");
            rollback.setEnabled(descriptor.enabled && descriptor.rollbackAvailable);
            rollback.setOnClickListener(view -> startPlugin(descriptor.pluginId, true));
            actions.addView(rollback);

            Button remove = actionButton("删除");
            remove.setOnClickListener(view -> confirmRemove(descriptor));
            actions.addView(remove);

            row.addView(actions);
            pluginList.addView(row);

            View divider = new View(this);
            divider.setBackgroundColor(Color.rgb(220, 220, 220));
            pluginList.addView(divider, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(1)
            ));
        }
    }

    private Button actionButton(String text) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1f);
        button.setLayoutParams(params);
        return button;
    }

    private void startPlugin(String pluginId, boolean rollback) {
        Intent intent = new Intent(this, PluginLoadActivity.class);
        intent.putExtra(Constant.KEY_PLUGIN_ID, pluginId);
        intent.putExtra(Constant.KEY_ROLLBACK, rollback);
        startActivity(intent);
    }

    private void confirmRemove(final ShadowPluginDescriptor descriptor) {
        new AlertDialog.Builder(this)
                .setTitle("删除插件")
                .setMessage("将删除 " + descriptor.displayName + " 的所有受管版本。审计日志会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> runOperation(
                        "正在删除插件...",
                        new PlatformOperation() {
                            @Override
                            public void run() throws Exception {
                                HostApplication.getApp().resetPluginRuntime("Plugin removed: " + descriptor.pluginId);
                                PluginHelper.getInstance().remove(descriptor.pluginId);
                            }
                        }
                ))
                .show();
    }

    private void runOperation(final String pendingMessage, final PlatformOperation operation) {
        statusView.setText(pendingMessage);
        refreshButton.setEnabled(false);
        PluginHelper.getInstance().executor().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    operation.run();
                    runOnUiThread(() -> refreshPluginState());
                } catch (final Throwable throwable) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            refreshButton.setEnabled(true);
                            statusView.setText("操作失败：" + rootMessage(throwable));
                        }
                    });
                }
            }
        });
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.length() == 0
                ? current.getClass().getSimpleName()
                : message;
    }

    private static String bounded(String value, int maxLength) {
        return value.length() <= maxLength
                ? value
                : value.substring(0, maxLength) + "...[truncated]";
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private interface PlatformOperation {
        void run() throws Exception;
    }
}
