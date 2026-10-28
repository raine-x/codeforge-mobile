package app.codeforge.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import app.codeforge.core.CoreService
import app.codeforge.core.EnvKit
import app.codeforge.core.KernelConfig
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.codeforge.ui.common.Glyph
import app.codeforge.ui.common.GlyphIcon
import app.codeforge.ui.common.noRippleClickable
import app.codeforge.ui.theme.Clay
import app.codeforge.voice.OfflineVoice
import kotlinx.coroutines.launch

/**
 * 设置弹层（抽屉底栏入口）。
 *
 * 目前两组设置：
 *  - 横屏旋转：开关（关 = 完全锁定竖屏）+ 灵敏度三档（迟钝/标准/灵敏，
 *    控制 [app.codeforge.ui.common.rememberDevicePose] 的切换迟滞角）；
 *  - 语音输入：系统服务状态展示 + 离线引擎按需下载/删除
 *    （给没有系统识别服务的 ROM 用的兜底，模型约 41 MB，只存应用私有目录）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(vm: app.codeforge.ui.ChatViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current

    LaunchedEffect(Unit) { OfflineVoice.refresh(context) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        // null = 主列表；其余为子页 key
        var page by rememberSaveable { mutableStateOf<String?>(null) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            // 二级页面：主列表 ↔ 子页。标题栏随页面切换，返回键回主列表。
            val pageTitle = when (page) {
                "appearance" -> "外观与交互"
                "model" -> "模型服务"
                "env" -> "运行环境"
                "kernel" -> "内核设置"
                "voice" -> "语音输入"
                "diag" -> "诊断"
                else -> null
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (pageTitle != null) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .noRippleClickable { page = null },
                        contentAlignment = Alignment.Center,
                    ) {
                        GlyphIcon(
                            glyph = Glyph.Back,
                            size = 18.dp,
                            color = MaterialTheme.colorScheme.onSurface,
                            strokeWidth = 2.6.dp,
                        )
                    }
                }
                Text(
                    text = pageTitle ?: "设置",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = if (pageTitle != null) 4.dp else 0.dp),
                )
            }

            // 主列表 ↔ 子页切换。
            //
            // ⚠️ 这里**不能**用 AnimatedContent：它在 verticalScroll 的 Column 里
            // 拿不到有界高度约束，新旧两页会永久叠在一起（真机实测内容全糊成一团）。
            // 改成直接 when 切换 + animateContentSize 做高度过渡 —— 页面高度不同时
            // 弹层会平滑长高/缩回，切换本身是即时的。
            androidx.compose.foundation.layout.Column(
                modifier = Modifier.animateContentSize(
                    animationSpec = androidx.compose.animation.core.tween(220),
                ),
            ) {
            run {
                when (page) {
                    null -> SettingsHome(onOpen = { page = it })
                    "appearance" -> {

            // ------------------------------------------------ 横屏旋转
            Text(
                text = "横屏旋转",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
            )

            val rotateEnabled = AppSettings.rotateWithDevice.value
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { AppSettings.setRotateWithDevice(!rotateEnabled) }
                    .padding(vertical = 12.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "横持时旋转界面",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "关闭后完全锁定竖屏，横过来画面不转",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Switch(
                    checked = rotateEnabled,
                    onCheckedChange = { AppSettings.setRotateWithDevice(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Clay),
                )
            }

            Text(
                text = "灵敏度",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RotationSensitivity.entries.forEach { level ->
                    val selected = AppSettings.rotationSensitivity.value == level
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                if (selected) Clay else MaterialTheme.colorScheme.surfaceVariant,
                            )
                            .noRippleClickable { AppSettings.setRotationSensitivity(level) }
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = level.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )

                }
                "model" -> {
            var modelSub by remember { mutableStateOf<String?>(null) }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )

            ProviderSection(vm, modelSub) { modelSub = it }

                }
                "env" -> {
            // ------------------------------------------------ 兼容环境
            Text(
                text = "兼容环境",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            LaunchedEffect(Unit) { EnvKit.refresh(context) }
            val envStatus = EnvKit.status.value
            val envText = when (envStatus) {
                is EnvKit.Status.Preparing -> "准备中 ${envStatus.percent}%"
                EnvKit.Status.Ready -> "已就绪"
                is EnvKit.Status.Failed -> envStatus.message
                EnvKit.Status.Idle -> "未就绪"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = envText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (envStatus is EnvKit.Status.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f),
                )
                if (envStatus !is EnvKit.Status.Preparing) {
                    Text(
                        text = if (envStatus is EnvKit.Status.Ready) "重新准备" else "立即准备",
                        style = MaterialTheme.typography.labelMedium,
                        color = Clay,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .noRippleClickable {
                                // 重装走 IO 线程（reset 主线程删 3800+ 文件会 ANR）
                                EnvKit.reprepare(context)
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )

            // 环境软件包（apk 安装语言运行时）
            EnvPackagesSection()

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )

                }
                "kernel" -> {
            // ------------------------------------------------ 内核设置
            KernelSettingsSection(vm)

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )

                }
                "voice" -> {
            // ------------------------------------------------ 语音输入
            Text(
                text = "语音输入",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )

            val systemAvailable = app.codeforge.ui.common.zhSpeechAvailable(context)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(
                            if (systemAvailable) Clay else MaterialTheme.colorScheme.secondary,
                            CircleShape,
                        ),
                )
                Text(
                    text = if (systemAvailable) "系统语音服务：可用" else "系统语音服务：不可用",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            Text(
                text = "没有系统服务的设备会自动改用离线引擎。模型已打进安装包，首次启动时在「环境准备中」阶段解压到应用数据目录，此后完全离线、无需联网。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp),
            )

            val status = OfflineVoice.status.value
            val actionLabel = when (status) {
                OfflineVoice.Status.Idle -> "重新准备离线引擎"
                is OfflineVoice.Status.Preparing -> "环境准备中 ${status.percent}%"
                OfflineVoice.Status.Ready -> "已就绪 · 重新准备"
                is OfflineVoice.Status.Failed -> "重试（${status.message}）"
            }
            val busy = status is OfflineVoice.Status.Preparing
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        when {
                            status is OfflineVoice.Status.Ready ->
                                MaterialTheme.colorScheme.surfaceVariant
                            busy -> MaterialTheme.colorScheme.surfaceVariant
                            else -> Clay
                        },
                    )
                    .noRippleClickable(enabled = !busy) {
                        if (status is OfflineVoice.Status.Ready) OfflineVoice.reset(context)
                        OfflineVoice.ensureReady(context)
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (busy) {
                    app.codeforge.ui.chat.ArcSpinner(
                        color = MaterialTheme.colorScheme.onSurface,
                        size = 14.dp,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                } else {
                    GlyphIcon(
                        glyph = if (status is OfflineVoice.Status.Ready) {
                            Glyph.Retry
                        } else {
                            Glyph.ArrowDown
                        },
                        size = 13.dp,
                        color = if (status is OfflineVoice.Status.Ready) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                // 百分比变化只淡切文字，避免每 1% 都弹跳
                AnimatedContent(
                    targetState = actionLabel,
                    transitionSpec = {
                        fadeIn(tween(140)) togetherWith fadeOut(tween(100))
                    },
                    label = "voice-prep-label",
                ) { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (status is OfflineVoice.Status.Ready || busy) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        },
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
        }
                "diag" -> {
            // ------------------------------------------------ 诊断
            Text(
                text = "诊断",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            DiagnosticsRow()

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 16.dp),
            )

                }
                }
            }
            }
        }
    }
}

// ---------------------------------------------------------------- 诊断

/**
 * 最小诊断区：崩溃现场（crash-*.txt）、环境流水线日志（env.log）与语音诊断（voice.log）。
 * 真机排障的第一手资料就在这几个文件里 —— 崩溃落盘是 2026-10-25 加的，
 * 这里给一个不依赖 adb 的查看/复制出口。voice.log 是「语音不出字」的
 * 断点定位数据（加载/采集/能量/识别全节点），没有这个出口它等于没埋。
 */
