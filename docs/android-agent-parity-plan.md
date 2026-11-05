# Android 端 CodeForge Agent 能力对齐方案（第一阶段：架构决策）

> 状态：**待确认，未开始实现**
> 日期：2026-10-28
> 范围：`app/`（Android 壳）与 `source/CodeForge-GO/CodeForge-go/`（Go 内核，独立仓库）
> 原则：先确认边界，再按阶段闭环；不在 Kotlin 与 Go 各维护一份业务真相。

---

## 0. 结论摘要

| 问题 | 结论 |
|---|---|
| UI 策略 | **原生 Compose 为主**，继续补缺口；WebView 只作为调试期的「行为对照页」，不进正式包 |
| 服务边界 | **Go 作为 App 进程的子进程运行**（现状），HTTP/WS 跑在 `127.0.0.1`；proot 只包住「工具命令执行」，不包住服务与状态 |
| 状态真相源 | **Go 侧唯一真相源**：会话、消息、撤销栈、检查点、审批、目标账本全部在 Go/SQLite/FS；Kotlin 只做只读投影 + OS 能力 |
| 同步机制 | **WebSocket 推送为主 + REST 快照兜底**；不做轮询；重连后按 `ready → history → checkpoints/context/todo → pending approvals` 的顺序重放 |
| .so 导出面 | **当前 0 个 JNI/C ABI 导出**：`libcodeforge.so` 是 ELF PIE 可执行文件，不是可 `dlopen` 的共享库。接口是 39 个 HTTP 路由 + 1 个 WebSocket；**不建议新增 C ABI**，只补 HTTP/WS 契约 |
| 阶段数 | 6 个阶段，每阶段一个可演示闭环 |

---

## 1. 先纠正三个前提（基于当前代码，不是推测）

### 1.1 Android 并不是「只跑通对话流」

当前 `app/src/main/java/com/hxy/codeforge` 已有 **41 个 Kotlin 文件、约 15,520 行**，其中已经落地：

- 会话侧栏、切换、重命名、归档、删除（`ui/sessions/SessionPane.kt`、`SessionActionsSheet.kt`）
- HITL 审批卡片与决策（`ui/chat/ApprovalCardView.kt`、`ChatViewModel.decideApproval`）
- 检查点/回滚/编辑重发/继续上一轮（`ChatUiState.checkpoints`、`continueTurn`、`rewindFiles`、`editUserMessage`）
- 子智能体进度卡（`ui/chat/StatusStrips.kt` 的 `SubagentBar`，`CoreEvent.SubagentUpdate`）
- 设置页、模型库、供应商管理、MCP/内置插件、技能、上下文压缩、diff 回放
- 语音、附件、相机、旋转、前台服务等移动端能力

所以本方案**不是从零重写**，而是「先修状态机断点，再补真正缺失的面」。

### 1.2 `libcodeforge.so` 不是可加载的共享库

实测 `readelf`：

- `Type: DYN (Shared object file)`，但 `FLAGS_1: PIE`、有入口地址；
- **没有任何 `Java_*` / `or_*` / JNI 导出**；
- `CoreService.kt` 用 `ProcessBuilder` 启动 `${nativeLibraryDir}/libcodeforge.so`，参数 `-config … -workdir … -no-open`，环境 `HOME`、`CODEFORGE_PORT=0`，从 stdout 读 `PORT=`。

结论：当前「.so」实质是**以 PIE 形式打包的可执行文件**。服务边界已经是「子进程 + localhost HTTP/WS」，不是「JNI 进程内调用」。

### 1.3 proot 只包住工具执行，不包住内核

`EnvKit.kt` 的契约是：

- `$CELL = filesDir` 单根，`workspace/`、`tmp/`、`usr/` 同树；
- 内核通过 `CF_EXEC_WRAPPER=/system/bin/sh …/cfrun` 让 `run_command` 进入 proot；
- proot 用 `-b $CELL:$CELL` 同路径绑定，宿主与 guest 看到同一棵目录树。

结论：**Go 服务、SQLite、撤销栈、审批状态都在 proot 外**；proot 里只有用户命令和工具链。不要在 proot 里再起一份服务。

---

## 2. Q1：UI 策略

