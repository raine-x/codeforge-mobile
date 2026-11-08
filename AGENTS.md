# AGENTS.md — AI 工作准则

> 本文件约束所有在此仓库工作的 AI。开始任何任务前先读完，任务结束前对照第 8 节自检。
> 违反第 4 节（三份记录）的产出视为**未完成**。

---

## 1. 这个项目是什么

CodeForge-Go 的 Android 宿主壳。Go 内核是一个 AI 编码 agent，原本配网页界面；本项目把它改成 Android 原生应用。

```
CodeForgeAPP/                       ← 本仓库，管 Android 壳
├── README.md                          门面：只讲性能、特点、特性（炫技向）
├── README_ZH.md                       中文详版：使用方法、技术实现、原理
├── README_EN.md                       英文详版：同上
├── LICENSE                            MIT + 商业授权双许可 + 第三方组件清单（联系 2653603072@qq.com）
├── AGENTS.md                          记录一：工作准则（本文件）
├── 工作进程.md                        记录二：待办与已办
├── 改动历史.md                        记录三：变更流水
├── docs/android-native-plan.md        参考：方案与架构（不属于三份记录）
├── app/src/main/java/com/hxy/codeforge/   Kotlin + Compose，无 XML 布局
└── app/src/main/jniLibs/arm64-v8a/    libcodeforge.so + libopenrender.so（预编译产物）
```

