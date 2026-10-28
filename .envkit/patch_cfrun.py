# -*- coding: utf-8 -*-
# 修软件包安装全挂：cfrun 缺 PROOT_TMP_DIR/PROOT_LOADER、/system/lib64 死绑定、
# dl-cdn 官方源国内不可达、isPackageInstalled 的 ${pkg.probe} 字面量 bug。
import io

p = 'app/src/main/java/app/codeforge/core/EnvKit.kt'
s = io.open(p, encoding='utf-8').read()
n = 0

# ---- 1) cfrunScript 重写 ----------------------------------------------------
old_cfrun = '''    private fun cfrunScript(cell: File): String = """
        #!/system/bin/sh
        # 由 EnvKit 生成 —— 路径是 bake 死的绝对值，勿手工迁移目录
        CELL=${cell.absolutePath}
        ROOT=${'$'}CELL/rootfs
        USR=${'$'}CELL/usr
        CMD="${'$'}*"
        export LD_LIBRARY_PATH="${'$'}USR/lib:${'$'}LD_LIBRARY_PATH"
        if [ -x "${'$'}ROOT/bin/busybox" ]; then
          exec "${'$'}USR/bin/proot" \\
            -r "${'$'}ROOT" \\
            -b "${'$'}CELL:${'$'}CELL" \\
            -b "${'$'}CELL/tmp:/tmp" \\
            -b /system/bin:/system/bin \\
            -b /system/lib64:/system/lib64 \\
            -b /proc:/proc \\
            /usr/bin/env HOME="${'$'}CELL" PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" TMPDIR=/tmp TERM=xterm \\
            /bin/sh -c "${'$'}CMD"
        else
          exec /system/bin/sh -c "${'$'}CMD"
        fi
    """.trimIndent() + "\\n"'''

new_cfrun = '''    private fun cfrunScript(cell: File): String = """
        #!/system/bin/sh
        # 由 EnvKit 生成 —— 路径是 bake 死的绝对值，勿手工迁移目录。
        # proot 离开 Termux 原生路径后必须显式给齐环境（坑记 AGENTS P-013）：
        #   PROOT_TMP_DIR  glue rootfs 的临时目录 —— Android 没有可写的 /tmp，
        #                   缺了必报 can't create glue rootfs，guest exec 连锁失败
        #   PROOT_LOADER   Termux 构建把 loader 路径 bake 死在 com.termux 数据目录，
        #                   换目录后必须重指，否则 guest 动态二进制起不来
        #   LD_LIBRARY_PATH proot 自身的 libtalloc / libandroid-shmem
        CELL=${cell.absolutePath}
        ROOT=${'$'}CELL/rootfs
        USR=${'$'}CELL/usr
        CMD="${'$'}*"
        export PROOT_TMP_DIR="${'$'}CELL/tmp"
        export PROOT_LOADER="${'$'}USR/libexec/proot/loader"
        export LD_LIBRARY_PATH="${'$'}USR/lib${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}"
        mkdir -p "${'$'}PROOT_TMP_DIR"
        if [ -x "${'$'}ROOT/bin/busybox" ]; then
          BINDS=""
          # 条件绑定：宿主上不存在的路径硬绑会产生 warning 并拖垮 glue（部分 ROM 无 /system/lib64）
          for D in /system/bin /system/lib64 /system/lib /system/etc; do
            [ -e "${'$'}D" ] && BINDS="${'$'}BINDS -b ${'$'}D:${'$'}D"
          done
          # 环境在宿主侧 export，proot 原样带给 guest —— 不经 /usr/bin/env 中转，少一个 exec 失败点
          export HOME="${'$'}CELL"
          export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
          export TMPDIR=/tmp
          export TERM=xterm
          exec "${'$'}USR/bin/proot" \\
            -r "${'$'}ROOT" \\
            -b "${'$'}CELL:${'$'}CELL" \\
            -b "${'$'}CELL/tmp:/tmp" \\
            -b /proc:/proc \\
            -b /dev:/dev \\
            ${'$'}BINDS \\
            /bin/sh -c "${'$'}CMD"
        else
          exec /system/bin/sh -c "${'$'}CMD"
        fi
    """.trimIndent() + "\\n"'''

assert old_cfrun in s, 'cfrun 原文未命中'
s = s.replace(old_cfrun, new_cfrun, 1); n += 1

# ---- 2) ensureResolv → ensureNet（强制重写 + 换中科大镜像） --------------------
old_resolv = '''    private fun ensureResolv(context: Context) {
        runCatching {
            val f = File(rootfsDir(context), "etc/resolv.conf")
            // 已有且非空就不动（用户可能自定义了）
            if (f.exists() && f.length() > 0) return
            f.parentFile?.mkdirs()
            f.writeText("nameserver 223.5.5.5\\nnameserver 8.8.8.8\\n")
        }
    }'''

new_net = '''    /**
     * 网络前提每次强制重写，不走「已有就不动」：
     * - resolv.conf：Android 没有 /etc/resolv.conf 可复制，musl 的解析器只认它；
     * - repositories：官方 dl-cdn 国内常超时，替换为中科大镜像（保留原版本号）。
     */
    private fun ensureNet(context: Context) {
        runCatching {
            val etc = File(rootfsDir(context), "etc")
            File(etc, "resolv.conf").writeText("nameserver 223.5.5.5\\nnameserver 8.8.8.8\\n")
            val repo = File(etc, "apk/repositories")
            val mirrored = repo.takeIf { it.exists() }?.readText()
                ?.replace("dl-cdn.alpinelinux.org", "mirrors.ustc.edu.cn")
            repo.parentFile?.mkdirs()
            repo.writeText(mirrored ?: "https://mirrors.ustc.edu.cn/alpine/v3.24/main\\nhttps://mirrors.ustc.edu.cn/alpine/v3.24/community\\n")
        }
    }'''

assert old_resolv in s, 'ensureResolv 原文未命中'
s = s.replace(old_resolv, new_net, 1); n += 1

# ---- 3) 调用点改名 ------------------------------------------------------------
old_call = '        ensureResolv(app)'
assert old_call in s, 'ensureResolv 调用未命中'
s = s.replace(old_call, '        ensureNet(app)', 1); n += 1

# ---- 4) isPackageInstalled 字面量 ${pkg.probe} bug -----------------------------
old_probe = '"${\'$\'}{pkg.probe} >/dev/null 2>&1"'
assert old_probe in s, 'probe 原文未命中'
s = s.replace(old_probe, 'pkg.probe + " >/dev/null 2>&1"', 1); n += 1

# ---- 5) apk add 全部加 --no-cache（无缓存索引时必拉新，docker 同款惯用法） ------
cnt = s.count('"apk add ')
s = s.replace('"apk add ', '"apk add --no-cache ')
n += 1
print('apk add 替换处数:', cnt)

io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('完成，共 %d 组修改' % n)
