# AGENTS.md — AI 工作准则

> 本文件约束所有在此仓库工作的 AI。开始任何任务前先读完，任务结束前对照第 8 节自检。
> 违反第 4 节（三份记录）的产出视为**未完成**。

---

## 1. 这个项目是什么

CodeForge-Go 的 Android 宿主壳。Go 内核是一个 AI 编码 agent（55,446 行），原本配网页界面；本项目把它改成 Android 原生应用。

```
CodeForgeAPP/                       ← 本仓库，管 Android 壳
├── AGENTS.md                          记录一：工作准则（本文件）
├── 工作进程.md                        记录二：待办与已办
├── 改动历史.md                        记录三：变更流水
├── docs/android-native-plan.md        参考：方案与架构（不属于三份记录）
├── app/src/main/java/app/codeforge/   Kotlin + Compose，无 XML 布局
└── source/CodeForge-GO/               Go 内核源码（独立 git 仓库，只读参考）
```

**边界**：`source/CodeForge-GO/` 是参考材料，不是本仓库的代码。要改内核必须明确说明并单独确认——它有独立的 git 仓库和 `AGENTS.md`。

---

## 2. 架构分层

### 2.1 分层与数据流

```
┌─────────────────────────────────────────────────────────┐
│ 入口层   MainActivity.kt / CodeForgeApp.kt               │
│          建窗口、装主题、挂 ViewModel。不含任何业务逻辑     │
└──────────────────────────┬──────────────────────────────┘
                           │ ChatUiState（只读）
┌──────────────────────────┴──────────────────────────────┐
│ 界面层   ui/AppRoot.kt · ui/chat/ · ui/sessions/         │
│          ui/common/ · ui/theme/                          │
│          纯 Compose。只读状态、只调 ViewModel 的方法       │
└──────────────────────────┬──────────────────────────────┘
                           │ 用户动作 → vm.send() / vm.stop() …
┌──────────────────────────┴──────────────────────────────┐
│ 状态层   ui/ChatViewModel.kt                             │
│          唯一状态源 ChatUiState。把事件折叠成界面状态      │
└──────────────────────────┬──────────────────────────────┘
                           │ CoreEvent（上行）/ 接口调用（下行）
┌──────────────────────────┴──────────────────────────────┐
│ 协议层   core/CoreTransport.kt（抽象）                    │
│          core/MockCoreTransport.kt（假数据）              │
│          core/HttpCoreTransport.kt（真实，HTTP + WS）     │
│          把内核的 JSON 帧翻译成 CoreEvent                 │
└──────────────────────────┬──────────────────────────────┘
                           │ 127.0.0.1:<随机端口>
┌──────────────────────────┴──────────────────────────────┐
│ 内核层   libcodeforge.so（独立进程，Go 写的）             │
│          36 个 REST + 1 个 WebSocket                     │
└─────────────────────────────────────────────────────────┘

跨层共用：model/CoreModels.kt（纯数据类）
旁挂服务：core/CoreService.kt（前台服务，管内核进程生命周期）
```

**单向数据流**：用户操作 → ViewModel → transport → 内核 → WS 推事件 → transport 解析成 `CoreEvent` → ViewModel 折叠成 `ChatUiState` → Compose 重组。

### 2.2 各层职责与文件

| 层 | 目录/文件 | 职责 | 行数 |
|---|---|---|---|
| 入口层 | `MainActivity.kt`、`CodeForgeApp.kt` | 建窗口、edge-to-edge、通知权限、拉起 CoreService、启动流水线（§2.6）、挂 ViewModel | 88 |
| 界面层 | `ui/AppRoot.kt` | 横屏=内容旋转接网页式双栏（侧栏+聊天）、「环境准备中」遮罩、设置弹层挂载、顶栏、横幅、空态问候页 | 591 |
| | `ui/chat/MessageList.kt` | 列表、贴底跟随、回到最新 | 210 |
| | `ui/chat/MessageBubble.kt` | 气泡 / 通知行 / 思考折叠 / 操作行（正文块渲染入口，见 OpenRenderMessage） | 419 |
| | `ui/chat/OpenRenderMessage.kt` | OpenRender IR → Compose：全块型/全 span 排版、公式 PNG、媒体围栏路由、流式光标 | ~860 |
| | `ui/chat/MessageBlocks.kt` | 宿主侧媒体渲染：SVG（WebView）/图片/视频/音频/附件缩略图与全屏 | ~480 |
| | `ui/chat/ToolCard.kt` | 工具卡片、diff 着色 | 249 |
| | `ui/chat/ApprovalCardView.kt` | 审批卡片 | 190 |
| | `ui/chat/Composer.kt` | 悬浮输入卡、四态主按钮、双引擎语音路由（系统→离线）、模型/附件弹层 | 704 |
| | `ui/chat/ModelPickerSheet.kt` | 选择模型弹层：四行式子页 + 思考强度拉条（测试模型时模型行静态化） | 820 |
| | `ui/chat/StatusStrips.kt` | 输入卡上方三条状态区：上下文表、任务清单、子智能体卡 | 380 |
| | `ui/sessions/SessionPane.kt` | 会话侧栏（抽屉与侧栏共用；头像=设置入口，胶囊=新建对话） | 268 |
| | `ui/settings/AppSettings.kt` | 设置存储：Compose 状态 + SharedPreferences（旋转开关/灵敏度） | 60 |
| | `ui/settings/SettingsSheet.kt` | 设置弹层：横屏旋转、状态栏显隐、语音引擎状态与重新准备 | 270 |
| | `ui/settings/FileTransferSection.kt` | 文件传输：导入到默认工作区、把工作区打包导出到手机 Download | ~240 |
| | `ui/common/Responsive.kt` | 横竖屏三档判定（优先读旋转后内容尺寸 LocalOrientation） | 63 |
| | `ui/common/Glyphs.kt` | 火焰 Logo（轮廓自动描摹自 🔥 emoji，含燃烧动效）与手绘描边图标库 | 615 |
| | `ui/common/NoRipple.kt` | 无涟漪点击扩展（圆形/胶囊按钮专用，见类注释） | 28 |
| | `ui/common/VoiceInput.kt` | 系统语音引擎封装（SpeechRecognizer，zh-CN） | 153 |
| | `ui/common/DeviceRotation.kt` | 物理姿态检测（迟滞角可配）与内容旋转层（insets 已转 dp，见 P-013） | 232 |
| | `ui/theme/Theme.kt` | 明暗配色（Claude 暖色系）、衬线/无衬线/等宽字体 | 129 |
| 状态层 | `ui/ChatViewModel.kt` | 单一状态源、事件折叠、乐观更新、按内核状态绑定 HttpCoreTransport | 412 |
| 协议层 | `core/CoreTransport.kt` | `CoreEvent` 22 种 + `CoreTransport` 接口 | 142 |
| | `core/MockCoreTransport.kt` | 假内核，驱动界面开发（含通知行/todo/子智能体/上下文全演练） | 365 |
| | `core/HttpCoreTransport.kt` | OkHttp HTTP + WS，帧解析（对照 ws_handler 22 种下行帧） | 510 |
| | `core/TestModelTransport.kt` | 内置测试模型（GLM-4.7 Flash 直连 BigModel，SSE 流式）：免内核免供应商的基础回复兜底 | 259 |
| 语音层 | `voice/OfflineVoice.kt` | Vosk 离线引擎 + 首启解压流水线（§2.6 的「环境准备中」） | 300 |
| | `voice/CloudVoice.kt` | 云端识别（硅基流动 OpenAI 兼容接口）：AudioRecord 16k PCM → WAV → multipart 上传 | ~330 |
| 服务层 | `core/CoreService.kt` | specialUse 前台服务：拉起 libcodeforge.so、PORT 握手、日志落盘、退避重启（`KernelState` 在同文件） | 289 |
| | `core/KernelConfig.kt` | 设置页 ↔ 内核 local.yaml 的桥（llm 段写入；首启种子见 CodeForgeApp） | 46 |
| | `core/EnvKit.kt` | 统一兼容环境：proot+Alpine rootfs 首启解压、路径契约（$CELL 单根）、cfrun 生成、kernelEnv 注入、软件包安装（apk 预设+probe+DNS） | 300 |
| | `core/KernelApi.kt` | 内核 REST 客户端（config/perm/compress/sessions/subagents/notify/workspace），Cookie 同 WS | 220 |
| 模型层 | `model/CoreModels.kt` | 纯数据类，无逻辑无 Android 依赖 | 111 |