**边界**：
- **.so 源码不在本仓库**：libcodeforge.so与
  libopenrender.so（引擎 + JNI 桥补丁）只以预编译产物进仓库；源码与构建材料在仓库外的
  `D:\code\CodeForgeAPP-private\`，内核原仓库在 `C:\Users\26536\Desktop\Code\CodeForge-GO`。
  **未经确认不得把源码重新引入本仓库**不得更改上面这两个源码仓库。
- **包名 `com.hxy.codeforge`**
Kotlin 包、namespace、
  applicationId、JNI 符号、proguard keep 规则五处必须同步，见 P-047。

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
| 入口层 | `MainActivity.kt`、`CodeForgeApp.kt` | 建窗口、edge-to-edge、通知权限、拉起 CoreService、启动流水线（§2.6）、挂 ViewModel | 117 / 77 |
| 界面层 | `ui/AppRoot.kt` | 横屏=内容旋转接网页式双栏（侧栏+聊天）、「环境准备中」遮罩、设置弹层挂载、顶栏、横幅、空态问候页、内置浏览器浮层 | 1494 |
| | `ui/chat/MessageList.kt` | 列表、贴底跟随、回到最新、探索分组 | 385 |
| | `ui/chat/MessageBubble.kt` | 气泡 / 通知行 / 思考折叠 / 操作行（正文块渲染入口，见 OpenRenderMessage） | 717 |
| | `ui/chat/OpenRenderMessage.kt` | OpenRender IR → Compose：全块型/全 span 排版、公式 PNG、表格按实际宽度分列、媒体围栏路由、HTML 块合并、流式光标 | 1014 |
| | `ui/chat/MessageBlocks.kt` | 宿主侧媒体渲染：SVG（WebView）/图片/视频/音频/附件缩略图与全屏；`LocalAttachmentRoot` 解析相对路径 | 554 |
| | `ui/chat/ToolCard.kt` | 工具卡片、diff 着色 | 460 |
| | `ui/chat/ToolLabels.kt` | 工具名/结果文案映射（与 Web 一致） | 166 |
| | `ui/chat/ApprovalCardView.kt` | 审批卡片 | 181 |
| | `ui/chat/Composer.kt` | 悬浮输入卡、四态主按钮、两引擎语音路由（云端→系统）、模型/附件弹层、@ 补全、页面元素 chip | 2106 |
| | `ui/chat/ActionPanel.kt` | `@plan` 计划书操作面板 | 200 |
| | `ui/chat/BackgroundTasksSheet.kt` | 后台脚本任务面板：轮询日志、启动、取消 | 347 |
| | `ui/chat/ModelPickerSheet.kt` | 选择模型弹层：供应商（按 id 归一）/模型分组/思考强度拉条/权限 | 952 |
| | `ui/chat/StatusStrips.kt` | 上下文表、目标模式、任务清单、子智能体卡 | 520 |
| 浏览器层 | `ui/browser/BrowserOverlay.kt` | 内置浏览器全屏页：返回/刷新/选择元素/全屏/三点菜单 | 399 |
| | `ui/browser/PageBridge.kt` | JS 桥 `window.__cf`（inspect/list/click/evaluate/contentHeight/viewport） | 358 |
| | `ui/browser/BrowserWebView.kt` | WebView 封装（调试开关、离屏截图、页面高度上报） | 150 |
| | `ui/browser/HtmlBlockView.kt` | 对话流内嵌 HTML 块（高度按内容实测，夹在 140–520dp） | 183 |
| | `ui/browser/HtmlDetection.kt` | 裸 HTML 识别与合并（`looksLikeHtmlDocument` / `mergeHtmlBlocks`） | 85 |
| | `ui/browser/BrowserModels.kt` | 浏览器数据类：`PickedElement` / `ElementInsert` / `BrowserTarget` | 104 |
| | `ui/browser/BrowserController.kt` | 浏览器控制器（CompositionLocal 下传） | 33 |
| | `ui/sessions/SessionPane.kt` | 会话侧栏：项目分组、项目行＋新建会话、MCP 入口、头像=设置 | 639 |
| | `ui/sessions/ProjectPanel.kt` | 新建项目、项目重命名/归档/删除 | 399 |
| | `ui/sessions/FileTreePanel.kt` | 全屏文件树、`..` 返回、选择工作区/跳过 | 300 |
| | `ui/sessions/McpSidebarPanel.kt` | 侧栏 MCP 全屏面板、启停/删除/添加 | 361 |
| | `ui/sessions/SessionActionsSheet.kt` | 会话重命名/归档/删除动作面板 | 189 |
| | `ui/settings/AppSettings.kt` | 设置存储：Compose 状态 + 加密 SharedPreferences（旋转/语音/外观/云端 STT） | 284 |
| | `ui/settings/SettingsSheet.kt` | 设置弹层：12 个二级页（模型服务/外观/记忆/技能/插件/MCP/归档/环境/文件传输/内核/语音/诊断） | 1736 |
| | `ui/settings/FileTransferSection.kt` | 文件传输：导入到默认工作区、打包导出到 `Download/CodeForge/` | 222 |
| | `ui/settings/ModelEditorSection.kt` | 模型完整表单：上下文/三态能力/RPM/密钥/测试/发现 | 653 |
| | `ui/settings/KernelSettingsSection.kt` | 内核设置：生成/重试/压缩/子智能体/通知/记忆/技能/插件 | 1032 |
| | `ui/settings/EnvPackagesSection.kt` | 兼容环境软件包安装（apk 预设 + probe + DNS） | 153 |
| | `ui/common/Responsive.kt` | 横竖屏三档判定（优先读旋转后内容尺寸 LocalOrientation） | 57 |
| | `ui/common/Glyphs.kt` | 火焰 Logo 与手绘描边图标库（含 Inspect/Fullscreen/MenuDots/Sidebar） | 747 |
| | `ui/common/NoRipple.kt` | 无涟漪点击扩展（圆形/胶囊按钮专用） | 26 |
| | `ui/common/VoiceInput.kt` | 系统语音引擎封装（SpeechRecognizer，zh-CN） | 208 |
| | `ui/common/DeviceRotation.kt` | 物理姿态检测（迟滞角可配）与内容旋转层（insets 已转 dp，见 P-013） | 230 |
| | `ui/common/ToastHost.kt` | 应用内 Toast 宿主 | 87 |
| | `ui/theme/Theme.kt` | 明暗配色（Claude 暖色系）、字体、AppBackground、文字色覆盖 | 303 |
| 状态层 | `ui/ChatViewModel.kt` | 单一状态源、事件折叠、发送串行队列、项目/工作区、后台任务、页面元素登记 | 2654 |
| 协议层 | `core/CoreTransport.kt` | `CoreEvent` 24 种 + `CoreTransport` 接口 | 187 |
| | `core/MockCoreTransport.kt` | 假内核，驱动界面开发（未覆盖 `task` 等新事件） | 340 |
| | `core/HttpCoreTransport.kt` | OkHttp HTTP + WS，自愈重连、帧解析（对照 ws_handler 下行帧） | 678 |
| | `core/AttachmentParsers.kt` | 附件解析（图片尺寸/时长/音视频元信息） | 176 |
| | `core/EnvProbe.kt` | 环境探针（可执行/库存在性检查） | 55 |
| 语音层 | `voice/CloudVoice.kt` | 云端识别（硅基流动 OpenAI 兼容）：AudioRecord 16k PCM → WAV → multipart 上传 | 292 |
| 服务层 | `core/CoreService.kt` | specialUse 前台服务：拉起 `libcodeforge.so`、PORT 握手、日志落盘、退避重启 | 334 |
| | `core/KernelConfig.kt` | 设置页 ↔ 内核 local.yaml 的桥（llm 段写入） | 48 |
| | `core/EnvKit.kt` | 统一兼容环境：proot+Alpine 首启解压、路径契约（$CELL 单根）、cfrun、文件传输、软件包安装 | 666 |
| | `core/BuiltinSkills.kt` | 内置纯知识技能：assets zip → 工作区 `.codeforge/skills/`（幂等，不覆盖用户自建技能） | 173 |
| | `core/KernelApi.kt` | 内核 REST 客户端（config/perm/sessions/workspace/models/providers/tasks/upload 等），Cookie 同 WS | 995 |
| 模型层 | `model/CoreModels.kt` | 纯数据类，无逻辑无 Android 依赖 | 296 |

渲染引擎层（旁挂原生库）：
| 目录/文件 | 职责 | 行数 |
|---|---|---|
| `render/OpenRenderNative.kt` | `libopenrender.so` 的 JNI 封装（含 RaTeX 开关与 `cacheDir` 透传）；加载失败进程级判死，界面退纯文本 | 102 |
| `render/RenderIr.kt` | IR 数据类、delta 应用器、`\(\)`/`\[\]` 公式定界符归一、按消息前缀 diff 喂引擎 | 362 |

引擎源码（JNI 桥补丁、构建脚本、字体子集）**已移出仓库**（2026-11-07，见第 1 节边界）——
P-039/P-047 所指的 `tools/openrender/` 路径已不在仓库，材料在 `D:\code\CodeForgeAPP-private\`。

语音层说明：`voice/` 不属于协议层（不碰内核帧），是跨界面能力，被 `ui/chat/Composer.kt`
（输入）与 `ui/settings/SettingsSheet.kt`（状态展示）共同消费；不引 Compose 之外的 Android UI。

渲染引擎层说明：OpenRender 只做 Markdown → IR（块 + 行内 span），不画像素。排版与媒体
播放由 `ui/chat/` 完成；`core/`、`ChatViewModel` 不许依赖它（渲染是纯界面能力）。

### 2.3 层间规则（硬性）

1. **界面层不许碰协议层**。`ui/` 下任何文件都不许 `import com.hxy.codeforge.core.HttpCoreTransport`。要新数据就加 `ChatUiState` 字段。
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
| 改语音输入（语言/交互/降级文案） | `ui/common/VoiceInput.kt` → `ui/chat/Composer.kt`（两引擎路由：云端 → 系统 `SpeechRecognizer`） |
| 改云端语音配置（供应商/模型/Key） | `ui/settings/AppSettings.kt` 的 `sttCloud*` + `ui/settings/SettingsSheet.kt` 的 VoiceSection + `voice/CloudVoice.kt` |
| 改输入框行为（多行、发送键） | `ui/chat/Composer.kt` |
| 改贴底跟随 / 回到最新 | `ui/chat/MessageList.kt` |
| 改配色、字体、深色模式 | `ui/theme/Theme.kt` |
| 改 Markdown 解析范围（CommonMark/GFM/公式） | 引擎源码不在仓库 → 重编 `.so` 联系 LICENSE 邮箱；壳侧解析在 `render/RenderIr.kt` |
| 改正文排版（标题/列表/引用/表格/代码块/公式外观） | `ui/chat/OpenRenderMessage.kt` |
| 改公式定界符兼容（`$`/`$$`/`\(\)`/`\[\]`） | `render/RenderIr.kt` 的 `normalizeMathDelimiters`（注意跳过代码围栏与 `$$` 内的 `\\[2pt]`） |
| 改公式里的中文渲染（Android 无 Unicode 回退字体，见 P-039） | 引擎侧 `install_unicode_font` 已随源码移出仓库 → 重编 `.so` 联系 LICENSE 邮箱 |
| 改表格列宽 | `ui/chat/OpenRenderMessage.kt` 的 `columnWidths`（按实际渲染宽度分配，别退回字符数加权） |
| 改 AI 出题/可交互网页（HTML 块、内置浏览器、元素挑选） | `ui/browser/`（`PageBridge.kt` JS 桥、`BrowserOverlay.kt` 全屏浏览器、`HtmlBlockView.kt` 内嵌块）+ `ui/chat/OpenRenderMessage.kt` 的 `mergeHtmlBlocks`；引擎**没有** Html 块类型，见 P-041 |
| 改注入给模型的能力说明（提示词） | `ui/CapabilityPrompt.kt` 的 `CLIENT_CAPABILITY_PROMPT`（只能混进上行文本，见 P-042；**改提示词必须同步改这里的通道清单并跑 `CapabilityPromptTest`**，见 P-054） |
| 改横屏左侧栏折叠 | `ui/AppRoot.kt` 的双栏布局（`sidebarCollapsed` + `animateDpAsState` 宽度过渡）+ `TopBar` 的 `onToggleSidebar`（三横线在主界面顶栏） |
| 改模型库 / 探测 / 测试连接 | `core/KernelApi.kt` 的 `discoverModels` / `testModel` —— **字段名必须与内核 `modelTestReq` 一致**，见 P-043 |
| 改供应商显示 | `ui/chat/ModelPickerSheet.kt` 的 `buildProviderEntries` + `ui/chat/Composer.kt` 的 provider 派生；**身份用 id**，见 P-044 |
| 改回复闪烁/流式滚动/新问题定位 | `ui/chat/MessageList.kt`（勿回加 animateItem，见改动历史 22:25 条） |
| 改消息块渲染（SVG/图片/视频/音频/原样开关） | `ui/chat/MessageBlocks.kt`（宿主媒体渲染）+ `model/CoreModels.kt` 的 verbatim 字段 |
| 改工具卡片展开逻辑、diff 着色 | `ui/chat/ToolCard.kt` |
| 改上下文表 / 任务清单 / 子智能体卡 | `ui/chat/StatusStrips.kt` |
| 改选择模型弹层（供应商/模型列表/思考拉条/权限） | `ui/chat/ModelPickerSheet.kt` |
| 改设置（旋转开关/灵敏度/语音引擎） | `ui/settings/AppSettings.kt`（存储）+ `ui/settings/SettingsSheet.kt`（弹层） |
| 改状态栏显隐（默认隐藏） | `AppSettings.showStatusBar`（存储）+ `MainActivity.kt`（WindowInsetsController） |
| 改附件选择/上传/预览 | `ui/chat/Composer.kt`（stageAttachment → outbox）+ `ui/ChatViewModel.kt`（uploadAttachments → `/api/upload_file`）+ `ui/chat/MessageBlocks.kt`（`LocalAttachmentRoot` 解析相对路径） |
| 改手机文件导入/导出（sdcard） | `core/EnvKit.kt` 的文件传输段 + `ui/settings/FileTransferSection.kt` |
| 改内置技能（内容/增删/触发词） | 资产 `app/src/main/assets/builtin-skills.zip` + `core/BuiltinSkills.kt`；**frontmatter 必须扁平单行**（见 P-052）；上游是 MiniMax-AI/skills |
| 改环境软件包（增删包/探测命令） | `core/EnvKit.kt` 的 `packages`；**probe 不许带管道**（见 P-051） |
| 改 @ 补全候选（来源/条数/滚动） | `ui/chat/Composer.kt` 的 `mentionCandidates` 与 `mentionMatches`；**列表用 LazyColumn + heightIn 上限，候选不许 take(n) 截断**（见 P-053） |
| 改消息滚动/自动跟随 | `ui/chat/MessageList.kt`（尾部只能 1dp 哨兵，勿回加 `fillParentMaxHeight`） |
| 改后台脚本任务（启动/列表/取消/日志） | Go 内核 `pkg/tasks`（进程组、输出环形缓冲）+ `pkg/server/tasks.go`（REST/WS）+ `ui/chat/BackgroundTasksSheet.kt` + `ChatViewModel.refreshTasks/startTask/killTask` |
| 改内核运行设置（生成/重试/压缩/子智能体/通知） | `ui/settings/KernelSettingsSection.kt` → `ChatViewModel` 服务操作 → `core/KernelApi.kt` |
| 改首启「环境准备」流程 | `core/EnvKit.kt` + `CodeForgeApp.kt`（注册处），并同步 §2.6 |
| 改审批卡片字段或按钮 | `model/CoreModels.kt` → `ui/chat/ApprovalCardView.kt` → `core/HttpCoreTransport.kt` 的 `hitl_request` 分支 |
| **新增一种内核事件** | ① `core/CoreTransport.kt` 加 `CoreEvent` 子类 ② `ui/ChatViewModel.kt` 的 `onEvent` 加分支 ③ `core/HttpCoreTransport.kt` 的 `handleFrame` 加解析（帧名/键名以 `pkg/agent/agent.go` 常量表与 `pkg/server/ws_handler.go` 为准，注意 P-008 的键名陷阱） |
| 改协议字段名 / 新增字段 | `core/HttpCoreTransport.kt` 的 `handleFrame` 与 `parse*` 系列 |
| **接上真内核** | `core/CoreService.kt`（拉起进程读端口）→ `MainActivity.kt`（启动服务）→ `ChatViewModel` 把 `MockCoreTransport` 换成 `HttpCoreTransport` |
| 加新页面（设置、模型管理） | `ui/` 下新建包，在 `ui/AppRoot.kt` 挂入口 |
| 加新的状态字段 | `ui/ChatViewModel.kt` 的 `ChatUiState` + `onEvent` 的对应分支 |

### 2.6 启动流水线与「环境准备中」

首启步骤全部注册在 `CodeForgeApp.kt` 的 `onCreate`，顺序固定，新增步骤必须同步更新本节：

1. 崩溃落盘 + 前后台可见性 —— 安装 `UncaughtExceptionHandler`（`filesDir/logs/crash-*.txt`，保留 5 份），
   并用 `ProcessLifecycleOwner` 维护 `AppVisibility`（切后台时内核据此决定是否发系统通知）；
2. `AppSettings.init` —— 读持久化设置（旋转/语音/外观/云端 STT），必须在首帧前完成；
3. 附件孤儿清理 —— 删 `filesDir/workspace/attachments` 下超过 7 天的文件（给 regenerate 留缓冲）；
4. `EnvKit.ensureReady` —— **首启「环境准备中」**：兼容环境解压 assets 里的 env-kit.tgz（proot）与
   rootfs-alpine.tgz（Alpine minirootfs）到 filesDir，生成统一执行入口 `usr/bin/cfrun`；
   期间 AppRoot 盖「环境准备中」遮罩（星标 + 进度条），成功后写 `.ready` 标记，后续启动秒过；
   失败不挡屏，设置页可看原因并重试。**环境契约**（路径单一事实源）
   见 EnvKit 类注释：一切锚定 filesDir 单根，proot 以同路径绑定 HOME/tmp，
   内核经 CF_EXEC_WRAPPER 走 cfrun，内外同一棵目录树。
5. 通知渠道与内核进程 —— 由 `CoreService.onStartCommand` 的 `ensureChannel()` 创建；
   `MainActivity.onStart` 每次进前台 `startForegroundService`，服务内部幂等。内核的拉起/
   PORT 握手/退避重启也在 CoreService（握手协议见其类注释）。

规则：任何「首启要做、之后跳过」的环境准备都走这条流水线——状态用
`core/EnvKit.kt` 的 Status 模式（Idle/Preparing/Ready/Failed），
**不要各自发明**；解压类步骤的目标目录一律 `filesDir` 下独立子目录 + 就绪标记。

---

## 3. 开工前必读

1. `README.md` —— 门面：性能、特点、特性
2. `README_ZH.md` —— 中文详版：使用方法、技术实现、原理
3. `工作进程.md` —— 现在做到哪、下一步是什么
4. 本文件第 2 节（架构分层）与第 9 节（踩过的坑）
5. `docs/android-native-plan.md` —— 架构为什么这么设计、有哪些已知风险
6. 要改的文件本身

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

**禁止主动提交和推送**

## 8. 任务结束前自检

逐条确认，任何一条为「否」都算没做完：

- [ ] `工作进程.md` 里对应项已勾选或已新增
- [ ] `改动历史.md` 已追加本次全部改动（含文档本身）
- [ ] 若改了分层或踩到新坑，`AGENTS.md` 第 2 / 第 9 节已同步
- [ ] `改动历史.md` 的「验证」栏如实写了跑到哪一级、什么没验证
- [ ] 没有跨层改（界面层没碰协议层，协议层没引界面层）
- [ ] 没有 `git add -A`、没有未经用户要求执行 `git push`
- [ ] 没有把 so 源码或构建材料重新引入仓库（见第 1 节边界）
- [ ] 回复里说清了：改了什么、验证到什么程度、还剩什么没做

---

## 9. 已经踩过的坑
以下仅在遇到问题时可以重点查看
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

`ui/common/Markdown.kt`（历史路径，已随 OpenRender 替换移除；规则仍适用于任何 `Row` + 多个 `Text` 的排版）

`Row` 里放两个 `Text`（圆点 + 内容），内容那个不加 `weight` 就不受约束，超出部分直接被裁。中文长句尤其明显。

### P-005 · `dataSync` 前台服务在 Android 15+ 有 6 小时/天上限

`AndroidManifest.xml`（**已修复**）

targetSdk 35+ 的 app，`dataSync` 类型前台服务每天累计只能跑 6 小时，超时由系统调 `Service.onTimeout()` 强制停止。

现状：本仓库已是 `specialUse` + `FOREGROUND_SERVICE_SPECIAL_USE` 权限 + `<property>` 声明用途。

**推广**：任何「常驻后台」的服务，先确认 `foregroundServiceType` 有没有时长配额。

### P-006 · 本仓库曾是「没有 .git」的目录（已解决）

2026-10-29 前本仓库只有 `.gitignore`，`git status` 报 `not a git repository`，无法 diff/回滚。
现在已是 git 仓库（`origin` = gitee `code-forge-m`），有远端与提交历史。

**保留的教训**：在仓库里执行 git 命令前先 `git remote -v` 确认目标；
`source/` 这类参考目录里的仓库是**另一个仓库**，见 P-048（2026-11-07 起该目录已移出仓库，
教训仍适用于一切 vendored/多仓库场景）。

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

`app/build.gradle.kts`（**当时**是语音模型 `voice/OfflineVoice.kt`；
2026-11-05 模型移除后已无 `openFd` 调用，`noCompress += "zip"` 随之删除）

**现象**：`AssetFileDescriptor.openFd` 对默认资源抛 `FileNotFoundException`，
「环境准备中」无法显示百分比。
**根因**：aapt 默认会再压一层已知后缀（含 zip），压缩后的资源在 APK 里没有连续
字节区间，只能走 `assets.open` 流式读，拿不到总长。
**修法**：`androidResources { noCompress += "zip" }`，资源以存储态进包，
`openFd().length` 即精确大小。
**推广**：任何需要「读 assets 进度/随机访问」的资源都要进 noCompress 白名单；
代价是包体里这块不压缩，大文件要掂量。
**反过来也成立**：只走 `assets.open` 流式读的包（如 `builtin-skills.zip`）
**不要**进 noCompress 白名单 —— 压缩存储更省包体。

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

任意 `System.loadLibrary` 依赖的类（**当时**是 Vosk 的 `OfflineVoiceEngine.start`，
2026-11-05 该引擎已随内置离线语音一起移除 —— 但这个坑对**任何**原生库都成立）

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

**根因**：JNI 导出符号是 `Java_com_hxy_codeforge_render_OpenRenderNative_nativeFeed`（原 `Java_app_codeforge_…`，2026-11-04 改包名，见 P-047），与 Kotlin 的包/类/方法名逐字绑定。改包名、改类名、或 release 里 R8 改名，符号就对不上；另外 Rust 交叉编译必须带 `aarch64` 目标与 `--features jni,ratex`，否则没有这些导出。

**修法**：`app/proguard-rules.pro` 加 `-keep class com.hxy.codeforge.render.OpenRenderNative { *; }`；改 Kotlin 包/类名时同步改 `app_jni.rs` 的函数名；重编后用 `llvm-readelf -sW libopenrender.so | grep Java_com_hxy_codeforge` 核对符号。

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

### P-039 · Android 上没有 Unicode 回退字体，含中文的公式整块渲染不出来

`tools/openrender/app_jni.rs` 的 `install_unicode_font`

**现象**：`$$\begin{aligned} S &= \pi r^2 &&\text{圆的面积} \end{aligned}$$` 整块不渲染，
界面退回原始 `$$…$$` 源码；而纯 ASCII 公式（哪怕同样是 `aligned`/`cases`/`bmatrix`
环境）全部正常。

**根因**：`libopenrender.so` 用 `--features ratex` 编译，内嵌的是 **KaTeX 字体**
（拉丁 + 数学符号）。`\text{…}` 里的中文要靠 **Unicode 回退字体**，而
`ratex-unicode-font` 只查三处：`RATEX_UNICODE_FONT` 环境变量、
Linux/macOS/Windows 的系统字体路径、fontdb —— **Android 三处都没有**。
找不到字体时 `render()` 直接返回 `None`，IR 里 `math` 为 null。
**是整块失败，不是逐字缺字形。**

**复现**：Linux 上把 `/usr/share/fonts` 挪走即等价于 Android 环境。

**修法**：把 Noto Sans SC 子集（GB2312 常用字，3.2MB，OFL-1.1）**内嵌进 `.so`**，
在 `nativeUseRatex` 里写到应用 cacheDir 并设 `RATEX_UNICODE_FONT`。
用的是该 crate **本就支持**的环境变量 —— 不新增依赖、不改上游 crate、不改引擎逻辑。
字体文件在 `tools/openrender/fonts/`，`build-android.ps1` 负责把它复制到 crate 的 src
（`include_bytes!` 要求同目录）。

**推广**：
- 字体查找结果被 `OnceLock` 缓存，**第一次查找之后再设环境变量也不生效** ——
  所以装字体必须早于首次公式渲染（本实现放在 `nativeUseRatex`）。
- **别用壳侧改写源码去绕**：把 `\text{中文}` 拆出来当正文，块级公式会被降成行内，
  而且 `\begin{aligned}`/`\begin{cases}` 这类环境**要求 display 模式**，
  拆成行内照样渲染不出来 —— 这条路对环境公式是死路。
- 新增公式相关能力时，**先在沙箱里用真实引擎验 IR**：
  拉上游源码（`api.github.com` 在部分沙箱可达）→ `cargo build --features ratex`
  → 写探针看真实 IR。crates.io 要走 **sparse index**，git index 指向 GitHub 会卡死。

### P-040 · JVM 不读 `http_proxy`，Gradle 走代理会静默超时

**现象**：本机走本地代理，`curl` 能通，但 Gradle 报

```
Downloading https://services.gradle.org/distributions/gradle-8.14.6-bin.zip
java.io.IOException: ... failed: timeout (10000ms)
```

**根因**：**JVM 不读 `http_proxy` / `https_proxy` 环境变量**，只认 `systemProp.*`。
所以「curl 通、Gradle 不通」是预期行为，不是网络故障。

**解法**：构建前临时写进 `~/.gradle/gradle.properties`（构建后可删）

```properties
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=<端口>
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=<端口>
systemProp.http.nonProxyHosts=localhost|127.0.0.1
```

若 `services.gradle.org` 被代理挡（`CONNECT tunnel failed, 502`），Gradle 发行版可从镜像预置 ——
目录名由 `distributionUrl` 的 MD5 决定，**直接从 wrapper 报错路径就能读到**，不用自己算：

```bash
D=~/.gradle/wrapper/dists/gradle-8.14.6-bin/<hash>
curl -L -o "$D/gradle-8.14.6-bin.zip" \
  https://mirrors.cloud.tencent.com/gradle/gradle-8.14.6-bin.zip
