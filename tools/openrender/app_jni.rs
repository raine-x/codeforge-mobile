//! JNI bridge for the CodeForge Android host.
//!
//! The upstream `jni_bridge` binds to `ai.openrender.NativeEngine`. CodeForge
//! keeps its own package (`app.codeforge.render.OpenRenderNative`), so this
//! module exports a parallel surface with the same semantics plus the optional
//! RaTeX math renderer entry point that the upstream Kotlin wrapper does not
//! expose.

use jni::objects::{JObject, JString};
use jni::sys::{jboolean, jfloat, jint, jlong, jstring};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};

use crate::config::Config;
use crate::engine::Engine;
use crate::ir::Delta;

/// 内嵌的 Unicode 回退字体（Noto Sans SC 子集，OFL-1.1）。
///
/// RaTeX 内嵌的是 KaTeX 字体，只覆盖拉丁与数学符号。公式里的中文
/// （`\text{圆的面积}`）需要 **Unicode 回退字体**，而 `ratex-unicode-font`
/// 只查三处：`RATEX_UNICODE_FONT` 环境变量、Linux/macOS/Windows 的系统字体路径、
/// fontdb —— **Android 上三处都没有**，于是含中文的公式整块渲染失败：
/// 引擎返回 `None`，IR 里 `math` 为 null，界面退回原始 `$$…$$` 源码。
///
/// 这里把字体写进应用 cacheDir 再指向它：用的是该 crate **本就支持**的环境变量，
/// 不新增依赖、不改上游 crate，也不用改引擎逻辑。字体是 GB2312 常用字子集，
/// 3.2MB。
///
/// 构建时该文件须与 `app_jni.rs` 同目录（见 `build-android.ps1` 的复制步骤）。
static EMBEDDED_UNICODE_FONT: &[u8] = include_bytes!("NotoSansSC-Subset.ttf");

