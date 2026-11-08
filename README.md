# CodeForge

**Android 上的 AI 编码工作台。**
**The AI coding workbench for Android.**

三种语言，一个进程，一个 17.4 MB 的 APK，而且能跑真代码。
Three languages, one process, one 17.4 MB APK — and it runs real code.

完整文档 / Full documentation：

- 中文完整版：[README\_ZH.md](README_ZH.md)
- English full docs: [README\_EN.md](README_EN.md)

***

## 硬指标 / Key Metrics

| 项    | 数值                                                |
| ---- | ------------------------------------------------- |
| 包体   | 17.4 MB 单包（曾 65.5 MB，砍掉内置语音模型后减少 72%）             |
| 界面   | 100% 原生 Compose，零 XML 布局，56 个 Kotlin 文件共 25,727 行 |
| 内核   | Go 原生库 16.6 MB，36 个 REST 端点加 1 条 WebSocket 长连接    |
| 排版引擎 | Rust 原生库 8.5 MB，自研增量 Markdown IR，自研公式排版           |
| 运行   | arm64-v8a，minSdk 26，随包携带完整 Linux 兼容环境             |
| 测试   | 66 个单元测试，零失败                                      |

| Item       | Value                                                                                 |
| ---------- | ------------------------------------------------------------------------------------- |
| Package    | 17.4 MB single APK (was 65.5 MB, 72% smaller after removing the bundled voice model)  |
| UI         | 100% native Compose, zero XML layouts, 25,727 lines across 56 Kotlin files            |
| Kernel     | 16.6 MB Go native library, 36 REST endpoints plus 1 WebSocket connection              |
| Typesetter | 8.5 MB Rust native library, in-house incremental Markdown IR, in-house formula layout |
| Runtime    | arm64-v8a, minSdk 26, complete Linux compat environment shipped in the package        |
| Tests      | 66 unit tests, zero failures                                                          |

***

## 项目特色 / Highlights

### 三种语言，一个 APK / Three languages, one APK

Go 当内核，Rust 当排版引擎，Kotlin 加 Compose 当界面。

Go runs the kernel, Rust does the typesetting, Kotlin plus Compose handles the UI.

界面与内核之间是进程内的一条 REST 加 WebSocket 长连接，界面与排版引擎之间是 JNI 直调。没有 WebView 套壳，没有跨进程序列化损耗。

Between UI and kernel: a single in-process REST plus WebSocket connection. Between UI and typesetter: direct JNI calls. No WebView shell, no cross-process serialization cost.

### 高性能排版引擎 / A high-performance typesetting engine

不是 Markdown 库加公式插件的拼装。是从增量 Markdown IR，到公式原生排版，再到表格列宽实测分配的一整条自研链路，编译成原生库随包分发，全程离线。
参考仓库：<https://github.com/raine-x/OpenRender.git>

Not a Markdown library bolted onto a formula plugin. It is a full in-house chain — incremental Markdown IR, native formula layout, table columns allocated from measured widths — compiled into a native library shipped in the package, fully offline.
Upstream repo: <https://github.com/raine-x/OpenRender.git>

含中文的 aligned、cases、bmatrix 完整呈现，不退回源码，这是很多现成方案做不到的。

aligned, cases and bmatrix with Chinese inside render in full, without falling back to source text — something most off-the-shelf solutions cannot do.

### 流式显示 / Streaming display

增量 IR 加增量排版，内容边到边出图边排版，不等整段结束。长回复不卡、不闪、不跳。

Incremental IR plus incremental typesetting: content renders as it arrives, no waiting for the whole block to finish. Long replies do not stall, flicker or jump.

### 模型输出的是页面，不是文本 / Models output pages, not text

模型吐出整页 HTML，直接渲染成能点的页面，按钮、展开收起、表单、动画全部可用。

A model emits a whole HTML page and it renders straight into an interactive one: buttons, expand and collapse, forms and animations all work.

内置浏览器还带元素挑选，点页面上的元素，编号进对话，元素布局随消息送给模型。指着页面说「这个改一下」就行。

The built-in browser also picks elements: tap one, it gets a number and joins the conversation, and its layout travels to the model with the message. Point at the page and say "change this".

### 装得进口袋，但不止是聊天 / Fits in a pocket, not just a chat app

会话、项目、模型、技能、子智能体、MCP、审批、diff、后台任务、语音输入、文件树、完整 Linux 环境，一个 APK 全装。

Sessions, projects, models, skills, subagents, MCP, approvals, diffs, background tasks, voice input, a file tree and a full Linux environment — all in one APK.

### 完整 Linux 兼容环境 / A complete Linux compat environment