touch "$D/gradle-8.14.6-bin.zip.ok"
```

### P-041 · 引擎没有 Html 块类型，裸 HTML 会以纯文本落在 paragraph 里

**现象**：模型直接输出一整页 HTML（不包 ```html 围栏）时，界面显示的是一屏尖括号。

**根因**：`crates/openrender-core/src/markdown.rs` 里 `Event::Html` → `push_text(&html)`，
即**裸 HTML 被当作普通文本推进 paragraph**。`BlockKind` 一共 9 种，没有 Html。

**修法（壳侧，不动引擎）**：
- 围栏形态：引擎给的是 `code_block` + `lang="html"` → `CodeFenceBlock` 里分流到 `HtmlBlockView`；
- 裸 HTML 形态：壳侧按文本特征认 —— `ui/browser/HtmlDetection.kt` 的 `looksLikeHtmlDocument`
  + `mergeHtmlBlocks`。
  **但事后合并救不了带缩进的文档** —— 碎片在引擎提交时就丢了，见 **P-049**；
  裸 HTML 的正规做法是**喂引擎之前**判定原文并直接挂页面（P-049 的 `looksLikeWholeHtmlDocument`）。

**合并是必须的**：markdown 的 HTML 块在**空行**处断开，一份带空行的 HTML 文档
（`<head>` 与 `<body>` 之间通常有空行）会被切成好几段，逐段看谁都不像完整文档。

