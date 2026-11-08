# CodeForge 中文文档

面向使用者的完整说明：怎么用、怎么工作、为什么这么做。

只看亮点，回主 [README.md](README.md)。英文版见 [README_EN.md](README_EN.md)。

Android 上的 AI 编码工作台。Go 内核负责执行，Rust 引擎负责排版，Compose 负责交互，三种语言，一个进程，一个 17.4 MB 的 APK。

---

## 目录

1. [快速开始](#快速开始)
2. [使用说明](#使用说明)
3. [技术实现](#技术实现)
4. [核心原理](#核心原理)
5. [构建](#构建)
6. [工程纪律](#工程纪律)
7. [已知限制](#已知限制)
8. [许可](#许可)

---

## 快速开始

### 环境要求

| 项 | 要求 |
|---|---|
| 架构 | arm64-v8a 真机，x86 模拟器装不上，Go 内核只交叉编译 arm64 |
| 系统 | Android 8.0，API 26 及以上 |
| 网络 | 无需联网，兼容环境随包携带，首启本地解压 |
| 依赖 | 无需 root，无需 Termux，无需 PC |

### 安装

下载 app-release.apk 侧载安装即可。已用自建固定证书签名，v2 加 v3，后续版本可直接覆盖升级。

换签名等于装不上更新，设备上若是旧 debug 签名包，需先卸载再装。

### 首次启动

1. App 启动后自动拉起内置内核，以 specialUse 前台服务常驻，通知栏可见；
2. 自动解压随包携带的兼容环境，即 proot 加 Alpine rootfs，约十几秒；
3. 首次使用先配置模型，进设置里的模型服务，添加供应商或模型。

---

## 使用说明

### 会话与项目

新建项目：侧边栏加号新建项目，选目录，项目名默认取目录末段，再选默认权限档。权限三档是只读、请求、自主，作为内核 perm 接口的初值。

工作区在会话创建那一刻绑定，之后切换项目不会串上下文。

会话可重命名、归档、取消归档、删除。可恢复的归档与不可恢复的删除在界面上分开摆放。

文件树在新建项目时选目录，或从侧边栏进入，浏览工作区文件。

### 模型接入

设置里的模型服务支持：

- 供应商：Base URL、协议族（openai 兼容或 anthropic）、API Key，掩码输入，可存环境变量
- 模型：模型 id、显示名、上下文上限、输入与输出上限、RPM、三态能力，即支持、不支持、未声明
- 测试连接：填完立刻能验证通不通，不用等到发消息才发现 Key 错
- 探测：让内核自动发现该供应商下可用的模型列表

### 技能、插件、MCP

三类东西在输入框里都用 at 补全：

| 来源 | 形态 | 说明 |
|---|---|---|
| 技能 | 工作区 .codeforge/skills 下每个名字一个 SKILL.md | 自带 frontmatter，含名称、描述、触发词、启用开关，目录名即 at 补全 token |
| 内置插件 | 内核内置功能 | plan、goal_mode、vulnerability_research、reverse_analysis、multi_agent、skill_creator 等，可单独开关 |
| MCP | 外部 MCP 服务 | 只补已加载的，未加载的工具还没注册，at 进去内核也调不到 |

输入 at 会弹出候选列表，可滚动，点选即插入。装得越多，列表越长。

### 内置浏览器与元素挑选

1. 点消息里的链接，选择系统浏览器或内置浏览器打开；
2. 内置浏览器里点选择元素，页面元素可点选；
3. 选中的元素编号进入对话，同时可以在气泡里补充一句说明，不用退出去手动打字；
4. 元素摘要，含标签、id、class、文本、位置、样式，随消息送给模型。

### 模型与智能体

- 权限三档：只读、请求、自主，新建项目时定档，随时可改
- 审批卡片：工具调用逐条确认，附参数与 diff
- 目标模式：目标、轮次、判定、findings 一条状态带
- 子智能体：独立任务、独立上下文、结果回灌
- 检查点与撤销：可回滚到某个步骤

### 后台任务

随手提交脚本或命令，任务在后台跑，面板看状态与输出，随时终止。

### 语音输入

- 系统语音服务，即 SpeechRecognizer，zh-CN
- 云端转写，用户自备 Key，OpenAI 兼容协议

两档路由：云端已配就优先云端，系统引擎判死自动切换，20 秒零结果触发看门狗。

### 兼容环境

设置里的运行环境可一键安装：

| 包 | 安装内容 |
|---|---|
| Python | python3 加 pip |
| Node.js | nodejs 加 npm |
| Go | go |
| C/C++ | build-base，含 gcc 等 |
| Rust | cargo 加 rust |
| Git | git |
| Java 17 | openjdk17-jre-headless |
| FFmpeg | ffmpeg |

已装标记由存在性探测决定。探测命令必须单条执行，带管道的话退出码取自管道末端，恒为 0，等于没探。

### 横竖屏

三档自适应：

| 宽度 | 布局 |
|---|---|
| 小于 600dp | 单栏加抽屉侧栏 |
| 600dp 到 840dp | 双栏 |
| 大于等于 840dp | 加宽双栏 |

横屏下侧栏收起时完全隐藏，只留主界面顶栏的三横线，展开与收起有 220 毫秒宽度过渡动画。

---

## 技术实现

### 分层

| 层 | 位置 | 职责 |
|---|---|---|
| 入口层 | MainActivity.kt、CodeForgeApp.kt | 建窗口、edge-to-edge、通知权限、拉起内核 |
| 界面层 | ui 下的 AppRoot、chat、sessions、browser、settings、theme | 纯 Compose，只读状态，只调 ViewModel 方法 |
| 状态层 | ui/ChatViewModel.kt | 唯一状态源 ChatUiState，把事件折叠成界面状态 |
| 协议层 | core 下的 CoreTransport、HttpCoreTransport | 把内核 JSON 帧翻译成 CoreEvent，共 24 种 |
| 内核层 | libcodeforge.so，独立进程，Go 写的 | 36 个 REST 加 1 个 WebSocket，执行、协议、工作区 |

单向数据流：用户操作到 ViewModel，到 transport，到内核，WS 推事件，transport 解析成 CoreEvent，ViewModel 折叠成 ChatUiState，Compose 重组。

界面层不许碰协议层，状态层不许碰 UI，协议层不许 import 界面层，三条硬边界靠编译期 import 约束保证。

### 内核进程怎么起

CoreService 是 specialUse 前台服务，用 ProcessBuilder 拉起 libcodeforge.so：

1. 环境 HOME 指向 filesDir，SQLite 库落在 .codeforge 目录，CODEFORGE_PORT 设为 0 即随机端口，写死端口会被别的进程占；
2. 内核绑定成功后把 PORT=xxxxx 打到 stdout，服务端逐行扫这个前缀；
3. 拿到端口才创建 HTTP 与 WS 连接。

鉴权走 Cookie，名为 codeforge_token，先 GET 根路径取值，不是 query 参数，这是最容易接错的地方。

### 排版引擎

libopenrender.so，上游是 raine-x 的 OpenRender：

```
原始文本，到增量 Markdown IR，到 Rust 侧排版，到 PNG 或结构化块，到 Compose 渲染
```

- 增量：每次只把新增或变化的段落喂进引擎，已排版部分不重算
- 公式：RaTeX 排版成 PNG，中文靠内嵌 Noto Sans SC 子集，靠 RATEX_UNICODE_FONT 开关
- 表格：列宽按实际渲染宽度分配，不是按字符数加权，表头不再被挤断行
- HTML 块：引擎没有 Html 块类型，裸 HTML 在喂引擎之前就要判定并直接挂 WebView

### 内嵌浏览器

- BrowserOverlay.kt 全屏浏览器，PageBridge.kt 是 JS 桥，window.__cf 提供 inspect、list、click、evaluate、contentHeight、viewport
- HtmlBlockView.kt 是对话流内嵌块，高度按内容实测，夹在 140dp 到 520dp
- HtmlDetection.kt 负责裸 HTML 识别与合并

### 内核接口

内核共 36 个 REST 端点，覆盖配置、权限、会话、工作区、模型、供应商、任务、记忆、技能、插件、MCP、文件树、撤销、diff、健康检查。

客户端调本地服务一律假设首次连接会失败。判活要打业务端点，也就是 health 接口，不能只探 TCP 端口，端口通了不代表 handler 接客了。

### 兼容环境

随包携带 env-kit.tgz，即 proot 5.1.107.101，以及 rootfs-alpine.tgz，即 Alpine 3.24.3 minirootfs，首启解压到 filesDir 下的 rootfs。

内核的 run_command 经 cfrun 在同一棵目录树上执行，路径契约是单根，也就是 CELL 变量。

镜像源走中科大，官方 dl-cdn 国内常超时，版本号从 rootfs 自己读，避免镜像升级后硬编码版本对不上。

### 签名

密钥在 app/keystore/codeforge.keystore，RSA 2048，有效期 10000 天，产物 v2 加 v3。

配置写了不等于产物有。查签名方案要看 apksigner verify 的输出，不看构建脚本，密钥文件不存在时构建会静默回落到 debug keystore，那只有 v2。

---

## 核心原理

### 为什么 targetSdk 28

Android 10 起禁止执行应用可写目录中的文件。内核是一个 ELF 可执行文件，必须能在应用私有目录里跑起来，所以 targetSdk 28 是刻意的，用兼容级别换运行能力，一次明确的取舍。

代价是 Play 政策敌视，但这个 app 本质是在设备上跑任意代码的开发者工具，侧载定位明确。

### 为什么自己写排版引擎

现成方案有三个硬伤：

1. 中文公式，常见引擎遇到中文会退回源码或错位；
2. 流式，整段解析完再排，长回复会等；
3. 离线，依赖远程渲染服务就等于联网才能看公式。

自研链路把这三条全占了，增量 IR 保证流式，嵌入 CJK 字体子集保证中文，全程本地保证离线。

### 为什么 HTML 要在喂引擎之前判定

CommonMark 的 HTML 块在空行处结束。模型输出带缩进的 HTML 文档时，引擎会把它切成碎片，碎片拼不回原文。

所以流程是：先判定是不是整页 HTML，是就直接挂 WebView 绕开引擎，不是才交给引擎。

### 为什么触屏上元素挑选不能靠 hover

触屏没有 mousemove，靠鼠标悬停记住当前选中元素的方案在手机上必然失效。

正确做法是点选：点一下，JS 桥回传元素信息，编号登记进对话。

### 为什么技能 frontmatter 必须是扁平单行

内核的 frontmatter 解析器是逐行 strings.Cut 按冒号切，不是 YAML 解析器。YAML 块标量，也就是 description 后跟竖线再加缩进多行，会被切碎，描述只剩一根竖线。

所以内置技能的 frontmatter 一律改写成内核认的扁平单行形式。

推广：跨进程传结构化元数据前，先确认对面的解析器到底吃哪种语法，「对面支持 YAML」这个假设往往是错的。

### 为什么探测命令不能带管道

gcc --version 管道 head -1 的退出码取自管道末端，也就是 head，恒为 0。于是 gcc 不存在也被判成已安装。

探测命令必须是目标命令自己的单条执行。

---

## 构建

```bash
# 需要 JDK 17 加 Android SDK，compileSdk 36，build-tools 36.0.0
./gradlew assembleDebug
# 产物在 app/build/outputs/apk/debug/app-debug.apk

./gradlew assembleRelease
# 产物在 app/build/outputs/apk/release/app-release.apk
```

| 组件 | 版本 |
|---|---|
| Gradle | 8.14.6，wrapper 自带 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.4.21 |
| Compose UI | 1.9.5 |
| Material 3 | 1.3.2 |
| compileSdk / targetSdk / minSdk | 36 / 28 / 26 |
| ABI | arm64-v8a |

版本是钉死的，不是随手升的，升级要整条链一起动。理由见 AGENTS.md 的版本锁定一节。

### 原生库

Go 内核与排版引擎以**预编译产物**随仓库分发（`app/src/main/jniLibs/arm64-v8a/`），构建 APK
不需要重编它们。两者的源码不在本仓库：重编或获取源码请联系 2653603072@qq.com（见「许可」）。

---

## 工程纪律

规矩写在文件里，不靠口头传承：

| 文件 | 记什么 |
|---|---|
| AGENTS.md | 架构分层、编辑准则、验证标准、提交规范，以及已踩过的坑，编号到 P-053 |
| 工作进程.md | 做到哪、下一步是什么、待决策项 |
| 改动历史.md | 逐次变更，时间、文件、改了什么、为什么、验证到哪一级，只归档不删条目 |

验证分四级，如实标注，没在真机跑过就写运行时未验证。文档比结论可信，结论比文档可信。

### 目录

```
app/src/main/java/com/hxy/codeforge/
  core/      内核传输、协议模型、兼容环境、内置技能
  render/    排版引擎桥接与 IR
  ui/        chat、sessions、settings、common、browser、theme
  voice/     云端语音
app/src/main/jniLibs/arm64-v8a/   Go 内核与 Rust 引擎（预编译产物）
docs/                              方案文档与对齐清单
```

---

## 已知限制

1. 只支持 arm64-v8a，Go 内核只交叉编译 arm64，x86 模拟器装不上。
2. 兼容环境未经真机验证，proot 走 mmap 的 PROT_EXEC 而非 execve，SELinux 对 app_data_file 的 PROT_EXEC 是否放行需要实测，不能靠推理。
3. 多会话并发分桶未实现，网页端按 session_id 分桶隔离流式帧，Android 侧没有这层机制，切会话时后台会话的帧可能画进当前视图。
4. 文件树的引用文件到对话未做，点文件只处理目录。
5. 子智能体没有独立面板，只有消息流内联卡片条与设置页策略。
6. 签名密钥明文写在构建脚本里，侧载自用可以，上架前必须换成离线保管的密钥加环境变量。

---

## 许可

本项目采用 **MIT + 商业授权双许可**（Copyright (c) 2026 raine-x）。

### MIT 许可（默认）

按标准 MIT 许可证使用、复制、修改、合并、发布、分发、再许可与销售——包括商业用途，
前提是保留版权声明与许可声明。随仓库分发的 `libcodeforge.so` 与 `libopenrender.so`
二进制产物同样按 MIT 授权；两者的构建源码不在本仓库，获取方式见下。

### 商业授权（可选）

如需超出 MIT 条款的使用方式——免除署名与来源标注、独家授权、OEM 预装与定制、
源码级交付、保修与技术支持等——联系 **2653603072@qq.com** 获取商业授权。
商业授权与 MIT 并行提供，任选其一；第三方组件不在其覆盖范围内。

### 第三方组件保留原许可

APK 内含的排版引擎（MIT）、Noto Sans SC 字体子集（SIL OFL 1.1）、proot（GPL-2.0 系）、
libtalloc（LGPL 系）与 Alpine rootfs 等第三方组件，著作权归各自作者，按各自许可证发布。
本项目的授权不改变、也不扩及这些组件，完整清单见 LICENSE 的「第三方组件与许可」一节。

### 关于源代码

内核（libcodeforge.so）源代码曾以 GPL-3.0 + 附加条款随旧版本分发；自本版本起著作权人
将其重许可为 MIT，且源码不再随本仓库分发。此前按旧许可获得的副本仍适用其当时的许可。

完整条款与全文见 [LICENSE](LICENSE)。
