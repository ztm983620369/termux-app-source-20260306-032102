/*
 * Tencent is pleased to support the open source community by making Tencent Shadow available.
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * Licensed under the BSD 3-Clause License (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.tencent.shadow.sample.host;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;


public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(R.style.TestHostTheme);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout rootView = new LinearLayout(this);
        rootView.setOrientation(LinearLayout.VERTICAL);
        rootView.setPadding(dp(18), dp(18), dp(18), dp(18));
        scrollView.addView(rootView);

        TextView titleView = new TextView(this);
        titleView.setText("Shadow 原生小程序宿主");
        titleView.setTextSize(24);
        titleView.setTextColor(Color.BLACK);
        rootView.addView(titleView);

        TextView flowView = new TextView(this);
        flowView.setText(
                "当前宿主保持干净状态，没有内置示例插件。\n\n"
                        + "宿主只保留 Shadow manager 加载能力。后续插件包由外部构建产物提供，"
                        + "没有插件包时不会展示示例插件入口。");
        flowView.setTextSize(15);
        flowView.setTextColor(Color.rgb(55, 55, 55));
        flowView.setPadding(0, dp(10), 0, dp(10));
        rootView.addView(flowView);

        setContentView(scrollView);

    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

}
