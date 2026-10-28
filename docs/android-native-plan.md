# CodeForge Android 原生重构方案

> 目标：把 CodeForge-Go（Go 内核 + Web UI）改造成 Android 原生应用，继承全部功能，支持多语言项目编译，UI 原生渲染并区分横竖屏。
>
> 本文所有代码事实均经实际检索/构建产物核对，未核实的判断已明确标注。

---

## 0. 一页结论

| 结论 | 依据 |
|---|---|
| **后端零改动**，"继承全部功能"靠保留 HTTP/WS 契约实现 | 36 个 REST + 1 个 WS 端点已是完整接口面，原生 UI 只是换了个客户端 |
| **前端必须重写**，这是主要工作量 | 现有 `web/dist` 12,392 行手写 JS/CSS/HTML，无法在 Compose 里复用 |
| **Python / Java 编译不需要 exec** | 有绕开 W^X 的干净解法，与 targetSdk 无关 |
| **Go 编译必须 exec** | 编译器是原生二进制且要 fork 子进程，是唯一硬骨头 |
| **内核不需要知道项目语言** | `run_command` 只是跑 shell 命令，多语言支持等于让 PATH 里有对应工具链 |

一句话：**把「重写前端」和「给 shell 装上工具链」这两件事分开做，内核不动。**

---

## 1. 代码事实（已核实）

### 1.1 内核规模

| 包 | 行数 | 文件 | 职责 |
|---|---:|---:|---|
| `pkg/tools` | 14,689 | 62 | 工具实现与执行器、审批、撤销栈 |
| `pkg/server` | 14,022 | 56 | HTTP/WS 服务、会话、工作区、模型管理 |
| `pkg/agent` | 12,570 | 43 | 对话主循环、上下文压缩、子智能体、目标模式 |
| `pkg/llm` | 3,788 | 17 | 供应商适配 |
| `pkg/store` | 1,462 | 9 | SQLite 持久化（`modernc.org/sqlite`，纯 Go） |
| `pkg/errs` / `backend` / `security` / `platform` / `logx` | 3,696 | 26 | 基础设施 |
| **合计** | **55,446** | **235** | |

依赖极轻：`gorilla/websocket`、`tetratelabs/wazero`（WASM 插件沙盒）、`yaml.v3`、`modernc.org/sqlite`。**全部零 CGO**，已有 `GOOS=android GOARCH=arm64` 产物 17.3 MB。

### 1.2 接口面

- **REST**：36 个端点（`/api/health` 免鉴权，其余走 `requireAuth`）
- **WebSocket**：`/ws`，双向 JSON
  - 客户端 → 服务端 **15 个动作**：`user_message` `steer` `regenerate` `edit_user_message` `continue_turn` `retry` `rewind` `checkpoints` `new_session` `list_sessions` `load_session` `hitl_decision` `context` `visibility` `cancel`
  - 服务端 → 客户端 **20 类事件**：`ready` `session` `sessions` `history` `busy` `idle` `error` `context` `todo` `checkpoints` `hitl_request` `steer_queued` `rewind` `subagent` `workspace_blocked` 等

这 35 个端点 + 35 个消息类型，就是原生 UI 的全部契约。**只要契约不变，功能就不会丢。**

### 1.3 前端规模

| 文件 | 行数 |
|---|---:|
| `web/dist/ui.js` | 7,944 |
| `web/dist/app.css` | 3,634 |
| `web/dist/index.html` | 733 |
| `web/dist/drag.js` | 81 |
| **合计** | **12,392** |

响应式断点只有一处：`@media (max-width: 820px), (max-height: 520px)`——现有 web UI 的横竖屏适配是**单点式的**，原生重写时可以做得更细。

### 1.4 现有 Android 壳

4 个 Kotlin 文件约 190 行，全部是骨架。`CoreService` 从未被任何代码启动，是死代码。

---

## 2. 三个关键判断

### 判断一：内核一行不改，只加端口握手

原生 UI 和 web UI 面对的是同一个 HTTP/WS 服务。所以：

