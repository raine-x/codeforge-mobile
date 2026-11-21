# CodeForge Android 特性与问答（200 条）

> 依据 `AGENTS.md`、`README*` 与当前代码整理，版本 v2.0。涉及具体数值、设备行为的条目以实际代码和真机为准；未经真机验证的地方已注明。

目录：A 总览与架构（1–20）· B 内核与通信（21–40）· C 聊天与渲染（41–70）· D 输入与附件（71–90）· E 手机操控 @phone（91–125）· F 模型与设置（126–145）· G 插件与技能（146–165）· H 环境与文件（166–185）· I 安全、隐私与排错（186–200）

---

## A. 总览与架构

1. **这是什么？** CodeForge-Go 内核（AI 编码 agent）的 Android 原生宿主壳，Kotlin + Compose，无 XML 布局。
2. **内核怎么运行？** 以预编译 `libcodeforge.so` 作为独立进程运行，App 通过 REST + WebSocket 与其通信。
3. **内核监听哪里？** `127.0.0.1:<随机端口>`，只在本机回环，不对外暴露。
4. **App 与内核如何鉴权？** Cookie（`codeforge_token`），先 GET `/` 取 Cookie，再连 `/ws`（P-003）。
5. **架构分几层？** 入口层、状态层、界面层、协议层、内核层。
6. **唯一状态源是什么？** `ChatViewModel` 里的 `ChatUiState`。
7. **数据流方向？** 单向：用户操作 → ViewModel → transport → 内核 → WS 推帧 → `CoreEvent` → `ChatUiState` → Compose 重组。
8. **界面层能直接用传输层吗？** 不能，`ui/` 不许 import `HttpCoreTransport`，要新数据就加状态字段。
9. **包名是什么？** `com.hxy.codeforge`，Kotlin 包、namespace、applicationId、JNI 符号、proguard 五处必须同步（P-047）。
10. **当前版本？** `versionName = 2.0`，`versionCode = 3`。
11. **支持哪个 ABI？** `arm64-v8a`，预编译库在 `app/src/main/jniLibs/arm64-v8a/`。
12. **仓库里有 `.so` 源码吗？** 没有，只入库预编译产物，源码材料在仓库外。
13. **许可证？** MIT + 商业授权双许可，联系邮箱见 `LICENSE`。
14. **应用是否需要联网？** 内核本身本地运行，但调用云端大模型需要网络；也可接本地/自建模型端点。
15. **有多少份文档？** `README.md`、`README_ZH.md`、`README_EN.md`，另有 `docs/android-native-plan.md`。
16. **项目如何记录进度？** 三份记录：`AGENTS.md`、`工作进程.md`、`改动历史.md`。
17. **渲染引擎是什么？** OpenRender，经 JNI 桥（`libopenrender.so`）把 Markdown 转为 IR，再由 Compose 排版。
18. **渲染库加载失败怎么办？** 进程级判死并退化为纯文本显示（P-016）。
19. **是否支持横屏？** 支持，竖屏单栏抽屉、横屏双栏（侧栏 + 聊天）。
20. **有哪些屏幕档位？** 由 `ui/common/Responsive.kt` 做三档判定。

## B. 内核与通信

21. **谁负责拉起内核？** `CoreService`，一个 specialUse 前台服务。
22. **为何用 specialUse 而不是 dataSync？** Android 15+ 的 dataSync 有每天 6 小时上限（P-005）。
23. **如何知道内核已就绪？** `CoreService` 通过 PORT 握手拿到端口。
24. **内核崩了会怎样？** `CoreService` 带退避重启。
25. **WS 断线会怎样？** `HttpCoreTransport` 自愈重连，退避 1/2/4/8 秒（P-019）。
26. **连接前如何探活？** 先打 `/api/health`，再取 Cookie、连 WS。
27. **为什么不押注一次连接？** 内核刚 bind 的毫秒窗口 accept 未就绪，会打死一次连接。
28. **`Link(connected)` 和 `Ready` 一样吗？** 不一样，两者分离。
29. **`disconnect()` 会关 client 吗？** 不会 `shutdown` client，便于重连。
30. **事件在代码里叫什么？** `CoreEvent`，定义在 `core/CoreTransport.kt`。
31. **新增内核事件改哪几处？** `CoreEvent` 子类、`ChatViewModel.onEvent` 分支、`HttpCoreTransport.handleFrame` 解析。
32. **帧名键名以什么为准？** 内核的 `pkg/agent/agent.go` 与 `pkg/server/ws_handler.go`（P-008）。
33. **`sessions` 帧的数组叫什么？** `items`，而 `ready` 帧里叫 `sessions`（P-008）。
34. **REST 客户端是哪个？** `KernelApi`，Cookie 与 WS 相同。
35. **模型列表接口返回什么？** `/api/models/list` 返回 `{models, providers, active}`。
36. **插件接口的方法有何坑？** `/api/plugins` POST 是添加，启停走 PATCH 且字段是 `name`（P-025）。
37. **后台任务接口？** 内核 `pkg/tasks` + `/api/tasks`。
38. **进程级 WS holder 是什么？** `LiveTransport`，让传输在界面重建时保持。
39. **通知里的审批如何回到内核？** `ApprovalReceiver` 处理通知 action。
40. **本地改完要等内核推帧吗？** 不要，删除/归档/重命名后内核不推 `sessions`，先本地更新再 `listSessions()` 对齐（P-022）。