| 维度 | A. WebView 加载 `web/dist` | B. 原生 Compose（现状继续） |
|---|---|---|
| 开发量 | UI 代码≈0；但要补 Android WebView 宿主：Cookie/鉴权、`onShowFileChooser`、相机、语音、通知、insets、旋转、返回键、深链，约 1k 行桥接 | 已有 15.5k 行；剩余约 5 个面板/状态机缺口（撤销、目标、工作区/文件树、记忆/技能/外观、审批超时） |
| 状态同步 | 不新增真相源，页面直接消费 WS/REST；但 WebView 内的 JS 状态与原生壳（语音/附件/通知）需要桥接，容易出现两套 UI 状态 | 已有 `ChatUiState` 单向数据流；Kotlin 只投影 Go 状态，边界清晰 |
| 性能 | WebView + 7,607 行 `ui.js` + 46 KB HTML + 156 KB CSS；流式重绘与滚动在移动端要重新调优 | Compose 原生列表已有贴底跟随/流式增量/图片缓存；代价是 Compose 自身开销 |
| 对现有 .so 边界改动 | **0**；服务已经 `go:embed` 了 `web/dist`，`GET /` 可加载 | **0**；仍然只走 HTTP/WS |
| Android 融合风险 | 高：输入法/insets、文件选择、相机、语音、通知点击、横持旋转都要额外桥；WebView 里的桌面布局在手机上体验不可控 | 中：现有移动端能力已适配，主要是补业务面板 |
| 主要风险 | 两套前端并存、原生能力割裂；WebView 安全面（localhost + Cookie） | 需要持续追平 `ui.js` 的功能清单 |

**推荐：B（原生 Compose）为主。**

理由：

1. 现有壳已经覆盖了大部分「重前端」工作，切 WebView 等于把 15.5k 行移动端适配作废；
2. 会话/审批/检查点/子智能体已经有原生组件，真正缺的是**状态接线**，不是 UI 重写；
3. 移动端最关键的文件选择、相机、语音、通知、横持旋转已经在原生侧解决，WebView 路线仍要重做这些桥；
4. 两条路线都只消费 HTTP/WS，语义一致性由 Go 保证，不靠 UI 框架。

**保留 WebView 的位置**：只在 debug 构建里做一个「Web 对照页」，用于逐项比对 Web 端行为（不进 release，不作为产品 UI）。如果验收时发现某个面板原生实现成本远高于收益，再单独评估该面板是否嵌 WebView，而不是整体切换。

---

## 3. Q2：服务边界

| 方案 | 形态 | 状态放在哪 | 优点 | 缺点 | 结论 |
|---|---|---|---|---|---|
| A. App 子进程（现状） | `ProcessBuilder` 启动 PIE；HTTP/WS 在 `127.0.0.1`；proot 只包工具命令 | 会话/消息/检查点：Go SQLite；撤销：Go `FS` 内存桶 + spill；审批：Go Executor/Approver；目标：Go Session Ledger | 进程隔离、崩溃不拖死 UI；已有 E1 真机通过；无需 cgo/JNI；与桌面/服务器同一套语义 | 需要端口握手与前台服务保活；WS 断开会停掉本连接的运行 | **推荐** |
| B. proot 内起服务 | Go 服务跑在 Alpine rootfs 里 | 全在 proot 内的 Go/SQLite | 与工具链同一环境 | 路径双世界（宿主 `$CELL` vs rootfs）、SQLite/撤销副本在 rootfs、端口/生命周期更复杂、性能更差 | 不推荐 |
| C. c-shared 进程内 | `-buildmode=c-shared` + JNI 启动 | 全在 App 进程内的 Go | 无子进程、生命周期归 ART | 需要 cgo/NDK，违背 `CGO_ENABLED=0` 目标；Go runtime/signal/fork 与 Android 生命周期耦合；一次 panic 可能拖死 App | 仅作为远期 Plan B，不在本周期 |

**推荐 A，并明确：**

- **会话、消息、撤销、检查点、审批、目标账本，全部以 Go 为唯一真相源**；
- Kotlin 只持有 `ChatUiState` 投影、输入草稿、主题/旋转/语音设置等 **UI 本地状态**；
- proot 容器只负责工具命令与工具链，不持有任何业务状态；
- 工作区切换、附件落盘都必须经 Go 的文件路径契约，Kotlin 不直接写 `workspace/`（当前 `Composer.copyToWorkspace` 是例外，见 §5）。

---

## 4. Q3：状态真相源与同步