- 不需要把 Go 逻辑翻译成 Kotlin
- 不需要重新实现 agent 循环、工具执行、审批、撤销
- 后端演进时前端只需跟随新增的事件类型

**唯一必须改的内核点**是端口：现在 `server.port` 从 `config/default.yaml` 读死值。需要支持 `port: 0` 时由内核绑定随机端口，并把 `PORT=xxxxx` 打到 stdout 第一行供宿主读取。

改动量：`pkg/server/server.go` 约 30 行 + `cmd/agent/main.go` 约 10 行。

### 判断二：三种语言的编译难度差一个数量级

| 语言 | 方案 | 需要 exec | 难度 | 结论 |
|---|---|---|---|---|
| **Python** | Chaquopy：CPython 编成 `.so`，JNI 调用 | **否** | 低 | 稳。代码在 APK 内，天然符合 W^X |
| **Java** | ECJ（纯 Java 编译器）+ D8（纯 Java dexer） | **否** | 中 | 可行。AIDE / Java N-IDE 验证过这条路 |
| **Go** | 官方 arm64 工具链 | **是** | 高 | 卡在 W^X，只能靠 proot 或降 targetSdk |

**这个分层是本方案的核心洞察。** 用户要求的三门语言里，两门根本不需要突破 exec 限制——因为它们本来就是「跑在 ART 上的字节码」，而不是「执行自己目录里的原生二进制」。

只有 Go 是硬骨头：`go build` 内部要 fork `compile`/`link`/`asm`/`cgo` 等子进程，每一个都是 exec。

### 判断三：多语言支持不在内核，在 PATH

内核的 `run_command` 只是执行 shell 命令，它不知道也不关心你在编译什么语言。所以：

```
多语言项目支持  =  让 shell 的 PATH 里能找到 go / javac / python
```

这意味着：
- `pkg/tools` 不需要任何改动
- 不需要给 agent 加「项目类型识别」
- 只需要一个**工具链管理层**：按需下载、解压、注入 PATH

用户提的「类似 vscode 插件式预编译包」正是这个思路，且是对的：工具链以预编译 tar 包按需下载，不塞进 APK。

---

## 3. 目标架构

```
┌──────────────────────────────────────────────────────┐
│  Android App 进程（Compose 原生 UI）                  │
│                                                       │
│  竖屏 (<600dp)        横屏/平板 (≥600dp)              │
│  ┌───────────┐        ┌────────┬──────────────┐      │
│  │  聊天     │        │ 会话列表│   聊天        │      │
│  │           │        │ 320dp  │              │      │
│  ├───────────┤        │        │              │      │
│  │ 底部导航  │        │        │              │      │
│  └───────────┘        └────────┴──────────────┘      │
│                                                       │
│  CoreClient：HTTP + WS 客户端（协程 + Flow）           │
└───────────────────────┬──────────────────────────────┘
                        │ 127.0.0.1:<随机端口>?token=xxx
┌───────────────────────┴──────────────────────────────┐
│  Go 内核进程（nativeLibraryDir/libcodeforge.so）      │
│                                                       │
│  pkg/server  ← 仅加 port:0 随机绑定 + stdout 握手     │
│  pkg/agent   ← 不改                                    │
│  pkg/tools   ← 不改                                    │
│  pkg/llm     ← 不改                                    │
└───────────────────────┬──────────────────────────────┘
                        │ run_command（shell）
┌───────────────────────┴──────────────────────────────┐
│  工具链层                                             │
│                                                       │
│  Python  ← Chaquopy（JNI，进程内）                    │
│  Java    ← ECJ + D8（ART 上跑，进程内）               │
│  Go      ← proot + 官方 arm64 SDK（预编译包）         │
└──────────────────────────────────────────────────────┘
```

### 3.1 进程模型

- **App 主进程**：Compose UI + `CoreClient`
- **内核进程**：独立进程，由 `CoreService` 通过 `ProcessBuilder` 从 `nativeLibraryDir` 拉起
- **隔离原因**：内核可能被 OOM 杀掉，独立进程可退避重启而不影响 UI；反之 UI 崩溃也不带崩 agent 会话

### 3.2 端口握手（内核唯一改动）