/// 把内嵌字体落到 `cache_dir` 并让 `ratex-unicode-font` 找到它。
///
/// 时机很关键：该 crate 用 `OnceLock` 缓存字体查找结果，**第一次查找之后
/// 再设环境变量也不生效**。所以必须早于首次公式渲染 —— 本函数在
/// `nativeUseRatex` 里调用，而渲染器正是在那之后才被喂公式。
pub fn install_unicode_font(cache_dir: &str) {
    static ONCE: std::sync::OnceLock<()> = std::sync::OnceLock::new();
    ONCE.get_or_init(|| {
        if cache_dir.is_empty() {
            return;
        }
        let path = std::path::Path::new(cache_dir).join("ratex-unicode-sc.ttf");
        if std::fs::write(&path, EMBEDDED_UNICODE_FONT).is_ok() {
            // 环境变量是该 crate 优先级最高的发现源。
            // ignore: set_var 在多线程下理论上有竞态；此处只在进程早期写一次，
            //         读取方在自己的 OnceLock 初始化里，不构成实际风险。
            unsafe { std::env::set_var("RATEX_UNICODE_FONT", &path) };
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_app_codeforge_render_OpenRenderNative_nativeNew(
    _env: JNIEnv,
    _this: JObject,
    throttle_ms: jint,
    max_tail_bytes: jint,
    max_document_bytes: jlong,
    enable_previews: jboolean,
) -> jlong {
    let mut cfg = Config::default();
    cfg.throttle_ms = throttle_ms.max(0) as u32;
    if max_tail_bytes > 0 {
        cfg.max_tail_bytes = max_tail_bytes as usize;
    }
    if max_document_bytes > 0 {
        cfg.max_document_bytes = max_document_bytes as usize;
    }
    cfg.enable_previews = enable_previews != 0;
    Box::into_raw(Box::new(Engine::new(cfg))) as jlong
}

#[no_mangle]
pub extern "system" fn Java_app_codeforge_render_OpenRenderNative_nativeFree(
    _env: JNIEnv,
    _this: JObject,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    unsafe {
        drop(Box::from_raw(handle as *mut Engine));
    }
}

#[no_mangle]
pub extern "system" fn Java_app_codeforge_render_OpenRenderNative_nativeFeed(
    mut env: JNIEnv,
    _this: JObject,
    handle: jlong,
    text: JString,
) -> jstring {
    let Some(engine) = (unsafe { engine_ref(handle) }) else {
        return std::ptr::null_mut();
    };
    let Ok(input) = env.get_string(&text) else {
        return std::ptr::null_mut();
    };
    let input: String = input.into();
    match catch_unwind(AssertUnwindSafe(|| engine.feed(&input).to_json())) {
        Ok(json) => into_jstring(&mut env, json),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_app_codeforge_render_OpenRenderNative_nativeFlush(
    mut env: JNIEnv,
    _this: JObject,
    handle: jlong,
) -> jstring {
    let Some(engine) = (unsafe { engine_ref(handle) }) else {
        return std::ptr::null_mut();
    };
    match catch_unwind(AssertUnwindSafe(|| engine.flush().to_json())) {
        Ok(json) => into_jstring(&mut env, json),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_app_codeforge_render_OpenRenderNative_nativeFinish(
    mut env: JNIEnv,
    _this: JObject,
    handle: jlong,
) -> jstring {
    let Some(engine) = (unsafe { engine_ref(handle) }) else {
        return std::ptr::null_mut();
    };
    match catch_unwind(AssertUnwindSafe(|| engine.finish().to_json())) {
        Ok(json) => into_jstring(&mut env, json),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_app_codeforge_render_OpenRenderNative_nativeReset(
    _env: JNIEnv,
    _this: JObject,
    handle: jlong,
) {
    if let Some(engine) = unsafe { engine_ref(handle) } {
        engine.reset();
    }
}

#[no_mangle]
pub extern "system" fn Java_app_codeforge_render_OpenRenderNative_nativeConfigure(
    _env: JNIEnv,
    _this: JObject,
    handle: jlong,
    parser_flags: jint,
    soft_break_as_space: jboolean,
    drop_html: jboolean,
    size_hints: jboolean,
    max_blocks: jlong,
) -> jboolean {
    let Some(engine) = (unsafe { engine_ref(handle) }) else {
        return 0;
    };
    let cfg = engine.config_mut();
    cfg.parser_flags = parser_flags.max(0) as u32;
    cfg.soft_break_as_space = soft_break_as_space != 0;
    cfg.drop_html = drop_html != 0;
    cfg.enable_size_hints = size_hints != 0;
    cfg.max_blocks = max_blocks.max(0) as u64;
    1
}

/// Install the built-in RaTeX renderer (PNG output with embedded fonts).
#[no_mangle]
pub extern "system" fn Java_app_codeforge_render_OpenRenderNative_nativeUseRatex(
    mut env: JNIEnv,
    _this: JObject,
    handle: jlong,
    font_size: jfloat,
    device_pixel_ratio: jfloat,
    cache_dir: JString,
) -> jboolean {
    let Some(engine) = (unsafe { engine_ref(handle) }) else {
        return 0;
    };

    #[cfg(feature = "ratex")]
    {
        // 必须在创建渲染器之前装好回退字体：字体查找结果被 OnceLock 缓存。
        let dir: String = env
            .get_string(&cache_dir)
            .map(|s| s.into())
            .unwrap_or_default();
        install_unicode_font(&dir);

        let mut renderer = openrender_latex::RatexRenderer::default();
        if font_size.is_finite() && font_size > 0.0 {
            renderer.font_size = font_size;
        }
        if device_pixel_ratio.is_finite() && device_pixel_ratio > 0.0 {
            renderer.device_pixel_ratio = device_pixel_ratio;
        }
        engine.set_math_renderer(Some(std::sync::Arc::new(renderer)));
        return 1;
    }

    #[cfg(not(feature = "ratex"))]
    {
        let _ = (font_size, device_pixel_ratio, cache_dir);
        0
    }
}

#[no_mangle]
pub extern "system" fn Java_app_codeforge_render_OpenRenderNative_nativeVersion(
    mut env: JNIEnv,
    _this: JObject,
) -> jstring {
    into_jstring(&mut env, "0.1.0".to_string())
}

#[no_mangle]
pub extern "system" fn Java_app_codeforge_render_OpenRenderNative_nativeEmptyDelta(
    mut env: JNIEnv,
    _this: JObject,
) -> jstring {
    into_jstring(&mut env, Delta::default().to_json())
}

fn into_jstring(env: &mut JNIEnv, value: String) -> jstring {
    match env.new_string(value) {
        Ok(value) => value.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

unsafe fn engine_ref<'a>(handle: jlong) -> Option<&'a mut Engine> {
    if handle == 0 {
        return None;
    }
    unsafe { (handle as *mut Engine).as_mut() }
}
