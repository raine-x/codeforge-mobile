plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * 固定签名密钥的位置。
 *
 * ## 为什么不用默认的 debug keystore
 *
 * AGP 默认的 debug keystore 落在 `$ANDROID_HOME/.android/debug.keystore`，
 * 而本沙箱的 `$ANDROID_HOME` 是 `/opt` —— **容器重启就会被清空**，
 * 于是每轮构建生成一把新密钥。签名一变，Android 就拒绝覆盖安装
 * （真机报「安装包签名不正确」），用户装不上更新。
 *
 * 所以密钥固定放在仓库内的这个路径（`*.keystore` 已被 .gitignore 覆盖，不进版本库）。
 * 换机器构建时把它拷过来即可 —— **没有它就装不了更新**。
 *
 * ## 密钥怎么来的
 *
 * 2026-11-05 用 JDK 的 keytool 生成（RSA 2048 / 有效期 10000 天）：
 *
 * ```
 * keytool -genkeypair -v -keystore app/keystore/codeforge.keystore \
 *   -alias codeforge -keyalg RSA -keysize 2048 -validity 10000 \
 *   -storepass codeforge -keypass codeforge \
 *   -dname "CN=CodeForge, OU=CodeForge, O=CodeForge, L=Shanghai, ST=Shanghai, C=CN"
 * ```
 *
 * 口令是「codeforge」——**这是侧载用的自用密钥，不是发布密钥**：
 * 它明文写在构建脚本里，任何拿到仓库的人都能签出「同名同签名」的包。
 * 真要上架应用市场，得换成离线保管的密钥，并把口令挪到环境变量。
 */
val persistentKeystore = file("keystore/codeforge.keystore")

/**
 * 临时旁路：`-Pcf.appIdSuffix=mi` 只给 **applicationId** 加后缀。
 *
 * ## 什么时候用
 *
 * 设备上若已存在（或残留着）同包名的记录，PackageManager 会先判「替换应用」，
 * **根本走不到验签那一步** —— 换签名无效。此时需要一个全新包名才能装上。
 *
 * ## 为什么只改 applicationId、不改 namespace
 *
 * `namespace` 决定 manifest 里 `.MainActivity` 怎么解析成类名，
 * 也决定 R 类的包 —— 它必须跟 Kotlin 包 `com.hxy.codeforge` 一致。
 * `applicationId` 只是**安装后的包 id**，两者可以不同。
 */
val appIdSuffix = (project.findProperty("cf.appIdSuffix") as String?) ?: ""

android {
    namespace = "com.hxy.codeforge"
    compileSdk = 36
    // 显式钉住，避免 AGP 去下载它默认的 build-tools 版本（本机只装了 34.0.0 与 36.0.0）。
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.hxy.codeforge$appIdSuffix"
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

    signingConfigs {
        // 固定密钥（见文件头 persistentKeystore 的说明）。
        // 密钥不存在时回落到默认 debug —— 但那样签名会随环境变，只能当临时产物。
        if (persistentKeystore.exists()) {
            create("cf") {
                storeFile = persistentKeystore
                storePassword = "codeforge"
                keyAlias = "codeforge"
                keyPassword = "codeforge"
                // v1 + v2 + v3 全开。
                // 只开 v2 时，部分国产 ROM 自带的安装器（自己解析 APK 而不是走系统
                // PackageInstaller）会判成「安装包签名不正确」。v1 多写一份
                // META-INF/*.SF+*.RSA，体积代价可以忽略，换来的是装得上的概率。
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                // v3 开了才带密钥轮换能力（apksigner 的 --rotation-min-days-version），
                // 前提是同一把密钥有 v2+v3 两条历史 —— 缺 v2 的旧包会装不上更新。
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
            // R8 压缩（2026-10-25 用户授权）：真机掉帧排查需要行号，
            // proguard-rules.pro 里保留 SourceFile/LineNumberTable + 输出 mapping
            isMinifyEnabled = true
            isShrinkResources = true
            // 侧载场景没有发布证书：借 debug 签名让 release 包可直接安装。
            // 用户点名要 release 包 —— debug 的 ART 可调试档会掉帧，
            // release 才有全速执行与完整资源优化。
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
        // 侧载定位（决策表 2026-10-25）：不上 Play，ExpiredTargetSdkVersion
        // 是 Play 分发政策检查，与代码质量无关 —— 显式豁免并留痕
        disable += "ExpiredTargetSdkVersion"
    }

    androidResources {
        // 语音模型已移除，不再需要「zip 不压缩」（那是为 openFd 拿精确长度用的）。
        // builtin-skills.zip 走 ZipInputStream 流式解包，压缩存储反而更省体积。
        // ⚠️ 不能用 .gz 后缀的资产 —— AAPT2 会自动解压并去掉后缀，
        // 运行时按原名打开直接 FileNotFound（.tgz 不受影响）
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

    debugImplementation(libs.androidx.compose.ui.tooling)
}
