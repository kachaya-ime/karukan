mod config;
mod converter;
mod dict_engine;

use config::*;
use converter::*;
use dict_engine::*;

use jni::JNIEnv;
use jni::objects::{JClass, JObject, JString, JValue};
use jni::sys::{jboolean, jobject};
use karukan_engine::{
    BracketStyle, Dictionary, KanaKanjiConverter, LearningCache, ModelSource, PunctuationStyle,
    RomajiConverter, SlashStyle, SymbolStyle, Width, WidthRules,
};
use std::path::PathBuf;
use std::sync::atomic::Ordering;

fn report_progress(env: &mut JNIEnv, listener: &JObject, message: &str, percent: i32) {
    if listener.is_null() {
        return;
    }
    if let Ok(msg_jstring) = env.new_string(message) {
        let _ = env.call_method(
            listener,
            "onProgress",
            "(Ljava/lang/String;I)V",
            &[(&msg_jstring).into(), percent.into()],
        );
        let _ = env.delete_local_ref(JObject::from(msg_jstring));
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_kachaya_karukan_KarukanEngine_nativeInit<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    model_path: JString<'a>,
) -> jboolean {
    let path_str: String = match env.get_string(&model_path) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    if let Ok(mut m_guard) = MODEL_PATH.lock() {
        *m_guard = Some(path_str.clone());
    }
    if let Ok(mut e_guard) = ENGINE.lock() {
        *e_guard = None;
    }

    let path_buf = PathBuf::from(path_str);
    let source = ModelSource::Path(path_buf);

    match KanaKanjiConverter::from_source(&source, "jinen-v2-xsmall-q5") {
        Ok(converter) => {
            let mut guard = CONVERTER.lock().unwrap();
            *guard = Some(converter);

            let mut learn_guard = LEARNING_CACHE.lock().unwrap();
            if learn_guard.is_none() {
                *learn_guard = Some(LearningCache::new(get_current_learning_config()));
            }

            1
        }
        Err(_) => 0,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_kachaya_karukan_KarukanEngine_nativeLoadSystemDict<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    dict_path: JString<'a>,
    progress_listener: JObject<'a>,
) -> jboolean {
    let path_str: String = match env.get_string(&dict_path) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    if let Ok(mut s_guard) = SYSTEM_DICT_PATH.lock() {
        *s_guard = Some(path_str.clone());
    }
    if let Ok(mut e_guard) = ENGINE.lock() {
        *e_guard = None;
    }

    report_progress(&mut env, &progress_listener, "システム辞書をロード中...", 50);

    let path_buf = PathBuf::from(path_str);
    if !path_buf.exists() || !path_buf.is_file() {
        let mut guard = SYSTEM_DICT.lock().unwrap();
        *guard = None;
        report_progress(&mut env, &progress_listener, "システム辞書ファイルが存在しません", 100);
        return 0;
    }

    let mut guard = SYSTEM_DICT.lock().unwrap();
    match Dictionary::load_auto(&path_buf) {
        Ok(dict) => {
            *guard = Some(dict);
            report_progress(&mut env, &progress_listener, "システム辞書のロード完了", 100);
            1
        }
        Err(_) => {
            *guard = None;
            report_progress(&mut env, &progress_listener, "システム辞書のロード失敗", 100);
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_kachaya_karukan_KarukanEngine_nativeLoadUserDicts<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    dict_dir_path: JString<'a>,
    progress_listener: JObject<'a>,
) -> jboolean {
    let dir_str: String = match env.get_string(&dict_dir_path) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    if let Ok(mut u_guard) = USER_DICT_DIR.lock() {
        *u_guard = Some(dir_str.clone());
    }
    if let Ok(mut e_guard) = ENGINE.lock() {
        *e_guard = None;
    }

    report_progress(&mut env, &progress_listener, "追加辞書ファイルを確認中...", 5);

    let dir_path = PathBuf::from(dir_str);
    if !dir_path.exists() || !dir_path.is_dir() {
        let mut guard = USER_DICTS.lock().unwrap();
        guard.clear();
        report_progress(&mut env, &progress_listener, "追加辞書ディレクトリが存在しません", 100);
        return 1;
    }

    let mut dict_files = Vec::new();
    if let Ok(entries) = std::fs::read_dir(&dir_path) {
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_file() {
                dict_files.push(path);
            }
        }
    }

    dict_files.sort();

    let total_files = dict_files.len();
    let mut loaded_dicts = Vec::new();

    for (index, path) in dict_files.iter().enumerate() {
        let file_name = path.file_name().and_then(|n| n.to_str()).unwrap_or("unknown");
        let percent = 10 + (((index + 1) * 80) / total_files.max(1)) as i32;
        let msg = format!("追加辞書「{}」を読み込み中... ({}/{})", file_name, index + 1, total_files);
        report_progress(&mut env, &progress_listener, &msg, percent);

        if let Ok(dict) = Dictionary::load_auto(path) {
            loaded_dicts.push(dict);
        }
    }

    report_progress(&mut env, &progress_listener, "追加辞書データの反映中...", 95);

    let mut guard = USER_DICTS.lock().unwrap();
    *guard = loaded_dicts;

    report_progress(&mut env, &progress_listener, "追加辞書データのロード完了", 100);

    1
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_kachaya_karukan_KarukanEngine_nativeCancelQuery<'a>(
    _env: JNIEnv<'a>,
    _class: JClass<'a>,
    query_id: jni::sys::jlong,
) {
    if query_id > 0 {
        LATEST_CANCELLED_QUERY_ID.fetch_max(query_id as u64, Ordering::Relaxed);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_kachaya_karukan_KarukanEngine_nativeUpdateEngineConfig<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    n_threads: jni::sys::jint,
    strategy: JString<'a>,
    max_latency_ms: jni::sys::jint,
    chunk_chars: jni::sys::jint,
    chunk_symbols: jni::sys::jint,
    chunk_digits: jni::sys::jint,
    chunk_alphabets: jni::sys::jint,
    beam_width: jni::sys::jint,
    beam_chars: jni::sys::jint,
    punctuation: JString<'a>,
    bracket: JString<'a>,
    slash: JString<'a>,
    _space: JString<'a>,
    kana_symbol_width: JString<'a>,
    ascii_symbol_width: JString<'a>,
    digit_width: JString<'a>,
    max_learning_entries: jni::sys::jint,
    is_learning_enabled: jboolean,
    is_date_conversion_enabled: jboolean,
) {
    IS_DATE_CONVERSION_ENABLED.store(is_date_conversion_enabled != 0, Ordering::Relaxed);
    IS_LEARNING_ENABLED.store(is_learning_enabled != 0, Ordering::Relaxed);

    if n_threads > 0 {
        let mut guard = CONVERTER.lock().unwrap();
        if let Some(ref mut converter) = *guard {
            converter.set_n_threads(n_threads as u32);
        }
    }

    let strat_str: String = env
        .get_string(&strategy)
        .map(|s| s.into())
        .unwrap_or_else(|_| "adaptive".to_string());

    {
        let mut guard = ENGINE_CONFIG.lock().unwrap();
        let config = guard.get_or_insert_with(AndroidEngineConfig::default);
        config.strategy = strat_str;
        config.max_latency_ms = max_latency_ms;
        config.chunk_chars = chunk_chars;
        config.chunk_symbols = chunk_symbols;
        config.chunk_digits = chunk_digits;
        config.chunk_alphabets = chunk_alphabets;
        config.beam_width = beam_width;
        config.beam_chars = beam_chars;
    }

    let p_str: String = env.get_string(&punctuation).map(|s| s.into()).unwrap_or_default();
    let b_str: String = env.get_string(&bracket).map(|s| s.into()).unwrap_or_default();
    let sl_str: String = env.get_string(&slash).map(|s| s.into()).unwrap_or_default();
    let kw_str: String = env.get_string(&kana_symbol_width).map(|s| s.into()).unwrap_or_default();
    let aw_str: String = env.get_string(&ascii_symbol_width).map(|s| s.into()).unwrap_or_default();
    let dw_str: String = env.get_string(&digit_width).map(|s| s.into()).unwrap_or_default();

    let p_style = match p_str.as_str() {
        "comma_period" => PunctuationStyle::CommaPeriod,
        _ => PunctuationStyle::KutenTouten,
    };
    let b_style = match b_str.as_str() {
        "square" => BracketStyle::Square,
        _ => BracketStyle::Corner,
    };
    let sl_style = match sl_str.as_str() {
        "slash" => SlashStyle::Slash,
        _ => SlashStyle::MiddleDot,
    };

    let symbol_style = SymbolStyle {
        punctuation: p_style,
        bracket: b_style,
        slash: sl_style,
    };

    let parse_width = |s: &str| match s {
        "half" => Width::Half,
        _ => Width::Full,
    };

    let width_rules = WidthRules {
        kana_symbol: parse_width(&kw_str),
        ascii_symbol: parse_width(&aw_str),
        digit: parse_width(&dw_str),
    };

    {
        let mut guard = ROMAJI_CONVERTER.lock().unwrap();
        *guard = Some(RomajiConverter::with_rules(symbol_style, width_rules));
    }

    let max_entries_usize = if max_learning_entries > 0 {
        max_learning_entries as usize
    } else {
        karukan_engine::LearningConfig::DEFAULT_MAX_ENTRIES
    };
    let previous_max_entries = CURRENT_MAX_LEARNING_ENTRIES.swap(max_entries_usize, Ordering::Relaxed);

    {
        let mut guard = LEARNING_CACHE.lock().unwrap();
        let config = get_current_learning_config();

        match *guard {
            Some(ref old_cache) => {
                if previous_max_entries != max_entries_usize {
                    let mut new_cache = LearningCache::new(config);
                    let all_entries = old_cache.prefix_lookup("");
                    for (reading, surface, _) in all_entries.into_iter().rev() {
                        new_cache.record(&reading, &surface);
                    }
                    *guard = Some(new_cache);
                }
            }
            None => {
                *guard = Some(LearningCache::new(config));
            }
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_kachaya_karukan_KarukanEngine_nativeConvert<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    input: JString<'a>,
    max_candidates: jni::sys::jint,
    context: JString<'a>,
    query_id: jni::sys::jlong,
) -> JString<'a> {
    let q_id = if query_id > 0 { query_id as u64 } else { 0 };

    let input_str: String = match env.get_string(&input) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("").unwrap(),
    };

    let context_str: String = match env.get_string(&context) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let result = convert_query(&input_str, max_candidates, &context_str, q_id)
        .unwrap_or_default();

    env.new_string(result)
        .unwrap_or_else(|_| env.new_string("").unwrap())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_kachaya_karukan_KarukanEngine_nativeRecordLearning<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    reading: JString<'a>,
    surface: JString<'a>,
) {
    let reading_str: String = match env.get_string(&reading) {
        Ok(s) => s.into(),
        Err(_) => return,
    };
    let surface_str: String = match env.get_string(&surface) {
        Ok(s) => s.into(),
        Err(_) => return,
    };

    record_learning(&reading_str, &surface_str);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_kachaya_karukan_KarukanEngine_nativeRemoveLearning<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    reading: JString<'a>,
    surface: JString<'a>,
) -> jboolean {
    let reading_str: String = match env.get_string(&reading) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let surface_str: String = match env.get_string(&surface) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };

    if remove_learning(&reading_str, &surface_str) {
        1
    } else {
        0
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_kachaya_karukan_KarukanEngine_nativeSaveLearning<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    file_path: JString<'a>,
) -> jboolean {
    let path_str: String = match env.get_string(&file_path) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let path = PathBuf::from(path_str);

    if save_learning(&path) {
        1
    } else {
        0
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_kachaya_karukan_KarukanEngine_nativeLoadLearning<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    file_path: JString<'a>,
) -> jboolean {
    let path_str: String = match env.get_string(&file_path) {
        Ok(s) => s.into(),
        Err(_) => return 0,
    };
    let path = PathBuf::from(path_str);

    if load_learning(&path) {
        1
    } else {
        0
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_kachaya_karukan_KarukanEngine_nativeClearLearning<'a>(
    _env: JNIEnv<'a>,
    _class: JClass<'a>,
) {
    clear_learning();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_kachaya_karukan_KarukanEngine_nativeRomajiToHiragana<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    input: JString<'a>,
) -> JString<'a> {
    let input_str: String = match env.get_string(&input) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("").unwrap(),
    };

    let mut guard = ROMAJI_CONVERTER.lock().unwrap();
    let converter = guard.get_or_insert_with(RomajiConverter::new);
    let converted = converter.convert(&input_str);
    let result = format!("{}{}", converted.text, converted.pending);

    env.new_string(result)
        .unwrap_or_else(|_| env.new_string("").unwrap())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_kachaya_karukan_KarukanEngine_nativeRomajiToHiraganaFlush<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    input: JString<'a>,
) -> JString<'a> {
    let input_str: String = match env.get_string(&input) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("").unwrap(),
    };

    let mut guard = ROMAJI_CONVERTER.lock().unwrap();
    let converter = guard.get_or_insert_with(RomajiConverter::new);
    let result = converter.convert_flush(&input_str);

    env.new_string(result)
        .unwrap_or_else(|_| env.new_string("").unwrap())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_kachaya_karukan_KarukanEngine_nativeProcessKey<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    keysym: jni::sys::jint,
    modifiers: jni::sys::jint,
    is_release: jboolean,
) -> jobject {
    let res = process_key_struct(keysym as u32, modifiers as u32, is_release == 0);

    let cls = match env.find_class("io/github/kachaya/karukan/ProcessKeyResult") {
        Ok(c) => c,
        Err(_) => return std::ptr::null_mut(),
    };

    let commit_jstr = match res.commit_text {
        Some(ref s) => env.new_string(s).ok().map(JObject::from),
        None => None,
    };

    let preedit_jstr = match res.preedit_text {
        Some(ref s) => env.new_string(s).ok().map(JObject::from),
        None => None,
    };

    let aux_jstr = match res.aux_text {
        Some(ref s) => env.new_string(s).ok().map(JObject::from),
        None => None,
    };

    let candidates_jlist = if let Some(ref c_list) = res.candidates {
        let array_list_cls = match env.find_class("java/util/ArrayList") {
            Ok(c) => c,
            Err(_) => return std::ptr::null_mut(),
        };
        let list_obj = match env.new_object(&array_list_cls, "(I)V", &[JValue::Int(c_list.len() as i32)]) {
            Ok(o) => o,
            Err(_) => return std::ptr::null_mut(),
        };

        for item in c_list {
            if let Ok(item_jstr) = env.new_string(item) {
                let _ = env.call_method(&list_obj, "add", "(Ljava/lang/Object;)Z", &[JValue::Object(&item_jstr)]);
                let _ = env.delete_local_ref(JObject::from(item_jstr));
            }
        }
        Some(list_obj)
    } else {
        None
    };

    let null_obj = JObject::null();

    let args = [
        JValue::Bool(if res.consumed { 1 } else { 0 }),
        JValue::Object(commit_jstr.as_ref().unwrap_or(&null_obj)),
        JValue::Bool(if res.has_preedit { 1 } else { 0 }),
        JValue::Object(preedit_jstr.as_ref().unwrap_or(&null_obj)),
        JValue::Int(res.caret as i32),
        JValue::Bool(if res.has_candidates { 1 } else { 0 }),
        JValue::Object(candidates_jlist.as_ref().unwrap_or(&null_obj)),
        JValue::Bool(if res.hide_candidates { 1 } else { 0 }),
        JValue::Object(aux_jstr.as_ref().unwrap_or(&null_obj)),
    ];

    let obj = match env.new_object(
        &cls,
        "(ZLjava/lang/String;ZLjava/lang/String;IZLjava/util/List;ZLjava/lang/String;)V",
        &args,
    ) {
        Ok(o) => o,
        Err(_) => return std::ptr::null_mut(),
    };

    obj.into_raw()
}
