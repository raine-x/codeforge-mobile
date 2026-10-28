# CodeForge APP

CodeForge-Go 的 Android 宿主壳。**内核尚未接入**，当前是一个能构建、能安装、能显示环境自检的空壳。

存在的目的只有一个：把「Go 内核怎么塞进 APK」这条路上所有会卡住的地方提前钉死，避免后续移植时才发现。

---

## 当前状态

| 项 | 状态 |
|---|---|
| 构建 | `assembleDebug` 通过，产出 `app-debug.apk`（11 MB） |
| 安装 | 未在真机验证 |
| 界面 | Compose 单页，列出接入内核前必须确认的环境项 |
| 内核 | **未接入**。`jniLibs/arm64-v8a/libcodeforge.so` 为空位 |
| 前台服务 | `CoreService` 已声明并注册，但只发通知，不启动进程 |

---

## 环境要求

| 组件 | 要求 | 本机实测 |
|---|---|---|
| JDK | 17 | Microsoft OpenJDK 17.0.20.1 |
| Android SDK | platforms 36、build-tools 36.0.0、platform-tools | `D:\AndroidSDK` |
| Gradle | 8.14.6（wrapper 管理，无需手装） | 已预置到 `~/.gradle/wrapper/dists` |
| 磁盘 | 约 1.5 GB（SDK + Gradle + 依赖缓存） | D 盘 |

`local.properties` 已指向 `D:\AndroidSDK`，不要提交到版本库（已在 `.gitignore` 中）。

---

## 版本矩阵

| 组件 | 版本 | 选它的原因 |
|---|---|---|
| Gradle | 8.14.6 | AGP 8.13 要求 Gradle 8.13+，取 8.x 末版 |
| AGP | 8.13.2 | 与 Gradle 8.14.6 配套，且 DSL 是传统写法（AGP 9 换了新 DSL 并内置 Kotlin） |
| Kotlin | 2.4.21 | 独立插件，与 AGP 8.13 兼容 |
| Compose UI | 1.9.5 | **不能用最新**，原因见下方「坑 2」 |
| Material3 | 1.3.2 | 与 Compose UI 1.9.x 配套 |
| Lifecycle | 2.9.4 | 2.10+ 要求 AGP 9.1 |
| core-ktx / activity-compose / webkit | 1.17.0 / 1.13.0 / 1.18.0 | 当前 AGP 下可用 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 | AGP 8.13 最高推荐 compileSdk 36 |
| ABI | 仅 `arm64-v8a` | Go 内核只交叉编译 arm64 |

---

## 目录结构

```
CodeForgeAPP/
├── settings.gradle.kts            仓库与模块声明
├── build.gradle.kts               根构建脚本（插件声明，apply false）
├── gradle.properties              Gradle 与 AndroidX 开关
├── local.properties               SDK 路径（不入库）
├── gradle/libs.versions.toml      版本目录，改版本只改这里
├── gradle/wrapper/                wrapper（8.14.6）
└── app/
    ├── build.gradle.kts           模块配置：compileSdk、abiFilters、useLegacyPackaging
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml    权限、Activity、前台服务声明
        ├── java/app/codeforge/
        │   ├── CodeForgeApp.kt    Application（留白）
        │   ├── MainActivity.kt    Compose 界面
        │   ├── core/
        │   │   ├── EnvProbe.kt    环境自检（接入前必须确认的六项）
        │   │   └── CoreService.kt 内核宿主前台服务
        │   └── ui/theme/Theme.kt
        └── res/values/            strings、themes
```

---

## 构建

```bash
cd D:\code\CodeForgeAPP
gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

已验证的 APK 属性（`aapt2 dump badging` / `dump xmltree`）：

```
package: app.codeforge  versionName 0.1.0
minSdkVersion    = 26
targetSdkVersion = 36
extractNativeLibs = true          ← 关键，见「坑 1」
native-code: 'arm64-v8a'
```

---

## 两个必读的坑

### 坑 1：Java 不读 `http_proxy`，构建会静默超时

本机走本地代理，`curl` 能通，但 Gradle 报：

```
Downloading https://services.gradle.org/distributions/gradle-8.14.6-bin.zip
java.io.IOException: ... failed: timeout (10000ms)
```

原因是 **JVM 不读 `http_proxy` / `https_proxy` 环境变量**，只认 `systemProp.*`。

解法（构建前临时写，构建后可删）：

```properties
# ~/.gradle/gradle.properties
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=<端口>
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=<端口>
systemProp.http.nonProxyHosts=localhost|127.0.0.1
```

如果 `services.gradle.org` 被代理挡住（`CONNECT tunnel failed, 502`），Gradle 发行版可从腾讯云镜像预置：

```bash
# 目录名由 distributionUrl 的 MD5 决定，可直接从 wrapper 报错路径读到
D=~/.gradle/wrapper/dists/gradle-8.14.6-bin/<hash>
curl -L -o "$D/gradle-8.14.6-bin.zip" \
  https://mirrors.cloud.tencent.com/gradle/gradle-8.14.6-bin.zip
