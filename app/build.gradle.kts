plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * 持久签名密钥路径（Git 忽略）；保持覆盖安装签名一致，缺失时回退 debug 签名。
 * 公开渠道分发时需替换为独立离线凭据。
 */
val persistentKeystore = file("keystore/codeforge.keystore")

/** 临时 applicationId 后缀（`-Pcf.appIdSuffix=...`），用于同机并存安装；不动 namespace 与 JNI 绑定。 */
val appIdSuffix = (project.findProperty("cf.appIdSuffix") as String?) ?: ""

android {
    namespace = "com.hxy.codeforge"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.hxy.codeforge$appIdSuffix"
        minSdk = 26
        // targetSdk 28 允许在私有目录直接 exec 二进制与 proot，同时保留高版本系统适配
        targetSdk = 28
        testInstrumentationRunner = "com.hxy.codeforge.phone.PhoneSmokeRunner"
        versionCode = 6
        versionName = "2.0.3"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (persistentKeystore.exists()) {
            create("cf") {
                storeFile = persistentKeystore
                storePassword = "codeforge"
                keyAlias = "codeforge"
                keyPassword = "codeforge"
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("cf")
                ?: signingConfigs.getByName("debug")
        }
        release {
            // 保留 SourceFile/LineNumberTable 配合 mapping.txt 定位日志
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("cf")
                ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }

    androidResources {
        // 避免声明 .gz 资产：AAPT2 会自动解压并剥除后缀，改用 .tgz / .zip
    }

    packaging {
        // 需将 native 可执行库解压至 nativeLibraryDir 才能直接 exec 启动内核
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.webkit)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.security:security-crypto:1.1.0-alpha07")
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation("junit:junit:4.13.2")
    // 提供完整 org.json 实现供纯 JVM 单测运行
    testImplementation("org.json:json:20240303")

    debugImplementation(libs.androidx.compose.ui.tooling)
}
