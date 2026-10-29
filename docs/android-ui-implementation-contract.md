# Android UI 并行实现契约（2026-10-28）

> 目的：多个子智能体同时改不同文件，避免互相覆盖。**只改自己名下的文件**；共享接口按本文件约定，不得擅自改名。
> 需求源：`c:\Users\26536\Desktop\改动详细.txt`（用户逐条批注）。

## 文件所有权（硬性）

| 智能体 | 独占文件 |
|---|---|
| A · 数据/协议 | `app/src/main/java/app/codeforge/model/CoreModels.kt`、`core/KernelApi.kt`、`ui/ChatViewModel.kt` |
| B · 项目/侧栏 | `ui/sessions/SessionPane.kt`、`ui/AppRoot.kt`、新文件 `ui/sessions/ProjectPanel.kt`、`ui/sessions/FileTreePanel.kt`、`ui/sessions/McpSidebarPanel.kt` |
| C · 输入区 | `ui/chat/Composer.kt`、新文件 `ui/chat/ActionPanel.kt` |
| D · 设置/模型/外观 | `ui/settings/SettingsSheet.kt`、`ui/settings/KernelSettingsSection.kt`、`ui/settings/AppSettings.kt`、新文件 `ui/settings/ModelEditorSection.kt` |
| E · 消息流/主题 | `ui/chat/MessageList.kt`、`ui/chat/MessageBubble.kt`、`ui/chat/ToolCard.kt`、`ui/chat/StatusStrips.kt`、`ui/theme/Theme.kt`、`ui/common/Glyphs.kt` |
| F · proot 挂载 | `core/EnvKit.kt` |

任何人**不得**改别人的文件；需要对方文件配合时，在最终报告里写清“需要 A/B/C/D/E 接线”，不要动手。

## 共享接口（A 负责先加，其他智能体按此使用）

### 模型（`model/CoreModels.kt`）

```kotlin
data class SessionItem(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val busy: Boolean = false,
    val workspace: String = "",
    val workspaceName: String = "",
    val archived: Boolean = false,
)

data class ProjectItem(
    val workspace: String,
    val name: String,
    val sessionCount: Int = 0,
    val busy: Boolean = false,
    val archived: Boolean = false,
)

data class TreeEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long = 0,
)

data class ActionPanelState(
    val title: String = "计划书已生成",
    val content: String,
)

/** 真实对话流片段：思考 / 工具 / 正文按发生顺序排列。 */
sealed interface MessageSegment {
    data class Thinking(val text: String) : MessageSegment
    data class Tool(val call: ToolCall) : MessageSegment
    data class Text(val text: String) : MessageSegment
}

data class ChatMessage(
    ... 现有字段 ...,
    val segments: List<MessageSegment> = emptyList(),
)
```

### 状态（`ChatViewModel.kt` 的 `ChatUiState`）

```kotlin
val projects: List<ProjectItem> = emptyList(),
val currentWorkspace: String = "",
val currentWorkspaceName: String = "",
val workspaceSwitching: Boolean = false,
val treeEntries: List<TreeEntry> = emptyList(),
val treePath: String = "",
val treeParent: String = "",
val treeError: String? = null,
val actionPanel: ActionPanelState? = null,
```

### 方法（`ChatViewModel`）

```kotlin
fun createProject(name: String, workspacePath: String, permission: PermissionMode)
fun switchProject(workspace: String)
fun renameProject(workspace: String, newName: String)
fun archiveProject(workspace: String, archived: Boolean)
fun deleteProject(workspace: String)
fun loadTree(path: String = "")
fun dismissActionPanel()
fun sendActionPanel(text: String)
```

