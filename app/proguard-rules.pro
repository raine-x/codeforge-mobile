# Release 混淆规则：保留 JNI 与反射绑定入口

# OpenRender JNI 符号按包名/类名/方法名固定绑定
-keep class com.hxy.codeforge.render.OpenRenderNative { *; }

# OkHttp / OkIO 反射与可选依赖探测
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepattributes Signature, InnerClasses, EnclosingMethod
-keep,allowobfuscation,allowshrinking interface okhttp3.WebSocketListener

-dontwarn org.json.**
-dontwarn kotlinx.coroutines.debug.**
-dontwarn com.google.errorprone.annotations.**

# 保留行号与源文件信息配合 mapping.txt 定位堆栈
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