渲染引擎层（旁挂原生库）：
| 目录/文件 | 职责 |
|---|---|
| `render/OpenRenderNative.kt` | `libopenrender.so` 的 JNI 封装（含 RaTeX 开关）；加载失败进程级判死，界面退纯文本 |
| `render/RenderIr.kt` | IR 数据类、delta 应用器、`\(\)`/`\[\]` 公式定界符归一、按消息前缀 diff 喂引擎 |
| `tools/openrender/` | 可复现构建：`app_jni.rs` JNI 桥补丁 + `build-android.ps1`（固定上游 commit） |

语音层说明：`voice/` 不属于协议层（不碰内核帧），是跨界面能力，被 `ui/chat/Composer.kt`
（输入）与 `ui/settings/SettingsSheet.kt`（状态展示）共同消费；不引 Compose 之外的 Android UI。

渲染引擎层说明：OpenRender 只做 Markdown → IR（块 + 行内 span），不画像素。排版与媒体
播放由 `ui/chat/` 完成；`core/`、`ChatViewModel` 不许依赖它（渲染是纯界面能力）。

### 2.3 层间规则（硬性）

1. **界面层不许碰协议层**。`ui/` 下任何文件都不许 `import app.codeforge.core.HttpCoreTransport`。要新数据就加 `ChatUiState` 字段。
2. **界面层不许有自己的业务状态**。展开/折叠这类纯视觉状态可以用 `rememberSaveable`；会话、消息、运行状态一律来自 `ChatUiState`。
3. **状态层不许碰 Android UI**。`ChatViewModel` 只依赖 `CoreTransport` 和 `model`，不引 Compose。
4. **协议层不依赖界面**。`core/` 不许 import `ui/`。事件用 `CoreEvent` 表达，不把原始 JSON 透传给上层。
5. **模型层保持纯粹**。`CoreModels.kt` 只有 data class 和 enum，不放逻辑、不放 Android 类型。
6. **服务层不参与业务**。`CoreService` 只管进程的拉起、存活、重启，不解析对话内容。
7. **渲染引擎层只服务界面**。`render/` 不依赖 `ui/`、`core/`、`ChatViewModel`；`core/` 也不许 import `render/`。

### 2.4 改功能该动哪层

| 想做什么 | 改哪里 |
|---|---|
| 改气泡样式、间距、圆角 | `ui/chat/MessageBubble.kt` |
| 改横竖屏断点或分栏宽度 | `ui/common/Responsive.kt`、`ui/AppRoot.kt` |
| 改「横持旋转」行为（阈值/迟滞/动画） | `ui/common/DeviceRotation.kt`（窗口锁竖屏在 manifest，别改回系统旋转） |
| 改语音输入（语言/交互/降级文案） | `ui/common/VoiceInput.kt` → `ui/chat/Composer.kt`（三引擎路由：云端 → 系统 → 离线） |
| 改云端语音配置（供应商/模型/Key） | `ui/settings/AppSettings.kt` 的 `sttCloud*` + `ui/settings/SettingsSheet.kt` 的 VoiceSection + `voice/CloudVoice.kt`（云端启用时 `Composer` 会调 `OfflineVoiceEngine.releaseModel()` 释放 Vosk 内存） |
| 改输入框行为（多行、发送键） | `ui/chat/Composer.kt` |
| 改贴底跟随 / 回到最新 | `ui/chat/MessageList.kt` |
| 改配色、字体、深色模式 | `ui/theme/Theme.kt` |
| 改 Markdown 解析范围（CommonMark/GFM/公式） | 上游引擎源码（`tools/openrender/build-android.ps1` 固定 commit）→ 重编 `.so`；壳侧解析在 `render/RenderIr.kt` |
| 改正文排版（标题/列表/引用/表格/代码块/公式外观） | `ui/chat/OpenRenderMessage.kt` |
| 改公式定界符兼容（`$`/`$$`/`\(\)`/`\[\]`） | `render/RenderIr.kt` 的 `normalizeMathDelimiters`（注意跳过代码围栏与 `$$` 内的 `\\[2pt]`） |
| 改回复闪烁/流式滚动/新问题定位 | `ui/chat/MessageList.kt`（勿回加 animateItem，见改动历史 22:25 条） |
| 改消息块渲染（SVG/图片/视频/音频/原样开关） | `ui/chat/MessageBlocks.kt`（宿主媒体渲染）+ `model/CoreModels.kt` 的 verbatim 字段 |
| 改工具卡片展开逻辑、diff 着色 | `ui/chat/ToolCard.kt` |
| 改上下文表 / 任务清单 / 子智能体卡 | `ui/chat/StatusStrips.kt` |
| 改选择模型弹层（供应商/模型列表/思考拉条/权限） | `ui/chat/ModelPickerSheet.kt` |
| 改设置（旋转开关/灵敏度/语音引擎） | `ui/settings/AppSettings.kt`（存储）+ `ui/settings/SettingsSheet.kt`（弹层） |
| 改状态栏显隐（默认隐藏） | `AppSettings.showStatusBar`（存储）+ `MainActivity.kt`（WindowInsetsController） |
| 改附件选择/上传/预览 | `ui/chat/Composer.kt`（stageAttachment → outbox）+ `ui/ChatViewModel.kt`（uploadAttachments → `/api/upload_file`）+ `ui/chat/MessageBlocks.kt`（`LocalAttachmentRoot` 解析相对路径） |
| 改手机文件导入/导出（sdcard） | `core/EnvKit.kt` 的文件传输段 + `ui/settings/FileTransferSection.kt` |
| 改消息滚动/自动跟随 | `ui/chat/MessageList.kt`（尾部只能 1dp 哨兵，勿回加 `fillParentMaxHeight`） |
| 改后台脚本任务（启动/列表/取消/日志） | Go 内核 `pkg/tasks`（进程组、输出环形缓冲）+ `pkg/server/tasks.go`（REST/WS）+ `ui/chat/BackgroundTasksSheet.kt` + `ChatViewModel.refreshTasks/startTask/killTask` |
| 改内核运行设置（生成/重试/压缩/子智能体/通知） | `ui/settings/KernelSettingsSection.kt` → `ChatViewModel` 服务操作 → `core/KernelApi.kt` |
| 改首启「环境准备」流程 | `voice/OfflineVoice.kt` + `CodeForgeApp.kt`（注册处），并同步 §2.6 |
| 改审批卡片字段或按钮 | `model/CoreModels.kt` → `ui/chat/ApprovalCardView.kt` → `core/HttpCoreTransport.kt` 的 `hitl_request` 分支 |
| **新增一种内核事件** | ① `core/CoreTransport.kt` 加 `CoreEvent` 子类 ② `ui/ChatViewModel.kt` 的 `onEvent` 加分支 ③ `core/HttpCoreTransport.kt` 的 `handleFrame` 加解析（帧名/键名以 `pkg/agent/agent.go` 常量表与 `pkg/server/ws_handler.go` 为准，注意 P-008 的键名陷阱） |
| 改协议字段名 / 新增字段 | `core/HttpCoreTransport.kt` 的 `handleFrame` 与 `parse*` 系列 |
| **接上真内核** | `core/CoreService.kt`（拉起进程读端口）→ `MainActivity.kt`（启动服务）→ `ChatViewModel` 把 `MockCoreTransport` 换成 `HttpCoreTransport` |
| 加新页面（设置、模型管理） | `ui/` 下新建包，在 `ui/AppRoot.kt` 挂入口 |
| 加新的状态字段 | `ui/ChatViewModel.kt` 的 `ChatUiState` + `onEvent` 的对应分支 |

### 2.5 尚未存在的层（规划中，不要提前实现）

| 层 | 计划位置 | 说明 |
|---|---|---|
| 工具链层 | 未定 | Python（Chaquopy）/ Java（ECJ+D8）/ Go（proot）。按 `docs/android-native-plan.md` 第 5 节推进，**动手前必须先跑 E1/E2 实验** |
| 设置层扩展 | `ui/settings/`（已起步） | 已有旋转/语音设置；P1 继续对应 `/api/models/*`、`/api/providers/*` 等 11 个端点 |

### 2.6 启动流水线与「环境准备中」

首启步骤全部注册在 `CodeForgeApp.kt` 的 `onCreate`，顺序固定，新增步骤必须同步更新本节：