- `switchProject`：调用 `POST /api/workspace` 后 `loadSession` 最新会话或 `newSession`，**不清空其他项目的运行状态**；运行中项目由 `sessions`/`busy` 帧维持。
- `createProject`：`POST /api/workspace` → `POST /api/perm` → WS `new_session`；成功后刷新 workspaces/sessions。
- `loadTree`：`GET /api/tree?path=&depth=1`，错误写 `treeError`。
- 会话 `workspace`/`workspace_name` 从内核 `sessions`/`ready` 载荷解析（字段名以 `agent.SessionMeta` 为准）。
- `actionPanel`：用户发送包含 `@plan` 的消息后，本轮 `idle` 时把最后一条助手正文作为 `ActionPanelState.content`；发送/关闭后清空。
- `segments`：折叠事件时同步维护；保留 `thinking`/`tools`/`text` 旧字段兼容。

### 设置持久化（`AppSettings`，D 负责加）

```kotlin
val inputBoxStyle = mutableStateOf("default")      // default / black / white / clear
val backgroundImagePath = mutableStateOf("")
val backgroundBlur = mutableIntStateOf(0)
val backgroundBrightness = mutableIntStateOf(100)
val textColorOverride = mutableStateOf("")          // "" = 跟随背景自动
```

配套 setter：`setInputBoxStyle`、`setBackgroundImage`、`setBackgroundBlur`、`setBackgroundBrightness`、`setTextColorOverride`。

### 组合函数签名

B 在 `AppRoot` 里调用 C 的 `Composer` 时，保持现有参数，并补：

```kotlin
skills = state.skills,
builtinPlugins = state.builtinPlugins,
mcpPlugins = state.mcpPlugins,
actionPanel = state.actionPanel,
onDismissActionPanel = vm::dismissActionPanel,
onSendActionPanel = vm::sendActionPanel,
```

C 负责给 `Composer` 增加这些参数（带默认值，便于 B 未接线时也能编译）。

## 需求要点（用户批注）

- 新建项目：侧栏项目入口；默认名 = 目录名；重复点空项目提示已存在；选中项目后“新建对话”建在该项目下；会话默认名“新建会话”，首条消息后改为首条内容。
- 项目行支持折叠、＋新建会话、重命名/归档/删除；项目归档/删除复用会话 UI。
- 侧栏 “code” 改为 “MCP”，点击铺满侧栏显示全部 MCP 工具，左上角返回。
- 工作区切换保留运行状态；从外部回来刷新最新状态；当前项目运行中转圈。
- 归档不显示在左侧栏，设置里新开归档页（永久删除 + 10 天说明）。
- ＋ → 当前目录文件；＋ → Skills和技能（复用模型上拉框）；@ 动态补全技能/插件；@ 高亮。
- 编辑重发不能重复渲染消息，回退到重发位置；编辑提示条。
- ActionPanel：消息框增高成选择框，默认选中计划书内容，下方输入框，左侧选中，发送键一个。
- 权限盾牌三档检查是否真能控制。
- 上下文占比改成环绕发送框的细线，或设置可关闭；删除当前上下文条。
- 思考-执行-思考-执行按真实顺序渲染，不能工具全堆在正文前。
- 用户消息长按图标去可爱化。
- 任务清单折叠放进右上角三点菜单，实时更新。
- Diff 独立面板不做。
- 模型：列表按供应商分组 + 设为当前；设为当前/删除/添加后刷新 UI；供应商详情双栏；表单含继承地址/密钥、密钥显示隐藏、上下文预置档、图片/视频三态、RPM（可选项）、获取模型列表（筛选/已添加/批量/错误）、测试连接/重置、就地编辑。
- 外观：输入框黑/白/透明、背景图（安卓图片选择器）、模糊、亮度、文字颜色自动/手动。
- 扩展：记忆就地编辑、技能独立页、内置插件说明、MCP 完整表单、归档页。
- proot 挂载到安卓文件系统。
- 文件树：放大到整个侧栏，含 `..` 返回，底部选择工作区，选择完成缩小动画（参考 MCP 放缩）。

## 协作规则

- 不改核心协议、不改 Go 源码（F 除外，F 只改 EnvKit）。
- 不运行完整 Gradle 构建（并行半成品会互相报错）；只保证自己文件逻辑/括号/import 自洽。
- 最终报告：改了哪些文件、需要谁接线、未完成点。