随包携带 proot 加 Alpine rootfs，一键装 Python、Node、Go、C++、Rust、Java 17、Git、FFmpeg。

proot plus an Alpine rootfs ships in the package; Python, Node, Go, C++, Rust, Java 17, Git and FFmpeg install in one tap.

模型可以在里面真的写代码、真的跑起来、真的装依赖。

The model can really write code in there, really run it, and really install dependencies.

### 全离线可用的公式与图表 / Formulas and diagrams, fully offline

公式、表格、代码块、diff 着色全部本地排版，不依赖任何远程渲染服务。深浅双主题，公式与图表随主题重新着色。

Formulas, tables, code blocks and diff coloring are all typeset locally, with no remote rendering service. Light and dark themes, and formulas and diagrams recolor with the theme.

***

## 特色清单 / Feature Matrix

| 领域  | 亮点                                                   |
| --- | ---------------------------------------------------- |
| 会话  | 多会话并行，工作区在创建那一刻绑定，归档、恢复、删除分开摆放，可恢复与不可恢复不混在一起         |
| 模型  | 自定义接入，支持 Base URL、Key、上下文、RPM、三态能力，支持测试连接，供应商按 id 归一 |
| 技能  | SKILL.md 技能、内置插件、MCP，输入框 @ 动态补全，随装随用                 |
| 智能体 | 子智能体独立上下文，目标模式带判定与 findings，审批卡片逐条确认附 diff           |
| 渲染  | 增量 IR 段落级重排，中文公式原生排版，表格列宽按实测宽度分配，思考过程可折叠可钉住          |
| 网页  | 整页 HTML 渲染成可操作页面，内置浏览器支持返回、刷新、全屏、快照，元素挑选             |
| 交互  | 横竖屏三档自适应，背景图、模糊、亮度、文字色可调，语音输入双引擎                     |
| 工程  | 单一状态源，单向数据流，四级验证标准如实标注                               |

| Area        | Highlight                                                                                                                           |
| ----------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| Sessions    | Parallel sessions, workspace bound at creation, archive, restore and delete laid out separately                                     |
| Models      | Custom endpoints with base URL, key, context, RPM, three-state capabilities, connection test, providers keyed by id                 |
| Skills      | SKILL.md skills, built-in plugins, MCP, dynamic at-completion in the input box                                                      |
| Agents      | Subagents with their own context, goal mode with verdict and findings, per-call approval cards with diffs                           |
| Rendering   | Paragraph-level re-typesetting on incremental IR, native Chinese formulas, measured table widths, collapsible and pinnable thinking |
| Web         | Whole HTML pages rendered as interactive ones; built-in browser with back, refresh, fullscreen, snapshots and element picking       |
| Interaction | Three responsive tiers, adjustable wallpaper, blur, brightness and text color, dual voice engines                                   |
| Engineering | Single source of truth, unidirectional data flow, four verification levels reported honestly                                        |

***

## 架构 / Architecture

| 层    | 技术                    | 体积               | 职责        |
| ---- | --------------------- | ---------------- | --------- |
| 界面   | Kotlin 加 Compose      | 56 文件 / 25,727 行 | 交互        |
| 排版引擎 | Rust，libopenrender.so | 8.5 MB           | 解析、排版、公式  |
| 内核   | Go，libcodeforge.so    | 16.6 MB          | 执行、协议、工作区 |

| Layer      | Tech                   | Size                    | Responsibility                 |
| ---------- | ---------------------- | ----------------------- | ------------------------------ |
| UI         | Kotlin plus Compose    | 56 files / 25,727 lines | Interaction                    |
| Typesetter | Rust, libopenrender.so | 8.5 MB                  | Parsing, layout, formulas      |
| Kernel     | Go, libcodeforge.so    | 16.6 MB                 | Execution, protocol, workspace |

界面与内核之间是进程内直连。
UI and kernel are connected in-process, directly.

***

## 状态 / Status

0.1.0，arm64-v8a，targetSdk 28

***

## 许可 / License

MIT + 商业授权双许可（Copyright (c) 2026 raine-x）\
默认按 MIT 许可使用、修改与分发（含商用）；如需免除署名、OEM 预装、源码交付或技术支持等
超出 MIT 条款的方式，联系 2653603072@qq.com 获取商业授权。
Dual-licensed: MIT by default; for uses beyond its terms (no-attribution, OEM, source
delivery, support), contact 2653603072@qq.com for a commercial license.

完整条款（含第三方组件清单，proot 等第三方组件保留原许可）见 [LICENSE](LICENSE)。
Full terms (including the third-party component list — proot etc. keep their original
licenses) are in [LICENSE](LICENSE).