## C. 聊天与渲染

41. **消息支持 Markdown 吗？** 支持，标题、列表、表格、代码块、公式等。
42. **公式怎么渲染？** 由 OpenRender 引擎处理，壳侧只归一定界符（`normalizeMathDelimiters`，P-028）。
43. **支持哪些公式定界符？** 常见的 `$…$`、`$$…$$`、`\(…\)`、`\[…\]` 会被归一。
44. **表格列宽怎么算？** 按实际宽度分配，不能退回字符数加权。
45. **能直接显示 SVG 吗？** 能，v2.0 起全链路直接渲染 SVG（聊天围栏、文件预览、附件）。
46. **SVG 是否执行脚本？** 渲染 SVG 不应依赖脚本；复杂交互请用 HTML 块。
47. **能显示图片/视频/音频吗？** 能，通过媒体围栏路由（`MessageBlocks`，视频见 P-055）。
48. **可以交互的网页怎么显示？** 流内 `HtmlBlockView`，或全屏 `BrowserOverlay`。
49. **裸 HTML 会被识别吗？** `HtmlDetection` 会判定并合并成 HTML 块（`mergeHtmlBlocks`）。
50. **引擎有 HTML 块类型吗？** 没有，HTML 块是壳侧补的（P-041/P-049/P-050）。
51. **页面能把元素回传给模型吗？** 能，`PageBridge` 的 JS 桥 `window.__cf` 支持元素挑选。
52. **消息列表如何贴底跟随？** `MessageList` 用 `collectIsDraggedAsState()` 判断手指拖动（P-001）。
53. **为何不用 `isScrollInProgress`？** 它会把程序化滚动也算进去，打断流式跟随。
54. **滚到底怎么做？** 末尾放 1dp 哨兵 item 再滚到它（P-002）。
55. **有“回到最新”按钮吗？** 有。
56. **思考过程会显示吗？** 气泡内有思考/推理区域，可折叠。
57. **工具调用怎么展示？** `ToolCard`，带 diff 着色和中文标签（`ToolLabels`）。
58. **探索类操作会合并吗？** 会，`MessageList` 有探索分组。
59. **审批如何展示？** `ApprovalCardView` 卡片，也可从通知里批准。
60. **任务清单/子智能体在哪看？** `StatusStrips`。
61. **上下文占用能看到吗？** `StatusStrips` 有上下文表。
62. **后台脚本任务在哪管理？** `BackgroundTasksSheet`。
63. **能换主题和字体吗？** 暖色配色、字体和深色模式在 `Theme.kt`，也支持背景图。
64. **状态栏默认显示吗？** 默认隐藏，可在设置里开启（`showStatusBar`）。
65. **会话能分组吗？** 侧栏按项目分组（`SessionPane`）。
66. **会话可以归档/重命名/删除吗？** 可以，经 `SessionActionsSheet`。
67. **是否有问候页？** 有，空会话显示问候页。
68. **气泡样式改哪？** `ui/chat/MessageBubble.kt`。
69. **正文排版改哪？** `ui/chat/OpenRenderMessage.kt`。
70. **长文本在 Row 里被裁怎么办？** 加 `weight(1f)`（P-004）。

## D. 输入与附件