1. `AppSettings.init` —— 读持久化设置（旋转开关/灵敏度），必须在首帧前完成；
2. `OfflineVoice.ensureReady` —— **首启「环境准备中」**：把 APK 内置的离线语音模型
   （`assets/voice-model-zh.zip`，zip 在 build.gradle 里声明 `noCompress` 才能拿到
   精确长度显示百分比）解压到 `filesDir/voice-model-zh`，期间 AppRoot 盖
   「环境准备中」遮罩（星标 + 进度条），成功后写 `.ready` 标记，后续启动秒过；
   失败不挡屏，设置页可看原因并重试；
3. 通知渠道 —— 由 `CoreService.onStartCommand` 的 `ensureChannel()` 创建；内核进程的拉起/
   PORT 握手/退避重启也在 CoreService（握手协议见其类注释）。
4. `EnvKit.ensureReady` —— 兼容环境：解压 assets 里的 env-kit.tgz（proot）与
   rootfs-alpine.tgz（Alpine minirootfs）到 filesDir，生成统一执行入口 `usr/bin/cfrun`；
   进度并入「环境准备中」遮罩；`.ready` 标记跳过。**环境契约**（路径单一事实源）
   见 EnvKit 类注释：一切锚定 filesDir 单根，proot 以同路径绑定 HOME/tmp，
   内核经 CF_EXEC_WRAPPER 走 cfrun，内外同一棵目录树。

规则：任何「首启要做、之后跳过」的环境准备都走这条流水线——状态用
`voice/OfflineVoice.kt` 的 Status 模式（Idle/Preparing/Ready/Failed），
**不要各自发明**；解压类步骤的目标目录一律 `filesDir` 下独立子目录 + 就绪标记。

---

## 3. 开工前必读

1. `工作进程.md` —— 现在做到哪、下一步是什么
2. 本文件第 2 节（架构分层）与第 9 节（踩过的坑）
3. `docs/android-native-plan.md` —— 架构为什么这么设计、有哪些已知风险
4. 要改的文件本身

**不许**只看待办清单就动手。待办里没写的约束，往往在分层规则和坑列表里。

---

## 4. 三份记录（强制）

每份只管一件事，**不要混写**。

### 4.1 `AGENTS.md` —— 工作准则本身

**管**：架构分层、编辑准则、验证标准、提交规范、已知的坑、禁止事项
**不管**：进度（去工作进程）、逐次改动（去改动历史）

**必须写的时机**：
- 踩到新坑并修掉 → 第 9 节新增 `P-00X`
- 新增或调整架构层 → 第 2 节同步更新（含「改功能该动哪层」表）
- 发现准则有缺口（某类操作反复出错）→ 补充对应章节
- 项目约束变化（版本锁定、ABI、targetSdk）→ 更新第 9 节末尾的约束表

**写法**：只写**可执行的规则**，不写背景故事。每条坑必须包含四段——现象、根因、修法、如何避免（抽象成一条可复用规则）。

### 4.2 `工作进程.md` —— 还有什么没做

**管**：待办清单、已办摘要、待决策项、已知问题
**不管**：具体改了哪个文件（去改动历史）、架构为什么这么设计（去方案文档）

**必须写的时机**：
- 完成任意一项待办 → 把 `- [ ]` 改成 `- [x]`
- 发现新待办 → 加进对应分组
- 状态发生实质变化（比如从「未验证」变成「已验证」）→ 更新「一句话现状」
- 方案有调整 → 更新「待决策」

**格式**：用 `- [ ]` / `- [x]`，分组编号用 A/B/C…，每组内按依赖顺序排。

### 4.3 `改动历史.md` —— 已经改了什么

**管**：每次改动的时间、文件、类型、改了什么、为什么
**不管**：还没做的事（去工作进程）

**必须写的时机**：**任何**文件被创建、修改、删除或重命名之后。包括文档本身。

**格式**（文末有可复制模板）：

```markdown
## YYYY-MM-DD HH:MM · 标题

### 描述
为什么做这件事，解决什么问题。

### 涉及文件
| 时间 | 文件 | 类型 | 改动内容 |
|---|---|---|---|
| HH:MM | path | 新增/修改/重写/删除 | 具体改了什么，关键位置 |

### 验证
跑了什么，结果如何。**没验证的部分必须写明。**
```

**硬性要求**：
- 按时间**正序**追加，不覆盖旧条目
- 「改动内容」要具体到「哪一行/哪个函数改成了什么」，不能写「优化了代码」
- 「验证」栏不许写「应该没问题」

---

## 5. 编辑准则

### 5.1 改之前

- **先读再改**。要改的文件必须先完整读一遍，不许凭文件名猜内容。
- **先查再问**。能在代码/文档里找到答案的，不要问用户。
- **确认程序状态**。涉及「正在运行的程序」时，先确认没在跑再动手。
- **确认改哪层**。按 2.4 的表定位，不要跨层改。

### 5.2 改的时候

- **最小改动**。不顺手重构无关代码，不顺手改格式。想重构就单独提。
- **保持既有风格**。注释用中文、解释「为什么」而不是「做什么」、不用 emoji。
- **不删 `.workbuddy-ai/`**。那是项目数据目录，不是缓存。
- **新增依赖要说明**。在 `改动历史.md` 里写清为什么加、版本、体积影响。

### 5.3 注释怎么写

这个项目的注释有个固定取向：**解释「为什么这样」和「不这样会怎样」，不复述代码**。

```kotlin
// 好：说清了取舍
// 只用「用户手指在拖」来判定，不能用 isScrollInProgress ——
// 后者把程序化滚动也算进去，流式跟随里我们自己调的 scrollToItem 会让它
// 短暂为 true，此时 isAtBottom 还是 false，autoFollow 被误设成 false。

// 差：复述代码
// 判断是否被拖动
```

---

## 6. 验证准则

**「构建成功」不是结论。** 这是本项目的硬规矩。

**构建完成后必须做**：在回复里给出本次 APK 产物目录、文件名与大小
（debug：`app/build/outputs/apk/debug/`，release：`app/build/outputs/apk/release/`），
并注明验证到第几级。不给产物路径的「构建成功」视为没交付。

按可靠性从高到低：

1. **跑起来断言** —— 装上真机，实际操作，看结果
2. **读产物内部结构** —— 解包 APK、`aapt2 dump`、检查 `kotlin-classes`、看 dex
3. **A/B 对照** —— 改前改后各跑一次，对比差异
4. **静态检查** —— 读代码、查 API 签名

拿不准就说拿不准，**不要用「应该」「理论上」把不确定包装成确定**。

`改动历史.md` 的「验证」栏必须如实反映用了哪一级。没跑过运行时，就要写「运行时未验证」。

---

## 7. 提交规范

**首行一句概括，正文分条列改动点。**

- 每一条**只保留第一句话**，不展开第二句或补充说明
- 不要写成「做了什么/怎么做的」的长段落

```
修复贴底跟随在流式输出时中断

- isScrollInProgress 把程序化滚动也算成用户拖动
- 改用 interactionSource.collectIsDraggedAsState 判定
- scrollToItem 不等于滚到底，末尾补 tail item
```

**其它**：
- 不要 `git add -A`。用户常同时持有未提交的在途改动。
- 不要 `git push`。本机没有远端凭据，推送由用户自己在终端做。
- 不要 `--no-verify` 跳过钩子。

---

## 8. 任务结束前自检

逐条确认，任何一条为「否」都算没做完：

- [ ] `工作进程.md` 里对应项已勾选或已新增
- [ ] `改动历史.md` 已追加本次全部改动（含文档本身）
- [ ] 若改了分层或踩到新坑，`AGENTS.md` 第 2 / 第 9 节已同步
- [ ] `改动历史.md` 的「验证」栏如实写了跑到哪一级、什么没验证
- [ ] 没有跨层改（界面层没碰协议层，协议层没引界面层）
- [ ] 没有 `git add -A`、没有尝试 `git push`
- [ ] 回复里说清了：改了什么、验证到什么程度、还剩什么没做

---

## 9. 已经踩过的坑

改代码前扫一眼，这些是已经付过代价的。

### P-001 · 贴底跟随不能用 `isScrollInProgress`

`ui/chat/MessageList.kt`

这个 API **把程序化滚动也算进去**。流式输出时我们自己每 20ms 调一次 `scrollToItem`，会让它短暂为 `true`；此刻列表还没到底，`isAtBottom` 为 `false`，于是 `autoFollow` 被误设成 `false`，表现为「流式输出几行后就不跟了」。

修法：改用 `listState.interactionSource.collectIsDraggedAsState()`，它只反映手指拖动。

