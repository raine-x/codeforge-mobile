# Android 未实现/半实现的 Web 前端 UI 清单

> 来源：逐项阅读 `web/dist/index.html`（733 行，页面骨架与全部设置页）与 `web/dist/ui.js`（7,607 行，动态面板与交互）。
> 对照：`app/src/main/java/app/codeforge` 现有 Compose 组件。
> 状态：❌ = Android 完全没有；◐ = 有等价物但缺字段/交互；✅ = 已对齐；— = 平台差异，不适用。
> 说明：UI 形态允许不同（Compose 不需要照抄 DOM），但**语义**必须一致。

## 先回答你点名的四项

| 项 | 状态 | Web 端证据 | Android 现状 |
|---|---|---|---|
| 模型上下文设置 | ❌ | `index.html` 660-668：输入/输出上下文，预置档位 + 自定义；`ui.js` `buildCtxPicker`（6433） | 添加模型只有 id/显示名/供应商，`KernelApi.saveModelFull` 支持 `ctx_in/ctx_out` 但 UI 没传、也没编辑入口 |
| 模型能力支持 | ❌ | `index.html` 674-693：图片/视频三态（不声明/支持/不支持）；`index.html` 697-700：RPM | 无三态控件、无 RPM；添加模型固定 `vision/video` 不传 |
| 新建项目 | ❌ | `index.html` 51-54「新建项目」；`ui.js` `openNewProjectDialog`（2785）/`buildNewProjectDialog`（2804） | `SessionPane` 只有「项目」占位导航（SessionPane.kt 89），没有项目/工作区概念 |
| @ 技能/插件 | ❌ | `ui.js` `renderMoreSkills`（5205）拉 `/api/skills` + `/api/builtin-plugins`，点击插入 `@slug`/`@id` | `Composer.kt` 447 只有固定 4 个触发词 `plan/goal/vuln/reverse`；没有动态技能、没有内置插件清单 |
| ＋ → Skills和技能 | ❌ | `index.html` 137-156：`添加文件` / `当前目录的文件` / `Skills和技能` 二级菜单 | `Composer.kt` 的 `+` 附件面板只有最近文件/拍照/相册/文件，没有 Skills和技能，也没有“当前目录的文件” |

## A. 侧栏 / 项目

| # | UI | 状态 | Web 证据 |
|---|---|---|---|
| A1 | 新建项目（项目名、工作区、默认权限） | ❌ | `index.html` 51-54；`ui.js` 2785-2887 |
| A2 | 项目按工作区分组；项目行折叠箭头、项目名、＋新建会话、⋯重命名/归档/删除 | ❌ | `index.html` 56-64；`ui.js` `renderSessions`（2569） |
| A3 | 侧栏 MCP 服务概览卡（已启用 N 个，title 明细） | ❌ | `index.html` 35-43；`ui.js` `renderMcpList`（3329） |
| A4 | 工作区切换/选择文件夹（左上角工作区标签） | ❌ | `ui.js` `openBuiltinPicker`（4162）、`/api/pick_folder`；Android 无 SAF→内核路径的映射入口 |
| A5 | 侧栏拖拽调宽（200px 起） | — | 桌面鼠标能力；Android 不适用 |
| A6 | 已归档会话管理页（恢复 / 立即永久删除 / 10 天自动删除说明） | ◐ | `index.html` 485-496；Android 侧栏有“已归档”折叠组与恢复，但没有独立管理页与永久删除说明 |

## B. 输入区

| # | UI | 状态 | Web 证据 |
|---|---|---|---|
| B1 | ＋ → 添加文件 | ✅ | Android 附件面板已覆盖（相册/文件/拍照） |
| B2 | ＋ → 当前目录的文件（工作区文件浏览/选择） | ❌ | `index.html` 147-149；`ui.js` `openBuiltinPicker` |
| B3 | ＋ → Skills和技能（技能 + 内置插件，点击插入 @） | ❌ | `index.html` 150-155；`ui.js` `renderMoreSkills`（5205-5265） |
| B4 | 输入框 @ 动态补全：技能 slug/display_name/description + 内置插件 @id | ❌ | `ui.js` 5205+；Android `Composer.kt` 447 固定四词 |
| B5 | @ 提及高亮镜像层（textarea 下叠一层只染 @） | ❌ | `index.html` 128-134 `input-mirror` |
| B6 | 编辑重发提示条（“正在编辑这条消息 / 发送将丢弃其后的对话与文件改动” + 取消/发送） | ❌ | `index.html` 116-127 `edit-bar`；Android 仅回填输入框 |
| B7 | ActionPanel（@plan 计划书完成后“直接发送/取消”，多页/多选/用户输入） | ❌ | `index.html` 113-115；`ui.js` `showActionPanel`（1833） |
| B8 | 输入区权限盾牌 + 三档 popup | ◐ | `index.html` 158-167；Android 权限三档在“选择模型”弹层里，语义有、入口不同 |
| B9 | 模型胶囊 + 模型/思考强度 popup | ✅ | `ModelPickerSheet.kt` |
| B10 | 发送/停止同键 | ✅ | `Composer.kt` 四态主按钮 |
| B11 | 输入卡折叠/居中（空态与滚动收起） | ✅ | `Composer.kt` 已有折叠逻辑 |

