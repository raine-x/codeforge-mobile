package com.hxy.codeforge.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hxy.codeforge.core.EnvKit
import com.hxy.codeforge.ui.chat.ArcSpinner
import com.hxy.codeforge.ui.common.Glyph
import com.hxy.codeforge.ui.common.GlyphIcon
import com.hxy.codeforge.ui.common.noRippleClickable
import com.hxy.codeforge.ui.theme.Clay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 环境软件包区：往 Alpine rootfs 里装语言运行时（apk 经 cfrun/proot 执行）。
 *
 * 这就是「工具链管理器 v1」的形态 —— 不自建下载管道，直接复用 Alpine
 * 官方仓库；装完即对内核 run_command 生效（rootfs 的 /usr/bin 已在 PATH）。
 * 按钮三态：未装（灰）/ 安装中（转圈）/ 已装（陶土描边，点击重装）。
 */
@Composable
fun EnvPackagesSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 包安装状态：null=探测中/未装，false=安装中，true=已装
    val installed = remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var log by remember { mutableStateOf("") }
    var busyPkg by remember { mutableStateOf<String?>(null) }

    // 进入时后台探测一遍已装状态（probe 是轻命令，允许渐次上色）
    fun probe(pkg: EnvKit.EnvPackage) {
        scope.launch(Dispatchers.IO) {
            // runInRootfs 的回调在 IO 线程；Compose snapshot 状态跨线程写安全
            val ok = EnvKit.isPackageInstalled(context, pkg)
            installed.value = installed.value + (pkg.label to ok)
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        EnvKit.packages.forEach { probe(it) }
    }

    Text(
        text = "环境软件包",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        text = "装进 Linux 兼容环境（Alpine 仓库），装完内核即可直接运行对应语言。需要网络。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
    )

    // 两行胶囊：4 + 3
    val rows = EnvKit.packages.chunked(4)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { rowPkgs ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowPkgs.forEach { pkg ->
                    val state = installed.value[pkg.label]
                    val installing = busyPkg == pkg.label
                    val done = state == true
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                when {
                                    installing -> MaterialTheme.colorScheme.surfaceVariant
                                    done -> Clay.copy(alpha = 0.12f)
                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                },
                            )
                            // 安装期间全禁用：apk 有 /var/lib/apk 全局锁，并发装必互相顶掉
                            .noRippleClickable(enabled = busyPkg == null) {
                                busyPkg = pkg.label
                                log = "» ${pkg.installCmd}\n"
                                scope.launch(Dispatchers.IO) {
                                    val code = EnvKit.runInRootfs(context, pkg.installCmd) { line ->
                                        log = (log + line + "\n").takeLast(1600)
                                    }
                                    busyPkg = null
                                    // ⚠️ 不能只信退出码：apk 在 proot 下装完所有包后会报
                                    // "failed to write database: Permission denied"（数据库写不进去），
                                    // 退出码非 0，但**文件已经解压到位、命令可用**
                                    // （真机实测 python3 能跑）。所以失败时再 probe 一次，
                                    // 以「命令是否真的能跑」为最终判据。
                                    val usable = code == 0 || EnvKit.isPackageInstalled(context, pkg)
                                    installed.value = installed.value + (pkg.label to usable)
                                    log = (
                                        log + when {
                                            code == 0 -> "✓ 完成"
                                            usable -> "✓ 已装（apk 数据库状态未更新，不影响使用）"
                                            else -> "✗ 失败（$code）"
                                        } + "\n"
                                        ).takeLast(1600)
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        if (installing) {
                            ArcSpinner(color = Clay, size = 12.dp)
                        } else {
                            GlyphIcon(
                                glyph = if (done) Glyph.Check else Glyph.ArrowDown,
                                size = 11.dp,
                                color = if (done) Clay else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = pkg.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (done) Clay else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                // 补齐行内空位，保持等宽
                repeat(4 - rowPkgs.size) { Row(modifier = Modifier.weight(1f)) {} }
            }
        }
    }

    if (log.isNotBlank()) {
        Text(
            text = log,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                .padding(10.dp),
        )
    }
}
