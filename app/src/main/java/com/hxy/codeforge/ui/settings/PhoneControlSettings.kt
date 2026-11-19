package com.hxy.codeforge.ui.settings

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hxy.codeforge.core.EnvKit
import com.hxy.codeforge.model.PermissionMode
import com.hxy.codeforge.phone.PhoneAppSkillStore
import com.hxy.codeforge.phone.PhoneArtifact
import com.hxy.codeforge.phone.PhoneArtifactStore
import com.hxy.codeforge.phone.PhoneArtifactType
import com.hxy.codeforge.phone.PhoneCapability
import com.hxy.codeforge.phone.PhoneClickDelayPreset
import com.hxy.codeforge.phone.PhoneConfirmRule
import com.hxy.codeforge.phone.PhoneControlPrefs
import com.hxy.codeforge.phone.PhoneMcpStatus
import com.hxy.codeforge.phone.PhoneMediaController
import com.hxy.codeforge.phone.PhoneObservationService
import com.hxy.codeforge.phone.PhonePermissionSnapshot
import com.hxy.codeforge.phone.PhoneRuntime
import com.hxy.codeforge.phone.PhoneScope
import com.hxy.codeforge.phone.PhoneSystemCapabilities
import com.hxy.codeforge.phone.PhoneTaskController
import com.hxy.codeforge.phone.PhoneTaskStatus
import com.hxy.codeforge.ui.ChatViewModel
import com.hxy.codeforge.ui.chat.toolLabel
import com.hxy.codeforge.ui.common.noRippleClickable
import com.hxy.codeforge.ui.setPermissionMode
import com.hxy.codeforge.ui.setPhoneControlMasterEnabled
import com.hxy.codeforge.ui.theme.Clay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置 → 手机操控完整控制面：
 * 1. 总开关与权限（`@phone` MCP 接入状态、无障碍/屏幕捕获/麦克风及系统高级权限）
 * 2. 执行状态（当前任务、步骤进度、暂停/继续与立即停止）
 * 3. 能力开关（12 项手机操控能力细粒度开关）
 * 4. 应用黑名单（搜索与勾选禁止操控应用）
 * 5. 确认设置（高风险动作确认选项）
 * 6. 操作动画（屏幕位置/轨迹动画与系统设置执行回执）
 * 7. 记录与产物（任务步骤记录、截图/录屏/录音查看、分享、导出与清除）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhoneControlSettings(vm: ChatViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiState by vm.state.collectAsStateWithLifecycle()
    val config by PhoneControlPrefs.config.collectAsStateWithLifecycle()
    val taskState by PhoneTaskController.state.collectAsStateWithLifecycle()
    val artifacts by PhoneArtifactStore.artifacts.collectAsStateWithLifecycle()
    val appSkills by PhoneAppSkillStore.skills.collectAsStateWithLifecycle()
    val mcpStatus by PhoneRuntime.mcpStatus.collectAsStateWithLifecycle()

    var perms by remember { mutableStateOf(PhoneSystemCapabilities.permissionSnapshot(context)) }
    var statusFeedback by remember { mutableStateOf("") }
    var showMorePerms by rememberSaveable { mutableStateOf(false) }
    var showBlacklistPicker by remember { mutableStateOf(false) }
    var showSkillLibrary by remember { mutableStateOf(false) }
    var previewArtifact by remember { mutableStateOf<PhoneArtifact?>(null) }
    var recordTab by rememberSaveable { mutableIntStateOf(0) } // 0 = 执行记录, 1 = 产物文件

    val importSkillLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        PhoneAppSkillStore.importSkillUri(context, uri)
            .onSuccess { skill ->
                statusFeedback = "已导入应用技能「${skill.name}」(${skill.id})"
            }
            .onFailure { err ->
                statusFeedback = "导入技能失败：${err.message}"
            }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                perms = PhoneSystemCapabilities.permissionSnapshot(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        perms = PhoneSystemCapabilities.permissionSnapshot(context)
        statusFeedback = if (granted) "麦克风权限已授予" else "麦克风权限未授予"
    }

    val extraPermsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        perms = PhoneSystemCapabilities.permissionSnapshot(context)
    }

    // ---------------- 1. 总开关与权限 ----------------
    PhoneSectionTitle("总开关与权限")
    Text(
        text = "开启后，聊天中发送 @phone 即可由 Agent 自主组合调用手机操控与搜索、文件等现有工具。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )

    CardSurface {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "启用手机操控插件 (@phone)",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val statusDesc = when (val st = mcpStatus) {
                    is PhoneMcpStatus.Running -> "MCP 已接通（${st.toolCount} 个工具 · 127.0.0.1:${st.port}）"
                    is PhoneMcpStatus.Error -> "连接提示：${st.message}"
                    PhoneMcpStatus.Stopped -> if (config.masterEnabled) "正在接入内核…" else "未启用"
                }
                Text(
                    text = statusDesc,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (mcpStatus is PhoneMcpStatus.Running) Clay else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Switch(
                checked = config.masterEnabled,
                onCheckedChange = { vm.setPhoneControlMasterEnabled(it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Clay),
            )
        }

        if (config.masterEnabled && uiState.permissionMode == PermissionMode.ReadOnly) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "当前会话处于「只读模式」，外部 MCP 工具将被内核拦截。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
                PillButton("切至请求模式", highlighted = true) {
                    vm.setPermissionMode(PermissionMode.Ask)
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    PermissionRow(
        title = "无障碍服务（读屏与界面操控）",
        granted = perms.accessibilityEnabled,
        grantedText = "已连接",
        missingText = "未开启",
        actionText = if (perms.accessibilityEnabled) "设置" else "去开启",
    ) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { statusFeedback = "无法打开系统无障碍设置" }
    }

    PermissionRow(
        title = "屏幕捕获授权（录屏 / 低版本截图）",
        granted = perms.screenCaptureAuthorized,
        grantedText = "已授权",
        missingText = "按需授权",
        actionText = if (perms.screenCaptureAuthorized) "已就绪" else "立即授权",
    ) {
        scope.launch {
            val ok = PhoneMediaController.requestScreenCaptureAuthorization(context)
            perms = PhoneSystemCapabilities.permissionSnapshot(context)
            statusFeedback = if (ok) "屏幕捕获已授权" else "屏幕捕获授权已取消"
        }
    }

    PermissionRow(
        title = "麦克风权限（录音）",
        granted = perms.microphoneGranted,
        grantedText = "已授予",
        missingText = "未授予",
        actionText = if (perms.microphoneGranted) "已就绪" else "立即授权",
    ) {
        micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .noRippleClickable { showMorePerms = !showMorePerms }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (showMorePerms) "收起更多系统权限 ▲" else "展开更多系统权限（通知/系统设置/勿扰/日历/通讯） ▼",
            style = MaterialTheme.typography.labelMedium,
            color = Clay,
            modifier = Modifier.weight(1f),
        )
    }

    if (showMorePerms) {
        ExtraPermissionsPanel(
            perms = perms,
            onOpenNotifications = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.onFailure { statusFeedback = "无法打开通知使用权设置" }
            },
            onOpenWriteSettings = {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            Uri.parse("package:${context.packageName}"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.onFailure { statusFeedback = "无法打开修改系统设置页" }
            },
            onOpenDnd = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.onFailure { statusFeedback = "无法打开勿扰权限页" }
            },
            onRequestRuntimeBatch = {
                extraPermsLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_CALENDAR,
                        Manifest.permission.WRITE_CALENDAR,
                        Manifest.permission.READ_CONTACTS,
                        Manifest.permission.SEND_SMS,
                    ),
                )
            },
        )
    }

    SectionDivider()

    // ---------------- 2. 执行状态 ----------------
    PhoneSectionTitle("执行状态")
    CardSurface {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "状态：${taskState.status.label} · 已执行 ${taskState.stepCount} 步",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (taskState.status == PhoneTaskStatus.RUNNING) Clay else MaterialTheme.colorScheme.onSurface,
                )
                if (taskState.currentTask.isNotBlank()) {
                    Text(
                        text = "当前任务：${taskState.currentTask}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (taskState.currentAction.isNotBlank()) {
                    Text(
                        text = "当前步骤：${taskState.currentAction}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Clay,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (taskState.status == PhoneTaskStatus.PAUSED) {
                PillButton("继续任务", highlighted = true, modifier = Modifier.weight(1f)) {
                    PhoneTaskController.resume()
                    statusFeedback = "已继续任务"
                }
            } else {
                PillButton(
                    label = "暂停",
                    highlighted = false,
                    enabled = taskState.status == PhoneTaskStatus.RUNNING,
                    modifier = Modifier.weight(1f),
                ) {
                    PhoneTaskController.pause("已在设置页暂停任务")
                    statusFeedback = "任务已暂停"
                }
            }
            PillButton(
                label = "立即停止",
                highlighted = taskState.status == PhoneTaskStatus.RUNNING || taskState.status == PhoneTaskStatus.PAUSED,
                danger = true,
                enabled = taskState.status == PhoneTaskStatus.RUNNING || taskState.status == PhoneTaskStatus.PAUSED,
                modifier = Modifier.weight(1f),
            ) {
                PhoneTaskController.stop("已在设置页立即停止")
                statusFeedback = "已发送立即停止指令"
            }
        }
    }

    SectionDivider()

    // ---------------- 3. 能力开关 ----------------
    PhoneSectionTitle("能力开关")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "按模块精确控制模型可调用的手机能力。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        PillButton("全开", highlighted = false) {
            PhoneControlPrefs.setAllCapabilities(
                context,
                PhoneCapability.entries.associateWith { true },
            )
            statusFeedback = "已启用全部手机能力"
        }
        PillButton("仅观察", highlighted = false) {
            PhoneControlPrefs.setAllCapabilities(
                context,
                PhoneCapability.entries.associateWith {
                    it == PhoneCapability.SCREEN_OBSERVE || it == PhoneCapability.DEVICE_STATUS
                },
            )
            statusFeedback = "已切换为仅观察模式"
        }
    }

    PhoneCapability.entries.forEach { cap ->
        val checked = config.isCapabilityEnabled(cap)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .noRippleClickable { PhoneControlPrefs.setCapability(context, cap, !checked) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = cap.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = cap.summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = { PhoneControlPrefs.setCapability(context, cap, it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Clay),
            )
        }
    }

    SectionDivider()

    // ---------------- 4. 应用黑名单 ----------------
    PhoneSectionTitle("应用黑名单")
    Text(
        text = "每次屏幕观察与界面操作前都会检查当前应用；一旦进入黑名单应用，立即停止执行。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )

    CardSurface {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "已禁止 ${config.excludedPackages.size} 个应用",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (config.excludedPackages.isEmpty()) "尚未添加禁止操控的应用" else "点击标签可快速移出黑名单",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PillButton("选择应用", highlighted = true) {
                showBlacklistPicker = true
            }
        }
        if (config.excludedPackages.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                config.excludedPackages.sorted().forEach { pkg ->
                    val label = remember(pkg) { PhoneScope.appLabel(context, pkg) }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .noRippleClickable {
                                PhoneControlPrefs.toggleExcludedPackage(context, pkg, false)
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = "$label ×",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }

    SectionDivider()

    // ---------------- 5. 确认设置 ----------------
    PhoneSectionTitle("确认设置")
    Text(
        text = "勾选后，模型执行对应高风险操作前会弹窗并推送通知请求你的明确确认。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
    PhoneConfirmRule.entries.forEach { rule ->
        val checked = config.isConfirmationRequired(rule)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .noRippleClickable { PhoneControlPrefs.setConfirmRule(context, rule, !checked) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rule.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = rule.summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = { PhoneControlPrefs.setConfirmRule(context, rule, it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Clay),
            )
        }
    }

    SectionDivider()

    // ---------------- 5.5 应用技能库 ----------------
    PhoneSectionTitle("应用技能库（按 App 预置经验）")
    CardSurface {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "打开应用时自动匹配技能",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "仅向模型暴露命中应用的名称与简介，按需再读取完整步骤，减少探索消耗。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = config.appSkillsEnabled,
                onCheckedChange = { PhoneControlPrefs.setAppSkillsEnabled(context, it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Clay),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "允许模型自动沉淀新技能",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "探索出新 App 的稳定路径后，允许调用 app_skill(save) 写入自定义技能库。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = config.allowSkillSelfLearning,
                onCheckedChange = { PhoneControlPrefs.setAllowSkillSelfLearning(context, it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Clay),
            )
        }
        Spacer(Modifier.height(10.dp))
        val enabledSkillCount = appSkills.count { it.enabled }
        Text(
            text = "已装载 ${appSkills.size} 条技能（启用 $enabledSkillCount 条），支持 .md 与 .yaml。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PillButton(
                label = "管理技能库 (${appSkills.size})",
                highlighted = true,
                onClick = { showSkillLibrary = true },
            )
            PillButton(
                label = "导入 .md / .yaml",
                highlighted = false,
                onClick = { importSkillLauncher.launch(arrayOf("*/*")) },
            )
        }
    }

    SectionDivider()

    // ---------------- 6. 操作动画 ----------------
    PhoneSectionTitle("操作动画与执行回执")
    CardSurface {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "显示屏幕点击过程、四周跑马灯与顶部思考提示",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "操控期间展示边界跑马灯、思考过程与开始时的通知栏停止提醒，并回显系统设置结果。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = config.showActionAnimation,
                onCheckedChange = { PhoneControlPrefs.setShowActionAnimation(context, it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Clay),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PillButton("预览动画效果", highlighted = false) {
                val obs = PhoneObservationService.active
                if (obs == null) {
                    statusFeedback = "请先开启「CodeForge 手机操控」无障碍服务以展示屏幕动画"
                } else {
                    obs.showPreviewDemo()
                    statusFeedback = "已在屏幕展示跑马灯、顶部思考提示与点击过程预览"
                }
            }
            if (taskState.lastResult.isNotBlank()) {
                Text(
                    text = "最近回执：${taskState.lastResult}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (taskState.lastResultError) MaterialTheme.colorScheme.error else Clay,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "随机点击延时与拟人按压（防点击失效）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "延长按压时长至 85–175ms 并加入步间随机延时与安全边界微偏移，当无障碍点击无反应时自动补发真实触摸手势。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = config.randomClickDelayEnabled,
                onCheckedChange = { PhoneControlPrefs.setRandomClickDelayEnabled(context, it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Clay),
            )
        }
        if (config.randomClickDelayEnabled) {
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PhoneClickDelayPreset.entries.forEach { preset ->
                    PillButton(
                        label = preset.label,
                        highlighted = config.clickDelayPreset == preset,
                        onClick = { PhoneControlPrefs.setClickDelayPreset(context, preset) },
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "任务完成后自动返回 CodeForge",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "操控完成或输出对比结果时，若当前停留在外部 App，自动切回 CodeForge 前台展示结果。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = config.autoReturnToAppOnFinish,
                onCheckedChange = { PhoneControlPrefs.setAutoReturnToAppOnFinish(context, it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Clay),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "回合结束自动停止操控",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "对话回合完成时自动停止任务态并收起屏幕跑马灯、思考提示与常驻通知。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = config.autoStopOnCompletion,
                onCheckedChange = { PhoneControlPrefs.setAutoStopOnCompletion(context, it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Clay),
            )
        }
    }

    SectionDivider()

    // ---------------- 7. 记录与产物 ----------------
    PhoneSectionTitle("记录与产物")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillButton("执行记录 (${taskState.steps.size})", highlighted = recordTab == 0) {
            recordTab = 0
        }
        PillButton("产物文件 (${artifacts.size})", highlighted = recordTab == 1) {
            recordTab = 1
        }
        Spacer(Modifier.weight(1f))
        PillButton(
            label = if (recordTab == 0) "清空记录" else "清除产物",
            highlighted = false,
            danger = true,
        ) {
            if (recordTab == 0) {
                PhoneTaskController.clearHistory()
                statusFeedback = "已清空执行记录"
            } else {
                PhoneArtifactStore.clearAll()
                statusFeedback = "已清除全部截图、录屏与录音产物"
            }
        }
    }

    val timeFmt = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }

    if (recordTab == 0) {
        if (taskState.steps.isEmpty()) {
            Text(
                text = "暂无手机操控执行记录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                taskState.steps.take(30).forEach { step ->
                    val label = remember(step.tool, step.summary) {
                        val mapped = toolLabel("phone.${step.tool}", "{}")
                        if (mapped == "调用了外部能力") step.summary else "$mapped · ${step.summary}"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = step.detail.ifBlank { if (step.success) "完成" else "失败" },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (step.success) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val meta = buildString {
                                append(timeFmt.format(Date(step.timestampMs)))
                                if (step.targetApp.isNotBlank()) append(" · ${step.targetApp}")
                                if (step.durationMs > 0) append(" · ${step.durationMs}ms")
                            }
                            Text(
                                text = meta,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = if (step.success) "成功" else "失败",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (step.success) Clay else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    } else {
        if (artifacts.isEmpty()) {
            Text(
                text = "暂无截图、录屏或录音产物。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                artifacts.take(40).forEach { item ->
                    ArtifactRow(
                        artifact = item,
                        timeText = timeFmt.format(Date(item.createdAtMs)),
                        onPreview = { previewArtifact = item },
                        onDelete = {
                            PhoneArtifactStore.delete(item.id)
                            statusFeedback = "已删除 ${item.fileName}"
                        },
                    )
                }
            }
        }
    }

    if (statusFeedback.isNotBlank()) {
        Text(
            text = statusFeedback,
            style = MaterialTheme.typography.labelSmall,
            color = Clay,
            modifier = Modifier.padding(top = 12.dp),
        )
    }

    if (showBlacklistPicker) {
        BlacklistPickerDialog(
            context = context,
            excludedPackages = config.excludedPackages,
            onTogglePackage = { pkg, blocked ->
                PhoneControlPrefs.toggleExcludedPackage(context, pkg, blocked)
            },
            onDismiss = { showBlacklistPicker = false },
        )
    }

    if (showSkillLibrary) {
        AppSkillLibraryDialog(
            skills = appSkills,
            onToggleEnabled = { id, enabled ->
                PhoneControlPrefs.setAppSkillEnabled(context, id, enabled)
                PhoneAppSkillStore.reload()
            },
            onSaveSkill = { id, name, desc, packages, appNames, tags, priority, content ->
                PhoneAppSkillStore.saveCustomSkill(
                    id = id,
                    name = name,
                    description = desc,
                    packages = packages,
                    appNames = appNames,
                    tags = tags,
                    priority = priority,
                    content = content,
                ).onSuccess { saved ->
                    statusFeedback = "已保存应用技能「${saved.name}」"
                }.onFailure { err ->
                    statusFeedback = "保存技能失败：${err.message}"
                }
            },
            onDeleteCustomSkill = { id ->
                if (PhoneAppSkillStore.deleteCustomSkill(id)) {
                    statusFeedback = "已删除自定义技能：$id"
                }
            },
            onImportClick = { importSkillLauncher.launch(arrayOf("*/*")) },
            onDismiss = { showSkillLibrary = false },
        )
    }

    previewArtifact?.let { item ->
        ArtifactPreviewDialog(
            artifact = item,
            onOpen = {
                PhoneArtifactStore.openInSystem(context, item).onFailure {
                    statusFeedback = "无法打开文件：${it.message}"
                }
            },
            onShare = {
                PhoneArtifactStore.share(context, item).onFailure {
                    statusFeedback = "无法分享文件：${it.message}"
                }
            },
            onExport = {
                val exported = PhoneArtifactStore.exportToDownloads(context, item)
                statusFeedback = if (exported != null) {
                    "已导出到 Download/CodeForge/${exported.name}"
                } else {
                    "导出失败，请检查存储目录"
                }
            },
            onDelete = {
                PhoneArtifactStore.delete(item.id)
                previewArtifact = null
                statusFeedback = "已删除 ${item.fileName}"
            },
            onDismiss = { previewArtifact = null },
        )
    }
}

@Composable
private fun PhoneSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(vertical = 14.dp),
    )
}

@Composable
private fun CardSurface(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        content()
    }
}

@Composable
private fun PillButton(
    label: String,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        danger && highlighted -> MaterialTheme.colorScheme.error
        highlighted -> Clay
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        danger && highlighted -> MaterialTheme.colorScheme.onError
        highlighted -> MaterialTheme.colorScheme.onPrimary
        danger -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .noRippleClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            maxLines = 1,
        )
    }
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    grantedText: String,
    missingText: String,
    actionText: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (granted) grantedText else missingText,
                style = MaterialTheme.typography.labelSmall,
                color = if (granted) Clay else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        PillButton(label = actionText, highlighted = !granted, onClick = onAction)
    }
}

@Composable
private fun ExtraPermissionsPanel(
    perms: PhonePermissionSnapshot,
    onOpenNotifications: () -> Unit,
    onOpenWriteSettings: () -> Unit,
    onOpenDnd: () -> Unit,
    onRequestRuntimeBatch: () -> Unit,
) {
    CardSurface {
        PermissionRow(
            title = "通知读取与按钮操作权限",
            granted = perms.notificationListenerEnabled,
            grantedText = "已授予",
            missingText = "未授予",
            actionText = if (perms.notificationListenerEnabled) "设置" else "去开启",
            onAction = onOpenNotifications,
        )
        PermissionRow(
            title = "修改系统设置（亮度 / 自动旋转）",
            granted = perms.writeSettingsGranted,
            grantedText = "已授予",
            missingText = "未授予",
            actionText = if (perms.writeSettingsGranted) "设置" else "去开启",
            onAction = onOpenWriteSettings,
        )
        PermissionRow(
            title = "勿扰模式控制权限",
            granted = perms.dndPolicyAccessGranted,
            grantedText = "已授予",
            missingText = "未授予",
            actionText = if (perms.dndPolicyAccessGranted) "设置" else "去开启",
            onAction = onOpenDnd,
        )
        val allRuntimeOk = perms.calendarGranted && perms.contactsGranted && perms.smsGranted
        PermissionRow(
            title = "日历 / 联系人 / 短信权限（Android ${Build.VERSION.RELEASE}）",
            granted = allRuntimeOk,
            grantedText = "已授予",
            missingText = "部分或全部未授予",
            actionText = if (allRuntimeOk) "已就绪" else "一键申请",
            onAction = onRequestRuntimeBatch,
        )
    }
}

@Composable
private fun ArtifactRow(
    artifact: PhoneArtifact,
    timeText: String,
    onPreview: () -> Unit,
    onDelete: () -> Unit,
) {
    val thumb = remember(artifact.absolutePath) {
        if (artifact.type == PhoneArtifactType.SCREENSHOT && File(artifact.absolutePath).isFile) {
            runCatching {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeFile(artifact.absolutePath, opts)
            }.getOrNull()
        } else {
            null
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .noRippleClickable(onClick = onPreview)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (thumb != null) {
            Image(
                bitmap = thumb.asImageBitmap(),
                contentDescription = artifact.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Clay.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = artifact.type.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Clay,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = artifact.fileName,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(artifact.type.label)
                    append(" · ")
                    append(EnvKit.formatFileSize(artifact.sizeBytes))
                    if (artifact.width > 0 && artifact.height > 0) {
                        append(" · ${artifact.width}×${artifact.height}")
                    }
                    if (artifact.durationMs > 0) {
                        append(" · ${String.format(Locale.US, "%.1f", artifact.durationMs / 1000.0)}s")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = timeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "删除",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .noRippleClickable(onClick = onDelete)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}