71. **输入框有什么特点？** 悬浮输入卡，支持多行，四态主按钮。
72. **四态主按钮是什么？** 按当前状态在发送、停止、语音等之间切换。
73. **支持语音输入吗？** 支持，只走云端识别（OpenAI 兼容接口，需在设置里启用并填 Key）；系统 `SpeechRecognizer` 已移除。
74. **云端语音接口？** OpenAI 兼容 `/audio/transcriptions`，16k PCM 封 WAV 后 multipart 上传。
75. **云端语音在哪配置？** 设置页 Voice 段（`sttCloud*`）。
76. **能用 @ 补全吗？** 能，`mentionCandidates` 做候选。
77. **@ 候选会被截断吗？** 不会，用 LazyColumn + heightIn，不许 `take(n)`（P-053）。
78. **能发附件吗？** 能，经附件弹层选择文件。
79. **小文件怎么传？** 小于 20MiB 走 `/api/upload_file`。
80. **大文件怎么传？** ≥20MiB 或服务端拒收，直写 `<workspace>/imports/` 并剥离 WS `attachments[]`。
81. **附件在气泡里怎么预览？** `MessageBlocks` 通过 `LocalAttachmentRoot` 渲染（P-034）。
82. **孤儿附件如何清理？** 首启流水线清理 `attachments/` 下超 7 天的文件。
83. **发送是串行的吗？** 是，ViewModel 有发送串行队列。
84. **能引用文件吗？** 能，文件管理器支持引用与装入附件。
85. **能插入技能吗？** 附件/技能弹层里可选。
86. **能切换模型吗？** 输入区有模型弹层。
87. **有“停止”能力吗？** 有，运行中主按钮变停止。
88. **发送前会校验 `@phone` 吗？** 会，发送前校验并同步手机操控状态。
89. **能粘贴图片吗？** 取决于系统剪贴板与选择器；未逐项验证。
90. **输入文字很长会怎样？** 输入卡可多行扩展，但具体上限未做专门测试。

## E. 手机操控 @phone

91. **@phone 是什么？** 让 agent 自主操控手机的内嵌能力，经环回 MCP 服务暴露工具。
92. **用什么协议暴露工具？** 环回 MCP（`PhoneMcpServer`）。
93. **MCP 工具共有几个？** 20 个（`PhoneMcpServerTest` 有数量断言）。
94. **怎么触发？** 在消息里 @phone，或在设置里启用手机操控插件。
95. **需要什么权限？** 无障碍服务等系统权限，由 `PhonePermissionActivity` 引导。
96. **如何观察屏幕？** `PhoneObservationService` 提供界面树观察，必要时用 `PhoneVisionAnalyzer` 视觉分析。
97. **怎么点击？** 先走节点点击，失败自动降级为坐标手势。
98. **点击失效可能是什么原因？** 点击过短，所以加入了拟人化点击时长 85–175ms。
99. **有随机点击延时吗？** 有，三档预设（`PhoneClickDelayPreset`）。
100. **随机延时的目的？** 贴近真人节奏，降低被应用判为机器的概率，也减少点击丢失。
101. **能批量操作吗？** 能，`phone.ui_batch` 在同一页面内顺序执行多步。
102. **批量后能自动观察吗？** 能，`observe_after` 参数。
103. **应用技能库是什么？** 按应用沉淀的操作经验，内置 11 个（含 `jd.md`）。
104. **怎么读取应用技能？** `phone.app_skill` 工具，两阶段渐进披露：先列目录再取细节。
105. **技能库在哪？** `assets/phone-app-skills.zip`，由 `PhoneAppSkillStore` 管理。
106. **能自己加应用技能吗？** 以文件形式扩充；具体入口以设置页为准。
107. **有黑名单吗？** 有，`PhoneControlSettings` 里可设置禁止操控的应用。
108. **危险操作要确认吗？** 要，`PhoneConfirmationGate` 弹确认。
109. **任务结束会自动停止吗？** 会，`markTurnCompleted`/`completeByAgent` 自动停止。
110. **结束后会回到 CodeForge 吗？** 会，`bringCodeForgeToFront`。
111. **有操作动画吗？** 有，`PhoneActionOverlay` 悬浮动画显示操作位置。
112. **能录屏/录音吗？** 能，`PhoneMediaService`。
113. **产物存哪？** `PhoneArtifactStore`，设置页可查看。
114. **能读通知吗？** 能，`PhoneNotificationService`，需要授权。
115. **系统能力封装在哪？** `PhoneSystemCapabilities` 与 `PhoneCommands`。
116. **执行历史保存吗？** `PhoneRuntime.init` 初始化偏好、产物元数据与执行历史。
117. **手机操控代码能用 Compose 吗？** 不能，`phone/` 不许引入 Compose UI 依赖。
118. **界面如何与它交互？** 通过 `PhoneControlPrefs`、`PhoneTaskController` 等的 StateFlow。
119. **技能怎么部署给 agent？** `PhoneSkillDeployer`，`SKILL_VERSION = "5"`。
120. **升级后技能会自动更新吗？** 落盘脚本不随 APK 自动更新，需“已就绪也重写”的下发层（P-014）。
121. **能同时操控多个应用吗？** 可跨应用，但批量只保证同页面内。
122. **操作失败怎么办？** 重新观察后重试，或降级坐标手势。
123. **会不会误触支付？** 敏感操作应走确认门，黑名单和确认可降低风险；仍建议自己盯着看。
124. **所有机型都能用吗？** 未全机型验证，主要在模拟器 `emulator-5554` 验证。
125. **适合无人值守吗？** 不建议，涉及资金、隐私的任务请保持人在场。

