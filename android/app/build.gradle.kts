import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing: keys live outside the repo (~/.android/agents-release.properties) or come from CI.
val keyProps = Properties().apply {
    val f = file(System.getenv("AGENTS_KEYSTORE_PROPS") ?: (System.getProperty("user.home") + "/.android/agents-release.properties"))
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.voer.agents"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.voer.agents"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "0.8.0"
    }
    signingConfigs {
        if (!keyProps.isEmpty) create("release") {
            storeFile = file(keyProps.getProperty("storeFile")); storePassword = keyProps.getProperty("storePassword")
            keyAlias = keyProps.getProperty("keyAlias"); keyPassword = keyProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (keyProps.isEmpty) "debug" else "release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
