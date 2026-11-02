# CodeForge

> 默认中英双语：上半部分中文，下半部分 English。
> Bilingual by default: Chinese first, English below.

**Android 上的 AI 编码工作台。**
Go 内核负责执行，Rust 引擎负责排版，Compose 负责交互 —— 三种语言，一个进程，一个 76 MB 的 APK。

| 规格 | |
|---|---|
| **界面** | 100% 原生 Compose（49 个 Kotlin 文件 / 25,387 行）· 不经 WebView · 无 XML 布局 |
| **内核** | Go 原生库 17.4 MB · 36 个 REST 端点 + 1 条 WebSocket · Cookie 鉴权 |
| **引擎** | Rust 原生库 8.3 MB · 增量 Markdown IR · 自研公式排版 · 随包分发 |
| **包体** | 76.1 MB 单包 · arm64-v8a · minSdk 26 / targetSdk 28 |
| **输入** | 文本 · 图片 · 视频 · 语音（云端 / 离线双引擎） |

---

## 为什么是它

**界面不经过 WebView。** 全部由 Compose 绘制，没有第二套布局文件，没有一个 JS 引擎参与渲染。
中文排版、代码块、表格、公式，走的是 Android 原生文本栈。

**三种语言，各用其长。** Go 承担内核（执行、协议、工作区），Rust 承担排版（解析、公式、表格布局），
Kotlin 承担交互。界面与内核之间是一条 REST + WebSocket 长连接，界面与排版引擎之间是 JNI 直调。

**排版引擎是自己写的。** 不是 Markdown 库加公式插件的拼装 —— 从增量 Markdown IR 到公式排版到
表格列宽分配，自成一套，编译成原生库随包分发，离线可用。

**装得进口袋，但不止是聊天。** 一个 APK 里跑完会话、项目、模型、技能、子智能体、MCP、审批、
diff、后台任务、语音输入和文件树。

**流式是真流式。** 增量 IR + 增量排版：内容边到边出图边排版，不等整段结束。

**每一步都留痕。** 三份记录并行：准则与坑位、待办与已办、变更流水。任何一个决定都能回溯到
「当时为什么这么选」。

---

## 能力

### 会话与组织

- 多会话并行，工作区在会话创建那一刻绑定
- 新建项目：选目录即得项目名，选完目录再改名是最多余的一步
- 会话重命名、归档、取消归档、删除（两次确认，可恢复与不可恢复分开摆放）
- 内置文件树，直接浏览工作区文件
- 后台任务：随手提交脚本或命令，任务名、运行状态、输出回放，随时终止

### 模型与智能体

- 自定义模型接入：模型 id、显示名、Base URL、API Key（掩码输入）、输入/输出上限、RPM，**支持测试连接**
- 权限三档：**只读 / 请求 / 自主**，新建项目时直接定档
- 技能（SKILL.md）与内置插件，输入框 `@` 动态补全，随技能、插件、MCP 实时更新
- 子智能体：独立任务、独立上下文、结果回灌
- MCP 服务接入与状态总览
- 审批卡片：工具调用逐条确认，附参数与 diff
- 目标模式：目标、轮次、判定、findings 一条状态带

### 渲染

- 增量 Markdown IR，段落级重排，流式不闪
- LaTeX 公式原生排版成图，**含中文的 `aligned` / `cases` / `bmatrix` 完整呈现**，不退回源码
- 表格列宽按实际渲染宽度分配，表头不再被挤断行
- 代码块、diff 着色、工具调用卡片、思考过程可折叠可钉住
- 深浅双主题，公式与图表随主题重新着色
- 全程本地排版，不依赖任何远程渲染服务

### 输入与交互

- 语音输入：云端识别 / 离线 Vosk 识别双引擎
- 附件：相机、相册、文件，图片与视频解析
- 上下文占用条：窗口、已用、预算、占比
- 任务清单面板，任务列表独立弹层
- 横竖屏三档自适应：`<600dp` 单栏抽屉 / `600–840dp` 双栏 / `≥840dp` 加宽双栏

---

## 架构

