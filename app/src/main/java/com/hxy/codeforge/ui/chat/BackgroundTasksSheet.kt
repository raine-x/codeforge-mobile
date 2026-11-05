package com.hxy.codeforge.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hxy.codeforge.model.BackgroundTask
import com.hxy.codeforge.ui.common.Glyph
import com.hxy.codeforge.ui.common.GlyphIcon
import com.hxy.codeforge.ui.common.noRippleClickable
import com.hxy.codeforge.ui.theme.Clay
import kotlinx.coroutines.delay

/**
 * 后台脚本任务面板（右上角三点菜单 → 后台任务）。
 *
 * 数据分两条链路，不要混：
 *  - 起停由 WS `task` 帧提示，壳侧收到后重拉 `GET /api/tasks`；
 *  - 面板打开期间按 1s 轮询同一条 REST —— 日志增长不推帧（128KiB 逐帧推送
 *    会把连接挤爆），只有轮询才能看到最新输出。
 *
 * 任务归内核进程：关掉面板、切会话、退到后台都不影响脚本；只有「取消」
 * 才会杀整个进程组（含 npm run dev 派生的 node 之类）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundTasksSheet(
    tasks: List<BackgroundTask>,
    onRefresh: () -> Unit,
    onStart: (command: String, label: String) -> Unit,
    onKill: (id: String) -> Unit,
    onDismiss: () -> Unit,
    /** 顶层横幅文案：弹层是 Dialog 层级，ToastHost 被盖住，错误得在这里露出来。 */
    banner: String? = null,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // 面板打开即轮询：起停事件之外的「输出增长」没有任何推送，
    // 不轮询的话用户只能看到启动那一刻的日志。
    LaunchedEffect(Unit) {
        while (true) {
            onRefresh()
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val running = tasks.count { it.status == "running" }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (running > 0) "后台任务 · $running 运行中" else "后台任务",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .noRippleClickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    GlyphIcon(
                        glyph = Glyph.Close,
                        size = 14.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            NewTaskRow(onStart = onStart)

            if (!banner.isNullOrBlank()) {
                Text(
                    text = banner,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            Text(
                text = "AI 也可以用 run_background 工具启动脚本；这里只负责查看输出与取消。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (tasks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "还没有后台任务。\n可以让 AI 启动开发服务器 / 监听脚本，或在上面手动启动。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(tasks, key = { it.id }) { task ->
                        TaskCard(task = task, now = now, onKill = { onKill(task.id) })
                    }
                }
            }
        }
    }
}

/** 新建任务：命令 + 可选名称，启动后清空命令（同名任务靠 id 区分）。 */
@Composable
private fun NewTaskRow(onStart: (String, String) -> Unit) {
    var command by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    val canStart = command.isNotBlank()

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            label = { Text("脚本 / 命令") },
            placeholder = { Text("例如：python3 -m http.server 8000") },
            minLines = 1,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("任务名（可选）") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (canStart) Clay else MaterialTheme.colorScheme.surfaceVariant)
                    .noRippleClickable(enabled = canStart) {
                        if (canStart) {
                            onStart(command, label)
                            command = ""
                            label = ""
                        }
                    }
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                Text(
                    text = "启动",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (canStart) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/** 单个任务卡：状态、命令、最近输出、取消按钮。 */
@Composable
private fun TaskCard(task: BackgroundTask, now: Long, onKill: () -> Unit) {
    val isRunning = task.status == "running"
    val statusColor = when {
        isRunning -> Clay
        task.status == "killed" -> MaterialTheme.colorScheme.onSurfaceVariant
        task.exitCode == 0 -> Color(0xFF3E9B4F)
        else -> MaterialTheme.colorScheme.error
    }
    val statusText = when {
        isRunning -> "运行中 · ${elapsedLabel(task.startedAt, now)}"
        task.status == "killed" -> "已取消"
        task.exitCode == 0 -> "已退出 · 完成"
        else -> "已退出 · 退出码 ${task.exitCode}"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(statusColor, CircleShape),
            )
            Text(
                text = task.label.ifBlank { task.command.take(40) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
            )
        }
        Text(
            text = task.command,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // 输出只渲染尾部：内核本身只保留 128KiB，但直接把 128KiB 塞进
        // 单个 Text 会拖垮布局；这里再截到 40 行 / 4000 字符。
        val tail = remember(task.output) { outputTail(task.output) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 160.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = tail.ifBlank { "（暂无输出）" },
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 15.sp,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = task.cwd.substringAfterLast('/').ifBlank { "工作区" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isRunning) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .noRippleClickable(onClick = onKill)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = "取消任务",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            } else {
                SpacerWidth()
            }
        }
    }
}

/** 占位宽度：让「已结束」行与运行中行的右侧留白一致（取消按钮消失时不跳动）。 */
@Composable
private fun SpacerWidth() {
    Box(modifier = Modifier.width(1.dp))
}

/** 毫秒时间戳 → mm:ss / h:mm:ss（只看运行时长，不看绝对时间）。 */
private fun elapsedLabel(start: Long, now: Long): String {
    val sec = ((now - start).coerceAtLeast(0) / 1000).toInt()
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%02d:%02d".format(m, s)
    }
}

/** 取输出尾部 40 行且不超过 4000 字符：既够看最近发生的事，又不会拖垮布局。 */
private fun outputTail(output: String): String {
    if (output.isBlank()) return ""
    val lines = output.trimEnd().takeLast(4000).lines()
    return lines.takeLast(40).joinToString("\n")
}
