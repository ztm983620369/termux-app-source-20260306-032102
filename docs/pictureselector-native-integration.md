# PictureSelector Native Integration

Source: `third_party/PictureSelector`, mirrored from `https://github.com/LuckSiege/PictureSelector` branch `version_component`, tag `v3.11.2`, commit `a880156`.

## Module Map

`app`
-> `:pictureselector-app-source`
-> `:pictureselector-selector`
-> `:pictureselector-ucrop`
-> `:pictureselector-compress`
-> `:pictureselector-camerax`
-> `:pictureselector-ijkplayer-java`

`pictureselector-app-source` is the full upstream demo app source converted into a library module for Termux integration. It includes `app/src/main/java`, `CoilEngine.kt`, `app/src/main/res`, and `app/libs` JNI libraries. Its Termux manifest keeps the demo activities available by explicit launch, but does not replace Termux's `Application` or launcher activity.

`pictureselector-selector` is the core API and UI module. It exposes `PictureSelector`, `PictureSelectionModel`, result models, style APIs, and extension engines such as `ImageEngine`, `CompressEngine`, `CompressFileEngine`, `CropFileEngine`, `SandboxFileEngine`, and media-player hooks.

`pictureselector-ucrop` provides the bundled crop UI/API under `com.yalantis.ucrop`.

`pictureselector-compress` provides the bundled Luban compression implementation under `top.zibin.luban`.

`pictureselector-camerax` provides the optional custom camera UI/API under `com.luck.lib.camerax`.

`pictureselector-ijkplayer-java` is only the Java wrapper for IjkPlayer. It does not bundle native `ijkffmpeg`, `ijksdl`, or `ijkplayer` shared libraries, so it should only be used after those native libraries are added.

## Gradle Adaptation

The upstream modules keep their original `build.gradle` files. Termux uses each module's `tindroid.gradle` instead, because the upstream Gradle files apply `publish.gradle` and are written for older AGP versions.

The `tindroid.gradle` files:

- set explicit AGP 8 namespaces;
- use Termux root `compileSdkVersion`, `minSdkVersion`, and `targetSdkVersion`;
- avoid upstream publishing configuration;
- use `AndroidManifest-tindroid.xml` files without manifest `package=` attributes;
- convert the upstream `app` module from an Android application into a library module so the complete app source can be compiled into Termux without taking over Termux's launcher or `Application`.

## Dependency Shape

Core selector dependencies:

- `androidx.annotation`
- `androidx.core`
- `androidx.appcompat`
- `androidx.recyclerview`
- `androidx.activity`
- `androidx.fragment`
- `androidx.exifinterface`
- `androidx.viewpager2`
- `androidx.constraintlayout`

Optional capability dependencies:

- Full demo app source: Navigation KTX, SubsamplingScaleImageView, ExoPlayer, Glide, Picasso, Coil/GIF/video, and the upstream demo JNI libs from `app/libs`
- Crop: `androidx.transition`, `okhttp3`, `gson`
- CameraX: `androidx.camera:camera-core`, `camera-camera2`, `camera-view`, `camera-lifecycle`, `androidx.concurrent`
- Compression: no external dependency
- IjkPlayer Java wrapper: no external dependency; the full app source module contributes the upstream `arm64-v8a` and `armeabi` Ijk `.so` files from `app/libs`

## Next Native Call Points

Termux should add a small app-side bridge instead of modifying PictureSelector internals:

- implement `ImageEngine` using existing Termux image stack, likely Coil;
- implement `OnResultCallbackListener<LocalMedia>` to translate selected media back into the Termux caller;
- add `CropFileEngine` or `CompressFileEngine` only when the caller asks for crop/compress behavior;
- add `CAMERA` and recording permissions only if `pictureselector-camerax` is exposed to users.
