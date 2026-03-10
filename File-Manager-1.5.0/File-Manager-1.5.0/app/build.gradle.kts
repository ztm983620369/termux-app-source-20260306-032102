plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.fossify.filemanager"
    compileSdk = project.properties["compileSdkVersion"].toString().toInt()

    defaultConfig {
        minSdk = project.properties["minSdkVersion"].toString().toInt()
        targetSdk = project.properties["targetSdkVersion"].toString().toInt()

        buildConfigField("String", "APPLICATION_ID", "\"com.termux.filemanager\"")
        buildConfigField("String", "VERSION_NAME", "\"1.5.0\"")
        buildConfigField("int", "VERSION_CODE", "150")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":fossify-commons"))
    implementation(project(":file-bridge"))
    implementation(project(":editor-sync-core"))
    implementation(project(":session-sync-core"))
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    implementation("com.github.naveensingh:RootTools:965c154e20")
    implementation("com.github.naveensingh:RootShell:bc7e5d398e")
    implementation("com.alexvasilkov:gesture-views:2.8.3")
    implementation("me.grantland:autofittextview:0.2.1")
    implementation("net.lingala.zip4j:zip4j:2.11.5")
}