```
┌───────────────────────────────────────────────────┐
│  Kotlin / Compose            49 文件 / 25,387 行 │  交互
├───────────────────────────────────────────────────┤
│  OpenRender (Rust)        libopenrender.so  8.3M │  解析 · 排版 · 公式
├───────────────────────────────────────────────────┤
│  CodeForge-Go             libcodeforge.so  17.4M │  执行 · 协议 · 工作区
└───────────────────────────────────────────────────┘
                    ↕ 进程内直连
```

| 层 | 职责 | 边界 |
|---|---|---|
| Compose | 全部界面与交互 | 不碰协议，不碰文件 |
| OpenRender | Markdown 解析、公式排版、表格布局 | 不发网络请求，不读用户文件 |
| Go 内核 | 命令执行、工作区、REST / WS 服务 | 不认识 UI |

**targetSdk 28 是刻意的**：为了保留在应用私有目录执行内核二进制的能力（Android 10 起禁止执行
应用可写目录中的文件）。用兼容级别换运行能力，一次明确的取舍。

---

## 构建

```bash
# 需要 JDK 17 + Android SDK（compileSdk 36 / build-tools 36.0.0）
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

| 组件 | 版本 |
|---|---|
| Gradle | 8.14.6（wrapper 自带） |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.4.21 |
| Compose UI | 1.9.5 |
| Material 3 | 1.3.2 |
| compileSdk / targetSdk / minSdk | 36 / 28 / 26 |
| ABI | arm64-v8a |

> 版本是钉死的，不是随手升的 —— 升级要整条链一起动。理由见 `AGENTS.md` 的「版本锁定」。

---

## 工程纪律

规矩写在文件里，不靠口头传承：

| 文件 | 记什么 |
|---|---|
| `AGENTS.md` | 架构分层、编辑准则、验证标准、提交规范，以及已踩过的坑（编号到 P-040） |
| `工作进程.md` | 做到哪、下一步是什么 |
| `改动历史.md` | 逐次变更：时间 / 文件 / 改了什么 / 为什么 / 验证到哪一级 |

**验证分四级**，如实标注：没在真机跑过，就写「运行时未验证」。
文档比结论可信，结论比文档可信。

---

## 目录

```
app/src/main/java/app/codeforge/
├── core/      内核传输、协议模型、环境自检
├── render/    渲染引擎桥接与 IR
├── ui/        chat/ sessions/ settings/ common/ theme/
└── voice/     云端与离线语音
app/src/main/jniLibs/arm64-v8a/   Go 内核 + Rust 引擎
tools/openrender/                  引擎源码、字体与构建脚本
source/CodeForge-GO/               Go 内核源码
docs/                              方案文档与对齐清单
```

---

## 状态

`0.1.0` · 内部测试中 · arm64-v8a

---

# English

**An AI coding workbench for Android.**
Go runs the kernel, Rust does the typesetting, Compose handles the interaction — three languages, one
process, one 76 MB APK.

| Spec | |
|---|---|
| **UI** | 100% native Compose (49 Kotlin files / 25,387 lines) · no WebView · no XML layouts |
| **Kernel** | 17.4 MB Go native library · 36 REST endpoints + 1 WebSocket · cookie auth |
| **Engine** | 8.3 MB Rust native library · incremental Markdown IR · own math typesetting · shipped in-app |
| **Package** | 76.1 MB single APK · arm64-v8a · minSdk 26 / targetSdk 28 |
| **Input** | Text · images · video · voice (cloud / offline engines) |

---

## Why

**No WebView in the UI.** Everything is drawn by Compose — no second layout system, no JS engine in the
render path. Chinese typography, code blocks, tables and formulas all go through the native text stack.

**Three languages, each doing what it is good at.** Go runs the kernel (execution, protocol, workspace),
Rust does typesetting (parsing, formulas, table layout), Kotlin handles interaction. The UI talks to the
kernel over one REST + WebSocket connection and calls the typesetting engine directly through JNI.

**The typesetting engine is our own.** Not a Markdown library plus a formula plugin — an incremental
Markdown IR, formula typesetting and table column-width allocation compiled into a native library,
fully offline.

**Fits in a pocket, does more than chat.** One APK covers sessions, projects, models, skills, subagents,
MCP, approvals, diffs, background tasks, voice input and the file tree.

**Streaming is real streaming.** Incremental IR plus incremental typesetting: content is laid out as it
arrives instead of waiting for the whole block.

**Every decision leaves a trace.** Three records in parallel: conventions and pitfalls, to-do and done,
change log. Any choice can be traced back to why it was made at the time.

---

## Capabilities

### Sessions and organization

- Multiple concurrent sessions; the workspace is bound at the moment a session is created
- Create a project: pick a directory and the name comes with it
- Rename, archive, unarchive and delete sessions (destructive and recoverable actions are kept apart)
- Built-in file tree for browsing the workspace
- Background tasks: submit a script or command, watch its status and output, terminate it at any time

### Models and agents

- Bring your own model: model id, display name, base URL, API key (masked input), input/output limits,
  RPM — with a test connection
- Three permission modes: **read-only / ask / auto**, chosen when the project is created
- Skills (SKILL.md) and built-in plugins, with live `@` completion in the input box
- Subagents with their own task and context, results merged back into the conversation
- MCP servers with an overview of their state
- Approval cards: per-call confirmation, with arguments and diffs
- Goal mode: goal, rounds, verdict and findings in one status strip

### Rendering

- Incremental Markdown IR, paragraph-level reflow, no flicker while streaming
- LaTeX typeset natively into images; Chinese inside `aligned` / `cases` / `bmatrix` renders in full
- Table column widths allocated from measured render widths, so headers no longer break
- Code blocks, diff highlighting, tool-call cards, reasoning that can be collapsed and pinned
- Light and dark themes; formulas and charts are re-tinted with the theme
- Typesetting is entirely local — no remote rendering service

### Input and interaction

- Voice input: cloud recognition / offline Vosk
- Attachments: camera, gallery and files; images and video are parsed
- Context meter: window, used, budget, share
- Task list panel with its own dialog
- Three responsive tiers: `<600dp` single-column drawer / `600–840dp` two columns / `≥840dp` wide two columns

---

## Architecture

```
┌───────────────────────────────────────────────────┐
│  Kotlin / Compose            49 files / 25,387 lines │  interaction
├───────────────────────────────────────────────────┤
│  OpenRender (Rust)        libopenrender.so  8.3M │  parse · typeset · formulas
├───────────────────────────────────────────────────┤
│  CodeForge-Go             libcodeforge.so  17.4M │  execute · protocol · workspace
└───────────────────────────────────────────────────┘
                    ↕ in-process
