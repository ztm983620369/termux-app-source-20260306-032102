package com.luck.pictureselector;

import android.content.Context;
import android.content.SharedPreferences;

import com.luck.picture.lib.animators.AnimationType;
import com.luck.picture.lib.config.SelectMimeType;
import com.luck.picture.lib.config.SelectModeConfig;
import com.luck.picture.lib.language.LanguageConfig;

public final class TermuxPictureSelectorSettings {
    public static final String EXTRA_FROM_ALBUM_SETTINGS =
            "com.luck.pictureselector.extra.FROM_ALBUM_SETTINGS";

    static final int STYLE_DEFAULT = 0;
    static final int STYLE_WHITE = 1;
    static final int STYLE_NUMBER = 2;
    static final int STYLE_WECHAT = 3;

    static final int IMAGE_ENGINE_GLIDE = 0;
    static final int IMAGE_ENGINE_PICASSO = 1;
    static final int IMAGE_ENGINE_COIL = 2;

    static final int VIDEO_PLAYER_MEDIA = 0;
    static final int VIDEO_PLAYER_EXO = 1;
    static final int VIDEO_PLAYER_IJK = 2;
    static final int VIDEO_PLAYER_SYSTEM = 3;

    private static final String PREFS = "termux_picture_selector_settings";

    private TermuxPictureSelectorSettings() {
    }

    public static Snapshot load(Context context) {
        SharedPreferences prefs = prefs(context);
        Snapshot snapshot = new Snapshot();
        snapshot.chooseMode = prefs.getInt("choose_mode", SelectMimeType.ofAll());
        snapshot.selectionMode = prefs.getInt("selection_mode", SelectModeConfig.MULTIPLE);
        snapshot.maxSelectNum = prefs.getInt("max_select_num", 9);
        snapshot.maxVideoSelectNum = prefs.getInt("max_video_select_num", 1);
        snapshot.styleMode = prefs.getInt("style_mode", STYLE_DEFAULT);
        snapshot.windowAnimationUp = prefs.getBoolean("window_animation_up", false);
        snapshot.recyclerAnimationMode = prefs.getInt("recycler_animation_mode", AnimationType.DEFAULT_ANIMATION);
        snapshot.language = prefs.getInt("language", LanguageConfig.UNKNOWN_LANGUAGE);
        snapshot.imageEngine = prefs.getInt("image_engine", IMAGE_ENGINE_GLIDE);
        snapshot.videoPlayer = prefs.getInt("video_player", VIDEO_PLAYER_MEDIA);
        snapshot.cropAspectX = prefs.getInt("crop_aspect_x", -1);
        snapshot.cropAspectY = prefs.getInt("crop_aspect_y", -1);

        snapshot.openClickSound = prefs.getBoolean("open_click_sound", false);
        snapshot.chooseMultiple = prefs.getBoolean("choose_multiple", true);
        snapshot.displayCamera = prefs.getBoolean("display_camera", true);
        snapshot.displayGif = prefs.getBoolean("display_gif", false);
        snapshot.withSelectVideoImage = prefs.getBoolean("with_select_video_image", true);
        snapshot.fastSlidingSelect = prefs.getBoolean("fast_sliding_select", true);
        snapshot.previewFullScreen = prefs.getBoolean("preview_full_screen", true);
        snapshot.previewZoom = prefs.getBoolean("preview_zoom", true);
        snapshot.previewImage = prefs.getBoolean("preview_image", true);
        snapshot.previewVideo = prefs.getBoolean("preview_video", true);
        snapshot.autoVideoPlay = prefs.getBoolean("auto_video_play", false);
        snapshot.videoPauseResume = prefs.getBoolean("video_pause_resume", false);
        snapshot.previewAudio = prefs.getBoolean("preview_audio", true);
        snapshot.compress = prefs.getBoolean("compress", false);
        snapshot.crop = prefs.getBoolean("crop", false);
        snapshot.pageStrategy = prefs.getBoolean("page_strategy", true);
        snapshot.maxSelectMask = prefs.getBoolean("max_select_mask", true);
        snapshot.originalControl = prefs.getBoolean("original_control", false);
        snapshot.directReturnSingle = prefs.getBoolean("direct_return_single", false);
        snapshot.onlySandboxDir = prefs.getBoolean("only_sandbox_dir", false);
        snapshot.timeAxis = prefs.getBoolean("time_axis", true);
        snapshot.querySortAsc = prefs.getBoolean("query_sort_asc", false);
        snapshot.hideCropControls = prefs.getBoolean("hide_crop_controls", false);
        snapshot.freeStyleCrop = prefs.getBoolean("free_style_crop", false);
        snapshot.showCropGrid = prefs.getBoolean("show_crop_grid", true);
        snapshot.showCropFrame = prefs.getBoolean("show_crop_frame", true);
        snapshot.cropCircular = prefs.getBoolean("crop_circular", false);
        snapshot.skipCropGif = prefs.getBoolean("skip_crop_gif", false);
        snapshot.forbidCropGif = prefs.getBoolean("forbid_crop_gif", false);
        return snapshot;
    }

