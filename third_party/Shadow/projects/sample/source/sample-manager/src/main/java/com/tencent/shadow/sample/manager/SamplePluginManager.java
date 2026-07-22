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

package com.tencent.shadow.sample.manager;

import static com.tencent.shadow.sample.constant.Constant.PART_KEY_PLUGIN_ANOTHER_APP;
import static com.tencent.shadow.sample.constant.Constant.PART_KEY_PLUGIN_BASE;
import static com.tencent.shadow.sample.constant.Constant.PART_KEY_PLUGIN_MAIN_APP;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import com.tencent.shadow.core.manager.installplugin.InstalledPlugin;
import com.tencent.shadow.dynamic.host.EnterCallback;
import com.tencent.shadow.sample.constant.Constant;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;


public class SamplePluginManager extends FastPluginManager {

    private static final String TAG = "SamplePluginManager";

    private ExecutorService executorService = Executors.newSingleThreadExecutor();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Context mCurrentContext;

    public SamplePluginManager(Context context) {
        super(context);
        mCurrentContext = context;
    }

    /**
     * @return PluginManager实现的别名，用于区分不同PluginManager实现的数据存储路径
     */
    @Override
    protected String getName() {
        return "test-dynamic-manager";
    }

    @Override
    protected File getPluginStorageRoot() {
        return engineDirectory("cache");
    }

    @Override
    protected File getInstalledPluginDatabaseFile() {
        return new File(engineDirectory("state"), "installed-plugins.db");
    }

    /**
     * @return 宿主中注册的PluginProcessService实现的类名
     */
    @Override
    protected String getPluginProcessServiceName(String partKey) {
        if (PART_KEY_PLUGIN_MAIN_APP.equals(partKey)) {
            return "com.tencent.shadow.sample.host.PluginProcessPPS";
        } else if (PART_KEY_PLUGIN_BASE.equals(partKey)) {
            return "com.tencent.shadow.sample.host.PluginProcessPPS";
        } else if (PART_KEY_PLUGIN_ANOTHER_APP.equals(partKey)) {
            return "com.tencent.shadow.sample.host.Plugin2ProcessPPS";//在这里支持多个插件
        } else {
            return "com.tencent.shadow.sample.host.PluginProcessPPS";
        }
    }

    @Override
    public void enter(final Context context, long fromId, Bundle bundle, final EnterCallback callback) {
        if (fromId == Constant.FROM_ID_NOOP) {
            //do nothing.
        } else if (fromId == Constant.FROM_ID_START_ACTIVITY) {
            onStartActivity(context, bundle, callback);
        } else if (fromId == Constant.FROM_ID_CLOSE) {
            close();
        } else if (fromId == Constant.FROM_ID_LOAD_VIEW_TO_HOST) {
            loadViewToHost(context, bundle);
        } else {
            throw new IllegalArgumentException("不认识的fromId==" + fromId);
        }
    }