**判定门槛为什么定这么高**（认错就是 bug）：
- 整段必须以 `<` 开头 —— 行内 HTML 混在文字里的不认；
- 必须是完整文档（doctype，或 `<html>` 且有闭合）或带 style/script 的整块；
- 必须够长（≥40 / ≥120）且以 `>` 收尾 —— 短片段多半是正文里贴的一行标签。

**流式期间不做识别**：文档还没写完，提前渲染只会得到半张页面。

### P-042 · 内核没有「系统提示词」字段，能力说明只能混进上行文本

**现状**：`GET /api/config` 只有模型、温度、重试、步数、权限、workDir 这些，
**没有 instructions / system_prompt / AGENTS 之类的入口**。
`POST /api/config` 同理。想让模型知道「这个客户端能渲染 HTML」，只有一条路：
把说明混进上行文本。

**关键**：**上行文本与气泡文本是分开的**（`PendingSend(text, attachments, bubbleText)`）。
所以注入只影响模型看到的内容，用户气泡里仍然是自己写的那句话。

**副作用**：用户重发（`resendUser`）时用的是 `message.text`（气泡文本），
元素布局信息会丢 —— 与附件的行为一致，可接受。

**回放泄露**：内核把上行文本原样存进历史，下一轮 `history` 帧回放时整段替换消息列表，
注入内容会漏回用户气泡。修法见 `ChatViewModel.stripInjectedText` —— 按标记切掉后缀，
而不是重算（上行文本是「用户文本 + 固定后缀」的结构，切掉后缀即精确还原原话）。

**⚠️ 标记不能改**：定位标记 `【CodeForge 客户端渲染能力】`（`ui/CapabilityPrompt.kt` 的
`INJECT_CAPABILITY_MARKER`）一个字都不能动 ——
内核历史里存的是带**旧标记**的整段上行文本，改了标记，旧会话回放就切不掉注入段，
整段提示词会漏回用户气泡。提示词正文可以随便改，标记不行。

### P-043 · 内核 `modelTestReq` 的字段名必须逐字对齐，Go 会静默忽略未知字段

**现象**：
- 「获取模型列表」探测出来的模型不对 —— 用的不是该供应商的地址与密钥；
- 「测试连接」永远报「API Key 为空」，从来没测过任何东西。

**根因**：内核的请求体是

```go
type modelTestReq struct {
    ProviderID string `json:"provider_id"`   // ← 不是 "provider"
    Protocol   string `json:"protocol"`
    BaseURL    string `json:"base_url"`
    Model      string `json:"model"`         // ← 不是 "id"
    KeySrc     string `json:"key_source"`
    KeyName    string `json:"key_name"`
    KeyValue   string `json:"key_value"`
    ProbeContext bool `json:"probe_context"`
}
```

而 App 早先发的是 `{"provider": …}` 与 `{"id": …}`。
**Go 的 `json.Decoder` 静默忽略未知字段** —— 不报错、不警告，
内核收到的是一片空：供应商 id 空 → `applyProviderDefaults` 直接返回 →
地址与密钥回退到 `s.cfg.LLM`（**当前生效模型**的那一份）。
于是「探测供应商 A」实际打的是当前模型的网关、用的是当前模型的密钥。

