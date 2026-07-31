package com.dynamic;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.dynamic.std.StdSuite;

import java.util.ArrayList;
import java.util.List;

public class RealAppScript {

    public static final int PANEL_TEXT_INPUT = 1;
    public static final int PANEL_APP_SELECTOR = 2;

    private static class CreateOption {
        String id;
        String title;
        int requiredPanel;
        String inputHint;

        CreateOption(String id, String title, int requiredPanel, String inputHint) {
            this.id = id;
            this.title = title;
            this.requiredPanel = requiredPanel;
            this.inputHint = inputHint;
        }
    }

    private CreateOption currentSelectedOption;
    private int selectedAppIndex = 0;
    
    private final List<Runnable> typeSelectionUpdaters = new ArrayList<>();
    private final List<Runnable> appSelectionUpdaters = new ArrayList<>();

    private final String[] mockApps = {"ecj", "QQ", "微信", "淘宝", "系统设置", "终端模拟器"};

    public void launch(Activity activity) {
        StdSuite.attach(activity);

        List<CreateOption> registryOptions = new ArrayList<>();
        registryOptions.add(new CreateOption("opt_folder", "文件夹", PANEL_TEXT_INPUT, "请输入文件夹名称..."));
        registryOptions.add(new CreateOption("opt_file", "空文件", PANEL_TEXT_INPUT, "请输入文件名称..."));
        registryOptions.add(new CreateOption("opt_app", "运行应用 (App)", PANEL_APP_SELECTOR, ""));
        registryOptions.add(new CreateOption("opt_shortcut", "桌面快捷方式", PANEL_TEXT_INPUT, "请输入快捷指令名称..."));
        registryOptions.add(new CreateOption("opt_url", "远程 Web 链接", PANEL_TEXT_INPUT, "请输入 URL 地址 (http://)..."));
        registryOptions.add(new CreateOption("opt_script", "自动化 Shell 脚本", PANEL_TEXT_INPUT, "请输入 .sh 脚本名称..."));
        registryOptions.add(new CreateOption("opt_cloud", "挂载 WebDAV 云盘", PANEL_TEXT_INPUT, "请输入远程挂载点名称..."));

        currentSelectedOption = registryOptions.get(0);

        // --- 根遮罩 ---
        FrameLayout rootOverlay = new FrameLayout(activity);
        rootOverlay.setBackgroundColor(Color.parseColor("#B3000000"));

        // --- 工业级对话框底板 ---
        LinearLayout dialogCard = new LinearLayout(activity);
        dialogCard.setOrientation(LinearLayout.VERTICAL);
        dialogCard.setElevation(dp(activity, 24));
        dialogCard.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        
        // 【注意这里】：把底部的 Padding 彻底设为 0，让内部视图可以 100% 贴到卡片的下边缘！
        dialogCard.setPadding(0, dp(activity, 24), 0, 0);
        
        dialogCard.setFocusable(true);
        dialogCard.setFocusableInTouchMode(true);
        dialogCard.requestFocus();

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.parseColor("#222223"));
        cardBg.setCornerRadius(dp(activity, 18));
        cardBg.setStroke(dp(activity, 1), Color.parseColor("#38383A"));
        dialogCard.setBackground(cardBg);

        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                dp(activity, 340), FrameLayout.LayoutParams.WRAP_CONTENT
        );
        cardParams.gravity = Gravity.CENTER;
        dialogCard.setLayoutParams(cardParams);

        // --- 标题 ---
        TextView title = new TextView(activity);
        title.setText("新建");
        title.setTextColor(Color.parseColor("#F0F0F0"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(activity, 24), 0, dp(activity, 24), 0);
        dialogCard.addView(title);

        // ==========================================
        // 动态插槽面板 (文本/App 切换区)
        // ==========================================
        FrameLayout dynamicSlot = new FrameLayout(activity);
        LinearLayout.LayoutParams slotParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 60) 
        );
        slotParams.topMargin = dp(activity, 12);
        dialogCard.addView(dynamicSlot, slotParams);

        EditText inputPanel = createInputField(activity);
        FrameLayout.LayoutParams inputParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        );
        inputParams.leftMargin = dp(activity, 24);  
        inputParams.rightMargin = dp(activity, 24); 
        dynamicSlot.addView(inputPanel, inputParams);
        inputPanel.setHint(currentSelectedOption.inputHint);

        HorizontalScrollView appPanel = new HorizontalScrollView(activity);
        appPanel.setHorizontalScrollBarEnabled(false);
        appPanel.setOverScrollMode(View.OVER_SCROLL_NEVER);
        appPanel.setClipToPadding(false); 
        appPanel.setPadding(dp(activity, 24), dp(activity, 4), dp(activity, 24), dp(activity, 8)); 
        appPanel.setVisibility(View.INVISIBLE);
        appPanel.setAlpha(0f);

        LinearLayout appListContainer = new LinearLayout(activity);
        appListContainer.setOrientation(LinearLayout.HORIZONTAL);
        appListContainer.setGravity(Gravity.CENTER_VERTICAL);
        appPanel.addView(appListContainer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.MATCH_PARENT
        ));
        dynamicSlot.addView(appPanel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ));

        for (int i = 0; i < mockApps.length; i++) {
            boolean isLast = (i == mockApps.length - 1);
            appListContainer.addView(createAppCapsule(activity, mockApps[i], i, isLast));
        }

        // ====================================================================
        // 【核心大重构：悬浮底框引擎】
        // 使用 FrameLayout 将列表与按钮重叠，实现“滑入底部虚无”的终极视觉
        // ====================================================================
        FrameLayout scrollAndActionFrame = new FrameLayout(activity);
        LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        frameParams.topMargin = dp(activity, 8);
        dialogCard.addView(scrollAndActionFrame, frameParams);

        ScrollView optionsScroller = new ScrollView(activity) {
            private final int MAX_HEIGHT = dp(activity, 260); // 最大高度池
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int customHeightSpec = MeasureSpec.makeMeasureSpec(MAX_HEIGHT, MeasureSpec.AT_MOST);
                super.onMeasure(widthMeasureSpec, customHeightSpec);
            }
        };
        optionsScroller.setVerticalScrollBarEnabled(false);
        optionsScroller.setOverScrollMode(View.OVER_SCROLL_NEVER); 
        optionsScroller.setVerticalFadingEdgeEnabled(false);
        
        // 【魔法所在】：开启穿透 Padding。
        // 给底部 70dp 的 Padding，这个高度恰好约等于底部悬浮按钮区的高度。
        // 这样既允许文字滑到绝对的底边0坐标，又保证最后一项到底时，不会永远被按钮遮住。
        optionsScroller.setClipToPadding(false);
        optionsScroller.setPadding(0, 0, 0, dp(activity, 70)); 

        scrollAndActionFrame.addView(optionsScroller, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout optionsContainer = new LinearLayout(activity);
        optionsContainer.setOrientation(LinearLayout.VERTICAL);
        optionsContainer.setPadding(dp(activity, 24), 0, dp(activity, 24), 0); 
        optionsScroller.addView(optionsContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        for (CreateOption option : registryOptions) {
            View row = createExtensibleRadioRow(activity, option, inputPanel, appPanel, dialogCard);
            optionsContainer.addView(row);
        }

        refreshTypeUI();
        refreshAppUI();

        // ==========================================
        // 悬浮在内容之上的底部操作器
        // ==========================================
        LinearLayout actionContainer = new LinearLayout(activity);
        actionContainer.setOrientation(LinearLayout.HORIZONTAL);
        actionContainer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        
        // 内边距调整：因为弹窗底板不再提供 Padding，由它自己撑起离底部的安全距离
        actionContainer.setPadding(dp(activity, 24), dp(activity, 16), dp(activity, 24), dp(activity, 16)); 
        
        // 【神来之笔】：给操作区加一个从透明到卡片底色的平滑渐变背景！
        // 当列表从它背后滑过时，会产生完美的融入黑暗的褪色效果，且文字不会打架。
        GradientDrawable actionGradient = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00222223, 0xD9222223, 0xFF222223} // #222223的透明度渐变 (0% -> 85% -> 100%)
        );
        actionContainer.setBackground(actionGradient);

        FrameLayout.LayoutParams actionParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        );
        actionParams.gravity = Gravity.BOTTOM; // 物理死锁在 FrameLayout 的最底边
        scrollAndActionFrame.addView(actionContainer, actionParams);

        TextView btnCancel = createActionButton(activity, "取消", "#8E8E93");
        btnCancel.setOnClickListener(v -> Toast.makeText(activity, "已取消", Toast.LENGTH_SHORT).show());
        actionContainer.addView(btnCancel);

        View actionSpacer = new View(activity);
        actionContainer.addView(actionSpacer, new LinearLayout.LayoutParams(dp(activity, 4), 1));

        TextView btnConfirm = createActionButton(activity, "确定", "#4B8DF8");
        btnConfirm.setTypeface(Typeface.DEFAULT_BOLD);
        btnConfirm.setOnClickListener(v -> {
            if (currentSelectedOption.requiredPanel == PANEL_TEXT_INPUT) {
                String text = inputPanel.getText().toString().trim();
                if (text.isEmpty()) {
                    Toast.makeText(activity, "内容不能为空！", Toast.LENGTH_SHORT).show();
                    return;
                }
                Toast.makeText(activity, "成功创建 [" + currentSelectedOption.title + "]: " + text, Toast.LENGTH_LONG).show();
            } else if (currentSelectedOption.requiredPanel == PANEL_APP_SELECTOR) {
                String selectedAppName = mockApps[selectedAppIndex];
                Toast.makeText(activity, "【配置完成】目标App绑定: " + selectedAppName, Toast.LENGTH_LONG).show();
            }
        });
        actionContainer.addView(btnConfirm);

        rootOverlay.addView(dialogCard);
        activity.setContentView(rootOverlay);
    }

    // =========================================================================
    // 引擎：状态更新与面板调度
    // =========================================================================
    private View createExtensibleRadioRow(Activity activity, CreateOption optionModel, EditText inputPanel, View appPanel, View rootContainer) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 48) 
        ));
        
        FrameLayout radioIconFrame = new FrameLayout(activity);
        View outerRing = new View(activity);
        GradientDrawable ringBg = new GradientDrawable();
        ringBg.setShape(GradientDrawable.OVAL);
        outerRing.setBackground(ringBg);
        
        View innerDot = new View(activity);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(Color.parseColor("#4B8DF8"));
        innerDot.setBackground(dotBg);

        int iconSize = dp(activity, 20);
        int dotSize = dp(activity, 10);
        radioIconFrame.addView(outerRing, new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER));
        radioIconFrame.addView(innerDot, new FrameLayout.LayoutParams(dotSize, dotSize, Gravity.CENTER));
        row.addView(radioIconFrame, new LinearLayout.LayoutParams(iconSize, iconSize));

        TextView label = new TextView(activity);
        label.setText(optionModel.title);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        labelParams.leftMargin = dp(activity, 12);
        row.addView(label, labelParams);

        typeSelectionUpdaters.add(() -> {
            boolean isSelected = (currentSelectedOption.id.equals(optionModel.id));
            
            ringBg.setStroke(dp(activity, isSelected ? 2 : 1), isSelected ? Color.parseColor("#4B8DF8") : Color.parseColor("#5A5A5C"));
            label.setTextColor(isSelected ? Color.parseColor("#4B8DF8") : Color.parseColor("#8E8E93"));
            label.setTypeface(isSelected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            
            if (isSelected) {
                innerDot.animate().scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(new OvershootInterpolator(1.5f)).start();
                
                if (optionModel.requiredPanel == PANEL_APP_SELECTOR) {
                    hideKeyboard(activity, inputPanel);
                    inputPanel.clearFocus();
                    rootContainer.requestFocus(); 

                    if (inputPanel.getVisibility() == View.VISIBLE) {
                        inputPanel.animate().alpha(0f).setDuration(150).withEndAction(() -> inputPanel.setVisibility(View.INVISIBLE)).start();
                    }
                    appPanel.setVisibility(View.VISIBLE);
                    appPanel.animate().alpha(1f).setDuration(250).start();
                } else if (optionModel.requiredPanel == PANEL_TEXT_INPUT) {
                    if (appPanel.getVisibility() == View.VISIBLE) {
                        appPanel.animate().alpha(0f).setDuration(150).withEndAction(() -> appPanel.setVisibility(View.INVISIBLE)).start();
                    }
                    inputPanel.setVisibility(View.VISIBLE);
                    inputPanel.animate().alpha(1f).setDuration(250).start();
                    inputPanel.setHint(optionModel.inputHint);
                }
            } else {
                innerDot.animate().scaleX(0f).scaleY(0f).setDuration(150).setInterpolator(new DecelerateInterpolator()).start();
            }
        });

        row.setOnClickListener(v -> {
            if (!currentSelectedOption.id.equals(optionModel.id)) {
                currentSelectedOption = optionModel;
                refreshTypeUI();
            }
        });
        return row;
    }

    private View createAppCapsule(Activity activity, String appName, int index, boolean isLast) {
        LinearLayout capsule = new LinearLayout(activity);
        capsule.setOrientation(LinearLayout.HORIZONTAL);
        capsule.setGravity(Gravity.CENTER_VERTICAL);
        capsule.setPadding(dp(activity, 16), dp(activity, 0), dp(activity, 12), dp(activity, 0));

        LinearLayout.LayoutParams capParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(activity, 38) 
        );
        capParams.rightMargin = isLast ? 0 : dp(activity, 10); 
        capsule.setLayoutParams(capParams);

        GradientDrawable capsuleBg = new GradientDrawable();
        capsuleBg.setCornerRadius(dp(activity, 19));
        capsule.setBackground(capsuleBg);
        capsule.setClickable(true);

        TextView nameText = new TextView(activity);
        nameText.setText(appName);
        nameText.setTextSize(14f);
        nameText.setTypeface(Typeface.DEFAULT_BOLD);
        nameText.setIncludeFontPadding(false);
        capsule.addView(nameText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        View spacerLeft = new View(activity);
        capsule.addView(spacerLeft, new LinearLayout.LayoutParams(dp(activity, 14), 1));

        View divider = new View(activity);
        capsule.addView(divider, new LinearLayout.LayoutParams(dp(activity, 1), dp(activity, 14)));

        View spacerRight = new View(activity);
        capsule.addView(spacerRight, new LinearLayout.LayoutParams(dp(activity, 14), 1));

        ImageView appIcon = new ImageView(activity);
        appIcon.setImageResource(android.R.drawable.sym_def_app_icon);
        appIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        appIcon.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(activity, 4));
            }
        });
        appIcon.setClipToOutline(true);
        capsule.addView(appIcon, new LinearLayout.LayoutParams(dp(activity, 20), dp(activity, 20)));

        appSelectionUpdaters.add(() -> {
            boolean isSelected = (selectedAppIndex == index);
            if (isSelected) {
                capsuleBg.setColor(Color.parseColor("#15243B")); 
                capsuleBg.setStroke(dp(activity, 1), Color.parseColor("#4B8DF8")); 
                nameText.setTextColor(Color.parseColor("#5A9CF9")); 
                divider.setBackgroundColor(Color.parseColor("#2A4B80")); 
            } else {
                capsuleBg.setColor(Color.parseColor("#1C1C1E")); 
                capsuleBg.setStroke(dp(activity, 1), Color.parseColor("#323234")); 
                nameText.setTextColor(Color.parseColor("#D0D0D0"));
                divider.setBackgroundColor(Color.parseColor("#3A3A3C"));
            }
        });

        capsule.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(120).setInterpolator(new DecelerateInterpolator()).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(new OvershootInterpolator(1.2f)).start();
                    break;
            }
            return false;
        });

        capsule.setOnClickListener(v -> {
            if (selectedAppIndex != index) {
                selectedAppIndex = index;
                refreshAppUI();
            }
        });

        return capsule;
    }

    private EditText createInputField(Activity activity) {
        EditText inputField = new EditText(activity);
        inputField.setHintTextColor(Color.parseColor("#555555"));
        inputField.setTextColor(Color.parseColor("#FFFFFF"));
        inputField.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        inputField.setSingleLine(true);
        inputField.setInputType(InputType.TYPE_CLASS_TEXT);
        inputField.setPadding(dp(activity, 16), dp(activity, 14), dp(activity, 16), dp(activity, 14));
        
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(Color.parseColor("#121212")); 
        inputBg.setCornerRadius(dp(activity, 10));
        inputBg.setStroke(dp(activity, 1), Color.parseColor("#2C2C2E"));
        inputField.setBackground(inputBg);

        inputField.setOnFocusChangeListener((v, hasFocus) -> {
            inputBg.setStroke(dp(activity, hasFocus ? 2 : 1), hasFocus ? Color.parseColor("#4B8DF8") : Color.parseColor("#2C2C2E"));
        });
        return inputField;
    }

    private TextView createActionButton(Activity activity, String text, String colorHex) {
        TextView btn = new TextView(activity);
        btn.setText(text);
        btn.setTextColor(Color.parseColor(colorHex));
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(activity, 16), dp(activity, 10), dp(activity, 16), dp(activity, 10));
        
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(activity, 8));
        RippleDrawable ripple = new RippleDrawable(ColorStateList.valueOf(Color.parseColor("#1AFFFFFF")), null, mask);
        btn.setBackground(ripple);
        btn.setClickable(true);
        return btn;
    }

    private void hideKeyboard(Activity activity, View view) {
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private void refreshTypeUI() {
        for (Runnable updater : typeSelectionUpdaters) updater.run();
    }

    private void refreshAppUI() {
        for (Runnable updater : appSelectionUpdaters) updater.run();
    }

    private static int dp(Activity activity, int dp) {
        return (int) (dp * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}