## C. 消息流 / 面板

| # | UI | 状态 | Web 证据 |
|---|---|---|---|
| C1 | 用户消息行内编辑按钮 | ◐ | `ui.js` 1101 `renderUserText`；Android 长按菜单可编辑，非行内 |
| C2 | 断点“继续这一轮”悬浮环（resume-ring，箭头不是刷新） | ❌ | `ui.js` `showResumeRing`（1779）；Android 只在检查点弹层里有文字入口 |
| C3 | 上下文占用明细弹层（点击进度条：窗口/已用/预算/占比/消息数/压缩/缓存） | ❌ | `index.html` 71-79；`ui.js` `renderCtxPop`（2286） |
| C4 | 任务清单折叠/展开头（todo-head） | ◐ | `index.html` 104-111；`ui.js` `toggleTodoBar`（950）；Android `TodoPanel` 无折叠头 |
| C5 | Diff 面板（标题/路径/+/−/与当前文件对比/超长截断） | ◐ | `ui.js` `showDiffPanel`（1539）；Android 有工具卡内联 diff，缺独立全屏面板 |
| C6 | 工具卡点击打开/回放 diff | ◐ | `ui.js` `openToolDiff`（1596）；Android 有回放，但不打开独立 diff 面板 |
| C7 | 审批卡片完整字段（tool/action/reason/detail/diff/subagent_id/mode） | ✅ | `ApprovalCardView.kt` |
| C8 | 目标模式状态条（目标/轮次/判定/findings） | ✅ | 本轮新增 `GoalStrip` |
| C9 | 子智能体卡（running/done/failed/detail/summary） | ✅ | `SubagentBar` |
| C10 | 历史回放（tool_use/tool_result）、通知行、正在思考 | ✅ | `MessageList/MessageBubble` |
| C11 | 回到底部浮动箭头 | ✅ | `MessageList` |
| C12 | 顶栏横幅（info/warn/error/sticky/关闭） | ◐ | Android `Banner` 只覆盖错误/重连，缺 web 的等级与 sticky/关闭组合 |
| C13 | 连续只读工具“探索分组”折叠 | ❌ | `ui.js` 中有探索分组逻辑（按只读工具连续折叠）；Android 每个工具一张卡 |

## D. 设置页

### D1. 常规 / 模型 / 外观