| 状态 | 真相源 | Kotlin 侧 | 同步方式 |
|---|---|---|---|
| 会话列表/标题/归档 | Go `agent.History` + SQLite `sessions` | `ChatUiState.sessions` 只读投影 | `ready` 帧的 `sessions`、`sessions` 帧（键是 `items`，注意 P-008）、`load_session` 的 `history` |
| 消息历史 | Go `Session.Messages` + SQLite `messages` | `messages` 只读投影 | `history` 快照帧；流式用 `text/reasoning/tool_*` 增量 |
| 撤销栈 | Go `builtin.FS.undo[sessionID]` + spill | 只显示「剩余步数/撤销结果」 | 新：`POST /api/undo` 带 `session_id`；可加 `GET /api/undo?session_id` 查 depth |
| 检查点 | Go `store.checkpoints` + `agent.CheckpointSteps` | `checkpoints` 只读投影 | `checkpoints` 帧（已有 `steps/editables/resume_back`） |
| 审批挂起 | **应在 Go Server 级**（当前在 `wsClient.approver`，见风险） | `approvals` 只读投影 | `hitl_request` 推送；新：重连重放 + `hitl_timeout` + `hitl_resolved` |
| 目标账本 | Go `Session.goal`（`GoalState`） | 只读投影 | **当前没有对外帧**；新：`goal` 帧 / `GET /api/goal?session_id` |
| 工作区 | Go `FS.root` / `Agent.WorkDir` | 只读当前路径 | `workspace_blocked`、`/api/workspace`、`/api/workspaces` |
| UI 主题/旋转/语音偏好 | Kotlin `AppSettings` | 本地持久化 | 不需要 Go 参与 |

**同步规则（硬性）：**

1. **推送优先**：所有状态变化由 Go 主动发 WS 帧；Kotlin 不轮询业务状态。
2. **快照兜底**：WS 重连、会话切换、历史被截断（`edit`）时，必须用 `history`/`sessions`/`checkpoints`/`context`/`todo` 快照重建，不靠增量猜。
3. **乐观更新只限用户气泡与本地 UI**：发送消息可以先上屏；服务端 `session`/`history` 回来必须能纠正，不能在 Kotlin 落库。
4. **禁止双写**：Kotlin 不得持久化会话、消息、撤销、检查点、审批；否则重装/重启后两份真相必然分叉。
5. **审批必须可在重连后恢复**：这是当前 `wsApprover` 的结构性缺口，见 §6.2。

---

## 5. Q4：接口面（当前 .so 与缺失契约）

### 5.1 当前真实导出

| 项目 | 现状 |
|---|---|
| JNI / C ABI | **0 个**。`libcodeforge.so` 是 PIE 可执行文件 |
| 进程启动 | `ProcessBuilder(nativeLibraryDir/libcodeforge.so, -config, -workdir, -no-open)` |
| 环境变量 | `HOME=filesDir`、`CODEFORGE_PORT=0`、`CF_EXEC_WRAPPER=…/cfrun` 等 |
| stdout 握手 | 首行 `PORT=xxxxx` |
| HTTP | 39 个路由（health/config/perm/notify/tree/sessions/rewind/workspaces/memory/skills/plugins/builtin-plugins/subagents/undo/diff/context/compress/workspace/pick_*/appearance*/stage_file/upload_file/termux/tools/models*/providers*/shutdown/ws/） |
| WS 上行 | `user_message/steer/regenerate/edit_user_message/continue_turn/retry/rewind/checkpoints/new_session/list_sessions/load_session/hitl_decision/context/visibility/cancel` |
| WS 下行 | `ready/session/sessions/history/busy/idle/text/reasoning/tool_pending/tool_call/tool_result/hitl_request/retry/compress/edit/steer/steer_queued/rewind/todo/context/subagent/checkpoints/workspace_blocked/error/warn/info/done` 等 |

### 5.2 需要补的契约（**不是新 .so 函数**）

按功能分组，全部通过现有 HTTP/WS 扩展：

1. **会话归属（P0，客户端 bug）**
   - `HttpCoreTransport.sendMessage` 只发了 `{type:user_message,text}`，**没有 `session_id`**；
   - 服务端 `startUserMessage` 在 `session_id == ""` 时 `History.Create` → **每说一句就新建会话**；
   - 修复：发送时带 `session_id`；`new_session` 只在用户显式新建时发。

