#!/usr/bin/env bash
#
# Linux 版：交叉编译 OpenRender → libopenrender.so（arm64）
# 与 tools/openrender/build-android.ps1（Windows 版）等价，步骤照抄它。
#
#   1. app_jni.rs 拷进 crate 的 src/（CodeForge 专属 JNI 面）
#   2. CJK 字体子集拷进同目录（include_bytes! 要求同目录）
#   3. lib.rs 里挂 mod app_jni;
#   4. cargo build --release --target aarch64-linux-android --features jni,ratex
#   5. 产物拷进 app/src/main/jniLibs/arm64-v8a/
#
set -euo pipefail

REPO=/workspace/code-forge-m
WORK=/opt/work/raine-x-OpenRender-6f11851
NDK=/opt/android-sdk/ndk/30.0.16248370
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"

export CARGO_HOME=/opt/cargo
export CARGO_TARGET_DIR=/opt/cargo-target
export RUSTUP_HOME=/opt/rustup
export PATH="/opt/cargo/bin:$PATH"
export ANDROID_NDK_HOME="$NDK"

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$BIN/aarch64-linux-android26-clang"
export CC_aarch64_linux_android="$BIN/aarch64-linux-android26-clang"
export AR_aarch64_linux_android="$BIN/llvm-ar"
export RANLIB_aarch64_linux_android="$BIN/llvm-ranlib"

# 3GB 内存：必须单线程编译，opt-level 降一档省内存
export CARGO_BUILD_JOBS=1
export CARGO_PROFILE_RELEASE_LTO=false
export CARGO_PROFILE_RELEASE_CODEGEN_UNITS=16

[ -d "$WORK" ] || { echo "✗ 找不到 OpenRender 源码：$WORK"; exit 1; }
[ -d "$BIN" ] || { echo "✗ 找不到 NDK：$BIN"; exit 1; }

SRC="$WORK/crates/openrender-core/src"

echo "==> 拷 JNI 桥与字体"
cp "$REPO/tools/openrender/app_jni.rs" "$SRC/app_jni.rs"
cp "$REPO/tools/openrender/fonts/NotoSansSC-Subset.ttf" "$SRC/NotoSansSC-Subset.ttf"

echo "==> 挂 mod app_jni;"
python3 - "$SRC/lib.rs" <<'PY'
import sys, pathlib, re
p = pathlib.Path(sys.argv[1]); s = p.read_text(encoding='utf-8')
if 'mod app_jni;' not in s:
    s = re.sub(r'(?m)^(\s*mod jni_bridge;)$',
               r'\1\n\n// CodeForge host JNI bridge (com.hxy.codeforge.render).\n#[cfg(feature = "jni")]\nmod app_jni;',
               s, count=1)
    p.write_text(s, encoding='utf-8')
    print('  已插入 mod app_jni;')
else:
    print('  已存在，跳过')
PY

echo "==> cargo build"
cd "$WORK"
cargo build --release --target aarch64-linux-android -p openrender-core --features jni,ratex 2>&1 | tail -15

SO="${CARGO_TARGET_DIR:-$WORK/target}/aarch64-linux-android/release/libopenrender.so"
[ -f "$SO" ] || { echo "✗ 没产出 .so"; exit 1; }

OUT="$REPO/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$OUT"
cp "$SO" "$OUT/libopenrender.so"
echo "==> $(stat -c%s "$OUT/libopenrender.so") 字节  sha256 $(sha256sum "$OUT/libopenrender.so" | cut -d' ' -f1)"
echo "==> 导出符号："
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -W --dyn-syms "$OUT/libopenrender.so" 2>/dev/null \
  | grep -oE "Java_[a-z_]*OpenRenderNative_[a-zA-Z]*" | sort -u