    public static void save(Context context, Snapshot snapshot) {
        prefs(context).edit()
                .putInt("choose_mode", snapshot.chooseMode)
                .putInt("selection_mode", snapshot.selectionMode)
                .putInt("max_select_num", snapshot.maxSelectNum)
                .putInt("max_video_select_num", snapshot.maxVideoSelectNum)
                .putInt("style_mode", snapshot.styleMode)
                .putBoolean("window_animation_up", snapshot.windowAnimationUp)
                .putInt("recycler_animation_mode", snapshot.recyclerAnimationMode)
                .putInt("language", snapshot.language)
                .putInt("image_engine", snapshot.imageEngine)
                .putInt("video_player", snapshot.videoPlayer)
                .putInt("crop_aspect_x", snapshot.cropAspectX)
                .putInt("crop_aspect_y", snapshot.cropAspectY)
                .putBoolean("open_click_sound", snapshot.openClickSound)
                .putBoolean("choose_multiple", snapshot.chooseMultiple)
                .putBoolean("display_camera", snapshot.displayCamera)
                .putBoolean("display_gif", snapshot.displayGif)
                .putBoolean("with_select_video_image", snapshot.withSelectVideoImage)
                .putBoolean("fast_sliding_select", snapshot.fastSlidingSelect)
                .putBoolean("preview_full_screen", snapshot.previewFullScreen)
                .putBoolean("preview_zoom", snapshot.previewZoom)
                .putBoolean("preview_image", snapshot.previewImage)
                .putBoolean("preview_video", snapshot.previewVideo)
                .putBoolean("auto_video_play", snapshot.autoVideoPlay)
                .putBoolean("video_pause_resume", snapshot.videoPauseResume)
                .putBoolean("preview_audio", snapshot.previewAudio)
                .putBoolean("compress", snapshot.compress)
                .putBoolean("crop", snapshot.crop)
                .putBoolean("page_strategy", snapshot.pageStrategy)
                .putBoolean("max_select_mask", snapshot.maxSelectMask)
                .putBoolean("original_control", snapshot.originalControl)
                .putBoolean("direct_return_single", snapshot.directReturnSingle)
                .putBoolean("only_sandbox_dir", snapshot.onlySandboxDir)
                .putBoolean("time_axis", snapshot.timeAxis)
                .putBoolean("query_sort_asc", snapshot.querySortAsc)
                .putBoolean("hide_crop_controls", snapshot.hideCropControls)
                .putBoolean("free_style_crop", snapshot.freeStyleCrop)
                .putBoolean("show_crop_grid", snapshot.showCropGrid)
                .putBoolean("show_crop_frame", snapshot.showCropFrame)
                .putBoolean("crop_circular", snapshot.cropCircular)
                .putBoolean("skip_crop_gif", snapshot.skipCropGif)
                .putBoolean("forbid_crop_gif", snapshot.forbidCropGif)
                .apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static final class Snapshot {
        public int chooseMode;
        public int selectionMode;
        public int maxSelectNum;
        public int maxVideoSelectNum;
        public int styleMode;
        public boolean windowAnimationUp;
        public int recyclerAnimationMode;
        public int language;
        public int imageEngine;
        public int videoPlayer;
        public int cropAspectX;
        public int cropAspectY;

        public boolean openClickSound;
        public boolean chooseMultiple;
        public boolean displayCamera;
        public boolean displayGif;
        public boolean withSelectVideoImage;
        public boolean fastSlidingSelect;
        public boolean previewFullScreen;
        public boolean previewZoom;
        public boolean previewImage;
        public boolean previewVideo;
        public boolean autoVideoPlay;
        public boolean videoPauseResume;
        public boolean previewAudio;
        public boolean compress;
        public boolean crop;
        public boolean pageStrategy;
        public boolean maxSelectMask;
        public boolean originalControl;
        public boolean directReturnSingle;
        public boolean onlySandboxDir;
        public boolean timeAxis;
        public boolean querySortAsc;
        public boolean hideCropControls;
        public boolean freeStyleCrop;
        public boolean showCropGrid;
        public boolean showCropFrame;
        public boolean cropCircular;
        public boolean skipCropGif;
        public boolean forbidCropGif;

        public boolean useSystemVideoPlayer() {
            return videoPlayer == VIDEO_PLAYER_SYSTEM;
        }
    }
}