```
内核启动
  → config: server.port = 0
  → net.Listen("tcp", "127.0.0.1:0") 拿到实际端口
  → stdout 首行输出 PORT=38471
  → CoreService 读取该行
  → 回调 UI，UI 用 http://127.0.0.1:38471/?token=<token> 建连
```

注意：现有 server 有访问令牌，首帧不带 token 会 401。

---

## 4. 原生 UI 设计

### 4.1 横竖屏策略

用 `WindowSizeClass` + `WindowLayoutInfo`（折叠屏）驱动布局，而不是简单判断宽高比：

| 宽度等级 | 布局 | 典型设备 |
|---|---|---|
| Compact (<600dp) | 单栏 + 底部导航，会话列表与聊天用共享元素过渡切换 | 手机竖屏 |
| Medium (600–840dp) | 双栏：列表 280dp 固定 + 聊天自适应 | 手机横屏、小平板竖屏 |
| Expanded (≥840dp) | 三栏：列表 280dp + 聊天 + 工具/文件面板 320dp | 平板横屏、折叠屏展开 |

**状态不能丢**：横竖屏切换时 `Activity` 会重建。方案：
- `android:configChanges` 已声明 `orientation|screenSize`，不会重建 Activity
- 但 Compose 里仍需用 `rememberSaveable` 保存滚动位置、输入框草稿、当前会话 ID
- WS 连接放在 `ViewModel`（或 Service）里，不随重组断开

### 4.2 Web → 原生的逐项替代

| Web 实现 | 原生方案 | 备注 |
|---|---|---|
| 流式追加 DOM 节点 | `LazyColumn` + `snapshotFlow` 增量更新 | 需处理「贴底跟随」与用户上滑冲突 |
| CSS `@media` 响应式 | `WindowSizeClass` | 更细粒度，可做三栏 |
| `hideWithAnim` 手写动画 | `AnimatedVisibility` + `fadeIn/scaleIn` | 代码量减少约 70% |
| 弹层 `.popup` | `ModalBottomSheet` / `DropdownMenu` | 竖屏用 Sheet，横屏用 Popup |
| Markdown 渲染 | 自研 `AnnotatedString` 渲染器 | 现有渲染逻辑可照抄规则，但不能照抄代码 |
| 代码块高亮 | 自研 tokenizer 或引入轻量库 | 需支持 Go/Java/Python/Kotlin |
| diff 预览 | 自研 LazyColumn 行渲染 | 数据来自 `/api/diff`，格式不变 |
| 目录选择器 | 原生 SAF（`ACTION_OPEN_DOCUMENT_TREE`）+ 自研浏览器 | Termux 上的浏览器式选择器逻辑可参考 |
| 拖拽上传 | `Modifier.dragAndDropTarget` | Compose 1.6+ 原生支持 |
| 输入框自适应高度 | `BasicTextField` + `onTextLayout` | 需自己算行数 |

### 4.3 动画

Compose 的动画能力足以覆盖现有 web UI 的全部动效，且更强：

- 发送/接收气泡：`animateItem()`（LazyColumn 内置的增删移动动画）
- 弹层进出：`AnimatedVisibility` + `spring(dampingRatio = 0.8f)`
- 会话切换：`SharedTransitionLayout` + `Modifier.sharedElement()`
- 工具卡片展开：`animateContentSize()`
- 审批卡片的脉冲提示：`InfiniteTransition`
- 输入框发送按钮两态：`animateColorAsState` + `animateFloatAsState`

### 4.4 重写分期（关键：不能一次全量）

7,944 行 JS 一次性重写风险极高。按「能对话 → 能用 → 完整」三批：

#### P0 —— 能对话（核心链路）
- WS 连接与重连、事件分发
- 会话列表（读 `list_sessions` / `load_session`）
- 聊天流：用户消息、助手消息、流式增量、thinking
- 输入框：发送、停止（`cancel`）、权限模式切换
- 工具调用卡片：名称、参数、结果折叠
- 审批卡片（`hitl_request` / `hitl_decision`）—— **必须有，否则 agent 会卡住**
- 错误提示、`busy`/`idle` 状态