```

| Layer | Responsibility | Boundary |
|---|---|---|
| Compose | All UI and interaction | Never touches the protocol or the filesystem |
| OpenRender | Markdown parsing, formula typesetting, table layout | No network, no user files |
| Go kernel | Command execution, workspace, REST / WS service | Knows nothing about the UI |

**targetSdk 28 is deliberate**: it preserves the ability to execute the kernel binary inside the app's
private directory (Android 10 forbids executing files from app-writable storage). Trading a compatibility
level for runtime capability, on purpose.

---

## Build

```bash
# requires JDK 17 + Android SDK (compileSdk 36 / build-tools 36.0.0)
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

| Component | Version |
|---|---|
| Gradle | 8.14.6 (bundled with the wrapper) |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.4.21 |
| Compose UI | 1.9.5 |
| Material 3 | 1.3.2 |
| compileSdk / targetSdk / minSdk | 36 / 28 / 26 |
| ABI | arm64-v8a |

> Versions are pinned on purpose — upgrading them means moving the whole chain at once.
> The reasoning lives in the "版本锁定" section of `AGENTS.md`.

---

## Conventions

| File | What it records |
|---|---|
| `AGENTS.md` | Layering, editing rules, verification levels, commit conventions, and the pitfalls hit so far (up to P-041) |
| `工作进程.md` | Where things stand and what comes next |
| `改动历史.md` | Every change: time / files / what / why / how far it was verified |

Verification is reported at four levels, honestly: if it was not run on a device, the record says
"runtime unverified". Documents are more trustworthy than conclusions, conclusions more than documents.

---

## Status

`0.1.0` · internal testing · arm64-v8a