@Composable
private fun DiagnosticsRow() {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }
    var exportResult by remember { mutableStateOf<String?>(null) }
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val scope = rememberCoroutineScope()

    fun latestLogs(): String {
        val dir = java.io.File(context.filesDir, "logs")
        val crashes = dir.listFiles { f -> f.name.startsWith("crash-") }
            ?.sortedByDescending { it.name }?.take(2)
            ?.map { "===== ${it.name} =====\n" + it.readText().take(2000) }
            ?.joinToString("\n\n").orEmpty()
        val env = java.io.File(dir, "env.log").takeIf { it.exists() }?.readText()?.takeLast(1500).orEmpty()
        val voice = java.io.File(dir, "voice.log").takeIf { it.exists() }?.readText()?.takeLast(1500).orEmpty()
        return buildString {
            if (crashes.isNotBlank()) append("【崩溃记录】\n$crashes\n\n")
            if (env.isNotBlank()) append("【环境日志】\n$env\n\n")
            if (voice.isNotBlank()) append("【语音日志】\n$voice")
            if (isEmpty()) append("暂无日志（无崩溃记录、环境未跑过流水线、语音未诊断过）")
        }
    }

    /**
     * 全量日志导出到系统下载文件夹：崩溃文件不截断，env/kernel 各留尾 100KB。
     * 29+ 走 MediaStore.Downloads（免权限）；26-28 直写公共目录（需存储权限，
     * 未授权给指引不弹窗 —— 老系统占比极低，不值得为此加一套权限流程）。
     * 读文件在 IO 线程（kernel.log 可能上 MB，主线程读必卡）。
     */
    fun exportLogs(): String {
        val dir = java.io.File(context.filesDir, "logs")
        val sb = StringBuilder()
        dir.listFiles { f -> f.name.startsWith("crash-") }
            ?.sortedByDescending { it.name }
            ?.forEach { sb.append("===== ${it.name} =====\n").append(it.readText()).append("\n\n") }
        java.io.File(dir, "env.log").takeIf { it.exists() }?.let {
            sb.append("===== env.log（尾 100KB）=====\n").append(it.readText().takeLast(100 * 1024)).append("\n\n")
        }
        java.io.File(dir, "kernel.log").takeIf { it.exists() }?.let {
            sb.append("===== kernel.log（尾 100KB）=====\n").append(it.readText().takeLast(100 * 1024)).append("\n\n")
        }
        java.io.File(dir, "voice.log").takeIf { it.exists() }?.let {
            sb.append("===== voice.log（尾 100KB）=====\n").append(it.readText().takeLast(100 * 1024)).append("\n\n")
        }
        if (sb.isEmpty()) return "没有可导出的日志"
        val name = "CodeForge-logs-" +
            java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) +
            ".txt"
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                val cv = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                }
                val uri = context.contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    cv,
                ) ?: return "写入下载文件夹失败"
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(sb.toString().toByteArray())
                } ?: return "写入下载文件夹失败"
                "已导出：Download/$name"
            } else {
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    return "此系统版本导出需要存储权限，请在系统设置中授予后重试"
                }
                val d = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS,
                )
                d.mkdirs()
                java.io.File(d, name).writeText(sb.toString())
                "已导出：Download/$name"
            }
        } catch (e: Exception) {
            "导出失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * 把日志写成文件、挂到系统分享面板。
     *
     * 与「导出」的区别：导出是落到 Download 让用户自己找，发送是直接拉系统分享
     * （微信 / 邮件 / 网盘都在里面）。日志先写 cache/logs/，经 FileProvider 授权读取，
     * 分享结束后系统回收 cache，不占用户空间。
     */
    fun shareLogs() {
        val text = StringBuilder()
        val dir = java.io.File(context.filesDir, "logs")
        dir.listFiles { f -> f.name.startsWith("crash-") }
            ?.sortedByDescending { it.name }
            ?.forEach { text.append("===== ${it.name} =====\n").append(it.readText()).append("\n\n") }
        listOf("env.log", "kernel.log", "voice.log").forEach { n ->
            java.io.File(dir, n).takeIf { it.exists() }?.let {
                text.append("===== $n（尾 100KB）=====\n")
                    .append(it.readText().takeLast(100 * 1024)).append("\n\n")
            }
        }
        if (text.isEmpty()) {
            exportResult = "没有可发送的日志"
            return
        }
        val name = "CodeForge-logs-" +
            java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date()) + ".txt"
        runCatching {
            val cacheDir = java.io.File(context.cacheDir, "logs").apply { mkdirs() }
            val f = java.io.File(cacheDir, name)
            f.writeText(text.toString())
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                f,
            )
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "CodeForge 诊断日志")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                android.content.Intent.createChooser(send, "发送日志"),
            )
        }.onFailure {
            exportResult = "发送失败：${it.message ?: it.javaClass.simpleName}"
        }
    }

    // 导出结果短暂展示后自清
    LaunchedEffect(exportResult) {
        if (exportResult != null) {
            kotlinx.coroutines.delay(3_200)
            exportResult = null
        }
    }

    Column(modifier = Modifier.padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "崩溃与环境日志",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "导出",
                style = MaterialTheme.typography.labelMedium,
                color = Clay,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .noRippleClickable {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val r = exportLogs()
                            exportResult = r
                        }
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Text(
                text = "发送",
                style = MaterialTheme.typography.labelMedium,
                color = Clay,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .noRippleClickable {
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) { shareLogs() }
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Text(
                text = if (expanded) "收起" else "查看",
                style = MaterialTheme.typography.labelMedium,
                color = Clay,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .noRippleClickable {
                        expanded = !expanded
                        if (expanded) logText = latestLogs()
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            if (expanded) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "复制",
                    style = MaterialTheme.typography.labelMedium,
                    color = Clay,
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .noRippleClickable {
                            scope.launch {
                                val data = android.content.ClipData.newPlainText("logs", logText)
                                clipboard.setClipEntry(androidx.compose.ui.platform.ClipEntry(data))
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        if (exportResult != null) {
            Text(
                text = exportResult.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = Clay,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (expanded) {
            Text(
                text = logText,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                    .padding(10.dp),
            )
        }
    }
}

/**
 * 设置主列表。
 *
 * 原来六组设置挤在一个长滚动页里，手机上要滑很久才能找到目标项，
 * 且「内核设置」那种大块会把上面的开关挤到视线外。拆成二级：
 * 这里只放入口，内容在各自子页。
 */
@Composable
private fun SettingsHome(onOpen: (String) -> Unit) {
    val items = listOf(
        Triple("appearance", "外观与交互", "横屏旋转、灵敏度"),
        Triple("model", "模型服务", "测试模型、供应商与模型库"),
        Triple("env", "运行环境", "Linux 兼容环境、语言软件包"),
        Triple("kernel", "内核设置", "生成参数、重试、权限、插件与技能"),
        Triple("voice", "语音输入", "系统语音服务、离线引擎"),
        Triple("diag", "诊断", "崩溃与环境日志、导出"),
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        items.forEach { (key, title, sub) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .noRippleClickable { onOpen(key) }
                    .padding(horizontal = 4.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    text = "›",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/**
 * 供应商管理：列表 + 新增/编辑 + 删除。
 *
 * 内核端点：`/api/providers/list` 读、`/api/providers/save` 写（按 id 判重，
 * 有则更新无则新增）、`/api/providers/delete` 删（内核会顺带解绑该供应商下的模型）。
 *
 * 密钥处理遵循内核的 key_touched 语义：编辑时不碰密钥输入框就发 key_touched=false，
 * 内核沿用库里的旧值 —— 界面拿到的是脱敏视图，空输入框绝不等于「清空密钥」。
 */
@Composable
private fun ProviderSection(
    vm: app.codeforge.ui.ChatViewModel,
    subPage: String?,
    onSubPage: (String?) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()

    var discovering by remember { mutableStateOf<String?>(null) }
    var discovered by remember { mutableStateOf<List<String>>(emptyList()) }
    var discoverProvider by remember { mutableStateOf("") }
    var pendingProviderDelete by remember { mutableStateOf<String?>(null) }
    var pendingModelDelete by remember { mutableStateOf<String?>(null) }

    var fId by remember { mutableStateOf("") }
    var fName by remember { mutableStateOf("") }
    var fUrl by remember { mutableStateOf("") }
    var fProtocol by remember { mutableStateOf("openai") }
    var fKey by remember { mutableStateOf("") }
    var fKeyTouched by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refreshProviderDetails() }

    fun resetForm() {
        fId = ""; fName = ""; fUrl = ""; fProtocol = "openai"
        fKey = ""; fKeyTouched = false
    }

    // 探测结果：选中即存，不跳页
    if (discovered.isNotEmpty()) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { discovered = emptyList() },
            title = { Text("探测到 ${discovered.size} 个模型") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    discovered.forEach { id ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = id,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "添加",
                                style = MaterialTheme.typography.labelMedium,
                                color = Clay,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .noRippleClickable {
                                        vm.saveModel(id, id, discoverProvider)
                                        discovered = discovered - id
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    discovered.forEach { id -> vm.saveModel(id, id, discoverProvider) }
                    discovered = emptyList()
                }) { Text("全部添加") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { discovered = emptyList() }) {
                    Text("关闭")
                }
            },
        )
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        when (subPage) {
            // ---------------- 主列表 ----------------
            null -> {
                Text(
                    text = "供应商与模型",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 14.dp),
                )
                SubPageEntry("供应商", "${state.providerDetails.size} 个") { onSubPage("providers") }
                SubPageEntry("模型库", "${state.libraryModels.size} 个") { onSubPage("library") }
                SubPageEntry("添加供应商", "") { resetForm(); onSubPage("add") }
            }

            // ---------------- 供应商列表 ----------------
            "providers" -> {
                BackRow { onSubPage(null) }
                Text(
                    text = "供应商",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (state.providerDetails.isEmpty()) {
                    Text(
                        text = "（暂无供应商）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
                state.providerDetails.forEach { p ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .noRippleClickable {
                                    fId = p.id
                                    fName = p.name
                                    fUrl = p.baseUrl
                                    fProtocol = p.protocol
                                    fKey = ""
                                    fKeyTouched = false
                                    onSubPage("add")
                                },
                        ) {
                            Text(
                                text = p.name.ifBlank { p.id },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = listOf(p.protocol, p.baseUrl).filter { it.isNotBlank() }
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        Text(
                            text = if (discovering == p.id) "探测中…" else "探测",
                            style = MaterialTheme.typography.labelMedium,
                            color = Clay,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .noRippleClickable {
                                    discovering = p.id
                                    vm.discoverModels(p.id) { list ->
                                        discovering = null
                                        discoverProvider = p.id
                                        discovered = list
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                        Text(
                            text = if (pendingProviderDelete == p.id) "确认删除" else "删除",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (pendingProviderDelete == p.id) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .noRippleClickable {
                                    if (pendingProviderDelete == p.id) {
                                        vm.deleteProvider(p.id)
                                        pendingProviderDelete = null
                                    } else {
                                        pendingProviderDelete = p.id
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }

            // ---------------- 模型库 ----------------
            "library" -> {
                BackRow { onSubPage(null) }
                Text(
                    text = "模型库（${state.libraryModels.size}）",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (state.libraryModels.isEmpty()) {
                    Text(
                        text = "（空。到「供应商」里点探测，把上游模型加进来）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 10.dp),
                    )
                }
                state.libraryModels.forEach { m ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = m.name.ifBlank { m.id },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = listOf(m.id, m.provider).filter { it.isNotBlank() }
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        Text(
                            text = if (pendingModelDelete == m.id) "确认删除" else "删除",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (pendingModelDelete == m.id) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .noRippleClickable {
                                    if (pendingModelDelete == m.id) {
                                        vm.deleteModel(m.id)
                                        pendingModelDelete = null
                                    } else {
                                        pendingModelDelete = m.id
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }

            // ---------------- 添加 / 编辑供应商 ----------------
            "add" -> {
                BackRow { onSubPage(null) }
                Text(
                    text = if (fId.isEmpty()) "添加供应商" else "编辑供应商",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = fName,
                    onValueChange = { fName = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = fUrl,
                    onValueChange = { fUrl = it },
                    label = { Text("Base URL，如 https://api.deepseek.com/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(
                    modifier = Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("openai", "anthropic").forEach { proto ->
                        val sel = fProtocol == proto
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (sel) Clay else MaterialTheme.colorScheme.surfaceVariant)
                                .noRippleClickable { fProtocol = proto }
                                .padding(horizontal = 16.dp, vertical = 7.dp),
                        ) {
                            Text(
                                text = proto,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (sel) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = fKey,
                    onValueChange = { fKey = it; fKeyTouched = true },
                    label = { Text(if (fId.isEmpty()) "API Key" else "API Key（留空=不改）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(Clay)
                            .noRippleClickable {
                                vm.saveProvider(
                                    id = fId.ifEmpty { providerIdOf(fUrl) },
                                    name = fName.trim().ifEmpty { hostOf(fUrl) },
                                    baseUrl = fUrl.trim(),
                                    protocol = fProtocol,
                                    keySource = "plain",
                                    keyName = "",
                                    keyValue = fKey,
                                    keyTouched = fKeyTouched,
                                )
                                resetForm()
                                onSubPage(null)
                            }
                            .padding(horizontal = 18.dp, vertical = 9.dp),
                    ) {
                        Text(
                            text = "保存",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .noRippleClickable { resetForm(); onSubPage(null) }
                            .padding(horizontal = 18.dp, vertical = 9.dp),
                    ) {
                        Text(
                            text = "取消",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 子页顶部的返回行。 */
@Composable
private fun BackRow(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .noRippleClickable(onClick = onBack)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(Glyph.Back, 16.dp, Clay, strokeWidth = 2.4.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            text = "返回",
            style = MaterialTheme.typography.bodyMedium,
            color = Clay,
        )
    }
}

/** 从 URL 里取 host 作显示名。 */
private fun hostOf(url: String): String =
    url.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').ifEmpty { "新供应商" }

/** 按内核既有命名习惯生成 id：p-api-deepseek-com。 */
private fun providerIdOf(url: String): String {
    val host = hostOf(url).lowercase().replace('.', '-').replace(Regex("[^a-z0-9-]"), "")
    return if (host.isEmpty()) "p-custom" else "p-$host"
}

/** 主列表里的子页入口：标题 + 右侧计数 + 箭头。 */
@Composable
private fun SubPageEntry(title: String, count: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .noRippleClickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (count.isNotEmpty()) {
            Text(
                text = count,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "  ›",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
