import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.galaxywk.originalmedialibrary.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.galaxywk.originalmedialibrary.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val signingFile = rootProject.file("keystore.properties")
            if (signingFile.exists()) {
                val properties = Properties().apply { signingFile.inputStream().use { load(it) } }
                signingConfig = signingConfigs.create("external")
                signingConfig?.storeFile = rootProject.file(properties.getProperty("storeFile"))
                signingConfig?.storePassword = properties.getProperty("storePassword")
                signingConfig?.keyAlias = properties.getProperty("keyAlias")
                signingConfig?.keyPassword = properties.getProperty("keyPassword")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

android.applicationVariants.all {
    outputs.all {
        @Suppress("UnstableApiUsage")
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = if (name.contains("debug")) "MediaDownloader-Android-1.0.0-debug.apk" else "MediaDownloader-Android-1.0.0.apk"
    }
}

dependencies {
    implementation(files("libs/ffmpeg-kit.aar"))
    implementation("com.arthenica:smart-exception-java:0.2.1")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.work:work-testing:2.10.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