## F. 模型与设置

126. **支持哪些模型？** 通过 OpenAI 兼容等端点接入，国内外主流模型均可配置。
127. **有模型预设吗？** `ModelPresets` 提供常见上下文规格，未知模型默认兜底 ≥128K。
128. **怎么探测可用模型？** `KernelApi.discoverModels`。
129. **怎么测试连接？** `KernelApi.testModel`，字段名必须与内核 `modelTestReq` 一致（P-043）。
130. **供应商按什么识别？** 用 provider id，不用显示名（P-044/P-045）。
131. **密钥如何存储？** 加密 SharedPreferences（`AppSettings`）。
132. **设置写给内核吗？** 经 `KernelConfig` 同步到 `local.yaml`。
133. **内核运行设置有哪些？** 生成、重试、压缩、子智能体、通知（`KernelSettingsSection`）。
134. **旋转策略？** 不锁方向，跟随系统原生旋转，或锁定竖屏。
135. **能自定义背景吗？** 能，支持背景图。
136. **能开深色模式吗？** 能。
137. **状态栏显隐如何同步弹层？** `StatusBarVisibility` 同步主窗与所有弹层窗。
138. **语音设置在哪？** 设置页 Voice 段。
139. **文件传输设置在哪？** `FileTransferSection`。
140. **环境软件包设置在哪？** `EnvPackagesSection`。
141. **模型编辑器在哪？** `ModelEditorSection`。
142. **设置页是几级？** 二级页，模型与供应商库在其中。
143. **新设置项改哪？** `AppSettings` 存储 + `SettingsSheet` 弹层。
144. **模型上下文不够怎么办？** 内核支持压缩，可在内核设置中调整。
145. **能为不同会话用不同模型吗？** 以 UI 当前选择为准；具体按会话粒度未专项验证。

## G. 插件与技能

146. **插件格式？** `.cfplugin` 包。
147. **如何安装？** 在插件侧栏导入，`PluginInstaller` 校验并原子安装。
148. **安装失败会留半成品吗？** 不会，原子安装。
149. **能启停插件吗？** 能，PATCH 接口（P-025）。
150. **插件有密钥配置吗？** 有，`PluginDetailSheet` 里配置。
151. **多工作区如何同步技能？** `PluginSkills` 同步到各工作区。
152. **插件侧栏分几段？** 三段（`McpSidebarPanel`）。
153. **内置技能是什么？** `assets/builtin-skills.zip`，由 `BuiltinSkills` 管理。
154. **技能 frontmatter 有何限制？** 必须扁平单行（P-052）。
155. **手机操控是插件吗？** 是内置插件形态，名为 `@phone`。
156. **能接外部 MCP 吗？** 内核插件机制支持 MCP；具体配置以插件详情为准。
157. **插件模型定义在哪？** `model/PluginModels.kt`。
158. **插件会越权吗？** 受内核与审批机制约束，仍建议只装可信来源。
159. **技能如何被模型发现？** 由内核按技能目录加载，并经能力提示词引导使用。
160. **能系统提示词注入客户端能力吗？** 能，`CapabilityPrompt.CLIENT_CAPABILITY_PROMPT`（P-042）。
161. **改提示词要测什么？** `CapabilityPromptTest`（P-054）。
162. **提示词里有 SVG 规则吗？** 有，v2.0 加入 SVG 使用与工具协同规则。
163. **提示词会被显示给用户吗？** 带有注入标记，便于识别与剥离（P-042/P-054）。
164. **能删除内置技能吗？** 不建议，会在升级时被重新下发。
165. **插件和技能有什么区别？** 插件是能力打包单元，技能是给模型的操作说明文档。