| # | UI | 状态 | Web 证据 |
|---|---|---|---|
| D1-1 | 项目/工作区路径 + 当前模型展示 | ◐ | `index.html` 241-256；Android 内核设置只读展示 workDir/模型 |
| D1-2 | 系统通知总开关 | ✅ | `KernelSettingsSection` |
| D1-3 | 工具调用轮数 max_steps | ✅ | `KernelSettingsSection` |
| D1-4 | 请求重试（次数/模式/间隔） | ✅ | `KernelSettingsSection` |
| D1-5 | 刷新页面（恢复 UI 异常） | — | native 无页面刷新语义；可用重启内核替代 |
| D2-1 | 模型列表按供应商分组 + 每行“设为当前” | ❌ | `index.html` 598-601；`ui.js` `renderModelItems`（6718） |
| D2-2 | 供应商管理双栏：左列表 / 右详情（连接信息 + 其下模型） | ◐ | `index.html` 603-614；`ui.js` `renderProviderDetail`（6911）；Android 只有列表+编辑表单 |
| D2-3 | 添加/编辑模型完整表单：归属供应商、继承地址/密钥、显示名、地址、协议、模型 id、密钥 env/plain+眼睛、上下文输入/输出预置档、图片/视频三态、RPM | ❌ | `index.html` 616-725；`ui.js` `buildModelEditor`（6611）、`buildCtxPicker`（6433） |
| D2-4 | 测试连接 / 重置 / 保存 | ◐ | `index.html` 718-723；Android 有 `KernelApi.testModel` 但设置表单没有测试按钮/结果显示 |
| D2-5 | 获取模型列表：筛选、已添加标记、批量添加、错误结果 | ❌ | `index.html` 702-716；`ui.js` `renderDiscList`（7595）；Android 只在供应商行有“探测”，无筛选/批量/已添加标记 |
| D3-1 | 对话框宽度滑条 | ❌ | `index.html` 500-510 |
| D3-2 | 对话框高度滑条 | ❌ | `index.html` 511-520 |
| D3-3 | 最高思考强度特效 Meteor/Forge 切换 | ❌ | `index.html` 521-530；`ui.js` `buildSlider`（440） |
| D3-4 | 液态玻璃外观开关 | ❌ | `index.html` 531-540；`ui.js` `applyLiquidGlass`（5565） |
| D3-5 | 输入框外观 黑/白/透明 | ❌ | `index.html` 541-551；`ui.js` `applyComposerBox`（5589） |
| D3-6 | 自定义背景图（选择/清除） | ❌ | `index.html` 552-561；`ui.js` `applyBgImage`（5712） |
| D3-7 | 背景模糊值 | ❌ | `index.html` 562-571 |
| D3-8 | 背景亮度 | ❌ | `index.html` 572-581 |

### D2（续）. 记忆 / 技能 / 插件 / 子智能体 / MCP / 归档

| # | UI | 状态 | Web 证据 |
|---|---|---|---|
| D4-1 | 记忆页：列表 + 新增 + 删除 | ✅ | 本轮已加；Web 也无就地编辑 |
| D4-2 | 记忆就地编辑（PATCH） | ❌ | 后端 `PATCH /api/memory` 已有；Web 当前只新增/删除，Android 也未做编辑 |
| D5-1 | 技能页（display_name/description/触发说明） | ◐ | `index.html` 332-343；Android 只有内核设置里的只读列表 |
| D6-1 | 内置插件页（名称/purpose/when_to_use/开关） | ◐ | `index.html` 346-357；Android 有开关，缺说明字段 |
| D7-1 | 子智能体页（总开关/并发滑条/单任务步数/允许写删记忆/结果提示） | ✅ | `KernelSettingsSection` 已有策略控件 |
| D8-1 | MCP 服务页（已注册/添加 tabs、mcp vs mcp-http、命令/参数/env/描述/端点） | ◐ | `index.html` 436-483；Android 有列表+添加，但字段/类型说明不全 |
| D9-1 | 归档页（恢复/永久删除/10 天说明） | ◐ | `index.html` 485-496；Android 仅侧栏归档组 |

## E. 文件 / 工作区

| # | UI | 状态 | Web 证据 |
|---|---|---|---|
| E1 | 工作区文件树浏览（tree，懒加载/深度/权限错误提示） | ❌ | `index.html` 147-149；`ui.js` `openBuiltinPicker`（4162） |
| E2 | 新建项目时选择文件夹 | ❌ | `ui.js` 2832-2847；Android SAF 返回 content URI，需先决定“应用私有空间/映射目录”策略 |
| E3 | 外观背景选择图片 | ❌ | `index.html` 557-560；`ui.js` `/api/appearance/pick` |
| E4 | 附件“最近文件”横滑 | ✅ | `Composer.kt` 已有 |

## F. 明确不适用（平台差异，不算缺失）

| 项 | 原因 |
|---|---|
| 侧栏拖拽调宽 | 桌面鼠标交互，手机无对应语义 |
| `/api/pick_folder` / `/api/pick_file` | Android 用 SAF，服务端无法弹系统选择器 |
| Termux 存储授权提示 / `/api/termux/tools` | 仅 Termux 环境 |
| “刷新页面” | native 无页面刷新；可用重启内核/重建界面替代 |
| 桌面三栏/平板第三面板 | 移动端布局差异 |

## 结论（数量）

- **完全缺失**：约 27 项（A1-A4、B2-B7、C2-C3、C13、D2-1、D2-3、D2-5、D3-1~D3-8、E1-E3、D4-2 等）。
- **半实现**：约 10 项（A6、B8、C1、C4-C6、C12、D1-1、D2-2、D2-4、D5-1、D6-1、D8-1、D9-1）。
- 其中你点名的 **模型上下文、模型能力、新建项目、@技能/插件、＋Skills和技能** 全部属于完全缺失。