2. **撤销（Go + Kotlin）**
   - 服务端 `FS.UndoSession(sessionID)` / `UndoDepthFor(sessionID)` 已存在且按会话分桶、带 32 MiB 预算与 spill；
   - 但 `handleUndo` 仍调用进程级 `s.fs.Undo()`，**没有接会话参数**；
   - 需改：`POST /api/undo` 接收 `session_id`，返回 `{ok,path,remaining}`；可加 `GET /api/undo?session_id` 查深度；
   - Kotlin：`KernelApi.undo(sessionId)` + 会话菜单/工具卡入口。

3. **审批可靠性（Go）**
   - `wsApprover.pending` 挂在 `wsClient` 上；连接断开时 `handleWS` 的 `defer` 会停掉该连接所有 run；
   - 结果：Android 网络闪断/重连后，审批卡片还在、审批 ID 已失效；运行中的轮次被停；
   - 需改：pending 审批提升到 `Server` 级（按 `session_id + approval_id`），重连时重放 `hitl_request`；运行生命周期与连接解耦（至少给一个短 grace period）；
   - 新增 `hitl_timeout`（带 `approval_id/session_id/timeout_ms`）与 `hitl_resolved` 帧；Executor 已有 15 分钟独立审批超时，但当前只把超时落成工具错误，界面无法区分「拒绝」与「无人处理」。

4. **目标模式（Go + Kotlin）**
   - Go 已有 `GoalState`（目标、轮次、最后判定、findings、步骤），但没有 WS 帧/HTTP 端点；
   - 需加：`goal` 帧（`SetGoal/RecordVerdict/ClearGoal` 时推送）与 `GET /api/goal?session_id`；
   - Kotlin：目标面板（目标文本、轮次/上限、PASS/FAIL/BLOCKED、发现清单、当前步骤）。

5. **断点/重试（客户端）**
   - 服务端已支持上行 `retry`；
   - `CoreTransport` 目前没有 `retry()`；Kotlin 只有 `regenerate/continueTurn`；
   - 需加：`retry(back, rollbackFiles)` 上行；UI 在 `resume_back` 与未完成轮次上提供「重试」。

6. **文件/附件（客户端）**
   - 服务端已有 `/api/upload_file`、`/api/stage_file`；
   - `Composer.copyToWorkspace` 直接 `FileOutputStream` 写 `workspace/attachments/…`，绕过服务端原子落盘/审计；
   - 需改：附件先 POST 到 Go 端点（或至少走服务端的原子写与命名规则），Kotlin 不再直接写工作区。

7. **其余已存在、无需新增 .so 的**
   - `sessions`、`checkpoints`、`rewind`、`diff`、`memory`、`skills`、`plugins`、`builtin-plugins`、`models/*`、`providers/*`、`workspaces`、`workspace`、`appearance` 均已有 HTTP；缺的是 Kotlin UI 接线，不是后端能力。

### 5.3 如果将来走 c-shared（仅登记，不批准）

最小 C ABI 集合才会是：

```c
int  codeforge_start(const char *config_dir, const char *work_dir, int port);
int  codeforge_stop(void);
int  codeforge_port(void);
int  codeforge_status(void);          // starting/ready/failed
int  codeforge_reload_config(void);   // 设置页保存后热重载
```

这需要 `-buildmode=c-shared` + cgo + NDK，和当前 `CGO_ENABLED=0`、16KB 页、进程隔离的取舍冲突；**本周期不采用**。

---

## 6. Q5：六阶段计划（每阶段一个可演示闭环）

### 阶段 1 · 会话归属与持久化闭环

**目标**：一条消息只属于一个会话；新建/切换/重命名/归档/删除/重启恢复全部正确。

- 状态边界：会话/消息全部 Go；Kotlin 只投影。
- 接口：无新 .so；修 `sendMessage` 带 `session_id`；核对 `new_session/list_sessions/load_session`。
- UI：`SessionPane`、会话菜单、空会话复用、归档折叠。
- 验收：
  1. 新建 A，连发 3 条，列表只有 A，消息 3 条；
  2. 杀进程重开，A 与 3 条消息完整恢复；
  3. 新建 B，切换 A/B，历史不串；
  4. 重命名/归档/删除后重启仍生效。
- 回归：流式输出、审批卡片、WS 重连。

### 阶段 2 · 审批 HITL 可靠性闭环

