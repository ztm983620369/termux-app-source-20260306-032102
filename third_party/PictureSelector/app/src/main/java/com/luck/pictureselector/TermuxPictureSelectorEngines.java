package com.luck.pictureselector;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.TextUtils;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;
import com.luck.picture.lib.config.PictureMimeType;
import com.luck.picture.lib.engine.CompressFileEngine;
import com.luck.picture.lib.engine.CropFileEngine;
import com.luck.picture.lib.engine.ImageEngine;
import com.luck.picture.lib.engine.UriToFileTransformEngine;
import com.luck.picture.lib.engine.VideoPlayerEngine;
import com.luck.picture.lib.interfaces.OnCallbackListener;
import com.luck.picture.lib.interfaces.OnKeyValueResultCallbackListener;
import com.luck.picture.lib.style.PictureSelectorStyle;
import com.luck.picture.lib.style.SelectMainStyle;
import com.luck.picture.lib.style.TitleBarStyle;
import com.luck.picture.lib.utils.DateUtils;
import com.luck.picture.lib.utils.PictureFileUtils;
import com.luck.picture.lib.utils.SandboxTransformUtils;
import com.luck.picture.lib.utils.StyleUtils;
import com.yalantis.ucrop.UCrop;
import com.yalantis.ucrop.UCropImageEngine;

import java.io.File;
import java.util.ArrayList;

import top.zibin.luban.CompressionPredicate;
import top.zibin.luban.Luban;
import top.zibin.luban.OnNewCompressListener;
import top.zibin.luban.OnRenameListener;

final class TermuxPictureSelectorEngines {
    private TermuxPictureSelectorEngines() {
    }

    static ImageEngine createImageEngine(TermuxPictureSelectorSettings.Snapshot settings) {
        if (settings.imageEngine == TermuxPictureSelectorSettings.IMAGE_ENGINE_PICASSO) {
            return PicassoEngine.createPicassoEngine();
        }
        if (settings.imageEngine == TermuxPictureSelectorSettings.IMAGE_ENGINE_COIL) {
            return new CoilEngine();
        }
        return GlideEngine.createGlideEngine();
    }

    static VideoPlayerEngine<?> createVideoPlayerEngine(TermuxPictureSelectorSettings.Snapshot settings) {
        if (settings.videoPlayer == TermuxPictureSelectorSettings.VIDEO_PLAYER_EXO) {
            return new ExoPlayerEngine();
        }
        if (settings.videoPlayer == TermuxPictureSelectorSettings.VIDEO_PLAYER_IJK) {
            return new IjkPlayerEngine();
        }
        return null;
    }

    static UriToFileTransformEngine createSandboxFileEngine() {
        return new UriToFileTransformEngine() {
            @Override
            public void onUriToFileAsyncTransform(Context context, String srcPath, String mineType, OnKeyValueResultCallbackListener call) {
                if (call != null) {
                    call.onCallback(srcPath, SandboxTransformUtils.copyPathToSandbox(context, srcPath, mineType));
                }
            }
        };
    }

    static CompressFileEngine createCompressFileEngine() {
        return new CompressFileEngine() {
            @Override
            public void onStartCompress(Context context, ArrayList<Uri> source, OnKeyValueResultCallbackListener call) {
                Luban.with(context).load(source).ignoreBy(100).setRenameListener(new OnRenameListener() {
                    @Override
                    public String rename(String filePath) {
                        int indexOf = filePath.lastIndexOf(".");
                        String postfix = indexOf != -1 ? filePath.substring(indexOf) : ".jpg";
                        return DateUtils.getCreateFileName("CMP_") + postfix;
                    }
                }).filter(new CompressionPredicate() {
                    @Override
                    public boolean apply(String path) {
                        if (PictureMimeType.isUrlHasImage(path) && !PictureMimeType.isHasHttp(path)) {
                            return true;
                        }
                        return !PictureMimeType.isUrlHasGif(path);
                    }
                }).setCompressListener(new OnNewCompressListener() {
                    @Override
                    public void onStart() {
                    }

                    @Override
                    public void onSuccess(String source, File compressFile) {
                        if (call != null) {
                            call.onCallback(source, compressFile.getAbsolutePath());
                        }
                    }

                    @Override
                    public void onError(String source, Throwable e) {
                        if (call != null) {
                            call.onCallback(source, null);
                        }
                    }
                }).launch();
            }
        };
    }

    static CropFileEngine createCropFileEngine(Context hostContext,
                                               TermuxPictureSelectorSettings.Snapshot settings,
                                               PictureSelectorStyle selectorStyle) {
        return new CropEngineImpl(hostContext.getApplicationContext(), settings, selectorStyle);
    }