**修法**：`KernelApi.discoverModels` / `testModel` 逐字对齐字段名，
并**主动带上供应商的明文密钥**（内核 `/api/providers/list` 每个供应商都回填 `key_plain`，
早先 App 把这个字段丢了）。

**推广**：
- 对着内核源码核字段名，别照着「看起来像」的命名写；
- 内核 `handleModelDiscover` 在「请求里没有可用密钥」时会回退到**当前生效模型**的密钥，
  并用 `key_from_active` 如实回报 —— **前端必须把这个字段显示出来**，
  否则「探测用的到底是谁的密钥」是黑箱，用户只会看到「探测失败」。

### P-044 · 供应商身份用 id，显示名只是外观

**现象**：模型选择栏里同时出现「anthropic」（内核 config 给的 provider id）
与「DeepSeek 官方」（供应商显示名），看着像两个不同的东西。

**根因**：供应商在几份数据里是**不同的键**：
内核 `config.provider` 给 **id**；模型库给 `provider_id` + `provider_name`；
`/api/providers/list` 给**显示名** + `key_plain`。早先把「显示名列表」拿去和内核的 id 比，
永远比不中。

**修法**：`ui/chat/ModelPickerSheet.kt` 的 `buildProviderEntries` 统一**以 id 为身份**，
显示时才翻成名字；去重时**按名字**判（供应商库那份只有名字没有 id，按 id 判会列成两家）。

### P-045 · 内核 `config.provider` 是协议族，不是供应商 id

**现象**：模型选择弹层的供应商行显示 `anthropic`，点进供应商页却是「DeepSeek 官方」。

**根因**：内核 `config/default.yaml` 里

```yaml
llm:
  provider: "anthropic"    # anthropic | openai
```

**这个字段是「协议族」**（决定请求走哪种协议），与供应商库里那些
（id 如 `deepseek`、带显示名的）记录是**两个命名空间**。
壳侧把它当成 provider id 去映射显示名，映射查不到就落回原始 id ——
于是界面看着一点没变。

**当前供应商的真相**：**当前生效模型所属的供应商**，要从模型库条目反查
（`ui/chat/ModelPickerSheet.kt` 的 `resolveActiveProviderId`）。
模型库为空 / 生效模型被删 → **没有当前供应商** → 显示「未选择」。

**首装状态长什么样**：内核会预置一个供应商（如 DeepSeek 官方），
但**模型库是空的**（模型要靠探测添加）。所以「供应商页有东西、模型库为空」
是正常的，不是加载失败。

**内核早给了信号**：`configView()` 返回 `model_in_library`
（「当前生效模型是否仍在模型库中」）—— App 早先没解析。
用它来区分「模型库空了」与「一切正常」，别再拿默认配置的模型 id 冒充当前模型。

### P-046 · 签名密钥不能放在「会被清空」的路径上

**现象**：真机装包报「安装包签名不正确」/ 无法覆盖安装。

**根因**：AGP 默认的 debug keystore 落在 `$ANDROID_HOME/.android/debug.keystore`，
而本沙箱的 `$ANDROID_HOME` 是 `/opt` —— **容器重启就被清空**，
于是每轮构建生成一把新密钥。实测指纹：

```
v3 / v4  → 7b0523dd…   （同一轮工具链内，一致）
v5      → 54d000f2…   （中间 /opt 被清空过，换了）
```

签名一变，Android 就拒绝覆盖安装（`INSTALL_PARSE_FAILED_*`）。

**修法**：固定密钥放在**仓库内**的 `app/keystore/codeforge.keystore`
（`*.keystore` 已被 `.gitignore` 覆盖，不进版本库），`app/build.gradle.kts` 里
显式建 `signingConfigs["cf"]` 并让 debug / release 都用它。

**⚠️ 这把密钥是命根子**：丢了就装不了任何更新（只能卸载重装，用户数据全没）。
换机器构建时必须把它拷到 `app/keystore/` 下。
口令：`codeforge`（store 与 key 同名，alias `codeforge`）。

**推广**：**凡是需要跨环境稳定的产物（签名、密钥、证书），都不能依赖
「构建机的某个目录」** —— 先确认那个目录在容器重启后还在。

**签名方案要全开**（v1 + v2 + v3）：只开 v2 时，部分国产 ROM 自带的安装器
（自己解析 APK 而不走系统 PackageInstaller）会判成「安装包签名不正确」。
开 v1 多写一份 `META-INF/CERT.SF + CERT.RSA`，体积代价可忽略。
验证时 `apksigner verify` 在 minSdk 26 下**不会**报 v1（它按 minSdk 决定验哪些），
要 `apksigner verify --min-sdk-version 23` 才看得到 v1 的真实状态。

**⚠️ 2026-11-05 补充：配置写了全开 ≠ 真的全开。**
上面这套 `signingConfigs["cf"]` 是在 `persistentKeystore.exists()` **为真**时才创建；
密钥不存在时 build.gradle.kts 会 `?: signingConfigs.getByName("debug")` 回落 debug，
而 **debug keystore 只有 v2**（没有 v3）——于是产物签名方案是 v2-only，
而配置里明明写着「v1 + v2 + v3 全开」，看代码完全看不出来。
本轮的实际状况就是如此：`app/keystore/` 目录压根不存在。
**查签名方案要看产物，不要看构建脚本**：

```bash
apksigner verify --verbose app/build/outputs/apk/release/app-release.apk
```

本轮补上密钥后（keytool 生成，RSA 2048 / 10000 天 / 口令 codeforge，
生成命令写在 `app/build.gradle.kts` 的注释里），产物才是
`v2: true / v3: true`（v1 因 minSdk 26 不再被 apksigner 单独验证，
但 `META-INF/CERT.SF` + `CERT.RSA` 确实在包里）。

真机侧可交叉验证：`dumpsys package <包名> | grep signatures`，
`PackageSignatures{... version:3, ...}` 里的 **version:3** 就是 v3 生效的证据。

**换签名 = 装不上更新**：debug 换成自建证书后，设备上旧包无法覆盖
（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），只能卸载重装（用户数据全没）。
换签名方案前先跟用户说清楚。

### P-047 · 改 Kotlin 包名要同步改 JNI 符号并重编 .so

JNI 的符号名是 `Java_<包名下划线化>_<类名>_<方法名>`。
把 `app.codeforge` 改成 `com.hxy.codeforge` 后，原来的
`Java_app_codeforge_render_OpenRenderNative_*` **全部对不上**，
运行时会 `UnsatisfiedLinkError`（而且只在真机上炸，编译期无感）。

**四处必须同步**：

1. `tools/openrender/app_jni.rs` 的函数名（`Java_com_hxy_codeforge_render_OpenRenderNative_*`）
2. `app/proguard-rules.pro` 的 `-keep class com.hxy.codeforge.render.OpenRenderNative`
3. 全部 `.kt` 的 `package` / `import`（本仓 61 个文件）
4. **重编 `libopenrender.so`**（Linux 用新增的 `tools/openrender/build-android.sh`）

**验符号**（改完必查）：

```bash
llvm-readelf -W --dyn-syms libopenrender.so | grep -oE "Java_[a-z_]*OpenRenderNative_[a-zA-Z]*"
```

> 注：`.so` 重编时为省内存关了 LTO（`CARGO_PROFILE_RELEASE_LTO=false`、
> `codegen-units=16`），产物比 LTO 版大约 600 KB —— 换来的是 3GB 内存下能编过。

### P-048 · `source/` 下的内核参考副本不是原仓库，禁止在它里面做 git 写操作

全仓（`source/CodeForge-GO`）

**现象**：把 `source/CodeForge-GO/CodeForge-go` 当成内核仓库，在里面 `commit` 并
`git push`，把提交推到了内核的 gitee / GitHub 远端，污染了用户仓库。

**根因**：参考副本自带 `.git`，`origin` 与内核原仓库同名同地址；git 只看当前目录，
不会提示「你操作的是 vendored 副本」。原仓库实际在
`C:\Users\26536\Desktop\Code\CodeForge-GO`（内含 `CodeForge-go` 子仓库）。

**规则**：
- 内核改动只在原仓库完成、提交、推送；本仓库只接收 `libcodeforge.so` 产物。
- 在 `source/` 下只允许 `git status/log/diff/show` 等只读命令。
- 执行任何 git 写操作前先 `git remote -v`，确认当前目录的远端是不是本仓库（`code-forge-m`）。