**推广**：凡是「区分用户操作 vs 程序操作」的场景，`isScrollInProgress` 都不够用。

### P-002 · `scrollToItem(lastIndex)` 不等于滚到底

`ui/chat/MessageList.kt`

它把第 index 项的**顶部**对齐视口顶部。最后一条比一屏高时（长代码块、长 diff），滚完还是看不到结尾。

修法：列表末尾垫一个 1dp 的 `item(key="__tail__")`，滚到它。

**推广**：LazyColumn 里任何「滚到底」需求都要靠尾部哨兵。

### P-003 · 内核鉴权是 Cookie，不是 `?token=`

`core/HttpCoreTransport.kt`

`pkg/server/server.go` 的 `validToken()` **只读名为 `codeforge_token` 的 Cookie**，完全不看 query 参数。令牌由 `handleRoot`（GET `/`）通过 `Set-Cookie` 下发。

修法：连接顺序必须是**先 GET `/` 收 Cookie，再连 `/ws`**。

**推广**：看到「401 但 token 明明是对的」，先查是不是少了拿 Cookie 那步。

### P-004 · `Row` 里的长文本要加 `weight(1f)`

`ui/common/Markdown.kt`

`Row` 里放两个 `Text`（圆点 + 内容），内容那个不加 `weight` 就不受约束，超出部分直接被裁。中文长句尤其明显。

### P-005 · `dataSync` 前台服务在 Android 15+ 有 6 小时/天上限

`AndroidManifest.xml`（待改）

targetSdk 35+ 的 app，`dataSync` 类型前台服务每天累计只能跑 6 小时，超时由系统调 `Service.onTimeout()` 强制停止。

修法：改 `specialUse` + `FOREGROUND_SERVICE_SPECIAL_USE` 权限 + `<property>` 声明用途。

**推广**：任何「常驻后台」的服务，先确认 `foregroundServiceType` 有没有时长配额。

### P-006 · 本仓库不是 git 仓库

有 `.gitignore` 但没有 `.git`，`git status` 报 `not a git repository`。无法 `git diff` 核对改动，也无法回滚。待用户决定是否 `git init`。

### P-007 · KDoc 注释里出现 `*/` 会提前终止块注释

`core/CoreTransport.kt`

**现象**：编译报一串 `Expecting a top level declaration`，定位到注释中间的行。
**根因**：注释里写了 `tool_*/hitl_request` —— `*/` 是块注释终止符，注释在 `tool_*` 后就结束了，后半行变成非法代码。
**修法**：把 `tool_*/` 展开写成 `tool_pending/tool_call/`。
**推广**：KDoc 里写「通配路径」类文字（`foo*/bar`、`a/**/b`）时必须展开或改用「…… 等」表述；编译报「注释行语法错」先查这个。

### P-008 · 内核同名帧的载荷键不统一

`core/HttpCoreTransport.kt`

**现象**：按 `ready` 帧的键名抄到 `sessions` 帧，解析永远拿空列表。
**根因**：`ready` 帧里会话数组叫 `sessions`，而 `sessions` 帧里同一个数组叫 `items`；`warn` 帧的错误文本键是 `error`（与 `error` 帧相同），不是 `text`。agent 事件常量表（`agent.go`）与 ws_handler 控制帧是两拨构造点，键名各定各的。
**修法**：`sessions` 帧按 `items` 解析（留 `sessions` 键兜底）；`warn` 按 `error` 取。
**推广**：对照契约时必须逐帧核对**构造点的 map 字面量**，不能从「相似帧」类推键（2026-10-25 第三证：/api/models/list 的载荷是 {models, providers, active} 三键，不存在 items —— REST 同样适用本条。）

### P-009 · 锁竖屏 + 内容自旋转的两个边界

`ui/common/DeviceRotation.kt` / `AndroidManifest.xml`

**现象**：① 旋转后内容偏出一块；② 横持时键盘与底部弹层不跟着转。
**根因**：① `graphicsLayer` 绕**内容自身中心**旋转，对调宽高后若不 `align(Alignment.Center)`，内容中心与窗口中心错开；② 系统输入法窗口与 M3 `ModalBottomSheet` 是**独立窗口**，应用层的旋转变换管不到它们——这是「窗口锁竖屏」路线的固有边界，不是 bug。
**修法/对策**：① 对调尺寸的盒子必须居中；② 接受此限制并记入已知问题：横持打字用语音输入补位，弹层操作建议回竖持。
**推广**：凡「应用自己转内容」的方案，先盘点哪些 UI 归系统窗口管（IME、弹层、菜单、Toast），它们都不随内容转。

### P-010 · 传感器方位角 → 内容旋转方向的映射不能推导，必须真机校准

`ui/common/DeviceRotation.kt`

**现象**：真机横持，界面旋转方向与手相反（画面倒了 180°）。
**根因**：`OrientationEventListener` 的方位角参考系与屏幕坐标系手性不同，纸面推导
`R(θ)·up = 重力反方向` 会差一个符号；初版按推导写，方向整个反掉。
**修法**：真机实测后翻转映射（传感器角 90° → 内容 -90°，270° → +90°）。
**推广**：凡是「物理量 → 屏幕变换」的映射（旋转/缩放/位移），一律真机校准后才算数；
迟滞阈值同理（P-010 配套：灵敏度三档就是留给校准后调的）。

### P-011 · assets 里的压缩包拿不到精确长度，除非声明 `noCompress`

`app/build.gradle.kts` / `voice/OfflineVoice.kt`

**现象**：`AssetFileDescriptor.openFd` 对默认资源抛 `FileNotFoundException`，
「环境准备中」无法显示百分比。
**根因**：aapt 默认会再压一层已知后缀（含 zip），压缩后的资源在 APK 里没有连续
字节区间，只能走 `assets.open` 流式读，拿不到总长。
**修法**：`androidResources { noCompress += "zip" }`，资源以存储态进包，
`openFd().length` 即精确大小。
**推广**：任何需要「读 assets 进度/随机访问」的资源都要进 noCompress 白名单；
代价是包体里这块不压缩，大文件要掂量。

### P-012 · AAPT2 会自动解压 `.gz` 后缀的资产并去后缀

`app/src/main/assets/`

**现象**：assets 放 `x.tar.gz`，APK 里变成 `x.tar`（内容已被解压），运行时 `assets.open("x.tar.gz")` 直接 FileNotFound。
**根因**：AAPT/AAPT2 的历史行为 —— 以 `.gz` 结尾的资产在打包时自动 gunzip 并截掉扩展名，`noCompress` 拦不住。
**修法**：资产改 `.tgz` 后缀（不受影响）；需要 openFd 精确长度的（语音模型）用 `.zip` + noCompress。
**推广**：往 assets 放任何压缩包先查扩展名黑名单（.gz 是坑，.zip/.tgz/.tar 安全）。

### P-013 · `WindowInsets.get*()` 返回像素，直接 `.dp` 当 dp 用 = 按密度放大 N 倍

`ui/common/DeviceRotation.kt`（mappedInsets）

**现象**：第一屏顶栏悬在半空（距状态栏 ~3 倍状态栏高度）；键盘一弹整屏内容被推出视口、只剩键盘（真机录屏「输入框被弹飞」）。
**根因**：`WindowInsets.statusBars/ime.getBottom(density)` 返回的是**像素**，原代码 `PaddingValues(l.dp, t.dp, ...)` 直接把像素数值当 dp —— 密度 2.75 的真机上一切 inset 被 ×2.75：键盘 ~800dp 变 ~2200dp（两倍多屏高），整个 Column 被推飞。
**修法**：`with(density) { px.toDp() }` 转换后再进 PaddingValues。
**推广**：凡是 `get*` 系（`getTop/getBottom/getLeft/getRight`）拿到的都是 px；要 dp 必须过 `Density.toDp()`。「物理量 → 屏幕变换」的映射一律真机校准（同 P-010）。

### P-014 · proot 挪出 Termux 目录后必须显式给 `PROOT_TMP_DIR` 与 `PROOT_LOADER`

`core/EnvKit.kt`（cfrunScript）