    static String sandboxPath(Context context) {
        File baseDir = context.getExternalFilesDir("");
        if (baseDir == null) {
            baseDir = context.getFilesDir();
        }
        File sandboxDir = new File(baseDir, "Sandbox");
        if (!sandboxDir.exists()) {
            sandboxDir.mkdirs();
        }
        return sandboxDir.getAbsolutePath() + File.separator;
    }

    static String cameraOutputPath(Context context) {
        File baseDir = context.getExternalFilesDir("");
        if (baseDir == null) {
            baseDir = context.getFilesDir();
        }
        File outputDir = new File(baseDir, "Camera");
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
        return outputDir.getAbsolutePath() + File.separator;
    }

    static String audioOutputPath(Context context) {
        File baseDir = context.getExternalFilesDir("");
        if (baseDir == null) {
            baseDir = context.getFilesDir();
        }
        File outputDir = new File(baseDir, "Audio");
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }
        return outputDir.getAbsolutePath() + File.separator;
    }

    private static final class CropEngineImpl implements CropFileEngine {
        private final Context appContext;
        private final TermuxPictureSelectorSettings.Snapshot settings;
        private final PictureSelectorStyle selectorStyle;

        private CropEngineImpl(Context appContext,
                               TermuxPictureSelectorSettings.Snapshot settings,
                               PictureSelectorStyle selectorStyle) {
            this.appContext = appContext;
            this.settings = settings;
            this.selectorStyle = selectorStyle;
        }

        @Override
        public void onStartCrop(Fragment fragment, Uri srcUri, Uri destinationUri,
                                ArrayList<String> dataSource, int requestCode) {
            UCrop uCrop = UCrop.of(srcUri, destinationUri, dataSource);
            uCrop.withOptions(buildOptions());
            uCrop.setImageEngine(new UCropImageEngine() {
                @Override
                public void loadImage(Context context, String url, ImageView imageView) {
                    if (!ImageLoaderUtils.assertValidRequest(context)) {
                        return;
                    }
                    Glide.with(context).load(url).override(180, 180).into(imageView);
                }

                @Override
                public void loadImage(Context context, Uri url, int maxWidth, int maxHeight,
                                      OnCallbackListener<Bitmap> call) {
                    Glide.with(context).asBitmap().load(url).override(maxWidth, maxHeight)
                            .into(new CustomTarget<Bitmap>() {
                                @Override
                                public void onResourceReady(@NonNull Bitmap resource,
                                                            @Nullable Transition<? super Bitmap> transition) {
                                    if (call != null) {
                                        call.onCall(resource);
                                    }
                                }

                                @Override
                                public void onLoadCleared(@Nullable Drawable placeholder) {
                                    if (call != null) {
                                        call.onCall(null);
                                    }
                                }
                            });
                }
            });
            uCrop.start(fragment.requireActivity(), fragment, requestCode);
        }

        private UCrop.Options buildOptions() {
            UCrop.Options options = new UCrop.Options();
            options.setHideBottomControls(!settings.hideCropControls);
            options.setFreeStyleCropEnabled(settings.freeStyleCrop);
            options.setShowCropFrame(settings.showCropFrame);
            options.setShowCropGrid(settings.showCropGrid);
            options.setCircleDimmedLayer(settings.cropCircular);
            options.withAspectRatio(settings.cropAspectX, settings.cropAspectY);
            options.setCropOutputPathDir(sandboxPath(appContext));
            options.isCropDragSmoothToCenter(false);
            options.setSkipCropMimeType(settings.skipCropGif ? new String[]{PictureMimeType.ofGIF(), PictureMimeType.ofWEBP()} : null);
            options.isForbidCropGifWebp(settings.forbidCropGif);
            options.isForbidSkipMultipleCrop(true);
            options.setMaxScaleMultiplier(100);

            SelectMainStyle mainStyle = selectorStyle.getSelectMainStyle();
            int statusBarColor = mainStyle.getStatusBarColor();
            if (StyleUtils.checkStyleValidity(statusBarColor)) {
                options.isDarkStatusBarBlack(mainStyle.isDarkStatusBarBlack());
                options.setStatusBarColor(statusBarColor);
                options.setToolbarColor(statusBarColor);
            } else {
                options.setStatusBarColor(ContextCompat.getColor(appContext, R.color.ps_color_grey));
                options.setToolbarColor(ContextCompat.getColor(appContext, R.color.ps_color_grey));
            }

            TitleBarStyle titleBarStyle = selectorStyle.getTitleBarStyle();
            if (StyleUtils.checkStyleValidity(titleBarStyle.getTitleTextColor())) {
                options.setToolbarWidgetColor(titleBarStyle.getTitleTextColor());
            } else {
                options.setToolbarWidgetColor(ContextCompat.getColor(appContext, R.color.ps_color_white));
            }
            return options;
        }
    }
}