**推广**：vendored/reference 目录、submodule、多仓库工作树里，git 命令的作用域永远是「当前目录」；
凡是可能写远端/历史的命令，先确认仓库身份再执行。

> 2026-11-07 起 `source/` 目录已整体移出仓库（内核源码包移至 `D:\code\CodeForgeAPP-private\`），
> 本条保留作历史经验：多仓库/vendored 场景的仓库身份确认纪律仍然适用。

### P-049 · CommonMark 的 HTML 块在空行处结束，缩进行会被切碎，碎片拼不回原文

**现象（雷电真机，2026-11-04）**：模型输出整页 HTML（缩进 + 空行），界面显示成一堆
代码块与段落碎片，**内嵌浏览器完全不出现**。

**根因**：CommonMark 规定 HTML 块（type 6）在**空行**处结束，之后的内容重新按
markdown 解析。于是缩进 ≥4 空格的 HTML 行变成 **indented code block**，
正文被从中间打断，**缩进还被引擎吃掉 4 格**。实测 IR：

```
paragraph "<!DOCTYPE html>\n"
paragraph "<html>\n"
code_block "<div id=\"a\">\n\n    <span>x</span>\n\n</div>\n"   ← 中间的碎片
paragraph "</body>\n"
```

引擎还是**增量**的（`find_commit_point` 在空行处提交前缀），碎片一旦提交就
**再也拼不回原文** —— 边界丢了、缩进丢了。壳侧事后合并（`mergeHtmlBlocks`）
只对「连续的 paragraph」有效，中间夹着 code_block 就断。

**修法（壳侧，喂引擎之前判定）**：`ui/browser/HtmlDetection.kt` 的
`looksLikeWholeHtmlDocument(raw)` 判**原文**是不是一整份文档
（有 doctype 或 `<html>`，且有 `</html>` / `</body>` 闭合，以 `<` 开头、以 `>` 收尾）；
`OpenRenderContent` 在 `!streaming` 时先判这个，命中就**直接挂 `HtmlBlockView`**，
完全不经过 markdown 引擎。

**为什么不在引擎侧改**：引擎是上游 `raine-x/OpenRender`（钉死 commit，见版本锁定），
改它要重编 `.so`；而且碎片在提交时就丢了，引擎侧也没有「原文」可依据。
判定必须在**引擎看到文本之前**发生。

**判定门槛仍要保守**：认错就是把正文里贴的一小段标签当整页渲染（吞掉周围文字）。
围栏形态（```html）由 `code_block` + `lang="html"` 另走一条路，不进这条判定。

### P-050 · 触屏没有 hover，元素挑选不能靠 `mousemove` 记住当前元素

**现象（雷电真机，2026-11-04）**：内置浏览器的「选择元素」模式能开启
（提示条出现、JS 桥通、`startPick` 返回 ok），但**点页面上的任何元素都没反应**，
不弹气泡。

**根因一（hover 不存在）**：原实现靠 `ov.addEventListener('mousemove')` 记住
当前悬停元素，click 时取用。**触屏设备根本没有 mousemove**，`current` 永远是
null，点什么都没反应。这条在真机上也一样犯 —— 手机上用手指点同样没有 mousemove。

**根因二（覆盖层挡着自己）**：透明覆盖层盖在页面上，`document.elementFromPoint(x, y)`
返回的是**覆盖层自己**。必须用 `elementsFromPoint` 拿整条命中链，
跳过覆盖层/高亮/提示/气泡，取第一个真正的页面元素。

**根因三（矩形字段名对不上）**：`describe` 给的矩形是 `{x, y, width, height}`，
而 `placeBubble` 读的是 `r.left` / `r.top` / `r.bottom` —— **全是 undefined**，
气泡被摆到无效位置。已改为统一用 `x` / `y` / `height`。

**修法**：`ui/browser/PageBridge.kt` 的 `SCRIPT` 里
- `click` 与 `touchend` **都**按坐标现场取元素（`pickAt`），不依赖 hover 状态；
- `touchend` 的坐标在 `changedTouches[0]`，`touchmove` 的在 `touches[0]`；
- `mousemove` 只用来做「预览高亮」，不再是取元素的唯一途径。

**推广**：任何「鼠标 hover → 点击」的 WebView 交互，在触屏上都要另给一条
「按坐标现场取目标」的通路；依赖 hover 状态的实现等于只支持鼠标。

### P-051 · 探测命令带管道，退出码取的是 `head` 的，恒为 0

**现象（雷电真机，2026-11-05）**：「运行环境 → 环境软件包」里，C/C++、Java 17、
FFmpeg 三项**默认就是「已装」**（陶土色 + 勾图标），可 rootfs 里连 gcc 都没有。
用户报「默认情况不自带语言但是环境那里却勾选了好几个」。

**根因**：`EnvKit.EnvPackage.probe` 是「跑一下看退出码」的**存在性探测**，
但三个包写成了带管道的形式：

```kotlin
EnvPackage("C/C++", "apk add --no-cache build-base", "gcc --version | head -1")
EnvPackage("Java 17", "...", "java -version 2>&1 | head -1")
EnvPackage("FFmpeg", "...", "ffmpeg -version | head -1")
```

**管道的退出码取自最后一个命令**（`head`）。命令不存在时 shell 报
`gcc: inaccessible or not found`，但 `head` 照样成功退出 0 →
探测结论恒为「已装」。

真机 rootfs 内实测（`/bin/sh -c 'gcc --version | head -1 >/dev/null 2>&1; echo $?'`）：
`gcc` 根本不存在，退出码却是 **0**；去掉管道后是 **127**（command not found）。

**修法**：probe 一律用**不带管道**的单条命令，退出码即目标命令自己的退出码：

```kotlin
EnvPackage("C/C++", "apk add --no-cache build-base", "gcc --version")
EnvPackage("Java 17", "...", "java -version")
EnvPackage("FFmpeg", "...", "ffmpeg -version")
```

**推广**：凡是「靠退出码判断成败」的探测命令，**不许带管道**（`| head` / `| tail` /
`| grep` 会换掉退出码）。要截输出就把重定向放在**整条管道之后**
（`cmd >/dev/null 2>&1`），别在中间插管道。同理 `cmd || true` 也会抹掉失败。

### P-052 · 内核的 frontmatter 解析器不支持 YAML 块标量

**现象（2026-11-05）**：内置纯知识技能（MiniMax skills 仓的 6 个）铺进工作区后，
内核 `/api/skills` 返回的 `description` 只剩一个 **`|`**，触发词全空，技能列表里
每个技能只有一根竖线。

**根因**：内核的 frontmatter 解析（`pkg/agent/skills.go` 的 `parseSkill`）是
**逐行 `strings.Cut(line, ":")`**，不是 YAML 解析器。而上游 skill 普遍用
YAML 块标量写多行描述：

```yaml
description: |
  iOS application development guide covering UIKit, SnapKit, and SwiftUI...
  Use when: developing iOS apps, implementing UI, ...
```

逐行切的结果：第一行 `description: |` → `description="|"`；后面的缩进行各自被
当成 `key: value` 切碎（`Use when` 之类），键名对不上就整行丢弃。

**修法**：内置资源的 frontmatter 一律改写成内核认的**扁平单行**形式：

```yaml
---
name: ios-application-dev
display_name: iOS 应用开发
description: iOS 应用开发指南：UIKit、SwiftUI、布局与安全区、导航、动态字体、深色模式与无障碍。
triggers: ios 应用, ios 开发, swiftui, uikit, swift 开发
enabled: true
---
```

`name` 必须与目录名一致（内核用它做 @ 补全 token）；`triggers` 逗号分隔。

**推广**：跨进程传结构化元数据前，先确认**对面**的解析器到底吃哪种语法 ——
「对方支持 YAML」这个假设往往是错的。本仓已有单测
（`BuiltinSkillsAssetTest`）按内核的朴素规则复刻解析并断言，防回归。

### P-053 · 列表「能滚」和「装得下」是两件事，截断会伪装成前者没问题

**现象（2026-11-05）**：用户反馈「@补全弹出的窗口怎么不支持滑动？」

**根因有两层，第二层才是主因**：

1. 候选列表用普通 `Column` + `forEach` —— 无高度上限、无滚动；
2. **候选被 `.take(4)` 硬截断** —— 只出 4 条。这条更隐蔽：**4 条永远装得下**，
   所以哪怕把容器换成可滚的，也没有「可滑的内容」；用户看到的是
   「后面那些补不出来」，而不是「列表被截断了」。

