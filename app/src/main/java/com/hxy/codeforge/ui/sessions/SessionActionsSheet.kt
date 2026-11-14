package com.hxy.codeforge.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hxy.codeforge.model.SessionItem
import com.hxy.codeforge.ui.ChatViewModel
import com.hxy.codeforge.ui.common.Glyph
import com.hxy.codeforge.ui.common.GlyphIcon
import com.hxy.codeforge.ui.common.SyncDialogStatusBar
import com.hxy.codeforge.ui.common.noRippleClickable
import com.hxy.codeforge.ui.deleteSession
import com.hxy.codeforge.ui.renameSession
import com.hxy.codeforge.ui.setSessionArchived
import com.hxy.codeforge.ui.theme.Clay
import com.hxy.codeforge.ui.undoLastWrite

/**
 * 会话长按操作弹层：重命名 / 归档 / 删除。
 *
 * ## 为什么从「胶囊按钮堆叠」改成「行式列表」
 *
 * 旧版每个操作是一个独立胶囊，纵向排开、间距大，一屏只能放四五个动作；
 * 「归档」和「取消归档」还并列摆着（同一时刻只有一个是合理的），
 * 用户得先判断该点哪个。现在改成行式：
 *
 *  - 图标 + 文字 + 44dp 行高，扫一眼就知道有哪些动作；
 *  - 重命名默认收起，点行才展开输入框（否则输入框一直占着半屏）；
 *  - 删除单独一组、红色、两次确认 —— 与可恢复操作在视觉上分开。
 *
 * 归档与取消归档仍并列：会话列表和「已归档」组共用同一个弹层，
 * 而 [SessionItem] 不携带 archived 标记，无法在这里判断当前属于哪一组。
 * 两个动作内核都是幂等的，点错也只是原地不动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionActionsSheet(
    session: SessionItem,
    vm: ChatViewModel,
    onDismiss: () -> Unit,
) {
    var renaming by rememberSaveable(session.id) { mutableStateOf(false) }
    var rename by rememberSaveable(session.id) { mutableStateOf(session.title) }
    var confirmDelete by rememberSaveable(session.id) { mutableStateOf(false) }

    val danger = MaterialTheme.colorScheme.error

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 1f),
    ) {
        SyncDialogStatusBar()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            // 会话标题：单行省略，太长的标题不该把弹层撑开
            Text(
                text = session.title.ifBlank { "未命名会话" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (renaming) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = rename,
                        onValueChange = { rename = it },
                        label = { Text("会话标题") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SmallPill("保存", primary = true) {
                            vm.renameSession(session.id, rename.trim())
                            onDismiss()
                        }
                        SmallPill("取消", primary = false) {
                            rename = session.title
                            renaming = false
                        }
                    }
                }
            } else {
                ActionRow(Glyph.Copy, "重命名") { renaming = true }
            }

            // 撤销按会话分桶：只撤这个会话最近一次文件写入，不影响别的会话
            ActionRow(Glyph.Retry, "撤销最近一次文件写入") {
                vm.undoLastWrite(session.id)
                onDismiss()
            }

            ActionRow(Glyph.Folder, "归档（从列表隐藏，可恢复）") {
                vm.setSessionArchived(session.id, true)
                onDismiss()
            }
            ActionRow(Glyph.Folder, "取消归档") {
                vm.setSessionArchived(session.id, false)
                onDismiss()
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 6.dp),
            )

            ActionRow(
                glyph = Glyph.Close,
                label = if (confirmDelete) "再点一次确认删除（不可恢复）" else "删除会话",
                tint = danger,
                highlighted = confirmDelete,
            ) {
                if (confirmDelete) {
                    vm.deleteSession(session.id)
                    onDismiss()
                } else {
                    confirmDelete = true
                }
            }
        }
    }
}

/** 一行操作：图标 + 文字，44dp 以上行高。 */
@Composable
private fun ActionRow(
    glyph: Glyph,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (highlighted) MaterialTheme.colorScheme.errorContainer else Color.Transparent,
            )
            .noRippleClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GlyphIcon(glyph = glyph, size = 17.dp, color = tint, strokeWidth = 1.7.dp)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = tint,
        )
    }
}

/** 小胶囊按钮（重命名的保存/取消）。 */
@Composable
private fun SmallPill(label: String, primary: Boolean, onClick: () -> Unit) {
    val bg = if (primary) Clay else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .noRippleClickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

