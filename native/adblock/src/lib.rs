//! JNI bridge for the browser's ad-blocking engine.
//!
//! The engine is process-wide: the app has one browser setting, so every
//! WebView checks against the same compiled rule set. Kotlin owns the filter
//! list files and the serialized engine cache; this crate owns parsing and
//! matching through `adblock-rust`.
//!
//! The engine is built without the crate's `single-thread` feature because
//! `WebViewClient.shouldInterceptRequest` runs on Chromium threads. An
//! `RwLock` guards the current engine, so request checks match concurrently
//! while parsing and deserialization happen before the write lock is taken;
//! old requests keep matching the previous engine during an update.

use adblock::lists::{ParseOptions, RuleTypes};
use adblock::request::Request;
use adblock::{Engine, FilterSet};
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jbyteArray, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::sync::RwLock;

static ENGINE: RwLock<Option<Engine>> = RwLock::new(None);

/// Parse a full filter list and install it as the current engine.
///
/// Returns the serialized cache, or null when parsing panics or the rule text
/// cannot be read. The cache is only a load-time optimization: a null result
/// leaves the previous engine in place.
#[no_mangle]
pub extern "system" fn Java_io_furryr_file_adblock_AdBlockNative_nativeBuildEngine<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    rules: JString<'local>,
) -> jbyteArray {
    let Ok(text) = env.get_string(&rules) else {
        return std::ptr::null_mut();
    };
    let rules = String::from(text);

    // Parsing a multi-megabyte list can panic on a malformed pattern. Never
    // let that cross the JNI boundary.
    let built = std::panic::catch_unwind(|| {
        let mut set = FilterSet::new(false);
        // The WebView blocks network requests only; cosmetic rules would be
        // parsed, stored and never used.
        set.add_filter_list(
            rules,
            ParseOptions {
                rule_types: RuleTypes::NetworkOnly,
                ..ParseOptions::default()
            },
        );
        Engine::new_with_filter_set(set)
    });
    let Ok(engine) = built else {
        return std::ptr::null_mut();
    };

    let serialized = engine.serialize();
    let Ok(mut current) = ENGINE.write() else {
        return std::ptr::null_mut();
    };
    *current = Some(engine);
    drop(current);

    env.byte_array_from_slice(&serialized)
        .map(|array| array.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Replace the current engine with a cache written by
/// [`nativeBuildEngine`]. Returns false when the cache is corrupt.
#[no_mangle]
pub extern "system" fn Java_io_furryr_file_adblock_AdBlockNative_nativeLoadEngine<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    serialized: jni::objects::JByteArray<'local>,
) -> jboolean {
    let Ok(bytes) = env.convert_byte_array(&serialized) else {
        return JNI_FALSE;
    };
    let loaded = std::panic::catch_unwind(|| {
        let mut engine = Engine::default();
        engine.deserialize(&bytes).ok().map(|()| engine)
    });
    let Ok(engine) = loaded else {
        return JNI_FALSE;
    };
    let Some(engine) = engine else {
        return JNI_FALSE;
    };
    let Ok(mut current) = ENGINE.write() else {
        return JNI_FALSE;
    };
    *current = Some(engine);
    JNI_TRUE
}

/// Drop the current engine and release its memory.
#[no_mangle]
pub extern "system" fn Java_io_furryr_file_adblock_AdBlockNative_nativeClear(
    _env: JNIEnv,
    _class: JClass,
) {
    if let Ok(mut current) = ENGINE.write() {
        *current = None;
    }
}

/// True when an engine is installed and ready to match requests.
#[no_mangle]
pub extern "system" fn Java_io_furryr_file_adblock_AdBlockNative_nativeIsLoaded(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    match ENGINE.read() {
        Ok(current) if current.is_some() => JNI_TRUE,
        _ => JNI_FALSE,
    }
}

/// Check one network request; returns 1 when it must be blocked.
///
/// A malformed URL or a missing engine is an allow, never an error: the
/// WebView must always get an answer.
#[no_mangle]
pub extern "system" fn Java_io_furryr_file_adblock_AdBlockNative_nativeCheck<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    url: JString<'local>,
    source_url: JString<'local>,
    request_type: JString<'local>,
    method: JString<'local>,
) -> jboolean {
    let (Ok(url), Ok(source), Ok(kind), Ok(method)) = (
        env.get_string(&url),
        env.get_string(&source_url),
        env.get_string(&request_type),
        env.get_string(&method),
    ) else {
        return JNI_FALSE;
    };
    let url = String::from(url);
    let source = String::from(source);
    let kind = String::from(kind);
    let method = String::from(method);

    let Ok(current) = ENGINE.read() else {
        return JNI_FALSE;
    };
    let Some(engine) = current.as_ref() else {
        return JNI_FALSE;
    };
    let blocked = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let request = Request::new(&url, &source, &kind, &method).ok()?;
        Some(engine.check_network_request(&request).should_block())
    }));
    match blocked {
        Ok(Some(true)) => JNI_TRUE,
        _ => JNI_FALSE,
    }
}