**修法**（`ui/chat/Composer.kt`）：`LazyColumn` + `.heightIn(max = 240.dp)`，
`.take(4)` → `.take(50)`（留兜底防极端渲染量，不再截断）。

**推广**：给「下拉/补全列表」做滚动之前，先问一句**条目数会不会超过一屏** ——
「加了 LazyColumn 就能滚」这个假设不成立，截断会先把内容吃光。
反过来，用户抱怨「列表不能滚」时，先查**是不是被 `take(n)` 截断了**，
而不是直接去改容器。

**注意**：断言 `take(4)` 消失时要**限定范围** —— 附件芯片与元素芯片另有
`take(4)`（那里是刻意只显示前 4 个 + “+N”），全文件扫会误报。
本仓用 `MentionListScrollTest` 守住这三点。

### P-054 · 能力说明写窄了，模型就只会那一种手段

**现象（2026-11-06）**：用户反馈「找到个视频，它可以用标签显示，却用了网页来显示」。

**根因**：注入给模型的能力说明（`ui/CapabilityPrompt.kt`）只讲了「内嵌可交互网页」，
还附了一份适用范围清单（试题、图表、演示、看板……一律可用网页）。
**能力面被写窄 = 模型只知道一种「能玩起来」的呈现方式** ——
宿主明明有原生播放器、图片通道、音视频跳转卡片，模型一概不用，
全都包成 HTML 页面（网页里嵌 `<video>`、图片、表格）。

**修法**：
1. **把能力面写全**：正文 / 公式 / 图片 / 视频 / 音频 / 音视频跳转 / SVG / 网页，逐条写清语法；
2. **给肯定式路由**（「讲一件事→正文」「放视频→视频或跳转标签格式」「要在页面上动手→才写网页」），
   不用禁令 —— 否定式清单会把模型锚定到那个失败形态（Claude Code 的 prompt-audit 经验）；
3. **把网页的专属约束条件化**（「仅写网页时适用」），否则那些约束本身就是「要富展示就写 HTML」的暗示；
4. 删掉「适用范围」清单 —— 那是偏向的根源。

**顺带暴露的真 bug**（`ui/chat/MessageBlocks.kt` 的 `MEDIA_JUMP_TAG_REGEX`）：
模型很爱把标签写成 Markdown 链接 `[ ▶视频 描述文字 ](url)`。
正则的地址组 `[^\s()（）\]】]+` 会吃掉「描述文字」当地址，方括号后面真正的链接反被丢弃 ——
渲染出一张点开提示「未指定跳转链接」的卡片。
修法：正则末尾加 `(?!\()`，这种形态**不被认成标签**，整段按普通 Markdown 链接渲染，
点开仍是浏览器打开视频，行为正确。

**推广**：
- 宿主每新增一种呈现通道，**必须同步写进能力说明**，否则模型不知道它能用 ——
  提示词是纯文本，没有编译期约束，写漏了不会有任何报错。
- **提示词与实现是两处独立的文本**：提示词里给的示例必须能被**真实解析函数**认出来。
  本仓用 `CapabilityPromptTest` 把这条钉死（示例串直接喂 `parseMediaJumpTag` 断言）。
- 提示词的定位标记 `【CodeForge 客户端渲染能力】` **一个字都不能改** ——
  内核把上行文本原样存进历史，回放时靠标记切掉注入段（P-042）；
  改了标记，旧会话回放会把整段提示词漏回用户气泡。

### P-055 · 裸 VideoView 交给 AndroidView 会被视频内在尺寸反测，MediaController 是独立窗口

**现象（2026-11-07）**：视频块四症状——① 尺寸失控：640×360 视频把外层 `height(240.dp)`
整个无视，后续内容被顶出消息区；② 系统 `MediaController` 的控制条浮在消息区/输入卡
之上（实测越界约 125px）；③ 加载失败只有一块纯黑屏；④ 播完停在末帧，点控制条无法重播。

**根因**（三条叠加，都在 `MessageVideoBlock` 旧实现）：
1. `AndroidView(factory = { view })` 返回的 View 按 **WRAP_CONTENT** 测量，
   `VideoView.onMeasure` 按视频内在分辨率定尺寸，Compose 的尺寸约束对它不生效；
   且 `VideoView` 内是 `SurfaceView`，画面渲染在独立 surface 上，Compose 的
   `Modifier.clip` 管不到它的溢出。
2. `MediaController` 会向 `WindowManager` 注册**独立 Dialog 窗口**，不参与
   Compose 层级裁剪，怎么裁容器都压不住它。
3. 全函数没挂 `setOnErrorListener` / `setOnCompletionListener`。

**修法**（`ui/chat/MessageBlocks.kt` 的 `MessageVideoBlock`）：
- VideoView 装进显式 `MATCH_PARENT + Gravity.CENTER` 的 FrameLayout——尺寸由外层
  定，VideoView 自己按宽高比收缩出黑边（不拉伸变形）；容器 `clipChildren` 兜底裁 SurfaceView；
- 控制条改 **Compose 自绘**（`AudioPlayGlyph` + `Slider` + `formatMs`，与音频块同款），
  `setMediaController` 删除；
- 挂 onError（失败整块换 `MediaErrorRow`，不留黑屏）与 onCompletion
  （`finished` 置位，下次播放 `seekTo(0)` 重播）。

**推广**：
- **`AndroidView.factory` 只在首次挂载时执行一次**，闭包捕获的 Compose 状态委托在
  key 变化（如 `remember(url)`）后全部过期，监听器写在 factory 里会往旧状态对象写。
  监听器必须放 **update**（每次重组重挂、永远拿到最新委托），View 引用经 holder 转发；
  播放状态一律问 View 自己（`vv.isPlaying`），不信 Compose 快照。
- **`DisposableEffect` 的 key 不要放换源参数（url）**：effects 的 disposal 跑在
  composition 应用（update 的 `setVideoURI`）**之后**，`onDispose { stopPlayback() }`
  会把刚设的新源一起杀掉。停播用 `DisposableEffect(Unit)` + `AndroidView.onRelease` 兜底。
- 给 `VideoView`/媒体类 View 上强约束时，**必须显式设 LayoutParams**——
  factory 返回的裸 View 是 WRAP_CONTENT，任何 Compose 尺寸修饰符都压不住它的自测尺寸。
- 同理可推广到一切「AndroidView + 有内在尺寸的 View」组合（WebView 正常是因为它
  尊重父约束，VideoView 这类按内容自测尺寸的控件要先约束后挂载）。

### P-056 · 通知 action 的响应路径不能依赖 ViewModel；cancel(id) 撤不掉带 tag 的通知

**现象**：做「审批走通知栏」时暴露的一串坑：
1. `MainActivity.onNewIntent` 的注释一直声称「singleTop 复用实例」，但 manifest
   **根本没设 launchMode**（默认 standard）——应用在前台点通知会叠一个新 Activity
   实例，deep-link 逻辑（`handleIntent`）压根不执行，注释自欺。
2. 通知 action 要把审批决定发回内核，而 WS 在 Activity 级 ChatViewModel 手里——
   任务被划掉时 `viewModelScope` 取消、transport disconnect，按钮变成死按钮。
3. `NotificationManager.cancel(id)` **不带 tag 撤不掉带 tag 的通知**（tag+id 才是
   主键）——批量撤销用 `cancel(1002)` 一条都撤不掉。
4. `PendingIntent` 同 requestCode + `FLAG_UPDATE_CURRENT` 会**互相覆盖**：
   完成通知用 3、审批正文用 4、批准/拒绝用 5/6——共用一个会把别家的 intent 改掉。

**根因**：通知栏 action 的执行环境是「进程活着，但 UI 栈可死可活」，与
Compose/ViewModel 的生命周期完全不同轴；Notification/PendingIntent 的主键规则
（tag+id、requestCode）也都是隐性契约。

**修法**（`core/LiveTransport.kt` + `core/ApprovalReceiver.kt` + `CoreService` companion）：
- 进程级 holder `LiveTransport.current`：`bind()` 登记、`onCleared()` 置 null；
  receiver 查它拿**已连接的那条 WS**（审批决定不能另开连接，内核 pending 挂在连接上）。
- 生命周期闭环：`onCleared()` 撤光审批通知——划掉任务后通知栏没有按不动的死按钮；
  点正文进 App 靠内核重放 pending 审批恢复卡片。