**现象**：真机点任何软件包安装全挂，日志三连：`can't create glue rootfs` → `warning: binding "/system/lib64": No such file or directory`（部分 ROM）→ `execve("/usr/bin/env"): Permission denied`。
**根因**：Termux 构建的 proot 把两个关键路径 bake 死在自己数据目录：临时目录默认 `/tmp`（Android 没有、不可写，glue rootfs 建不出来，绑定与 exec 连锁失败）；loader 路径默认 `/data/data/com.termux/files/usr/libexec/proot/loader`（我们装在 `$CELL/usr/libexec/proot/loader`，不重指则 guest 动态二进制起不来）。另：宿主不存在的路径硬 `-b` 绑定会告警并拖垮 glue。
**修法**：cfrun 里 `export PROOT_TMP_DIR=$CELL/tmp`（先 `mkdir -p`）、`export PROOT_LOADER=$USR/libexec/proot/loader`、绑定一律先 `[ -e ]` 探测存在再绑；环境在宿主侧 export 直传 guest，不经 `/usr/bin/env` 中转（少一个 exec 失败点）。
**推广**：搬用任何 Termux 构建的原生工具（proot/其他），先盘点它编译期 bake 死的绝对路径，逐个用环境变量重指到自己的安装根。同理：**落盘的生成脚本不会随 APK 自动更新** —— 升级脚本逻辑必须配「已就绪也重写」的下发层（EnvKit.ensureReady 已加，runInRootfs 执行前 syncCfrun 兜底）。

### P-015 · 并行会话同仓开发：补齐在途半成品前先全仓查重

全仓（多文件）

**现象**：并行会话在 MessageBubble.kt 引用了未定义的附件组件（LocalAttachmentThumb 等），我按旧先例新建 AttachmentMedia.kt 补齐 → 编译报 Conflicting overloads —— 并行会话几乎同时把同名组件写进了 MessageBlocks.kt。本轮还有三次「刚修完又被它改」的往返（ToolCard 缺 import、Composer 草稿签名）。
**根因**：两个会话在同一个工作树并行写码，文件 mtime 检查有滞后；「缺什么补什么」没有先确认对方是否正在写同一符号。
**修法**：删除我方重复文件让位；对在途半成品只做最小补齐（缺 import 补 import、无效语法改等效语法、签名加默认参数），不新建可能与对方撞名的组件文件。
**推广**：见到「unresolved reference 指向并行会话刚改过的文件」时：① 先 `grep -rn 符号名` 全仓确认无人已在别处实现；② 若需新建组件文件，先等 40 秒无文件动静再动手；③ 补齐优先「改既有文件」而非「建新文件」——建新文件的撞名成本远高于改文件。

### P-016 · 库加载失败的二次异常是 NoClassDefFoundError，catch Exception 拦不住

`voice/OfflineVoice.kt`（OfflineVoiceEngine.start）

**现象**：16KB 页设备点语音，第一次弹「原生库加载失败」提示，之后每次点击**直接崩进程**，崩溃日志 `NoClassDefFoundError: org.vosk.LibVosk`（主线程触摸事件里）。
**根因**：类静态初始化器里的 `System.loadLibrary` 失败一次后，JVM 把该类标记为初始化失败，后续引用抛 NoClassDefFoundError / ExceptionInInitializerError —— 都是 **LinkageError** 家族，不是 Exception 子类；原 catch 只接 UnsatisfiedLinkError 和 Exception，两扇门都漏。另一根因是 libvosk.so 0.3.47 全段 4KB 对齐（16KB 页设备 dlopen 必败）——升 vosk-android 0.3.75（全段 0x4000）修根。
**修法**：catch 整个 `LinkageError`；配进程级 `nativeDead` 标记，失败一次本进程内彻底绕开该库（类初始化器不会重跑，再碰必再抛）。
**推广**：任何 System.loadLibrary 依赖的库，catch 面必须盖到 LinkageError；失败要记进程级标记而不是重试。引入带原生库的依赖先解包看 PT_LOAD p_align（16KB 页时代 ≥0x4000 才行）。

### P-017 · 经 bash+python 管道打的补丁，反斜杠会被各转义层吃掉一层

全仓（本轮本会话与并行会话同坑三次）

**现象**：补丁脚本里写的双反斜杠 n / 双反斜杠 s 落到 .kt 文件变成真实换行 / 单反斜杠 —— Kotlin 报 Unsupported escape sequence、字符串里裸换行、正则失效。并行会话甚至把 Python 的 r 前缀三引号语法当 Kotlin 写进 MessageBlocks。
**根因**：工具调用参数本身过一层 JSON 转义，bash heredoc、python 字符串字面量又各有一层 —— 手数反斜杠必错。
**修法**：含反斜杠/正则的代码一律用 Edit/Write 工具直写（单反斜杠直达）；必须走 python 时用 chr(92) 构造反斜杠、Kotlin 正则改三引号原始串免转义。
**推广**：凡是补丁内容含反斜杠，先想「最终文件里应该是什么」，再倒推每一层吃几刀；编译器报 Unsupported escape sequence 优先怀疑这个。

### P-018 · release 包里资源被 R8 改名，`res/` 下找不到原名是正常的

`app/build/outputs/apk/release/app-release.apk`

**现象**：解包 release 包，`res/` 里搜不到 `ic_launcher`，容易误判「图标没打进包」。debug 包能搜到，因为 debug 不启资源收缩。

**根因**：`isShrinkResources = true` 时 R8 会把资源重命名为短名（本例 `mipmap-anydpi-v26/ic_launcher.xml` → `res/BW.xml`），原始名称与路径折叠进 `resources.arsc`，manifest 里的引用也同步改写。

**验证方法**（不要靠文件名猜，一律走 aapt2）：

```bash
AAPT=/d/AndroidSDK/build-tools/36.0.0/aapt2.exe
"$AAPT" dump badging app-release.apk | grep "^application:"        # icon='res/BW.xml'
"$AAPT" dump xmltree --file res/BW.xml app-release.apk             # 看 adaptive-icon 三层
"$AAPT" dump resources app-release.apk | grep 0x7f020004           # 确认引用非断链
```

三层齐全 + 两个引用能解析出 `color/ic_launcher_background` / `drawable/ic_launcher_foreground`，才算证明图标在包里。

**推广**：release 包里任何资源都不能靠文件名找；「资源不存在」的结论必须由 `dump resources` 支撑，不能由 `unzip -l | grep` 支撑。

### P-019 · 连本地服务不能押注一次连接

`core/HttpCoreTransport.kt`

**现象**：通知栏稳定显示「内核运行中 · 127.0.0.1:42083」，设置页却写「内核未连接」。内核进程是好的，UI 侧没绑上。

**根因**：三层「一次性」叠加。
1. `connect()` 只跑一次，`onFailure` 发个 `Failure` 事件就永久放弃 —— 内核刚 bind 完、accept 还没就绪的毫秒级窗口足以打死它；
2. `connected` 只由 WS 的 `ready` 帧驱动，ready 帧缺席（解析失败/版本差异/压根没发）时界面永远显示未连接，哪怕消息其实能收发；
3. REST 是**另一条独立链路**（独立 client 与 CookieJar），`KernelApi.init()` 同样只试一次，它失败就导致 `/api/config` 拉不到、设置页永久显示未连接。

**修法**：
- 传输层改自愈循环：`/api/health` 探活 → GET `/` 取 Cookie → 连 WS → 挂起等断 → 退避 1/2/4/8s 重连；连上稳定 30s 后断才重置退避；
- 加 `CoreEvent.Link(connected)`，WS `onOpen` 即算连上，与 `Ready`（业务首帧）分离；
- REST 初始化失败按 1/2/4s 重试 4 次；
- `disconnect()` 不 `shutdown` client，否则重连时 client 已死。

**推广**：客户端连本地服务（内核、proot、opencode serve）一律假设**首次连接会失败**。判活要打业务端点（`/api/health`），不能只探 TCP 端口——端口通了不代表 handler 接客了。

### P-020 · Alpine 仓库源：空文件、缺 v、单镜像，三个坑连着踩

`core/EnvKit.kt` 的 `ensureNet`

**现象**：环境软件包全部装不上，`apk add` 报 `unable to select packages: python3 (no such package)`。

**三层根因**：
1. **minirootfs 的 `/etc/apk/repositories` 是空文件，不是不存在** —— `mirrored ?: 默认源` 只在 null 时兜底，空字符串原样写回，仓库源永远是空的。用 `ifBlank { 默认源 }`。
2. **版本目录必须带 `v`** —— `/etc/alpine-release` 内容是 `3.24.3`，取前两段得 `3.24`，但镜像路径是 `v3.24`。少了 v 所有镜像的 APKINDEX 全 404。
3. **单镜像不够** —— 中科大在移动网络下对 APKINDEX 返回 404（同一 URL 从 PC 访问是 200，疑似运营商劫持/限流）。默认写三家并列，apk 会自动跳过失败的。

