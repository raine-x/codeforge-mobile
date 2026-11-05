# CodeForge release 混淆规则（2026-10-25 启用 R8）
# 原则：默认全混淆，只保留「按名字反射/JNI 查找」的类；缺省规则随 AAR
# consumer rules 自动并入（Compose/OkHttp/Coroutines 均自带）。

# --- OpenRender：JNI 符号按 Java_包名_类名_方法名 绑定，类/方法改名即 UnsatisfiedLinkError ---
-keep class com.hxy.codeforge.render.OpenRenderNative { *; }

# --- 内置离线语音（Vosk）已移除：模型资产、libvosk.so、vosk-android 依赖一并去掉。
# 对应的 JNA/Vosk keep 规则随之删除（R8 会对不存在的类告警）。

# --- OkHttp / OkIO：大量按名反射与可选依赖探测 ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepattributes Signature, InnerClasses, EnclosingMethod
# WS 事件回调走接口反射的兜底（正常由 consumer rules 覆盖，双保险）
-keep,allowobfuscation,allowshrinking interface okhttp3.WebSocketListener

# --- org.json 是 Android 系统库，不需要处理它的警告 ---
-dontwarn org.json.**

# --- coroutines debug probe 仅调试用 ---
-dontwarn kotlinx.coroutines.debug.**

# --- Tink（security-crypto 传递依赖）引用的 errorprone 注解是 compileOnly，运行时不存在 ---
-dontwarn com.google.errorprone.annotations.**

# --- 保留行号便于真机日志定位（配合 mapping.txt 反混淆） ---
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
