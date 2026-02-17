/*******************************************************************************
 *    sora-editor - the awesome code editor for Android
 *    https://github.com/Rosemoe/sora-editor
 *    Copyright (C) 2020-2024  Rosemoe
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 *
 *     Please contact Rosemoe by email 2073412493@qq.com if you need
 *     additional information or have any questions
 ******************************************************************************/

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    sourceSets {
        named("main") {
            assets.srcDir(file("../extensions"))
        }
    }
    compileSdk = (project.findProperty("compileSdkVersion") as String).toInt()
    defaultConfig {
        minSdk = (project.findProperty("minSdkVersion") as String).toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    androidResources {
        additionalParameters.add("--warn-manifest-validation")
    }
    buildFeatures {
        viewBinding = true
    }
    packaging {
        resources.pickFirsts.addAll(
            arrayOf(
                "license/README.dom.txt",
                "license/LICENSE.dom-documentation.txt",
                "license/NOTICE",
                "license/LICENSE.dom-software.txt",
                "license/LICENSE",
            )
        )
    }
    namespace = "io.github.rosemoe.sora.app"
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    // androidx & material
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Editor
    implementation(project(":sora-editor"))
    implementation(project(":sora-language-java"))
    implementation(project(":sora-language-textmate"))
    implementation(project(":sora-language-monarch"))
    implementation(project(":sora-editor-lsp"))
    implementation(project(":sora-language-treesitter"))
    implementation(project(":sora-oniguruma-native"))

    // Tree-sitter languages
    implementation("com.itsaky.androidide.treesitter:tree-sitter-java:4.3.1")

    // Monarch Languages
    implementation("io.github.dingyi222666.monarch:monarch-language-pack:1.0.2")

    // Kotlin coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation(project(":file-bridge"))
    implementation(project(":termux-shared"))

    // Lua language server
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.21.1")

    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
}