    private void loadViewToHost(final Context context, Bundle bundle) {
        Intent pluginIntent = new Intent();
        pluginIntent.setClassName(
                context.getPackageName(),
                "com.tencent.shadow.sample.plugin.app.lib.usecases.service.HostAddPluginViewService"
        );
        pluginIntent.putExtras(bundle);
        try {
            mPluginLoader.startPluginService(pluginIntent);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    private void onStartActivity(final Context context, Bundle bundle, final EnterCallback callback) {
        final String pluginZipPath = bundle.getString(Constant.KEY_PLUGIN_ZIP_PATH);
        String requestedPartKey = bundle.getString(Constant.KEY_PLUGIN_PART_KEY);
        if (requestedPartKey == null) {
            requestedPartKey = PART_KEY_PLUGIN_MAIN_APP;
        }
        final String partKey = requestedPartKey;
        final String className = bundle.getString(Constant.KEY_ACTIVITY_CLASSNAME);
        if (className == null) {
            throw new NullPointerException("className == null");
        }
        final Bundle extras = bundle.getBundle(Constant.KEY_EXTRAS);
        final ResultReceiver resultReceiver = bundle.getParcelable(Constant.KEY_RESULT_RECEIVER);
        final String pluginId = bundle.getString(Constant.KEY_PLUGIN_ID);
        final String generation = bundle.getString(Constant.KEY_PLUGIN_GENERATION);
        final String operationId = bundle.getString(Constant.KEY_OPERATION_ID);
        final long stabilityWindowMs = bundle.getLong(
                Constant.KEY_HEALTH_STABILITY_WINDOW_MS,
                1_500L
        );

        if (callback != null) {
            final View view = LayoutInflater.from(mCurrentContext).inflate(R.layout.activity_load_plugin, null);
            callback.onShowLoadingView(view);
        }

        executorService.execute(new Runnable() {
            @Override
            public void run() {
                RuntimeHealthBridge healthBridge = null;
                try {
                    InstalledPlugin installedPlugin = installPlugin(pluginZipPath, null, true);
                    pruneInstalledPluginCache(installedPlugin.UUID);

                    boolean needsSampleBase = PART_KEY_PLUGIN_BASE.equals(partKey)
                            || PART_KEY_PLUGIN_MAIN_APP.equals(partKey)
                            || PART_KEY_PLUGIN_ANOTHER_APP.equals(partKey);
                    if (needsSampleBase) {
                        loadPlugin(installedPlugin.UUID, PART_KEY_PLUGIN_BASE);
                    }
                    if (!PART_KEY_PLUGIN_BASE.equals(partKey)) {
                        loadPlugin(installedPlugin.UUID, partKey);
                    }
                    if (needsSampleBase) {
                        callApplicationOnCreate(PART_KEY_PLUGIN_BASE);
                    }
                    if (!PART_KEY_PLUGIN_BASE.equals(partKey)) {
                        callApplicationOnCreate(partKey);
                    }

                    if (resultReceiver != null) {
                        healthBridge = new RuntimeHealthBridge(
                                resultReceiver,
                                mPpsController.getPluginLoader(),
                                pluginId,
                                generation,
                                operationId
                        );
                        Log.i(TAG, "Runtime health bridge armed: " + pluginId + "/" + generation
                                + " operationId=" + operationId);
                    }

                    Intent pluginIntent = new Intent();
                    pluginIntent.setClassName(
                            context.getPackageName(),
                            className
                    );
                    if (extras != null) {
                        pluginIntent.replaceExtras(extras);
                    }
                    if (healthBridge != null) {
                        pluginIntent.putExtra(
                                Constant.KEY_RESULT_RECEIVER,
                                healthBridge.pluginReceiver()
                        );
                        pluginIntent.putExtra(Constant.KEY_PLUGIN_ID, pluginId);
                        pluginIntent.putExtra(Constant.KEY_PLUGIN_GENERATION, generation);
                        pluginIntent.putExtra(Constant.KEY_OPERATION_ID, operationId);
                        pluginIntent.putExtra(
                                Constant.KEY_HEALTH_PROTOCOL_VERSION,
                                Constant.HEALTH_PROTOCOL_VERSION
                        );
                        pluginIntent.putExtra(
                                Constant.KEY_HEALTH_STABILITY_WINDOW_MS,
                                stabilityWindowMs
                        );
                    }
                    Intent intent = mPluginLoader.convertActivityIntent(pluginIntent);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    mPluginLoader.startActivityInPluginProcess(intent);
                } catch (final Exception e) {
                    if (healthBridge != null) {
                        healthBridge.close();
                    }
                    Log.e(TAG, "Failed to start Shadow plugin. partKey=" + partKey
                            + " activity=" + className, e);
                    if (resultReceiver != null) {
                        Bundle error = new Bundle();
                        error.putString(Constant.KEY_ERROR_TYPE, e.getClass().getName());
                        error.putString(Constant.KEY_ERROR_MESSAGE, rootMessage(e));
                        error.putString(Constant.KEY_ERROR_STACK_TRACE, Log.getStackTraceString(e));
                        resultReceiver.send(Constant.RESULT_CODE_START_ERROR, error);
                    }
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(
                                    context.getApplicationContext(),
                                    "Shadow 插件启动失败：" + rootMessage(e),
                                    Toast.LENGTH_LONG
                            ).show();
                            if (callback != null) {
                                callback.onCloseLoadingView();
                            }
                        }
                    });
                    return;
                }
                if (callback != null) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onEnterComplete();
                            callback.onCloseLoadingView();
                        }
                    });
                }
            }
        });
    }

    /**
     * Bridges runtime proof back to the Host while the plugin-process Binder is alive.
     * The death recipient remains armed between FIRST_FRAME_READY and RUNTIME_STABLE,
     * closing the race that previously promoted an Activity which crashed in onCreate.
     */
    private final class RuntimeHealthBridge implements IBinder.DeathRecipient {
        private final ResultReceiver hostReceiver;
        private final IBinder pluginProcessBinder;
        private final String pluginId;
        private final String generation;
        private final String operationId;
        private final AtomicBoolean terminal = new AtomicBoolean(false);
        private final ResultReceiver pluginReceiver = new ResultReceiver(mainHandler) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                if (resultCode == Constant.RESULT_CODE_RUNTIME_READY) {
                    if (!terminal.get()) {
                        Log.i(TAG, "Forward runtime FIRST_FRAME_READY: " + pluginId
                                + "/" + generation);
                        hostReceiver.send(resultCode, resultData);
                    }
                    return;
                }
                if (!terminal.compareAndSet(false, true)) {
                    return;
                }
                unlink();
                Log.i(TAG, "Forward runtime terminal result=" + resultCode + ": "
                        + pluginId + "/" + generation);
                hostReceiver.send(resultCode, resultData);
            }
        };
        private final ResultReceiver transportReceiver;

        RuntimeHealthBridge(
                ResultReceiver hostReceiver,
                IBinder pluginProcessBinder,
                String pluginId,
                String generation,
                String operationId
        ) throws RemoteException {
            this.hostReceiver = hostReceiver;
            this.pluginProcessBinder = pluginProcessBinder;
            this.pluginId = pluginId;
            this.generation = generation;
            this.operationId = operationId;
            transportReceiver = frameworkTransport(pluginReceiver);
            pluginProcessBinder.linkToDeath(this, 0);
        }

        ResultReceiver pluginReceiver() {
            return transportReceiver;
        }

        @Override
        public void binderDied() {
            if (!terminal.compareAndSet(false, true)) {
                return;
            }
            Bundle failure = new Bundle();
            failure.putString(Constant.KEY_PLUGIN_ID, pluginId);
            failure.putString(Constant.KEY_PLUGIN_GENERATION, generation);
            failure.putString(Constant.KEY_OPERATION_ID, operationId);
            failure.putString(Constant.KEY_ERROR_TYPE, "PLUGIN_PROCESS_DIED");
            failure.putString(
                    Constant.KEY_ERROR_MESSAGE,
                    "Plugin process died before the runtime stability window completed"
            );
            hostReceiver.send(Constant.RESULT_CODE_PLUGIN_PROCESS_DIED, failure);
        }

        void close() {
            if (terminal.compareAndSet(false, true)) {
                unlink();
            }
        }

        private void unlink() {
            try {
                pluginProcessBinder.unlinkToDeath(this, 0);
            } catch (Throwable ignored) {
                // Binder may already be dead; terminal state is still authoritative.
            }
        }
    }

    /**
     * Re-parcels a callback as the framework ResultReceiver type before it crosses into the
     * isolated Loader class path. Passing the anonymous manager subclass directly makes Android
     * try to load SamplePluginManager$RuntimeHealthBridge$1 in the plugin process.
     */
    private static ResultReceiver frameworkTransport(ResultReceiver receiver) {
        Parcel parcel = Parcel.obtain();
        try {
            receiver.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return ResultReceiver.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.length() == 0) {
            message = current.getClass().getSimpleName();
        }
        return message;
    }

    private File engineDirectory(String child) {
        File directory = new File(
                mHostContext.getFilesDir(),
                "home/.termux-shadow/engine/" + child
        );
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("Failed to create Shadow engine directory: " + directory);
        }
        return directory;
    }

    private void pruneInstalledPluginCache(String activeUuid) {
        List<InstalledPlugin> installed = getInstalledPlugins(256);
        int retained = 0;
        for (InstalledPlugin plugin : installed) {
            if (activeUuid.equals(plugin.UUID) || retained < 3) {
                retained++;
                continue;
            }
            if (!deleteInstalledPlugin(plugin.UUID)) {
                Log.w(TAG, "Failed to prune Shadow engine cache for uuid=" + plugin.UUID);
            }
        }
    }
}