**目标**：Ask 策略下审批卡片可跨重连存活；超时与拒绝在界面上明确区分；子智能体审批不混淆。

- 状态边界：审批 pending / 运行生命周期 Go Server 级；Kotlin 只投影。
- 接口：新增 `hitl_timeout`、`hitl_resolved`；重连重放 `hitl_request`；运行与连接解耦。
- UI：卡片倒计时/超时态、拒绝态、子任务标识。
- 验收：
  1. 触发写文件审批，拔网/重连后卡片仍在且可批准；
  2. 批准后工具继续执行；拒绝后工具不执行；
  3. 把审批超时调短，界面显示「审批超时」而非「用户拒绝」；
  4. 两个子智能体同时审批，卡片按 `subagent_id` 区分。
- 回归：普通工具调用、取消、busy/idle、上下文帧。

### 阶段 3 · 撤销栈与文件安全闭环

**目标**：按会话撤销、字节预算、spill 落盘、工作区围栏全部可用；Android 附件不再绕过服务端文件路径。

- 状态边界：撤销栈在 Go `builtin.FS`（已有）；Kotlin 只显示结果。
- 接口：`/api/undo` 接 `session_id`；新增 depth 查询；`Composer` 改走 `/api/upload_file`。
- UI：会话菜单/工具卡「撤销最近写入」、剩余步数、失败原因。
- 验收：
  1. A/B 两会话各写文件，撤销 A 只影响 A；
  2. 连续撤销按会话内顺序回退；
  3. 超过 32 MiB/100 条的预算时按策略淘汰；
  4. 杀进程重启后，spill 副本仍可撤销；
  5. 附件经服务端落盘，未读先写/越界写仍被 Go 拒绝。
- 回归：检查点回滚、diff、工具卡、附件发送。

### 阶段 4 · 断点续跑、重试、检查点闭环

**目标**：未完成轮次跨重启保留；继续/重试/编辑重发/文件回滚语义与 Web 一致。

- 状态边界：检查点与历史 Go；Kotlin 只投影。
- 接口：补 `retry` 上行；核对 `checkpoints.resume_back`、`edit_user_message`、`rewind`。
- UI：继续按钮、重试菜单、检查点面板、编辑重发锚点。
- 验收：
  1. 运行中杀掉 App，重开后出现「继续」；
  2. `retry` 保留用户原话、截断未完成回复；
  3. 编辑重发截断历史并回退文件；
  4. 检查点回滚只动文件、不动对话。
- 回归：阶段 1 会话切换、阶段 2 审批、阶段 3 撤销。

### 阶段 5 · 子智能体与目标模式闭环

**目标**：`delegate_subagents` 进度可观察；`goal_verify` 的强约束、轮次上限与判定可见。

- 状态边界：子智能体事件/目标账本 Go；Kotlin 只投影。
- 接口：新增 `goal` 帧/`GET /api/goal`；核对 `subagent` 帧。
- UI：子智能体卡（已有，补审批身份/失败态）；目标面板（目标、轮次、判定、findings）。
- 验收：
  1. 并行子智能体逐个显示 running/done/failed；
  2. 目标模式触发后，`goal_verify` PASS 关闭账本、FAIL 保留轮次并注入发现；
  3. 超过轮次上限时停止并明确提示；
  4. 验证者工具白名单/步数上限生效。
- 回归：普通工具卡、上下文、todo、审批。

### 阶段 6 · 前端 parity 清点与发布闭环

**目标**：以 `web/dist/ui.js` 为清单逐项对照，所有差异有原生等价物或明确 TODO；出 release 包。

- 范围：工作区分组/文件树/SAF 切换、记忆新增/编辑、技能、插件、外观、诊断/health、模型/供应商全量表单、diff 回放、通知点击跳转。
- 验收：
  1. 对照表每项「已对齐 / 有意差异 / TODO」；
  2. 真机全量回归（阶段 1–5 的验收项全跑）；
  3. release APK 产物路径与大小记录，R8 后 JNI/so 仍在包；
  4. 已知限制写入 `工作进程.md`，不静默删除。
- 回归：全部历史功能。

---

## 7. 当前已确认的阻塞点（必须进 TODO，不能糊弄）