**推广**：写「默认配置文件」时，判断依据要覆盖「空文件」这种状态；拼版本号要先确认目标端的格式前缀。

### P-021 · apk 在 proot 下写不了数据库，但包是好的

`core/EnvKit.kt`（proot 参数）、`ui/settings/EnvPackagesSection.kt`（结果判定）

**现象**：`apk add` 把 35 个包全部下载安装完（`(19/19) Installing py3-pip`），最后一行
`ERROR: System state may be inconsistent: failed to write database: Permission denied`，退出码非 0。
但 `python3 --version` 正常、`print(1+1)` 输出 2 —— **包完全可用**。

**已排除**：文件权限（db 目录 700 属主 app）、`flock`、`rename`、`touch` 单独测都成功；加 `-0`（fake root，guest 里 `id` 确认 uid=0）也不能解决。

**处理**：安装结果判定改为「退出码 0 **或** probe 命令成功」，UI 显示「✓ 已装（apk 数据库状态未更新，不影响使用）」。

**已知限制**：apk 会认为这些包没装，重复安装会重下重解压；`apk del` 不可用。根因未定位到 syscall 级（设备上没有 strace）。

**推广**：外部包管理器的退出码不等于「功能是否可用」。能用 probe 验证的能力，一律以 probe 为准。

### P-022 · 本地改完不能指望内核推帧刷新

`ui/ChatViewModel.kt` 的 `deleteSession` / `setSessionArchived` / `renameSession`

**现象**：会话删除点了两次确认、弹层关掉了，列表里那条还在 —— 看起来「删不掉」。实际内核已经删了（重装后列表空了）。

**根因**：三个方法都只依赖内核推送 `sessions` 帧来刷新列表，而实测内核在这些操作后**不推**。删除分支当时甚至只清了消息区、没动 `sessions` 列表。

**修法**：本地立即更新（删除/归档从 `sessions` 摘除、重命名就地改），再 `transport.listSessions()` 拉一次兜底对齐。

**推广**：任何「REST 改状态 + 等 WS 推刷新」的组合，都要先确认服务端真的会推。不确认就本地先改，再拉一次真值。

### P-023 · AnimatedContent 别塞进 verticalScroll

`ui/settings/SettingsSheet.kt` 的页面切换

**现象**：给设置页加子页切换动画，用 `AnimatedContent` 包住 `when(page)`，结果新旧两页**永久叠在一起**，文字全糊成一团（真机截图里「新增供应商」按钮压在输入框上）。

**根因**：`AnimatedContent` 需要测量子内容尺寸来做过渡，而它被放在 `Column(verticalScroll)` 里，纵向约束是无限的 —— 高度测量塌陷，两个内容都按最小高度布局在同一位置。`contentAlignment = TopStart` 也救不了。

**结论**：可滚动容器里不要用 `AnimatedContent` 做整页切换。要么把 `verticalScroll` 下沉到每个子页内部（各自滚自己的），要么放弃滑动过渡。当前采用后者：`when` 直接切 + 外层 `Column.animateContentSize()`，页面高度变化时弹层平滑长高。

### P-024 · 系统 Toast 在 Android 16 会被手势条切掉

`ui/common/ToastHost.kt`

**现象**：`Toast.makeText(...).show()` 弹在屏幕最底边，被导航手势条切掉一半，只剩一条白边。

**试过无效**：`Toast.setGravity(Gravity.BOTTOM, 0, offset)` —— 抬高的请求被系统忽略（Android 12+ 对 toast 位置收紧了）。

**结论**：要控制位置就用自绘浮层（Compose `Box` + `Alignment.BottomCenter` + 距底 150dp 避开输入卡）。代价是盖不住 `ModalBottomSheet`（Dialog 层级）—— 所以涉及弹层的操作要在选中后主动 `sheet = null`。

### P-025 · 客户端调用内核端点前先核对 HTTP 方法

`core/KernelApi.kt` 的 `setPlugin`

**现象**：MCP 插件开关点了完全没反应，也不报错。

**根因**：客户端用 `POST /api/plugins` 发 `{id, enabled}`，而内核 POST 分支是「**添加**插件」（要求 name + type + command/endpoint），于是每次都被「名称不能为空」拒掉。启停实际走的是 **PATCH**，字段是 `name` 不是 `id`。

**推广**：同一个路径上不同 method 语义完全不同的端点（`/api/plugins`、`/api/sessions`、`/api/providers/*` 都是这样），接客户端前必须逐个核对 method + 字段名，不能按「列表/保存/删除」的直觉套。

### P-026 · JNI 符号由「包名 + 类名 + 方法名」拼成，改名/混淆即 UnsatisfiedLinkError

`render/OpenRenderNative.kt` / `tools/openrender/app_jni.rs`

**现象**：库明明在包里，`System.loadLibrary` 成功，但第一次 `feed` 就 `UnsatisfiedLinkError`。

**根因**：JNI 导出符号是 `Java_app_codeforge_render_OpenRenderNative_nativeFeed`，与 Kotlin 的包/类/方法名逐字绑定。改包名、改类名、或 release 里 R8 改名，符号就对不上；另外 Rust 交叉编译必须带 `aarch64` 目标与 `--features jni,ratex`，否则没有这些导出。

**修法**：`app/proguard-rules.pro` 加 `-keep class app.codeforge.render.OpenRenderNative { *; }`；改 Kotlin 包/类名时同步改 `app_jni.rs` 的函数名；重编后用 `llvm-readelf -sW libopenrender.so | grep Java_app_codeforge` 核对符号。

**推广**：任何 `System.loadLibrary` 的类都要同时满足三件事——符号名对齐、R8 keep、ELF 的 `PT_LOAD` 对齐 ≥ 0x4000（16KB 页设备）。本条与 P-016 配套：加载失败要进程级判死，不要重试。

### P-027 · OpenRender IR 的键名按类型分叉，不能从「相似的 span」类推

`render/RenderIr.kt`

**现象**：公式、图片、音频在界面上是空的——块在，但 `text`/`src` 永远取不到。

**根因**：`json.rs` 的 `write_span` 按类型换键：`text/code` 用 `text`，`latex_inline` 用 `tex`，`image/audio` 用 `src` + `alt`；`math` 对象只有 mime/width/height/data，没有 baseline。按 P-008 的教训从「text span」类推键名必错。

**修法**：`parseSpan` 先读 `type` 再分支取键；新块型/span 一律对照 `crates/openrender-core/src/json.rs` 的构造点逐键核对，并用 `preview/delta.json`（引擎真实输出）做单测。

**推广**：这是 P-008 在 IR 层的继续——**同名概念在不同类型下有不同键**，契约核对必须看写方代码，不能看读方直觉。

### P-028 · 公式定界符归一必须跳过代码围栏与 `$$` 内的 `\\[2pt]`

`render/RenderIr.kt` 的 `normalizeMathDelimiters`

**现象**：给 GLM/Qwen 的 `\(…\)` 做兼容替换后，代码块里的 LaTeX 示例被改写；`$$` 里的 `\\[2pt]` 变成 `$$2pt]`，矩阵排版炸掉。

**根因**：pulldown-cmark 的 math 扩展只认 `$`/`$$`，宿主必须把 `\(\)`/`\[\]` 归一；但**代码围栏内是代码内容**，`$$…$$` 内是 LaTeX 命令空间，两处都不能动。