## H. 环境与文件

166. **首启做什么？** 解压环境包并准备 proot + Alpine 运行环境。
167. **会遮屏吗？** 会盖“环境准备中”遮罩，失败不挡屏，可重试。
168. **环境根目录？** 一切锚定 `filesDir` 单根（`$CELL`）。
169. **内核如何进入沙盒？** 经 `CF_EXEC_WRAPPER` 走 `cfrun`。
170. **为什么用 proot？** 在无 root 的 Android 里提供类 Linux 环境。
171. **proot 要特殊环境变量吗？** 要 `PROOT_TMP_DIR` 和 `PROOT_LOADER`（P-014）。
172. **Alpine 源怎么配？** 多镜像并列，版本目录带 `v`（如 `v3.24`），空文件用 `ifBlank`（P-020）。
173. **`apk add` 报错一定失败吗？** 不一定，以退出码 0 或 probe 成功为准（P-021）。
174. **probe 能带管道吗？** 不能（P-051）。
175. **能装软件包吗？** 能，`EnvKit.packages` 管理增删与探测。
176. **文件管理器是哪个？** `FileManagerSheet`，统一浏览多个根目录。
177. **能浏览哪些位置？** 当前工作区、默认空间、全部工作区、手机存储、暂存缓存。
178. **能选目录当项目吗？** 能，目录选择器模式，可一键设为侧栏项目。
179. **能预览/播放文件吗？** 能，含图片、SVG、媒体。
180. **能导入导出吗？** 能。
181. **能重命名和删除吗？** 能，删除带安全保护。
182. **工作区是什么？** 项目目录，会话与文件操作都以它为根。
183. **工作区之间技能怎么共享？** 经 `PluginSkills` 多工作区同步。
184. **apk 里资源会被改名吗？** release 经 R8 会改名，验证用 `aapt2`（P-018）。
185. **assets 用 `.tar.gz` 可以吗？** 不行，AAPT2 会解压去后缀，用 `.tgz`/`.zip`（P-012）。

## I. 安全、隐私与排错

186. **数据发往哪？** 会发往你配置的模型服务商；内核与文件本身在本机。
187. **有统计上报吗？** 本项目未内置专门的统计上报；以实际代码和所选模型服务商为准。
188. **内核端口会被外部访问吗？** 绑定回环地址，外部设备不能直连。
189. **别的 App 能连内核吗？** 需要 Cookie 鉴权，其他 App 无法直接使用。
190. **崩溃日志在哪？** `filesDir/logs/crash-*.txt`，保留 5 份。
191. **内核起不来怎么办？** 看通知和日志，确认 `.so` 存在、ABI 为 arm64；`CoreService` 会退避重启。
192. **JNI 报 `UnsatisfiedLinkError`？** 符号名与包名不一致（P-026）。
193. **原生库二次报 `NoClassDefFoundError`？** 首次加载失败后的 LinkageError 家族，不重试（P-016）。
194. **Toast 位置不对？** Android 16 上 `setGravity` 被忽略，因此用自绘 `ToastHost`（P-024）。
195. **页面切换动画重叠？** 别把 `AnimatedContent` 放进 `verticalScroll`（P-023）。
196. **Insets 数值异常？** `WindowInsets` 返回像素，要转 dp（P-013）。
197. **想换名字怎么办？** 建议只改对外显示名，保留包名与 `.so`；改 applicationId 会让老用户无法覆盖升级。
198. **会自动提交代码吗？** 不会，按 `AGENTS.md` 只在你明确要求时提交、推送。
199. **如何验证发布包？** 看产物路径、大小，并用 `aapt2 dump` 读结构；最好装机运行断言。
200. **在哪提问题或商业授权？** 通过 `LICENSE` 中的邮箱联系；开源问题可到 GitHub 仓库提 issue。
