package com.luck.pictureselector;

import android.app.Activity;
import android.content.Context;
import android.provider.MediaStore;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.luck.picture.lib.app.IApp;
import com.luck.picture.lib.app.PictureAppMaster;
import com.luck.picture.lib.basic.PictureSelectionModel;
import com.luck.picture.lib.basic.PictureSelector;
import com.luck.picture.lib.config.PictureMimeType;
import com.luck.picture.lib.config.SelectMimeType;
import com.luck.picture.lib.engine.PictureSelectorEngine;
import com.luck.picture.lib.entity.LocalMedia;
import com.luck.picture.lib.interfaces.OnResultCallbackListener;
import com.luck.picture.lib.style.PictureSelectorStyle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TermuxPictureSelectorLauncher {

    public interface ImageSelectionCallback {
        void onImagesSelected(@NonNull List<SelectedImage> images);

        void onCancel();
    }

    public static final class SelectedImage {
        @NonNull private final String availablePath;
        @NonNull private final String sourcePath;
        @NonNull private final String realPath;
        @NonNull private final String originalPath;
        @NonNull private final String mimeType;
        @NonNull private final String fileName;
        private final int width;
        private final int height;
        private final long size;

        private SelectedImage(@NonNull LocalMedia media) {
            availablePath = safe(media.getAvailablePath());
            sourcePath = safe(media.getPath());
            realPath = safe(media.getRealPath());
            originalPath = safe(media.getOriginalPath());
            mimeType = safe(media.getMimeType());
            fileName = safe(media.getFileName());
            width = Math.max(0, media.getWidth());
            height = Math.max(0, media.getHeight());
            size = Math.max(0L, media.getSize());
        }

        @NonNull public String getAvailablePath() {
            return availablePath;
        }

        @NonNull public String getSourcePath() {
            return sourcePath;
        }

        @NonNull public String getRealPath() {
            return realPath;
        }

        @NonNull public String getOriginalPath() {
            return originalPath;
        }

        @NonNull public String getMimeType() {
            return mimeType;
        }

        @NonNull public String getFileName() {
            return fileName;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public long getSize() {
            return size;
        }
    }

    private TermuxPictureSelectorLauncher() {
    }

    public static void openGallery(@NonNull Activity activity) {
        openGallery(activity, false, null);
    }

    public static void openImageGallery(@NonNull Activity activity,
                                        @NonNull ImageSelectionCallback callback) {
        openGallery(activity, true, callback);
    }

    private static void openGallery(@NonNull Activity activity,
                                    boolean imagesOnly,
                                    ImageSelectionCallback callback) {
        ensurePictureSelectorInitialized(activity.getApplicationContext());

        Context appContext = activity.getApplicationContext();
        TermuxPictureSelectorSettings.Snapshot settings = TermuxPictureSelectorSettings.load(appContext);
        PictureSelectorStyle selectorStyle = TermuxPictureSelectorStyleFactory.create(activity, settings);
        int chooseMode = imagesOnly ? SelectMimeType.ofImage() : settings.chooseMode;
        String outputPath = chooseMode == SelectMimeType.ofAudio()
                ? TermuxPictureSelectorEngines.audioOutputPath(activity)
                : TermuxPictureSelectorEngines.cameraOutputPath(activity);

        PictureSelectionModel model = PictureSelector.create(activity)
                .openGallery(chooseMode)
                .setSelectorUIStyle(selectorStyle)
                .setImageEngine(TermuxPictureSelectorEngines.createImageEngine(settings))
                .setVideoPlayerEngine(TermuxPictureSelectorEngines.createVideoPlayerEngine(settings))
                .setCropEngine(settings.crop
                        ? TermuxPictureSelectorEngines.createCropFileEngine(activity, settings, selectorStyle)
                        : null)
                .setCompressEngine(settings.compress
                        ? TermuxPictureSelectorEngines.createCompressFileEngine()
                        : null)
                .setSandboxFileEngine(TermuxPictureSelectorEngines.createSandboxFileEngine())
                .setInjectLayoutResourceListener(new TermuxPictureSelectorLayoutResourceListener())
                .setSelectionMode(settings.selectionMode)
                .setLanguage(settings.language)
                .setQuerySortOrder(settings.querySortAsc ? MediaStore.MediaColumns.DATE_MODIFIED + " ASC" : "")
                .setOutputCameraDir(outputPath)
                .setOutputAudioDir(outputPath)
                .setQuerySandboxDir(chooseMode == SelectMimeType.ofAudio()
                        ? TermuxPictureSelectorEngines.audioOutputPath(activity)
                        : TermuxPictureSelectorEngines.cameraOutputPath(activity))
                .isDisplayTimeAxis(settings.timeAxis)
                .isOnlyObtainSandboxDir(settings.onlySandboxDir)
                .isPageStrategy(settings.pageStrategy)
                .isOriginalControl(settings.originalControl)
                .isDisplayCamera(settings.displayCamera)
                .isOpenClickSound(settings.openClickSound)
                .setSkipCropMimeType(settings.skipCropGif
                        ? new String[]{PictureMimeType.ofGIF(), PictureMimeType.ofWEBP()}
                        : null)
                .isFastSlidingSelect(settings.fastSlidingSelect)
                .isWithSelectVideoImage(!imagesOnly && settings.withSelectVideoImage)
                .isPreviewFullScreenMode(settings.previewFullScreen)
                .isVideoPauseResumePlay(settings.videoPauseResume)
                .isPreviewZoomEffect(settings.previewZoom)
                .isPreviewImage(settings.previewImage)
                .isPreviewVideo(settings.previewVideo)
                .isPreviewAudio(settings.previewAudio)
                .isAutoVideoPlay(settings.autoVideoPlay)
                .isLoopAutoVideoPlay(settings.autoVideoPlay)
                .isUseSystemVideoPlayer(settings.useSystemVideoPlayer())
                .isMaxSelectEnabledMask(settings.maxSelectMask)
                .isDirectReturnSingle(settings.directReturnSingle)
                .setMaxSelectNum(settings.maxSelectNum)
                .setMaxVideoSelectNum(settings.maxVideoSelectNum)
                .setRecyclerAnimationMode(settings.recyclerAnimationMode)
                .isGif(settings.displayGif);

        model.forResult(new OnResultCallbackListener<LocalMedia>() {
            @Override
            public void onResult(ArrayList<LocalMedia> result) {
                if (callback != null) {
                    ArrayList<SelectedImage> selected = new ArrayList<>();
                    if (result != null) {
                        for (LocalMedia media : result) {
                            if (media == null) continue;
                            selected.add(new SelectedImage(media));
                        }
                    }
                    callback.onImagesSelected(Collections.unmodifiableList(selected));
                    return;
                }
                Toast.makeText(appContext,
                        appContext.getString(R.string.termux_picture_selector_result, result.size()),
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onCancel() {
                if (callback != null) {
                    callback.onCancel();
                    return;
                }
                // PictureSelector closes its own Activity; Termux is already the next Activity in the stack.
            }
        });
    }

    @NonNull
    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static void ensurePictureSelectorInitialized(@NonNull Context appContext) {
        if (PictureAppMaster.getInstance().getApp() != null) {
            return;
        }
        PictureAppMaster.getInstance().setApp(new IApp() {
            @Override
            public Context getAppContext() {
                return appContext;
            }

            @Override
            public PictureSelectorEngine getPictureSelectorEngine() {
                return new PictureSelectorEngineImp();
            }
        });
    }
}