**修法**：逐行状态机：维护围栏开关（```` ``` ````/`~~~`）与 `$$` 块开关；只有三者都不在时才替换；`\(`/`\)` → `$`，`\[`/`\]` → `$$`。单测覆盖「围栏内原样」「`$$` 内 `\\[2pt]` 原样」。

**推广**：任何「源码重写」类兼容层（公式定界符、HTML 白名单、自动链接）必须识别代码区域；否则改的是用户想展示的原文。

### P-029 · `sessions` 帧不带运行态，列表刷新会把 busy 抹掉

`core/HttpCoreTransport.kt` / `ui/ChatViewModel.kt`

**现象**：项目行/会话行的「运行中转圈」时有时无；从后台回到前台（触发一次 `list_sessions`）后，正在跑的会话转圈全没了，只剩 `running` 标记。

**根因**：内核 `agent.SessionMeta` 只有 `id/workspace/workspace_name/title/created_at/updated_at/archived_at`，**没有 busy 字段**；运行态只由 `busy`/`idle` 帧驱动。每次 `sessions` 帧按载荷整体覆盖 `sessions` 列表，就把本地已知的 busy 冲成 false。

**修法**：列表帧到达时做 `mergeBusy(incoming, previous)` —— 先把旧的 `busy=true` 集合并回新列表；当前会话的运行态由 `load_session` 时内核补发的 `busy` 帧恢复（`ws_handler.go` 的 `runFor(...) != nil` 分支）。

**推广**：任何「按载荷整体覆盖本地集合」的帧，先核对载荷里**有没有**本地关心的派生字段（运行态、选中态、计数）。没有就得合并，不能直接覆盖。

### P-030 · `POST /api/workspace` 自己不防「有会话在跑」，跨项目切换必须壳侧拦

`core/KernelApi.kt` / `ui/ChatViewModel.kt`

**现象**：A 项目的一轮还在跑，点到 B 项目后，A 的下一轮工具调用落到了 B 的目录里，分析结果混进别的项目且毫无察觉。

**根因**：工作区是内核进程的**全局** `FS.root`（还没下沉到会话）。`load_session` 走 `switchSessionWorkspace` 时会检查 `anySessionRunning()` 并拒绝跨项目切换，但 `POST /api/workspace`（新建项目/切换项目走的正是它）**完全没有这个检查**，直接 `SetRoot`。壳侧若只发 POST，等于绕过内核唯一的防线。

**修法**：`createProject`/`switchProject` 在发 POST 前先看 `sessions.any { it.busy }`，有在跑的就提示「有会话正在运行，暂不能切换项目」；同项目内切会话不受影响（内核对该场景放行）。

**推广**：客户端调用「改全局状态」的端点前，要确认服务端是否做了保护；服务端两个入口语义不一致时（`load_session` vs `/api/workspace`），以**弱的那个**为准在客户端补。

### P-031 · 三态 JSON 字段（`vision`/`video`）不能用 `optBoolean(key, false)` 解析

`core/KernelApi.kt` 的 `listModels` / `saveModelFull`

**现象**：设置页把每个模型都显示成「不支持图片」，用户勾「支持」保存后，未声明过的模型仍然收不到图片附件。

**根因**：内核 `config.ModelEntry.Vision/Video` 是 `*bool`（三态：`nil` 未声明 / `true` / `false`），进 `sanitizeEntries` 的 map 后**未声明就序列化成 JSON `null`**。`optBoolean(key, false)` 把 `null` 与「键不存在」都读成 `false`，等于替用户声明了「不支持」——而内核把未声明当乐观发送、false 才发前拦截，两者语义完全相反。

**修法**：只要字段是三态，一律先判 `o.has(key) && !o.isNull(key)` 再取真值，null 原样传给 Kotlin 的 `Boolean?`。

**推广**：`Boolean?` 字段在 JSON 里必然有「缺失 / null / true / false」四种输入，用带默认值的 `optBoolean` 会压掉前两种。凡是「未声明 ≠ 否定」的语义（能力声明、开关继承、权限覆盖），解析层必须保留 null。

### P-032 · `session` 帧不是 `history`：新建会话后传输层必须同步 currentSession

`core/HttpCoreTransport.kt` / `ui/ChatViewModel.kt`

**现象**：项目下新建会话后发消息，有概率「又自动新开一个对话」，或消息落进上一个会话；新建后界面还挂着旧会话的历史，必须切走再切回才清空。

**根因**：三层各自只做了一半。① `new_session` 的内核应答是 `session` 帧，旧实现只在 `history` 分支写 `currentSession`，于是新会话的上行帧仍带旧 id（旧 id 被内核清理过时就成了空 id，服务端按「新会话」自动 Create）；② `SessionClaimed` 只改 id/title，没有清会话级状态（messages/running/todos/…）；③ 项目「＋新建会话」是 `switchProject()` + 立刻 `newSession()`，REST 切工作区是异步的，帧先到内核时会话归属到上一个项目。

**修法**：`CoreTransport.setCurrentSession()` 在 `session`/`history`/换绑三处同步；`SessionClaimed` 发现 id 变化即清空全部会话级状态；发送统一进串行队列（`pendingSends` + `newSessionGate`），等会话与工作区落定再上行；跨项目新建走 `newSessionInProject()`（先挂起 POST 工作区，再 new_session）。

**推广**：「当前实体 id」必须由**每一条**能确立它的事件同步，不能只写加载路径；状态机里「id 变了」就是重置派生状态的信号。

### P-033 · 会话的工作区在创建那一刻写死，不能事后按会话 id 猜目录

`ui/ChatViewModel.kt`（applySessionSpace）/ `core/EnvKit.kt`（inboxSpace）

**现象**：按 `sessionSpace(context, sessionId)` 给每个会话建的私有目录，与内核实际工作区、附件落点、后续加载时切回的工作区三者对不上。

**根因**：内核 `History.Create(ws, …)` 在创建会话时把当时的 `FS.root()` 写进 `sessions.workspace`，之后壳侧再 POST `/api/workspace` 也改不回这条记录；而「按会话 id 建目录」要求先有 id，会话又需要工作区才能建 —— 死循环。未选项目的会话因此要么 workspace 为空串（永远归不进任何项目），要么落在猜错的目录。

**修法**：未绑定工作区的会话统一使用一个稳定的应用私有 `spaces/inbox`；新建会话前若内核当前根为空，先 POST inbox 再 `new_session`；「不选文件夹的新项目」由 `EnvKit.newProjectWorkspace` 在 `$CELL/workspace/<项目id>` 下建独立目录，并把显示名写入 `workspace_names`（项目靠 workspace 分组，不能共用 inbox）。

**推广**：任何「实体创建时固化归属」的字段，壳侧不要事后补写；要么创建前把归属准备好，要么接受一个稳定默认值。

### P-034 · 附件落点不能由壳侧猜，统一走 `/api/upload_file`

`ui/chat/Composer.kt`（stageAttachment）/ `core/KernelApi.kt`（uploadFile）/ `ui/ChatViewModel.kt`（uploadAttachments）

**现象**：拍照/相册选完发送后气泡一直转圈，缩略图加载不出来，内核读附件报「路径越出工作区范围」。

**根因**：壳侧把附件复制到 `spaces/<sessionId>/attachments`，而内核 `readAttachmentMedia(ws, …)` 以**当前工作区**为根（项目会话就是项目目录）；写入方与读取方不是同一棵树。预览侧 `attachmentFile` 又固定按 `filesDir/workspace` 解析，三处各猜各的。

**修法**：选择器只把文件落到 `EnvKit.outboxDir`（绝对路径），发送时由 `POST /api/upload_file` 原子写进内核当前工作区的 `attachments/`，气泡改存内核返回的相对路径；预览用 `LocalAttachmentRoot`（与 `applySessionSpace` 逐字一致的优先级）解析相对路径。

**推广**：跨进程共享的文件路径，落点必须由**读方**决定；壳侧只负责把字节送过去，然后使用服务端返回的路径。

### P-035 · Material3 `DropdownMenu` 默认取 surfaceContainer，自定义主题不覆盖就会两个色系

`ui/AppRoot.kt` / `ui/chat/Composer.kt`

**现象**：右上角三点菜单、权限盾牌下拉与页面暖色主题明显不符（偏灰紫）。

**根因**：M3 弹出菜单默认 `MenuDefaults.containerColor` 走 `surfaceContainer` token；本项目只定制了 `surface/background/onSurface` 等基础色，没给容器色板配色，弹层就落回默认 Material 色。

**修法**：`DropdownMenu` 显式传 `containerColor = MaterialTheme.colorScheme.surface`、`tonalElevation = 0.dp`、主题圆角与 `outlineVariant` 描边；菜单项显式 `MenuDefaults.itemColors(textColor = onSurface …)`。

**推广**：任何落在独立窗口的 Material 组件（菜单、弹层、Dialog）都要显式使用项目色板，不能依赖默认 token。

### P-036 · `exec.Cmd` 一旦设置 `Cancel` 就必须用 `CommandContext` 创建

Go 内核 `pkg/tasks/manager.go`

**现象**：后台任务全部启动失败：`exec: command with a non-nil Cancel was not created with CommandContext`。

**根因**：`platform.StartGrouped` 为了「杀整个进程组」会覆盖 `cmd.Cancel`，而 `os/exec` 校验「非 nil 的 Cancel 只能存在于 CommandContext 创建的 Cmd 上」。后台任务用 `exec.Command` 创建，正好踩中。

**修法**：用 `exec.CommandContext(context.Background(), ...)` 创建（ctx 永不取消），再交给 `StartGrouped` 覆盖 Cancel；取消时手动调 `cmd.Cancel()` 并等 wait goroutine 收尾。

**推广**：任何复用 `platform.StartGrouped` 的「非超时」执行路径都要用永不取消的 ctx；`Cancel()` 的返回值不可靠（Windows 上进程已被 taskkill 结束后会报 invalid argument），是否生效以进程真正 Wait 返回为准。

### P-037 · 后台任务的输出必须有界，且 WS 只推状态不推日志

Go `pkg/tasks` / `pkg/server/tasks.go` / `ui/chat/BackgroundTasksSheet.kt`

**现象（设计阶段就能预见）**：开发服务器/构建日志动辄上兆，逐行推 WS 会挤爆连接；全量留在内存会把内核 OOM；全量塞进 LLM 工具结果会撑爆上下文。

**修法**：内核为每个任务保留 128KiB 环形尾部（超 2× 才裁剪，均摊 O(1)）；WS `task` 帧只带 id/状态/退出码；App 面板打开时 1s 轮询 REST；`list_background` 工具只回最近 10 个任务、每个 2000 字符。

**推广**：任何「长生命周期 + 持续输出」的资源，都要分别给内存、连接、模型上下文三个消费者设定各自的预算，不能共用一份全量数据。

### P-038 · 同一实体的两份数据用了两套键，按字符串拼接会重复显示

`ui/chat/ModelPickerSheet.kt`

**现象**：设置里只有 1 个供应商，模型弹层的供应商页却显示 2 个（一个显示名、一个 `provider_id`）。

**根因**：`/api/providers/list`（`KernelApi.listProviders`）下发的是**显示名**（`name`），模型库（`/api/models/list`）用的是 `provider_id` + `provider_name`。旧实现把 `libraryProviders + libraryModels.map { it.provider }` 直接拼接去重，两套键互不相等，同一家供应商自然出现两次。

**修法**：以显示名为身份合并：模型库的 `provider_name`（空则回落 id）先入列；`libraryProviders` 里凡是名称或 id 已被模型库代表的都跳过。

**推广**：与 P-008/P-031 同源 —— 两个端点描述**同一个实体**时，先核对两侧用的键是什么；不要在字符串层面做集合并集，先归一身份键再合并。

### 版本锁定（不要随手升）

- Compose `1.9.5` —— 再高要求 AGP 9.1 + compileSdk 37，整条链要一起动
- AGP `8.13.2` / Gradle `8.14.6` / Kotlin `2.4.21` —— 三者互相配套
- `vosk-android` `0.3.75`（16KB 页对齐，2026-10-25 从 0.3.47 升；传递依赖 JNA 5.18.1 已加 keep） —— Maven Central；升版本要重验 `RecognitionListener` API 与首启解压
- R8 已启用（release）：规则在 `app/proguard-rules.pro`；**真机日志反混淆必须用**
  `app/build/outputs/mapping/release/mapping.txt`（retrace）。改 keep 规则前先读 P-012 同目录的注释
- 兼容环境资产：`env-kit.tgz`（proot 5.1.107.101 + libtalloc 2.5.0 + libandroid-shmem 0.7，源 Termux 仓库）
  与 `rootfs-alpine.tgz`（Alpine 3.24.3 minirootfs aarch64）；升级/换发行版走 `.envkit/` 重建管道
- targetSdk **28**（2026-10-25 定，侧载定位）—— 升回去前必须复核决策表；lint 已豁免 ExpiredTargetSdkVersion
- ABI 仅 `arm64-v8a` —— Go 内核只交叉编译 arm64，x86 模拟器装不上
- `jniLibs/arm64-v8a/libcodeforge.so` —— 产物，源在 `source/CodeForge-GO`；重编：
  `CGO_ENABLED=0 GOOS=android GOARCH=arm64 go build -trimpath -ldflags "-s -w" -o <此处> ./cmd/agent`；
  内核侧端口握手补丁见改动历史 2026-10-25「Go 内核打包接入」条；
  后台脚本任务（`pkg/tasks` + `run_background/list_background/kill_background` + `/api/tasks`）
  见改动历史 2026-10-29 条，旧库备份在 `jniLibs-backup-before-background-tasks-20261029/`；
  图片附件**不设像素上限**（单张 20MiB、最多 4 张、单条消息图片总量 20MiB），`maxIntegrityDecodePixels`
  仅用于本地完整性解码的内存保护，超预算只校验头部、不拒绝上传
- `jniLibs/arm64-v8a/libopenrender.so` —— 产物，上游 `raine-x/OpenRender` @
  `6f11851722772b1a18388364b20a613a0aa2a020`（2026-10-28，0.1 preview），
  `--features jni,ratex`（内嵌 KaTeX 字体，4.85 MB / 16KB 页对齐）；
  重编走 `tools/openrender/build-android.ps1`（JNI 桥补丁在 `tools/openrender/app_jni.rs`）。
  升上游前必须重跑 Rust workspace 测试 + 核对 IR 键名（P-027）

### 已确认的设计取舍

| 取舍 | 结论 | 一句话理由 |
|---|---|---|
| UI 框架 | Compose 而非 Flutter | 现有壳已是 Compose，`configChanges` 已声明，换 Flutter 等于推倒重来 |
| 横竖屏判定 | `LocalConfiguration` 而非裸加速度计 | 旋转锁定 / 躺姿 / 分屏三种场景下裸传感器会误判（此条仍适用于分栏判定） |
| 横屏策略 | 窗口锁 `portrait` + `DeviceRotation` 转内容 | 用户要求「不是简单转成横屏」：竖屏元素原样旋转，布局/动画零改动；代价见 P-009（IME 与弹层不随转） |
| 语音识别 | 双引擎：系统 `SpeechRecognizer` 优先，Vosk 离线兜底（模型 41 MB zip 打进包体，首启解压，见 §2.6） | 用户设备无系统识别服务（国内无 GMS ROM 常见）且要求开箱可用；包体增大换可用性，模型不联网、只存应用私有目录 |
| 对话兜底 | 内核优先，内置测试模型（GLM-4.7 Flash 云直连）兜底 | E1 未验证前保证开箱有基础回复；开关在设置页；**密钥硬编码仅测试期，上架前必须外置** |
| 内核改造 | 端口握手 + Server 级运行注册表 + 后台任务（pkg/tasks）；其余零改动 | 36 REST + 35 WS 消息类型已是完整契约，新增能力都走独立端点/帧 |
| 三语言路线 | Python/Java 走进程内，只有 Go 需要 exec | 前两者是 ART 上的字节码，天然符合 W^X |
| 多语言支持 | 在 PATH 里装工具链，不在内核里加逻辑 | `run_command` 只跑 shell，不关心编译什么 |
| Markdown 引擎 | 内置 Rust `libopenrender.so`（增量 IR）而非 Kotlin 手写解析 + 公式 WebView | 流式复杂度从 O(N²) 降到 O(增量)，公式离线 PNG 化，去掉每公式一个 WebView；代价是 +4.85 MB `.so` 与上游 0.1 preview 风险 |
| 图片附件上限 | 不设像素上限；单张 20MiB、最多 4 张、单条消息图片总量 20MiB | 用户要求取消像素限制并把单张上限提到 20MB；总量不放大避免单次请求体膨胀上百 MB |
| 无文件夹项目的默认工作区 | `$CELL/workspace/<项目id>`，显示名写入 `workspace_names` | 工作区必须在会话创建前定好且事后不可改；项目 id 稳定、无碰撞，重命名只改显示名不动目录 |

---

## 10. 禁止事项

- ❌ 改 `source/CodeForge-GO/` 而不事先说明（那是另一个仓库）
- ❌ 跨层调用（界面层直接 new `HttpCoreTransport`、`core/` import `ui/`）
- ❌ 在界面层放业务状态（会话、消息、运行状态必须来自 `ChatUiState`）
- ❌ 删除或清空 `.workbuddy-ai/`
- ❌ 跳过三份记录直接交付
- ❌ 把「构建成功」当成「功能可用」上报
- ❌ 顺手重构无关代码、顺手改格式
- ❌ 引入新依赖不说明理由
- ❌ 执行 `git push`
- ❌ 未经确认删除文件（尤其 `D:` 盘上用户的其他东西）
