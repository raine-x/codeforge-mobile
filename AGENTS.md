# AGENTS.md — AI 工作准则

> 开工前读第 2、3 节；收尾对照第 8 节。违反第 4 节（三份记录）的产出视为**未完成**。

---

## 1. 项目边界

CodeForge-Go 的 Android 宿主壳。Go 内核是 AI 编码 agent（原本配网页界面），本项目把它做成原生 App：
Kotlin + Compose（无 XML 布局），内核以预编译 `libcodeforge.so` 独立进程运行，REST + WS 通信。

```
CodeForgeAPP/
├── README.md / README_ZH.md / README_EN.md   门面 / 中文详版 / 英文详版
├── LICENSE                                   MIT + 商业授权双许可 + 第三方组件清单（2653603072@qq.com）
├── AGENTS.md · 工作进程.md · 改动历史.md       三份记录（见第 4 节）
├── docs/android-native-plan.md               方案与架构（不属于三份记录）
├── app/src/main/java/com/hxy/codeforge/      Kotlin + Compose
└── app/src/main/jniLibs/arm64-v8a/           libcodeforge.so + libopenrender.so（预编译产物）
```

**硬边界**
- **`.so` 源码不在本仓库**：内核与 OpenRender（含 JNI 桥补丁、构建脚本、字体子集）只以预编译产物入库。
  材料在仓库外 `D:\code\CodeForgeAPP-private\`；内核原仓库在 `C:\Users\26536\Desktop\Code\CodeForge-GO`。
  **未经确认不得把源码/构建材料重新引入本仓库**，也不得改上面两个外部源码仓库。
- **包名 `com.hxy.codeforge`**：Kotlin 包、namespace、applicationId、JNI 符号、proguard keep 五处同步（P-047）。
- **未经确认不删除文件/目录**（尤其仓库外的用户数据）；不执行与任务无关的破坏性操作。

---

## 2. 架构分层

### 2.1 分层与数据流

```
入口层  MainActivity / CodeForgeApp        建窗口、主题、通知、拉起 CoreService、启动流水线
状态层  ui/ChatViewModel                   唯一状态源 ChatUiState（把事件折叠成界面状态）
界面层  ui/**（AppRoot · chat · sessions · settings · browser · common · theme）  纯 Compose
协议层  core/CoreTransport + HttpCoreTransport   JSON 帧 ⇄ CoreEvent（HTTP + WS）
内核层  libcodeforge.so                    独立进程，127.0.0.1:<随机端口>，Cookie 鉴权
```

**单向数据流**：用户操作 → ViewModel → transport → 内核 → WS 推帧 → 解析成 `CoreEvent` →
折进 `ChatUiState` → Compose 重组。跨层共用只有 `model/CoreModels.kt`（纯数据类）。

### 2.2 各层职责

| 层 | 文件 | 职责 |
|---|---|---|
| 入口 | `MainActivity.kt`、`CodeForgeApp.kt` | 建窗口、edge-to-edge、状态栏显隐、通知权限、拉起 CoreService、首启流水线（§2.5） |
| 状态 | `ui/ChatViewModel.kt` + `ChatViewModel{Sessions,Models,Plugins,Workspace}.kt` | 唯一状态源、事件折叠、发送串行队列、项目/工作区、模型/插件配置、后台任务、页面元素登记 |
| 界面·骨架 | `ui/AppRoot.kt` | 竖屏单栏抽屉 / 横屏双栏（侧栏+聊天）、“环境准备中”遮罩、顶栏/横幅/问候页、弹层挂载、内置浏览器浮层 |
| 界面·聊天 | `ui/chat/` | `MessageList`（贴底跟随、回到最新、探索分组）· `MessageBubble`（气泡/思考/操作行）· `OpenRenderMessage`（IR→Compose 排版、媒体围栏路由、HTML 块）· `MessageBlocks`（媒体与附件渲染、`LocalAttachmentRoot`）· `Composer` + `ComposerPanels`（悬浮输入卡、附件/技能弹层、四态主按钮、双引擎语音、模型弹层、@ 补全）· `ToolCard` / `ToolLabels` / `ApprovalCardView` / `ActionPanel` / `BackgroundTasksSheet` / `ModelPickerSheet` / `StatusStrips` |
| 界面·浏览器 | `ui/browser/` | `BrowserOverlay`（全屏页）· `PageBridge`（JS 桥 `window.__cf`）· `BrowserWebView` · `HtmlBlockView`（流内 HTML 块）· `HtmlDetection`（裸 HTML 判定/合并）· `BrowserModels` / `BrowserController` |
| 界面·文件 | `ui/files/` | `FileManagerSheet`（当前工作区/默认空间/全部工作区/手机存储/暂存缓存统一文件浏览、搜索排序、预览/播放、导入导出、重命名与安全删除、引用与装入附件） |
| 界面·会话 | `ui/sessions/` | `SessionPane`（项目分组/侧栏）· `ProjectPanel` · `FileTreePanel` · `McpSidebarPanel`（三段插件面板）· `PluginDetailSheet` · `SessionActionsSheet` |
| 界面·设置 | `ui/settings/` | `AppSettings`（Compose 状态 + 加密 SharedPreferences）· `SettingsSheet` + `SettingsModelSection`（二级页、模型与供应商库）· `FileTransferSection` · `ModelEditorSection` · `KernelSettingsSection` · `EnvPackagesSection` |
| 界面·通用 | `ui/common/` `ui/theme/` | `Responsive`（三档判定）· `Glyphs`（图标库）· `NoRipple` · `VoiceInput`（系统识别）· `DeviceRotation`（已停用的旧内容旋转遗留实现）· `StatusBarVisibility`（主窗+弹层状态栏显隐同步）· `ToastHost` · `Theme.kt`（暖色配色/字体/`AppBackground`） |
| 界面·提示词 | `ui/CapabilityPrompt.kt` | 注入给模型的「客户端渲染能力」说明与注入标记（P-042 / P-054） |
| 协议 | `core/` | `CoreTransport`（`CoreEvent` + 接口）· `HttpCoreTransport`（HTTP+WS 自愈重连/帧解析）· `AttachmentParsers` · `EnvProbe` |
| 服务 | `core/` | `CoreService`（specialUse 前台服务：拉起 `.so`、PORT 握手、退避重启）· `KernelApi`（REST 客户端，Cookie 同 WS）· `KernelConfig`（设置 ↔ local.yaml）· `ModelPresets`（国内外常见模型上下文规格预设与 ≥128K 默认兜底）· `EnvKit`（proot+Alpine、`$CELL` 单根、cfrun、软件包）· `BuiltinSkills` · `PluginInstaller`（`.cfplugin` 校验与原子安装）· `PluginSkills`（多工作区技能同步）· `LiveTransport`（进程级 WS holder）· `ApprovalReceiver`（通知 action） |
| 语音 | `voice/CloudVoice.kt` | 云端识别（OpenAI 兼容 `/audio/transcriptions`）：16k PCM → WAV → multipart |
| 模型 | `model/CoreModels.kt`、`model/PluginModels.kt` | 纯 data class / enum，无逻辑无 Android 依赖 |
| 渲染 | `render/` | `OpenRenderNative`（JNI 封装，加载失败进程级判死退纯文本）· `RenderIr`（IR 解析、delta 应用、公式定界符归一） |

`voice/` 不碰内核帧，是跨界面能力（Composer 输入 + 设置页状态），不引 Compose 之外的 Android UI。
`render/` 只服务界面：只做 Markdown → IR，不画像素；`core/`、`ChatViewModel` 不许依赖它。

### 2.3 层间规则（硬性）

1. **界面层不许碰协议层**：`ui/` 不许 `import ...core.HttpCoreTransport`。要新数据就加 `ChatUiState` 字段。
2. **界面层不许有自己的业务状态**：展开/折叠可用 `rememberSaveable`；会话/消息/运行态一律来自 `ChatUiState`。
3. **状态层不许碰 Android UI**：`ChatViewModel` 只依赖 `CoreTransport` 与 `model`，不引 Compose。
4. **协议层不依赖界面**：`core/` 不许 import `ui/`；事件用 `CoreEvent`，不把原始 JSON 透传给上层。
5. **模型层保持纯粹**：`CoreModels.kt` 只有 data class / enum。
6. **服务层不参与业务**：`CoreService` 只管进程拉起、存活、重启。
7. **渲染引擎层只服务界面**：`render/` 不依赖 `ui/`、`core/`、`ChatViewModel`。

### 2.4 改功能该动哪层

| 想做什么 | 改哪里 |
|---|---|
| 气泡样式/间距/圆角 | `ui/chat/MessageBubble.kt` |
| 横竖屏断点或分栏宽度 | `ui/common/Responsive.kt`、`ui/AppRoot.kt` |
| 横竖屏旋转策略 | `AndroidManifest.xml`（不锁方向）+ `MainActivity.resolveRequestedOrientation`（跟随系统原生旋转 / 锁定竖屏） |
| 语音输入（语言/交互/降级） | `ui/common/VoiceInput.kt` → `ui/chat/Composer.kt`（云端 → 系统 `SpeechRecognizer`） |
| 云端语音配置 | `AppSettings.sttCloud*` + `SettingsSheet` 的 Voice 段 + `voice/CloudVoice.kt` |
| 输入框行为（多行、发送键） | `ui/chat/Composer.kt` |
| 贴底跟随 / 回到最新 | `ui/chat/MessageList.kt`（P-001 / P-002） |
| 配色 / 字体 / 深色 / 背景图 | `ui/theme/Theme.kt` |
| Markdown 解析范围 | 引擎源码不在仓库（重编 `.so` 走 LICENSE 邮箱）；壳侧解析在 `render/RenderIr.kt` |
| 正文排版（标题/列表/表格/代码块/公式外观） | `ui/chat/OpenRenderMessage.kt` |
| 公式定界符兼容 | `render/RenderIr.kt` 的 `normalizeMathDelimiters`（P-028） |
| 公式中文渲染 | 引擎侧 `install_unicode_font` 随源码移出仓库（P-039） |
| 表格列宽 | `OpenRenderMessage.kt` 的 `columnWidths`（按实际宽度分配，别退回字符数加权） |
| 可交互网页 / 元素挑选 | `ui/browser/` + `OpenRenderMessage.kt` 的 `mergeHtmlBlocks`；引擎没有 Html 块（P-041 / P-049 / P-050） |
| 注入给模型的能力说明 | `ui/CapabilityPrompt.kt` 的 `CLIENT_CAPABILITY_PROMPT`（P-042；改通道清单必须跑 `CapabilityPromptTest`，P-054） |
| 横屏侧栏折叠 | `AppRoot` 的双栏布局（`sidebarCollapsed` + 宽度过渡）+ `TopBar.onToggleSidebar` |
| 模型库 / 探测 / 测试连接 | `KernelApi.discoverModels` / `testModel`（字段名必须与内核 `modelTestReq` 一致，P-043） |
| 供应商显示 | `ModelPickerSheet.buildProviderEntries` + `Composer` 的 provider 派生（身份用 id，P-044 / P-045） |
| 消息块渲染（SVG/图片/视频/音频/原样） | `ui/chat/MessageBlocks.kt` + `CoreModels` 的 verbatim 字段（视频见 P-055） |
| 工具卡片 / diff 着色 | `ui/chat/ToolCard.kt`、`ToolLabels.kt` |
| 上下文表 / 任务清单 / 子智能体卡 | `ui/chat/StatusStrips.kt` |
| 选择模型弹层 | `ui/chat/ModelPickerSheet.kt` |
| 设置项（旋转/语音/外观/内核） | `AppSettings`（存储）+ `SettingsSheet`（弹层） |
| 状态栏显隐（默认隐藏） | `AppSettings.showStatusBar` + `MainActivity` + `ui/common/StatusBarVisibility.kt`（同步主窗与所有 `ModalBottomSheet` Dialog 窗） |
| 附件选择/上传/预览 | `Composer`（stage → outbox）+ `ChatViewModel.uploadAttachments`（<20MiB 走 `/api/upload_file`，≥20MiB 或服务端拒收自动直写 `<workspace>/imports/` 并剥离 WS `attachments[]`）+ `MessageBlocks` 的 `LocalAttachmentRoot`（P-034） |
| 手机文件导入/导出与统一管理 | `EnvKit` 文件传输/文件管理原语 + `ui/files/FileManagerSheet.kt` + `ui/settings/FileTransferSection.kt` |
| 内置技能 | `assets/builtin-skills.zip` + `core/BuiltinSkills.kt`；frontmatter 必须扁平单行（P-052） |
| 动态插件包（`.cfplugin` 导入/启停/密钥/多工作区技能同步） | `model/PluginModels.kt` + `core/PluginInstaller.kt` + `core/PluginSkills.kt` + `ui/sessions/McpSidebarPanel.kt` / `PluginDetailSheet.kt` |
| 环境软件包（增删包/探测命令） | `EnvKit` 的 `packages`；probe 不许带管道（P-051） |
| @ 补全候选 | `Composer` 的 `mentionCandidates` / `mentionMatches`；LazyColumn + heightIn，不许 `take(n)` 截断（P-053） |
| 后台脚本任务 | 内核 `pkg/tasks` + `/api/tasks` + `BackgroundTasksSheet` + `ChatViewModel.refreshTasks/startTask/killTask` |
| 内核运行设置（生成/重试/压缩/子智能体/通知） | `KernelSettingsSection` → `ChatViewModel` → `KernelApi` |
| 首启「环境准备」流程 | `EnvKit` + `CodeForgeApp.kt`，并同步 §2.6 |
| 审批卡片字段/按钮 | `CoreModels` → `ApprovalCardView` → `HttpCoreTransport` 的 `hitl_request` 分支 |
| **新增内核事件** | ① `CoreTransport` 加 `CoreEvent` 子类 ② `ChatViewModel.onEvent` 加分支 ③ `HttpCoreTransport.handleFrame` 加解析（帧名/键名以 `pkg/agent/agent.go` 与 `pkg/server/ws_handler.go` 为准，注意 P-008） |
| 协议字段名 / 新增字段 | `HttpCoreTransport.handleFrame` 与 `parse*` |
| 加新页面 | `ui/` 下新建包，在 `AppRoot` 挂入口 |
| 加新状态字段 | `ChatUiState` + `onEvent` 对应分支 |

### 2.6 首启流水线

步骤全部注册在 `CodeForgeApp.onCreate`，顺序固定，新增必须同步本节：

1. 崩溃落盘（`filesDir/logs/crash-*.txt`，留 5 份）+ `ProcessLifecycleOwner` 维护前后台可见性；
2. `AppSettings.init`（首帧前必须完成）；
3. 附件孤儿清理（`attachments/` 下超 7 天）；
4. `EnvKit.ensureReady`：解压 `env-kit.tgz`（proot）与 `rootfs-alpine.tgz` 到 `filesDir`，生成 `usr/bin/cfrun`；期间盖「环境准备中」遮罩，成功写 `.ready`，失败不挡屏可重试。**一切锚定 `filesDir` 单根**，内核经 `CF_EXEC_WRAPPER` 走 cfrun；
5. 通知渠道与内核进程：`CoreService.ensureChannel()`；`MainActivity.onStart` 每次 `startForegroundService`（幂等），PORT 握手与退避重启都在 CoreService。

规则：任何「首启要做、之后跳过」的步骤一律走这条流水线，状态用 `EnvKit` 的 Status 模式（Idle/Preparing/Ready/Failed），**不要各自发明**。

---

## 3. 开工前必读

1. `README.md`（门面）、`README_ZH.md`（详版）
2. `工作进程.md`（做到哪、下一步）
3. 本文件第 2 节（分层）与第 9 节（坑）
4. `docs/android-native-plan.md`（为什么这么设计、已知风险）
5. 要改的文件本身

**不许**只看待办清单就动手 —— 约束往往在分层规则与坑列表里。

---

## 4. 三份记录（强制）

| 文件 | 只管 | 不管 |
|---|---|---|
| `AGENTS.md` | 分层、编辑准则、验证标准、提交规范、坑、边界 | 进度、逐次改动 |
| `工作进程.md` | 待办（`- [ ]`/`- [x]`，分组 A/B/C）、已办摘要、待决策、已知问题 | 改了哪个文件、为什么这么设计 |
| `改动历史.md` | 每次改动的时间/文件/类型/改了什么/为什么 | 还没做的事 |

**必须写的时机**
- 踩坑并修掉 → 第 9 节新增 `P-0XX`（写可执行规则，不写故事）
- 新增/调整架构层、约束变化（版本锁定/ABI/targetSdk）→ 第 2 节与第 9 节约束表
- 完成待办 → 勾选；发现新待办 → 加进分组；状态实质变化 → 更新「一句话现状」
- **任何**文件被创建/修改/删除/重命名之后 → 追加 `改动历史.md`（含文档本身）

`改动历史.md` 格式（按时间**正序**追加，不覆盖）：

```markdown
## YYYY-MM-DD HH:MM · 标题
### 描述      为什么做，解决什么问题
### 涉及文件   | 时间 | 文件 | 类型 | 改动内容 |（具体到哪一行/哪个函数）
### 验证      跑了什么、结果如何；没验证的必须写明，不许写「应该没问题」
```

---

## 5. 编辑准则

**改之前**：先读再改（要改的文件完整读一遍）；先查再问；涉及运行中程序先确认状态；按 §2.4 定位层级，不跨层改。

**改的时候**：最小改动（不顺手重构、不顺手改格式）；保持既有风格（中文注释、讲「为什么」、不用 emoji）；新增依赖要在 `改动历史.md` 说明理由/版本/体积。

**注释**：解释「为什么这样」与「不这样会怎样」，不复述代码。

```kotlin
// 好：只用「手指在拖」判定，不能用 isScrollInProgress ——
// 后者把程序化滚动也算进去，流式跟随里我们自己调的 scrollToItem 会让它短暂为 true。
// 差：判断是否被拖动
```

---

## 6. 验证准则

**「构建成功」不是结论。** 回复里必须给出**产物路径、文件名、大小**，并注明验证到第几级。
可靠性从高到低：

1. **跑起来断言**（装上设备实际操作）
2. **读产物内部结构**（解包 / `aapt2 dump` / 看 dex）
3. **A/B 对照**（改前改后各跑一次）
4. **静态检查**（读代码、查 API 签名）

拿不准就说拿不准，不要用「应该」「理论上」包装。`改动历史.md` 的「验证」栏必须如实写跑到哪一级；没跑运行时写「运行时未验证」。
注意：增量构建可能报 `UP-TO-DATE` 骗人（新增文件未编译），必要时 `--rerun-tasks`。

---

## 7. 提交

**禁止主动提交和推送** —— 只在用户明确要求时执行，且只推本仓库 `origin`。
不要 `git add -A`（用户常同时持有未提交改动）；不要 `--no-verify`。

---

## 8. 任务结束前自检

- [ ] `工作进程.md` 已勾选/新增
- [ ] `改动历史.md` 已追加本次全部改动（含文档本身）
- [ ] 改了分层或踩到新坑 → `AGENTS.md` 第 2 / 第 9 节已同步
- [ ] 「验证」栏如实写了跑到哪一级、什么没验证
- [ ] 没有跨层改
- [ ] 没有主动 commit / push，没有 `git add -A`
- [ ] 没有把 `.so` 源码或构建材料重新引入仓库
- [ ] 回复里说清了：改了什么、验证到什么程度、还剩什么没做

---

## 9. 已经踩过的坑

> 仅在遇到相关问题时查。编号被代码注释大量引用（`见 P-0XX`），**只压缩文字，不许改号**。

### P-001 · 贴底跟随不能用 `isScrollInProgress`
`MessageList.kt`。它把程序化滚动也算成拖动 → 流式输出时自动跟随会断。改用 `interactionSource.collectIsDraggedAsState()`。

### P-002 · `scrollToItem(lastIndex)` 不等于滚到底
它把第 index 项**顶部**对齐视口顶部。末尾垫 1dp 哨兵 item 再滚到它。

### P-003 · 内核鉴权是 Cookie，不是 `?token=`
`server.go` 的 `validToken()` 只读 `codeforge_token` Cookie。连接顺序必须是「先 GET `/` 收 Cookie，再连 `/ws`」。

### P-004 · `Row` 里的长文本要 `weight(1f)`
不加就超出即裁，中文长句最明显。

### P-005 · `dataSync` 前台服务在 Android 15+ 有 6 小时/天上限
已改 `specialUse` + `FOREGROUND_SERVICE_SPECIAL_USE` + `<property>`。常驻服务先查配额。

### P-006 · git 命令的作用域永远是「当前目录」
本仓库 `origin` 是 `code-forge-m`；`source/` 等 vendored 目录里是**另一个仓库**（P-048）。写操作前先 `git remote -v`。

### P-007 · KDoc 里出现 `*/` 会提前终止块注释
写「通配路径」（`tool_*/`、`a/**/b`）要展开或改成「…… 等」。编译报「注释行语法错」先查这个。

### P-008 · 内核同名帧的载荷键不统一
`sessions` 帧的数组叫 `items`（`ready` 帧叫 `sessions`）；`warn` 的错误键是 `error`。**逐帧核对构造点的 map 字面量**，不能从相似帧类推（REST 同理：`/api/models/list` 是 `{models, providers, active}`）。

### P-009 · 锁竖屏 + 内容自旋转的两个边界
① 对调宽高后必须 `align(Center)`，否则内容偏出；② IME 与 M3 弹层是**独立窗口**，不随内容旋转 —— 这是该路线的固有边界，不是 bug。

### P-010 · 「物理量 → 屏幕变换」必须真机校准
传感器方位角与屏幕坐标系手性不同，纸面推导会差一个符号（实测反了 180°）。旋转、缩放、迟滞阈值同理。

### P-011 · assets 压缩包要 `openFd` 精确长度必须 `noCompress`
否则只能流式读、拿不到总长。反过来：只流式读的包**不要**进白名单（压缩更省体积）。

### P-012 · AAPT2 会自动解压 `.gz` 资产并去后缀
`x.tar.gz` 在包里变成 `x.tar`。用 `.tgz` / `.zip` / `.tar`；`noCompress` 拦不住。

### P-013 · `WindowInsets.get*()` 返回像素
直接 `.dp` 当 dp 用会按密度放大 N 倍。必须过 `with(density) { px.toDp() }`。

### P-014 · proot 移出 Termux 目录必须显式给两个环境变量
`PROOT_TMP_DIR`（默认 `/tmp`，Android 不可写）与 `PROOT_LOADER`（默认指向 Termux 路径）；绑定前先 `[ -e ]` 探测宿主路径存在。**落盘脚本不会随 APK 自动更新** —— 升级逻辑要配「已就绪也重写」的下发层。

### P-015 · 并行会话同仓开发：补齐前先全仓查重
「缺什么补什么」前先 `grep -rn 符号名`；优先改既有文件，别新建可能撞名的组件。

### P-016 · 原生库二次异常是 `NoClassDefFoundError`
`System.loadLibrary` 失败一次后该类被标记初始化失败，后续抛 `LinkageError` 家族（不是 `Exception`）。catch 要盖 `LinkageError` + 进程级 `nativeDead` 标记，不重试。

### P-017 · 含反斜杠的补丁必须直写文件
shell heredoc / python 字面量 / 工具参数各吃一层转义。用 Edit/Write 直写；编译器报 `Unsupported escape sequence` 先怀疑这个。

### P-018 · release 包资源被 R8 改名
`isShrinkResources` 时 `res/` 下搜不到原名。验证资源必须走 `aapt2 dump badging / xmltree / resources`，不能用 `unzip -l | grep` 下结论。

### P-019 · 连本地服务不能押注一次连接
内核刚 bind、accept 未就绪的毫秒窗口足以打死一次连接。做法：打业务端点 `/api/health` 探活 → GET `/` 取 Cookie → 连 WS → 退避 1/2/4/8s 重连；`Link(connected)` 与 `Ready` 分离；`disconnect()` 不 `shutdown` client。

### P-020 · Alpine 仓库源三个坑
① `repositories` 是**空文件**不是不存在（用 `ifBlank`）；② 版本目录必须带 `v`（`v3.24`）；③ 单镜像在移动网络会 404，默认写多家并列。

### P-021 · 外部包管理器的退出码 ≠ 功能可用
proot 下 `apk add` 报「failed to write database」，但包实际可用。判定改为「退出码 0 **或** probe 成功」。

### P-022 · 本地改完不能指望内核推帧刷新
删除/归档/重命名后内核**不推** `sessions`。先本地更新，再 `listSessions()` 拉一次真值对齐。

### P-023 · `AnimatedContent` 别塞进 `verticalScroll`
纵向约束无限 → 高度测量塌陷，新旧页永久叠在一起。要么把滚动下沉到子页，要么只用 `animateContentSize`。

### P-024 · 系统 Toast 位置在 Android 16 不可控
`setGravity` 被忽略、被手势条切。改用自绘浮层（Compose Box）；代价是盖不住弹层 —— 涉及弹层的操作要主动 `sheet = null`。

### P-025 · 同一路径不同 method 语义完全不同
`/api/plugins` 的 POST 是「添加」，启停走 **PATCH** 且字段是 `name` 不是 `id`。接端点前逐字核对 method + 字段名。

### P-026 · JNI 符号 = 包名 + 类名 + 方法名
改名即 `UnsatisfiedLinkError`（只在真机炸）。要同时满足：符号名对齐、R8 `-keep`、ELF `PT_LOAD` 对齐 ≥ 0x4000。

### P-027 · OpenRender IR 键名按类型分叉
`text/code` 用 `text`、`latex_inline` 用 `tex`、`image/audio` 用 `src` + `alt`。新块型一律对照 `json.rs` 的构造点，不要从相似 span 类推。

### P-028 · 公式定界符归一必须跳过代码围栏与 `$$` 内
否则代码里的 LaTeX 示例被改写、`\\[2pt]` 变成 `$$2pt]`。逐行状态机维护两个开关。

### P-029 · `sessions` 帧不带运行态
载荷里没有 busy，整体覆盖会把本地 `busy` 冲掉。列表帧到达时 `mergeBusy(incoming, previous)`。

### P-030 · `POST /api/workspace` 不防「有会话在跑」
跨项目切换必须壳侧先查 `sessions.any { it.busy }`，否则上一轮工具调用会落进新项目目录。

### P-031 · 三态 JSON 字段不能用 `optBoolean(key, false)`
`vision`/`video` 是 `Boolean?`：缺失/null/true/false 四态，「未声明 ≠ 否定」。先 `has() && !isNull()` 再取值。

### P-032 · `session` 帧不是 `history`
每条能确立会话 id 的事件（`session` / `history` / 换绑）都要同步 `currentSession`；id 变了就清空全部会话级派生状态。

### P-033 · 会话的工作区在创建那一刻写死
不能事后按会话 id 猜目录。未绑定工作区的会话统一用 `spaces/inbox`；新建会话前若根为空先 POST inbox。

### P-034 · 附件落点不能由壳侧猜
统一走 `POST /api/upload_file`（写进内核当前工作区 `attachments/`），气泡存**服务端返回的相对路径**，预览用 `LocalAttachmentRoot` 解析。

### P-035 · M3 `DropdownMenu` 默认取 `surfaceContainer`
自定义主题不覆盖就两个色系。显式传 `containerColor` / `tonalElevation = 0.dp` / 描边；菜单项显式 `itemColors`。

### P-036 · 设了 `Cancel` 的 `exec.Cmd` 必须用 `CommandContext` 创建
否则 `os/exec` 校验失败（后台任务全部起不来）。取消时手动 `cmd.Cancel()` 并以 Wait 返回为准。

### P-037 · 长生命周期输出要分别设预算
内存（128KiB 环形尾）、连接（WS 只推状态）、模型上下文（工具结果截断）三个消费者三份预算，不能共用全量数据。

### P-038 · 同一实体的两份数据用了两套键
`/api/providers/list` 给显示名，模型库给 `provider_id`+`provider_name` —— 按字符串拼接会重复。先归一身份键再合并。

### P-039 · Android 没有 Unicode 回退字体
含中文的 `\text{}` 会让整块公式渲染失败。修法：把 Noto Sans SC 子集内嵌进 `.so`、早于首次渲染设置 `RATEX_UNICODE_FONT`（字体查找结果被 `OnceLock` 缓存）。**别用壳侧改写源码去绕**（拆行内会破坏 `aligned/cases` 环境）。

### P-040 · JVM 不读 `http_proxy`
Gradle 走代理必须写 `systemProp.*` 到 `~/.gradle/gradle.properties`；发行版可从镜像预置到 wrapper 报错路径。

### P-041 · 引擎没有 Html 块类型
`Event::Html` 被当纯文本推进 paragraph。围栏形态走 `code_block` 的 `lang="html"`；裸 HTML 必须在**喂引擎之前**判定（P-049）。

### P-042 · 内核没有「系统提示词」字段
能力说明只能混进上行文本（与气泡文本分开）；内核把上行文本存进历史 → 回放时按 `INJECT_CAPABILITY_MARKER` 切掉。
**⚠️ 标记一个字都不能改**：旧历史里存的是旧标记，改了旧会话就会把整段提示词漏回气泡。

### P-043 · 内核 `modelTestReq` 字段名必须逐字对齐
`provider_id` / `model` / `key_source` / `key_name` / `key_value`。Go 的 `json.Decoder` **静默忽略未知字段** → 探测会退回当前生效模型的地址与密钥。前端必须显示 `key_from_active`。

### P-044 · 供应商身份用 id，显示名只是外观
模型库给 `provider_id`，`/api/providers/list` 只给显示名。以 id 为身份，去重按名字判（供应商库那份没有 id）。

### P-045 · `config.provider` 是协议族，不是供应商 id
取值只有 `anthropic | openai`（决定走哪种协议）。当前供应商要从**生效模型所属供应商**反查；模型库空 = 没有当前供应商，显示「未选择」。内核已给 `model_in_library` 信号。

### P-046 · 签名密钥不能放在会被清空的路径
固定密钥在仓库内 `app/keystore/codeforge.keystore`（口令 `codeforge`，**丢了装不了更新**）。签名方案要看**产物**：`apksigner verify --verbose`；配置写了全开 ≠ 真的全开（缺密钥会回落 debug，只有 v2）。

### P-047 · 改 Kotlin 包名要同步 JNI 符号并重编 `.so`
四处必须同步：`tools/openrender` 侧函数名（现已移出仓库）、`proguard-rules.pro` 的 keep、全部 `.kt` 的 package/import、重编 `.so`。验符号：`llvm-readelf -W --dyn-syms | grep Java_com_hxy_codeforge`。

### P-048 · `source/` 下的内核副本不是原仓库
它有独立 `.git` 与同名远端，**禁止在其内做任何 git 写操作**（add/commit/push/reset）。只允许 `status/log/diff/show`。

### P-049 · CommonMark 的 HTML 块在空行处结束
缩进 ≥4 空格的 HTML 行会变成 `code_block`，碎片在引擎提交时已丢失、拼不回原文。修法：`!streaming` 时先用 `looksLikeWholeHtmlDocument(raw)` 判**原文**，命中则直接挂 `HtmlBlockView`，不经过引擎。判定门槛要保守（认错会把正文里的一小段标签当整页）。

### P-050 · 触屏没有 hover
元素挑选不能靠 `mousemove` 记当前元素（永远 null）。`click`/`touchend` 按坐标现场取（`elementsFromPoint`，跳过覆盖层），`touchend` 坐标在 `changedTouches[0]`；矩形字段统一 `x/y/height`。

### P-051 · 探测命令带管道，退出码取的是最后一个命令
`gcc --version | head -1` 恒为 0 → 全被误判「已装」。probe 一律单条命令不带管道，重定向放整条管道之后。

### P-052 · 内核 frontmatter 解析器不支持 YAML 块标量
它是逐行 `strings.Cut(line, ":")`。内置技能 frontmatter 必须**扁平单行**；`name` 与目录名一致，`triggers` 逗号分隔。

### P-053 · 列表「能滚」和「装得下」是两件事
`take(4)` 截断会伪装成「后面补不出来」。用 `LazyColumn` + `heightIn(max)`，候选不截断（附件/元素芯片的 `take(4)` 是刻意显示上限，别一起改）。

### P-054 · 能力说明写窄了，模型就只会那一种手段
只讲「内嵌网页」+ 适用范围清单 → 模型把本该走原生通道的内容也包成网页（视频不做成播放器、做成页面）。
修法：**能力面写全**（正文/公式/图片/视频/音频/跳转标签/SVG/网页）+ **肯定式路由** + 网页约束条件化；不用禁令（禁令会把模型锚定到失败形态）。
配套：提示词里的示例必须能被**真实解析函数**认出（`CapabilityPromptTest` 直喂 `parseMediaJumpTag`）；`MEDIA_JUMP_TAG_REGEX` 末尾 `(?!\()` 挡住 Markdown 链接形态被误认。

### P-055 · 裸 `VideoView` 被内在尺寸反测，`MediaController` 是独立窗口
`AndroidView(factory = { VideoView })` 按 WRAP_CONTENT 测量、按视频分辨率定尺寸，外层 `height()` 无效；
`MediaController` 注册**独立 Dialog 窗口**，压不住、会盖住输入卡。
修法（`MessageBlocks.MessageVideoBlock`）：VideoView 装进显式 `MATCH_PARENT` 的 FrameLayout（容器 `clipChildren` 兜底）+ 控制条改 Compose 自绘（`AudioPlayGlyph` + `Slider`）+ 挂 onError/onCompletion。
推广：**`AndroidView.factory` 只执行一次**，闭包捕获的状态委托会过期 —— 监听器必须放 `update`，播放状态问 View 自己；`DisposableEffect` 的 key **不要放换源参数**（disposal 跑在 update 之后，会把新源杀停）。

### P-056 · 通知 action 不能依赖 ViewModel
审批 action 的执行环境是「进程活着、UI 栈可能死」。WS 用进程级 holder `LiveTransport.current`（`bind` 登记、`onCleared` 置 null 并撤通知）。
另外三条：`cancel(id)` **撤不掉带 tag 的通知**（tag+id 才是主键，批量走 `activeNotifications`）；`PendingIntent` 同 requestCode + `FLAG_UPDATE_CURRENT` 会互相覆盖（3 完成 / 4·5·6 审批 / 0·1 前台 / 2 停止 已占用）；`BroadcastReceiver` 里不能启 Activity。
`BroadcastReceiver` 缺失的布尔按**更安全的一侧**取默认（审批按「拒绝」）。

### P-057 · 重编 `.so` 前必须证明「源码谱系 ⊇ 现役产物 ∪ 历史版本」
现役或历史 `.so` 里的安卓专属适配若未回写上游，直接重编会静默丢功能（11-08 重编补了 `pkg/tasks` 与 `CODEFORGE_PORT`，但漏了 `312ccfe` 里的 `CF_EXEC_WRAPPER`、`CODEFORGE_DNS`、`resolvePath` 空工作区回退与 20MiB 无像素上限图片校验，导致 `cfrun` 容器包装失效、内核 DNS 打 `[::1]:53` 被拒）。
规则：重编 `libcodeforge.so` 前必须用字节级标记同时核验**全部**安卓侧契约符号：`CF_EXEC_WRAPPER`（`local.go`/`driver_mcp.go`/`tasks/manager.go` 三处）、`CODEFORGE_DNS`（`pkg/netdns`）、`CODEFORGE_PORT`/`PORT=%d`、`run_background`/`list_background`/`kill_background`/``/api/tasks``、`engagedPluginSection`/`@vuln_hunt`/`reverse_analysis`/`reverse_analysis_allow_modify`、`内置只读逆向工程能力`/`用户已显式开启并确认 5 秒免责协议`/`[二进制摘要 · ELF]`/`dex_inspector.py`/`elf_jni_scanner.py`、`每张图片不能超过20MiB`/`图片尺寸无效`、`title_custom`/`GenerateSessionTitle`/`SetAutoTitle`/`会话标题生成器`、`实时进度同步提醒`/`todoSyncReminder`，确认无误且 ELF `PT_LOAD` 对齐 ≥ `0x4000` 后再换入 `jniLibs`。

### P-058 · `History` 帧不能顺手清 `banner`
`ChatViewModel` 的 `banner` 同时承担「一次性提示」和「状态横幅」两种用途。`History` 分支原先置
`banner = null`（想清掉重连类错误），但 history 会在本机动作后几十毫秒内到达（内核连上/会话确立即推），
把刚弹出的「导入失败」「保存失败」一起抹掉 —— 实测导入坏包时错误提示 47ms 后消失，用户看到「点了没反应」。
修法：`History` 分支不再动 `banner`；一次性提示交给 `ToastHost` 按 2s / 3.2s 自行退场，
连接恢复的清理由 `connected = true/false` 两条分支负责。新增「本机动作立即提示」时不要往帧分支里塞清理逻辑。

### P-059 · 壁纸模式把 `Theme.background` 设成透明、`surface` 设成 `0.92f`
`Theme.backgroundScheme` 在启用壁纸时令 `background = Color.Transparent`、`surface` alpha 为 `0.92f`。
后果有二：① 用 `onBackground` 做胶囊底 + `background` 做前景色（如 `SessionPane.BottomBar` 的「新建对话」）会变成实黑胶囊、文字与图标完全隐身；② 弹层（`ModalBottomSheet`/`ModalDrawerSheet`）和全屏遮罩直接取 `background`/`surface` 会透出底层聊天内容，叠加 `fadeIn`/`fadeOut` 更会在过渡帧里双层叠字。
规则：前景色绝不直接复用 `colorScheme.background`（透明度 `< 1f` 时按底色亮度取反色）；全屏遮罩与弹层统一取 `surface.copy(alpha = 1f)`，全屏侧栏浮层走水平滑入滑出替代透明度渐变。

---

## 版本锁定（不要随手升）

- Compose `1.9.5`；AGP `8.13.2` / Gradle `8.14.6` / Kotlin `2.4.21`（三者配套）
- OkHttp `4.12.0` / Coroutines `1.10.2`（升级要回归重连与转写）
- 无离线语音（Vosk 已移除，`NoOfflineVoiceTest` 守着）；语音走「云端 → 系统 `SpeechRecognizer`」两档
- R8 已启用（release），规则在 `app/proguard-rules.pro`；反混淆用 `mapping/release/mapping.txt`
- `targetSdk 28`（侧载定位，升前复核决策表，lint 已豁免 `ExpiredTargetSdkVersion`）；ABI 仅 `arm64-v8a`
- `libcodeforge.so` / `libopenrender.so` 均为预编译产物，源码在仓库外（见 §1）；OpenRender 钉上游
  `6f11851722772b1a18388364b20a613a0aa2a020`、`--features jni,ratex`；重编走私有材料目录，升上游要重跑 Rust 测试并核对 IR 键名（P-027）
- 图片附件不设像素上限（单张 20MiB、最多 4 张、单条消息总量 20MiB）
- 包名 `com.hxy.codeforge`（五处同步，P-047）；签名密钥 `app/keystore/codeforge.keystore`（P-046）
