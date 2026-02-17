# ProGuard/R8 rules for the "releaseOptimized" build type.
#
# Goal: maximum shrink + obfuscation while keeping required runtime behaviour.

# Plugins (and termux-shared) read BuildConfig by its original class name via reflection.
# Keep both the class name and the field name stable.
-keep class com.termux.BuildConfig { *; }

# Be explicit about JNI bindings (also present in default Android rules, but keep it here).
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep enough info for useful crash reports while hiding original source file names.
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# Optional static-analysis annotations referenced by some dependencies.
-dontwarn afu.org.checkerframework.checker.formatter.qual.ConversionCategory
-dontwarn org.checkerframework.checker.formatter.qual.ConversionCategory
-dontwarn org.checkerframework.dataflow.qual.Pure
