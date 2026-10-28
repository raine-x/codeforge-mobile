plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.codeforge"
    compileSdk = 36
    // 显式钉住，避免 AGP 去下载它默认的 build-tools 版本（本机只装了 34.0.0 与 36.0.0）。
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "app.codeforge"
        minSdk = 26
        // 侧载定位（决策表 2026-10-25）：28 解锁 filesDir exec 与 proot，
        // 是工具链管理器（Termux 二进制）与 opencode 设备内运行的必要条件；
        // Play 上架本就不在计划内。36 合规代码（specialUse/edge-to-edge）全部保留。
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"

        // 只出 64 位：Go 内核只交叉编译 arm64，32 位设备不覆盖。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // R8 压缩（2026-10-25 用户授权）：真机掉帧排查需要行号，
            // proguard-rules.pro 里保留 SourceFile/LineNumberTable + 输出 mapping
            isMinifyEnabled = true
            isShrinkResources = true
            // 侧载场景没有发布证书：借 debug 签名让 release 包可直接安装。
            // 用户点名要 release 包 —— debug 的 ART 可调试档会掉帧，
            // release 才有全速执行与完整资源优化。
            signingConfig = signingConfigs.getByName("debug")
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
        // 侧载定位（决策表 2026-10-25）：不上 Play，ExpiredTargetSdkVersion
        // 是 Play 分发政策检查，与代码质量无关 —— 显式豁免并留痕
        disable += "ExpiredTargetSdkVersion"
    }

    androidResources {
        // 语音模型 zip 保持存储不压缩：AssetFileDescriptor 才能给出精确长度，
        // 「环境准备中」百分比才有数据源。⚠️ 不能用 .gz 后缀的资产 —— AAPT2 会
        // 自动解压并去掉后缀，运行时按原名打开直接 FileNotFound（.tgz 不受影响）
        noCompress += "zip"
    }

    packaging {
        // 关键：useLegacyPackaging=true 等价于 AndroidManifest 的
        // android:extractNativeLibs="true"。
        // 默认 false 时 .so 直接从 APK mmap，nativeLibraryDir 下没有实际文件，
        // 无法 exec —— 而 CodeForge 内核就是靠 nativeLibraryDir 里的可执行文件启动的。
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

    // 前后台可见性监听（B5-2：上报 visibility，内核据此发系统通知）
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")

    // API Key 等敏感项加密存储（B5-5，androidx 官方方案，Keystore 支撑）
    implementation("androidx.security:security-crypto:1.1.0-alpha07")

    // 内核是本地 HTTP + WebSocket 服务，OkHttp 同时覆盖两者。
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    // 渲染 IR 解析与公式定界符归一是纯文本逻辑，用 JVM 单测断言，
    // 不必等真机/原生库才知道键名和块结构有没有解析对。
    testImplementation("junit:junit:4.13.2")
    // OpenRender 的 IR 是 JSON（Android 自带 org.json 在 JVM 单测里只有空壳），
    // 测试期用 crates.io 同款实现跑真实解析；仅 testImplementation，不进 APK。
    testImplementation("org.json:json:20240303")

    // 离线语音兜底（Vosk）：系统无识别服务的 ROM（国内无 GMS 机型常见）用。
    // AAR 的 arm64 增量约 2 MB；中文模型 41 MB 以 zip 放 assets，首启解压
    // （见 voice/OfflineVoice.kt）。版本 0.3.47 来自 Maven Central。
    implementation("com.alphacephei:vosk-android:0.3.75")

    debugImplementation(libs.androidx.compose.ui.tooling)
}