touch "$D/gradle-8.14.6-bin.zip.ok"
```

### 坑 2：Compose 最新版要求 AGP 9.1，与 AGP 8.13 冲突

最初用 `compose-bom:2026.09.00`，构建失败：

```
Dependency 'androidx.compose.ui:ui-android:1.12.1' requires
  Android Gradle plugin 9.1.0 or higher.
Dependency 'androidx.compose.ui:ui-android:1.12.1' requires
  compile against version 37 or later of the Android APIs.
```

AGP 8.13.2 的最高推荐 compileSdk 是 36，两者无法同时满足。

**处理方式：降 Compose 到 1.9.5 一档，放弃 BOM 改为钉死版本。**

如果要升到最新 Compose，需要整条链一起动：

```
AGP 9.4.1 + Gradle 9.6+ + compileSdk 37 + Kotlin 内置（不再 apply kotlin-android）
```

注意 AGP 9 的破坏性变更：**内置 Kotlin 支持默认开启，`org.jetbrains.kotlin.android` 插件与新 DSL 不兼容**，必须迁移到内置 Kotlin，或设 `android.builtInKotlin=false` 退出。这一步没做，留作后续。

---

## 接入内核要做什么

### 第一步：修内核的三个地方（不改就跑不起来）

| 文件 | 现状 | 必须改成 |
|---|---|---|
| `pkg/platform/sys_linux.go:16` | `if runtime.GOOS == "android" { return true }` | 去掉这个短路，只靠 `PREFIX` 是否含 `com.termux` 判断。否则 APK 内会被误判为 Termux |
| `pkg/platform/sys_linux.go:23` | `defaultShell()` 探测 `/bin/bash`、`/bin/sh` | android 且非 termux 时返回 `/system/bin/sh`（Android 没有 `/bin/sh`） |
| `pkg/server/termux.go` | 启动时引导安装 `termux-tools` | 在非 Termux 环境整体短路，否则每次启动弹安装窗 |

### 第二步：放二进制

```bash
cd <CodeForge-GO>/CodeForge-go
CGO_ENABLED=0 GOOS=android GOARCH=arm64 \
  go build -trimpath -ldflags "-s -w" -o libcodeforge.so ./cmd/agent
```

产物拷到：

```
app/src/main/jniLibs/arm64-v8a/libcodeforge.so
```

**为什么必须叫 `lib*.so` 且放这个目录**：`targetSdk 29+` 的 app 不允许 `execve` 自己可写数据目录里的文件（Termux 因此卡在 targetSdk 28）。`nativeLibraryDir` 是 APK 挂载点，是唯一可 exec 的位置。改名只为骗过 Gradle 打包规则，它本身是 ELF 可执行文件，**不要 dlopen**。

配套要求（已配好）：

- `app/build.gradle.kts` → `packaging { jniLibs { useLegacyPackaging = true } }`
- 这等价于 `android:extractNativeLibs="true"`。若为 false，`.so` 留在 APK 内不落盘，`nativeLibraryDir` 下没有实际文件，拿不到可 exec 的路径。

### 第三步：端口握手

内核现在把端口写死在 config 里。接壳时改成：

1. `port: 0` 时由内核绑定随机端口
2. 绑定成功后把 `PORT=xxxxx` 打到 stdout 第一行
3. `CoreService` 读这一行，再让 WebView 加载 `http://127.0.0.1:xxxxx/?token=...`

这是壳与内核之间**唯一**的启动握手，必须做对。注意现有 server 有访问令牌，首帧不带 token 会 401。

### 第四步：启动顺序

```
MainActivity 启动
  → 检查 nativeLibraryDir/libcodeforge.so 是否存在（EnvProbe 已在做）
  → startForegroundService(CoreService)
  → CoreService: ProcessBuilder 起内核 + 读 PORT
  → 拿到端口后回调 UI
  → WebView 加载 http://127.0.0.1:<port>
  → 进程退出：退避重启（1s/2s/4s，上限 30s），通知里反映状态
```

`CoreService.onStartCommand` 里已写好注释标注这几步的位置。

---

## 已知限制

1. **只出 arm64-v8a**。x86 模拟器装不上，需另编 `GOARCH=386/amd64`。
2. **targetSdk 36 下不能 exec app 私有目录**。所以所有二进制（内核、未来的 git/busybox）都必须走 `nativeLibraryDir`，数量有限。
3. **proot + rootfs 方案未验证**。proot 最终要 `execve` rootfs 里的程序，那些文件在 app home 目录下，可能被 SELinux 拒绝。这一项是「手机上能不能构建」的生死线，需实测，不能靠推理。
4. **未做真机验证**。当前只验证了构建与 APK 内部结构，界面实际渲染、前台服务存活、WebView 行为都还没跑过。
5. **未接入 WebView**。界面是纯 Compose，没有 WebView 容器，也没有 `?token=` 注入。

---

## 下一步优先级

1. 真机装一次，确认界面渲染与前台服务不崩
2. 跑 `nativeLibraryDir` 的 exec 实验（静态 busybox 改名 `libbb.so`）
3. 修内核三个必改点，放 `libcodeforge.so`，验证端口握手
4. 加 WebView 容器，加载内核 UI
5. 视第 2 步结果决定 proot 路线走不走