1. **审批器是连接级**：`ws_client.approver` + 断连停 run → 重连后审批/运行不可恢复。需要 Go 侧结构改动（阶段 2）。
2. **`/api/undo` 没接会话**：`FS` 已分桶，HTTP 仍走进程级 `Undo()`（阶段 3）。
3. **目标状态没有出口**：`GoalState` 只在 `Session` 内，WS/HTTP 都看不到（阶段 5）。
4. **`sendMessage` 丢 `session_id`**：服务端按空会话新建，这是「一句话一个新会话」的直接根因（阶段 1）。
5. **附件直写工作区**：`Composer.copyToWorkspace` 绕过 `/api/upload_file` 的原子落盘与审计（阶段 3）。
6. **`retry` 上行缺失**：服务端已有，客户端没发（阶段 4）。
7. **Web 端 7,607 行功能清单**：尚未逐项建立 Android 对照表（阶段 6）。

---

## 8. 待确认决策

请确认以下三项后再进入实现：

1. UI：**原生 Compose 为主**（推荐） / WebView 为主 / 混合；
2. 服务：**Go 子进程 + proot 只包工具**（推荐） / proot 内起服务 / c-shared 进程内；
3. 状态：**Go 唯一真相源 + WS 推送 + REST 快照**（推荐） / Kotlin 本地持久化 / 双写。

确认后从**阶段 1（会话归属与持久化）**开始，按阶段验收，不跨阶段合并。

---

## 9. 实现进度（2026-10-28 本轮）

已完成并出包：

- 会话归属：`sendMessage` 带 `session_id`；`cancel` 带 `session_id`；新建只在显式操作时发生。
- 工具文案：`run_command` 与 Web 对齐为「运行」；全量 Kotlin 单测恢复通过。
- 撤销：Go `/api/undo` 支持 GET 查深度 / POST 带 `session_id` 撤销；Android 会话菜单新增「撤销最近一次文件写入」。
- 目标模式：Go 新增 `goal` 帧与 `GET /api/goal`；Android 新增目标状态条（目标/轮次/判定/findings）。
- 审批：Go 新增 `hitl_timeout` / `hitl_resolved` 帧；Android 超时卡收掉并显示独立警告，断开连接时清空过期卡片。
- 断点：`CoreTransport.retry` 上行接通；检查点弹层新增「重试未完成的轮次」。
- 记忆：KernelApi 增加 add/update；设置页内核区新增记忆列表、新增与删除。
- 附件：Composer 落盘改为同目录临时文件 + rename（内核 casWrite 同口径），仍保留在会话空间 attachments。
- 内核：`libcodeforge.so` 重编（arm64，17,301,798 字节），产物包含 `api/goal`、`hitl_timeout`、`goalEvent`、`UndoDepthFor` 等符号。

仍然未完成（不静默删除）：

| 项 | 状态 | 阻塞点/原因 |
|---|---|---|
| 审批跨 WS 重连存活 | TODO | `wsApprover.pending` 与 `wsClient.runs` 都是连接级；要真正存活需把运行注册表提升到 Server 级并支持事件重绑定，属 Go 侧结构性改动 |
| `/api/tree` 文件树 | TODO | 无 Android UI；手机端文件浏览入口需单独设计 |
| `/api/appearance*` 外观背景 | TODO | Android 有独立主题，未把背景图接口映射到 Compose |
| `/api/workspace` 工作区选择 | 部分 | SAF 返回 content URI，不是内核要求的文件系统路径；`pick_folder` 在 Android 不可用（平台限制），只能选应用私有空间或已映射目录 |
| `/api/memory` PATCH 编辑 UI | 部分 | API 已接，列表只支持新增/删除，未做就地编辑 |
| Web 端 7,607 行功能逐项 parity | TODO | 尚未建立完整对照表（阶段 6） |
| 真机验证 | TODO | 本机无连接设备；JNI/撤销/目标/审批超时均只有编译与接口级验证 |

平台明确不允许/不适用：

- `/api/pick_folder`、`/api/pick_file`：Android 用 SAF，服务端无法弹系统选择器。
- `/api/termux/tools`：非 Termux 环境不适用。
- 服务端 `Go` 文件系统对话框/宿主浏览器打开：Android 无对应语义。

## 10. 下一轮建议

1. 先做 Go 侧运行注册表改造（审批与运行跨连接存活），这是唯一会影响所有工具调用的结构性风险。
2. 再做 `/api/tree` + 工作区入口（应用私有空间 + SAF 导入目录）。
3. 最后做 `ui.js` 逐项对照表与真机回归。
