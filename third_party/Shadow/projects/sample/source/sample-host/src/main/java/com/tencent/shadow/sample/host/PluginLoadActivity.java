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
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.tencent.shadow.dynamic.host.EnterCallback;
import com.tencent.shadow.sample.constant.Constant;

import java.io.File;


public class PluginLoadActivity extends Activity {

    private ViewGroup mViewGroup;

    private Handler mHandler = new Handler();


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_load);

        mViewGroup = findViewById(R.id.container);
        TextView statusView = findViewById(R.id.load_status);
        statusView.setText(
                "正在进入 Shadow 插件链路\n\n"
                        + "pluginZip = " + pluginZipPath() + "\n"
                        + "managerApk = " + PluginHelper.getInstance().pluginManagerFile.getAbsolutePath() + "\n"
                        + "partKey = " + getIntent().getStringExtra(Constant.KEY_PLUGIN_PART_KEY) + "\n"
                        + "activity = " + getIntent().getStringExtra(Constant.KEY_ACTIVITY_CLASSNAME)
        );

        startPlugin();
    }


    public void startPlugin() {

        PluginHelper.getInstance().singlePool.execute(new Runnable() {
            @Override
            public void run() {
                HostApplication.getApp().loadPluginManager(PluginHelper.getInstance().pluginManagerFile);
                if (PluginHelper.getInstance().pluginZipFile == null
                        || !PluginHelper.getInstance().pluginZipFile.isFile()) {
                    throw new IllegalStateException("Shadow plugin zip is not available");
                }

                Bundle bundle = new Bundle();
                bundle.putString(Constant.KEY_PLUGIN_ZIP_PATH, PluginHelper.getInstance().pluginZipFile.getAbsolutePath());
                bundle.putString(Constant.KEY_PLUGIN_PART_KEY, getIntent().getStringExtra(Constant.KEY_PLUGIN_PART_KEY));
                bundle.putString(Constant.KEY_ACTIVITY_CLASSNAME, getIntent().getStringExtra(Constant.KEY_ACTIVITY_CLASSNAME));

                HostApplication.getApp().getPluginManager()
                        .enter(PluginLoadActivity.this, Constant.FROM_ID_START_ACTIVITY, bundle, new EnterCallback() {
                            @Override
                            public void onShowLoadingView(final View view) {
                                mHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        mViewGroup.addView(view);
                                    }
                                });
                            }

                            @Override
                            public void onCloseLoadingView() {
                                finish();
                            }

                            @Override
                            public void onEnterComplete() {

                            }
                        });
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        HostApplication.getApp().getPluginManager().enter(this, Constant.FROM_ID_CLOSE, null, null);
        mViewGroup.removeAllViews();
    }

    private static String pluginZipPath() {
        File pluginZipFile = PluginHelper.getInstance().pluginZipFile;
        return pluginZipFile == null || !pluginZipFile.isFile()
                ? "none"
                : pluginZipFile.getAbsolutePath();
    }
}