#### P1 —— 能用
- 模型与供应商管理（`/api/models/*`、`/api/providers/*` 共 11 个端点）
- 设置页：权限策略、上下文预算、最大步数、重试策略
- 工作区切换（`/api/workspace`、`/api/workspaces`）
- 目录/文件选择器
- 会话管理：新建、归档、重命名、rewind
- Markdown 完整渲染（表格、代码块、链接）

#### P2 —— 完整
- diff 预览与撤销（`/api/diff`、`/api/undo`）
- 附件与图片上传（`/api/stage_file`、`/api/upload_file`）
- 子智能体进度面板（`subagent` 事件）
- 目标模式、todo 面板（`todo` 事件）
- 记忆与技能管理（`/api/memory`、`/api/skills`）
- MCP / 插件配置（`/api/plugins`、`/api/builtin-plugins`）
- 外观设置（`/api/appearance/*`）
- 上下文压缩（`/api/context/compress`）

**P0 完成后应用即可日常使用**，这是最重要的里程碑。

---

## 5. 多语言与编译

### 5.1 Python：Chaquopy

CPython 编译成 `.so` 打进 APK，通过 JNI 在进程内调用。**不需要 exec，不受 W^X 影响。**

- 优点：干净、稳、官方支持
- 代价：APK 增大约 15–25 MB；Chaquopy 商业使用需授权（需确认当前许可条款）
- 备选：BeeWare / Kivy 的 embedded Python

### 5.2 Java：ECJ + D8

- `ECJ`（Eclipse Compiler for Java）是纯 Java 实现的编译器，可在 ART 上直接运行
- `D8` 是 Google 的 dexer，同样是纯 Java，可在 ART 上运行
- 链路：`.java` → ECJ → `.class` → D8 → `.dex` → `DexClassLoader` 加载
- 这条路 AIDE 验证过，但**集成复杂度不低**，需处理 classpath、android.jar stub、多 dex

若只需「编译通过并看报错」而不要求运行，可省掉 D8。

### 5.3 Go：唯一硬骨头

Go 编译器必须 exec，因此只有两条路：

**路线 A：targetSdk 28 + proot（推荐）**

- 官方 `go1.25.linux-arm64.tar.gz` 解压到 app 私有目录
- `proot` 放在 `nativeLibraryDir`（可 exec），由它加载 rootfs 内的 Go 工具链
- 关键疑点：proot 加载 guest 程序时是 `open` + `mmap(PROT_EXEC)` + 跳入口，**不走 execve**；但 SELinux 对 `app_data_file` 的 `mmap PROT_EXEC` 同样会拦。这是**必须实测**的点，不能靠推理。

**路线 B：保 targetSdk 36 + memfd 桥**

- `memfd_create` + `fexecve`（`execveat(AT_EMPTY_PATH)`）加载匿名内存中的 ELF
- 理论上绕开 SELinux 的文件 exec 检查
- 但 Go 要 fork 十几个子进程，每个都要过桥，等于自己写一个 mini-loader
- **无成熟先例，不建议作为首选**

### 5.4 工具链分发（「vscode 插件式预编译包」）

```
首次需要 Go 时：
  UI 提示 → 下载 go-arm64-1.25.tar.zst（约 70 MB）
  → 校验 sha256
  → 解压到 filesDir/toolchains/go/1.25/
  → 写 manifest（版本、路径、安装时间）
  → 注入 PATH
```

这样做的理由：
- APK 不膨胀（Go SDK 解压后约 500 MB）
- 可按需只装用户需要的语言
- 版本可独立升级

---

## 6. 分期实施

