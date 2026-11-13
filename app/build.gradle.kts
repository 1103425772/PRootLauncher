plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.proot.launcher"
    compileSdk = 36
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.proot.launcher"
        minSdk = 24
        versionCode = 41
        versionName = "1.14.0-v30"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // v30：只保留 hi（targetSdk 34）。lo（targetSdk 28）已停用 ——
    // hi 在 Android 9/10/11 上实测可用，继续维护双包没有收益。
    flavorDimensions += "abi"
    productFlavors {
        create("hi") {
            dimension = "abi"
            targetSdk = 34
        }
    }

    sourceSets {
        getByName("hi") {
            manifest.srcFile("src/hi/AndroidManifest.xml")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    signingConfigs {
        // v14 起 keystore 固定为项目内 keystore/debug.keystore，保证覆盖升级不丢 rootfs
        create("release") {
            storeFile = file("../keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging {
        // jniLibs 里 proot/loader/pulseaudio 是原生可执行文件（非 .so API），
        // 需要保留 debug 信息与可执行位（extractNativeLibs=true 解压后直接 exec）
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    // ---- Compose Multiplatform + Material 3 ----
    implementation("org.jetbrains.compose.runtime:runtime:1.10.3")
    implementation("org.jetbrains.compose.foundation:foundation:1.10.3")
    implementation("org.jetbrains.compose.ui:ui:1.10.3")
    implementation("org.jetbrains.compose.material3:material3:1.9.0")

    implementation("androidx.activity:activity-compose:1.9.3")

    // rootfs 解压：tar.xz（XZInputStream）
    implementation("org.tukaani:xz:1.9")
}