- 批量撤销走 `mgr.activeNotifications.filter { it.id == APPROVAL_NOTIF_ID }` 逐条
  `cancel(it.tag, it.id)`。
- manifest 补 `android:launchMode="singleTop"`，与既有 `onNewIntent` 逻辑对齐。

**推广**：
- **凡「通知/广播触发、不经 UI」的路径，一律假设 ViewModel 可能已死**——需要的
  协作者（transport、api）必须挂进程级 holder，且 holder 置空与通知撤销要在
  `onCleared` 成对做完，不留中间态。
- `BroadcastReceiver` 里**不能启 Activity**（Android 10+ 后台启动限制）——
  「带用户回 App」只能靠通知的 contentIntent。
- 新增任何 PendingIntent 前先盘点全仓 requestCode：3（完成通知）、4/5/6（审批）、
  0/1（前台通知）、2（停止）已占用，新类别必须错开。
- extra 缺失的布尔按**更安全的一侧**取默认（审批按「拒绝」：误拒绝可重试，
  误批准撤不回）。

### 版本锁定（不要随手升）

- Compose `1.9.5` —— 再高要求 AGP 9.1 + compileSdk 37，整条链要一起动
- AGP `8.13.2` / Gradle `8.14.6` / Kotlin `2.4.21` —— 三者互相配套
- OkHttp `4.12.0` / Coroutines `1.10.2` —— REST、WS 自愈重连、云端语音 multipart；升级要回归重连与转写
- 内置离线语音（Vosk）已于 2026-11-05 **整体移除**（模型 41.9 MB + libvosk.so 10 MB + 依赖）——
  APK 18.2 MB。语音改走「云端 → 系统 `SpeechRecognizer`」两档，**没有离线兜底**。
  `NoOfflineVoiceTest` 守着它不被加回来。
- R8 已启用（release）：规则在 `app/proguard-rules.pro`；**真机日志反混淆必须用**
  `app/build/outputs/mapping/release/mapping.txt`（retrace）。改 keep 规则前先读 P-012 同目录的注释
- 兼容环境资产：`env-kit.tgz`（proot 5.1.107.101 + libtalloc 2.5.0 + libandroid-shmem 0.7，源 Termux 仓库）
  与 `rootfs-alpine.tgz`（Alpine 3.24.3 minirootfs aarch64）；升级/换发行版走 `.envkit/` 重建管道
- targetSdk **28**（2026-10-25 定，侧载定位）—— 升回去前必须复核决策表；lint 已豁免 ExpiredTargetSdkVersion
- ABI 仅 `arm64-v8a` —— Go 内核只交叉编译 arm64，x86 模拟器装不上
- `jniLibs/arm64-v8a/libcodeforge.so` —— **预编译产物，源码不在本仓库**（2026-11-07 起移出，
  材料在仓库外 `D:\code\CodeForgeAPP-private\`，内核原仓库在 `C:\Users\26536\Desktop\Code\CodeForge-GO`；
  重编/交付联系 LICENSE 邮箱）。内核侧端口握手补丁见改动历史 2026-10-25「Go 内核打包接入」条；
  后台脚本任务（`pkg/tasks` + `run_background/list_background/kill_background` + `/api/tasks`）
  见改动历史 2026-10-29 条，旧库备份在 `jniLibs-backup-before-background-tasks-20261029/`；
  图片附件**不设像素上限**（单张 20MiB、最多 4 张、单条消息图片总量 20MiB），`maxIntegrityDecodePixels`
  仅用于本地完整性解码的内存保护，超预算只校验头部、不拒绝上传
- `jniLibs/arm64-v8a/libopenrender.so` —— **预编译产物**，上游 `raine-x/OpenRender` @
  `6f11851722772b1a18388364b20a613a0aa2a020`（2026-10-28，0.1 preview），
  `--features jni,ratex`；**8.9 MB**（内嵌 KaTeX 字体 + Noto Sans SC 子集，16KB 页对齐；
  重编时为省内存关 LTO，比 LTO 版大约 600 KB，见 P-047）。源码/构建脚本/字体子集已随
  so 源码移出仓库（材料在 `D:\code\CodeForgeAPP-private\so-source\openrender-tools\`，
  重编时把 fonts/ 拷回上游 crate 目录供 `include_bytes!`），升上游前必须重跑
  Rust workspace 测试 + 核对 IR 键名（P-027）
- **包名 `com.hxy.codeforge`**（2026-11-04 定，原 `app.codeforge`）—— Kotlin 包、namespace、
  applicationId、JNI 符号、proguard keep 规则五处同步，见 P-047
- **签名密钥 `app/keystore/codeforge.keystore`** —— 固定密钥，丢了装不了更新，见 P-046

### 已确认的设计取舍

| 取舍 | 结论 | 一句话理由 |
|---|---|---|
| UI 框架 | Compose 而非 Flutter | 现有壳已是 Compose，`configChanges` 已声明，换 Flutter 等于推倒重来 |
| 横竖屏判定 | `LocalConfiguration` 而非裸加速度计 | 旋转锁定 / 躺姿 / 分屏三种场景下裸传感器会误判（此条仍适用于分栏判定） |
| 横屏策略 | 窗口锁 `portrait` + `DeviceRotation` 转内容 | 用户要求「不是简单转成横屏」：竖屏元素原样旋转，布局/动画零改动；代价见 P-009（IME 与弹层不随转） |
| 语音识别 | 两引擎：云端（硅基流动，设置里独立配置）→ 系统 `SpeechRecognizer` | 云端只存 Key、接口与模型内置；系统服务在国内 ROM 常缺失；**没有离线兜底**（内置 Vosk 已于 2026-11-05 移除，见 §2.6 注） |
| 对话兜底 | 只走内核；内置测试模型链路已移除 | 壳侧不再维护第二套对话栈，避免两套状态机分叉 |
| 内核改造 | 端口握手 + Server 级运行注册表 + 后台任务（pkg/tasks）；其余零改动 | 现有 REST + WS 契约已完整，新增能力都走独立端点/帧 |
| 三语言路线 | Python/Java 走进程内，只有 Go 需要 exec | 前两者是 ART 上的字节码，天然符合 W^X |
| 多语言支持 | 在 PATH 里装工具链，不在内核里加逻辑 | `run_command` 只跑 shell，不关心编译什么 |
| Markdown 引擎 | 内置 Rust `libopenrender.so`（增量 IR）而非 Kotlin 手写解析 + 公式 WebView | 流式复杂度从 O(N²) 降到 O(增量)，公式离线 PNG 化，去掉每公式一个 WebView；代价是 +4.85 MB `.so` 与上游 0.1 preview 风险 |
| 图片附件上限 | 不设像素上限；单张 20MiB、最多 4 张、单条消息图片总量 20MiB | 用户要求取消像素限制并把单张上限提到 20MB；总量不放大避免单次请求体膨胀上百 MB |
| 无文件夹项目的默认工作区 | `$CELL/workspace/<项目id>`，显示名写入 `workspace_names` | 工作区必须在会话创建前定好且事后不可改；项目 id 稳定、无碰撞，重命名只改显示名不动目录 |
| 网页承载 | 内置 WebView（`ui/browser/`）而非扩展渲染引擎 | 引擎无 Html 块类型（P-041），且用户要「能点的页面 + 选元素」，扩展引擎做不到；裸 HTML 要在喂引擎**之前**认（P-049），元素挑选要支持触屏（P-050） |

---

## 10. 禁止事项

- ❌ 把 so 源码或构建材料重新引入本仓库、或把它们复制/外传到仓库与 `D:\code\CodeForgeAPP-private\` 之外（材料已移出，未经用户确认不得变动）
- ❌ 未经用户明确要求执行 `git push`；用户要求时也只推本仓库 `origin`（`code-forge-m`）
- ❌ 跨层调用（界面层直接 new `HttpCoreTransport`、`core/` import `ui/`）
- ❌ 在界面层放业务状态（会话、消息、运行状态必须来自 `ChatUiState`）
- ❌ 删除或清空 `.workbuddy-ai/`
- ❌ 跳过三份记录直接交付
- ❌ 把「构建成功」当成「功能可用」上报
- ❌ 顺手重构无关代码、顺手改格式
- ❌ 引入新依赖不说明理由
- ❌ 未经确认删除文件（尤其 `D:` 盘上用户的其他东西）