| 阶段 | 内容 | 产出 | 依赖 |
|---|---|---|---|
| **S0** | 可行性实验（见第 7 节） | 结论：走哪条 Go 路线 | 无 |
| **S1** | 内核端口握手 + `CoreService` 真正启动内核 | 能拉起内核并拿到端口 | S0 的 E1 |
| **S2** | 修 FGS 类型（dataSync → specialUse）+ 通知权限 | 内核可长期存活 | 无（可并行） |
| **S3** | Compose UI P0 | **能对话，可日常使用** | S1 |
| **S4** | 横竖屏三档布局 + 折叠屏 | 平板/横屏体验 | S3 |
| **S5** | Compose UI P1 | 能配置模型/工作区 | S3 |
| **S6** | Python（Chaquopy）+ Java（ECJ）工具链 | 两语言可编译 | S5 |
| **S7** | Go 工具链（proot 或 memfd） | 三语言齐 | S0 结论 |
| **S8** | Compose UI P2 | 功能对齐 web 版 | S5 |

**并行建议**：S2 与 S0/S1 无依赖，可先做——它是当前架构里已确认的缺陷（`dataSync` 在 Android 15+ 每天上限 6 小时，会掐掉内核）。

---

## 7. P0 验证实验（先做，决定路线）

这四个实验的结果直接决定方案骨架，**必须在写 UI 之前跑完**。

### E1：nativeLibraryDir 能否 exec 静态二进制

```
把静态编译的 busybox 改名 libbb.so 放进 jniLibs/arm64-v8a/
真机安装后 ProcessBuilder(nativeLibraryDir + "/libbb.so", "--help")
断言：能输出帮助信息
```
- 通过 → 内核与 proot 可从这里启动，方案成立
- 失败 → 整个「app 内跑原生二进制」路线不成立，只能退回 Termux 共存

### E2：proot 能否在 targetSdk 28 下跑 rootfs 内程序

```
targetSdk 临时改 28，装一个最小 rootfs（busybox + sh）
proot -r <rootfs> /bin/sh -c "echo ok"
```
- 通过 → Go 路线 A 可行
- 失败 → 改试 memfd 桥，或放弃真机 Go 编译

### E3：端口握手

```
内核 config 设 port: 0，断言 stdout 首行形如 PORT=xxxxx
UI 侧连接 http://127.0.0.1:<port>/api/health 拿到 200
```

### E4：前台服务存活

```
FGS 改 specialUse，启动内核，锁屏放置 8 小时
断言：进程未被杀、端口仍可访问、通知仍在
```
- 当前 `dataSync` 类型在 Android 15+ 有 6 小时/天上限，这个实验就是验证它是否真的会掐

---

## 8. 风险登记

| ID | 风险 | 等级 | 应对 |
|---|---|---|---|
| R1 | W^X 限制导致 proot 不可用 | **高** | E2 实测；失败则改 memfd 或放弃真机 Go |
| R2 | `dataSync` FGS 6 小时上限掐掉内核 | **高** | 改 `specialUse` + `FOREGROUND_SERVICE_SPECIAL_USE`，E4 验证 |
| R3 | 7,944 行 JS 重写过程中功能漂移 | **高** | 严格按 WS/REST 契约做对照清单，每个端点有对应 UI 断言 |
| R4 | Go 编译内存峰值（手机 4–8 GB） | 中 | `GOMEMLIMIT` 已按物理内存 25% 设，Go 编译本身仍需 ~1 GB |
| R5 | 横竖屏切换丢状态 | 中 | `rememberSaveable` + WS 放 ViewModel |
| R6 | Chaquopy 许可与体积 | 中 | 需确认当前条款；备选 BeeWare |
| R7 | 本地 HTTP 端口被同机其他 app 访问 | 低 | 现有 token 鉴权已覆盖 |
| R8 | `nativeLibraryDir` 只能放少量 `.so` | 中 | 只放内核与 proot，其余走私有目录 + proot |

---

## 9. 待决策

| 项 | 选项 | 建议 |
|---|---|---|
| UI 框架 | Compose / Flutter | **Compose**——延续现有壳，与内核通信不变，横竖屏用 `WindowSizeClass` 最直接 |
| targetSdk | 28（侧载）/ 36（上架） | **28**——Play 对「能跑任意代码」的 app 本来就不友好，而工具链是核心需求 |
| Go 工具链 | proot / memfd / 放弃 | 由 E2 结果决定，不预设 |
| Java 深度 | 仅编译 / 编译+运行 | 先做仅编译（ECJ 出 `.class`），D8 与运行后置 |
