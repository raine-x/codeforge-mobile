package app.codeforge.core

import android.content.Context
import android.os.Build
import java.io.File

/**
 * 接入内核前必须确认的环境项。
 *
 * 这些值不是"参考信息"，而是决定内核能不能启动的硬条件：
 *  - nativeLibraryDir 是可 exec 的唯一位置（app 私有目录在 targetSdk 29+ 被 SELinux 拒绝 exec）
 *  - /system/bin/sh 是 run_command 依赖的 shell（Android 没有 /bin/sh）
 */
object EnvProbe {

    data class Item(
        val label: String,
        val value: String,
        val hint: String = "",
    )

    fun collect(ctx: Context): List<Item> {
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        val kernel = File(nativeDir, "libcodeforge.so")
        val sh = File("/system/bin/sh")

        return listOf(
            Item(
                label = "支持的 ABI",
                value = Build.SUPPORTED_ABIS.joinToString(", "),
                hint = "Go 内核只交叉编译 arm64-v8a；x86 模拟器需另编。",
            ),
            Item(
                label = "nativeLibraryDir",
                value = nativeDir,
                hint = "内核二进制只能从这里 exec。",
            ),
            Item(
                label = "libcodeforge.so",
                value = if (kernel.exists()) "存在，${kernel.length()} 字节" else "未放入",
                hint = "把 Go 产物拷到 app/src/main/jniLibs/arm64-v8a/libcodeforge.so",
            ),
            Item(
                label = "/system/bin/sh",
                value = if (sh.exists()) "可用" else "缺失",
                hint = "Android 没有 /bin/sh，platform.defaultShell() 必须回落到这里。",
            ),
            Item(
                label = "运行时 SDK",
                value = "API ${Build.VERSION.SDK_INT}",
                hint = "minSdk 26 / targetSdk 36，见 build.gradle.kts。",
            ),
            Item(
                label = "extractNativeLibs",
                value = "true（useLegacyPackaging）",
                hint = "若为 false，.so 留在 APK 内不落盘，没有可 exec 的路径。",
            ),
        )
    }
}
