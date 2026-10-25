use jni::JNIEnv;
use jni::objects::{JClass, JObject, JObjectArray, JString};
use jni::sys::jboolean;
use karukan_engine::{
    BracketStyle, DateConfig, DateRewriter, Dictionary, EmojiRewriter, KanaKanjiConverter,
    LearningCache, LearningConfig, ModelSource, PunctuationStyle, Rewriter, RomajiConverter,
    SlashStyle, SymbolStyle, Width, WidthRules,
};
use std::collections::HashSet;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;

static CONVERTER: Mutex<Option<KanaKanjiConverter>> = Mutex::new(None);
static ROMAJI_CONVERTER: Mutex<Option<RomajiConverter>> = Mutex::new(None);
static DATE_REWRITER: Mutex<Option<DateRewriter>> = Mutex::new(None);
static LEARNING_CACHE: Mutex<Option<LearningCache>> = Mutex::new(None);
static SYSTEM_DICT: Mutex<Option<Dictionary>> = Mutex::new(None);
static USER_DICTS: Mutex<Vec<Dictionary>> = Mutex::new(Vec::new());

static LATEST_CANCELLED_QUERY_ID: AtomicU64 = AtomicU64::new(0);

#[derive(Debug, Clone)]
pub struct AndroidEngineConfig {
    pub strategy: String,
    pub max_latency_ms: i32,
    pub chunk_chars: i32,
    pub chunk_symbols: i32,
    pub chunk_digits: i32,
    pub chunk_alphabets: i32,
    pub beam_width: i32,
    pub beam_chars: i32,
}

impl Default for AndroidEngineConfig {
    fn default() -> Self {
        Self {
            strategy: "adaptive".to_string(),
            max_latency_ms: 100,
            chunk_chars: 30,
            chunk_symbols: 1,
            chunk_digits: 0,
            chunk_alphabets: 0,
            beam_width: 3,
            beam_chars: 30,
        }
    }
}

static ENGINE_CONFIG: Mutex<Option<AndroidEngineConfig>> = Mutex::new(None);


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

    let path_buf = PathBuf::from(path_str);
    let source = ModelSource::Path(path_buf);

    match KanaKanjiConverter::from_source(&source, "jinen-v2-xsmall-q5") {
        Ok(converter) => {
            let mut guard = CONVERTER.lock().unwrap();
            *guard = Some(converter);

            let mut learn_guard = LEARNING_CACHE.lock().unwrap();
            if learn_guard.is_none() {
                *learn_guard = Some(LearningCache::new(LearningConfig::default()));
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
pub extern "system" fn Java_io_github_kachaya_karukan_KarukanEngine_nativeSetNThreads<'a>(
    _env: JNIEnv<'a>,
    _class: JClass<'a>,
    n_threads: jni::sys::jint,
) {
    let mut guard = CONVERTER.lock().unwrap();
    if let Some(ref mut converter) = *guard {
        converter.set_n_threads(n_threads as u32);
    }
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
    strategy: JString<'a>,
    max_latency_ms: jni::sys::jint,
    chunk_chars: jni::sys::jint,
    chunk_symbols: jni::sys::jint,
    chunk_digits: jni::sys::jint,
    chunk_alphabets: jni::sys::jint,
    beam_width: jni::sys::jint,
    beam_chars: jni::sys::jint,
) {
    let strat_str: String = env
        .get_string(&strategy)
        .map(|s| s.into())
        .unwrap_or_else(|_| "adaptive".to_string());

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

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_kachaya_karukan_KarukanEngine_nativeConvert<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    input: JString<'a>,
    max_candidates: jni::sys::jint,
    context: JString<'a>,
) -> JString<'a> {
    let input_str: String = match env.get_string(&input) {
        Ok(s) => s.into(),
        Err(_) => return env.new_string("").unwrap(),
    };

    if input_str.is_empty() {
        return env.new_string("").unwrap();
    }

    let context_str: String = match env.get_string(&context) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let max_cand_usize = if max_candidates > 0 {
        max_candidates as usize
    } else {
        let guard = ENGINE_CONFIG.lock().unwrap();
        guard.as_ref().map(|c| c.beam_width as usize).unwrap_or(9)
    };

    // 高速な重複防止のための HashSet
    let mut seen_candidates = HashSet::new();

    // 0. Search via DateRewriter & EmojiRewriter
    let mut date_candidates = Vec::new();
    {
        let mut guard = DATE_REWRITER.lock().unwrap();
        let rewriter = guard.get_or_insert_with(|| DateRewriter::new(DateConfig::default()));
        for (text, _desc) in rewriter.rewrite(&input_str) {
            if seen_candidates.insert(text.clone()) {
                date_candidates.push(text);
            }
        }
    }

    let mut emoji_candidates = Vec::new();
    for (emoji, _desc) in EmojiRewriter.rewrite(&input_str) {
        if !seen_candidates.contains(&emoji) {
            emoji_candidates.push(emoji);
        }
    }

    // 1. Search in user dictionaries
    let mut user_dict_candidates = Vec::new();
    {
        let dict_guard = USER_DICTS.lock().unwrap();
        for dict in dict_guard.iter() {
            if let Some(res) = dict.exact_match_search(&input_str) {
                for cand in res.candidates {
                    if seen_candidates.insert(cand.surface.clone()) {
                        user_dict_candidates.push(cand.surface.clone());
                    }
                }
            }
        }
    }

    // 2. Search in system dictionary
    let mut system_dict_candidates = Vec::new();
    {
        let dict_guard = SYSTEM_DICT.lock().unwrap();
        if let Some(ref dict) = *dict_guard {
            if let Some(res) = dict.exact_match_search(&input_str) {
                for cand in res.candidates {
                    if !seen_candidates.contains(&cand.surface) {
                        system_dict_candidates.push(cand.surface.clone());
                    }
                }
            }
        }
    }

    // 3. Search via AI model (ロック保持範囲を推論部のみに極小化)
    let model_candidates = {
        let guard = CONVERTER.lock().unwrap();
        if let Some(ref converter) = *guard {
            converter
                .convert(&input_str, &context_str, max_cand_usize)
                .unwrap_or_else(|_| vec![input_str.clone()])
        } else {
            vec![input_str.clone()]
        }
    };

    // Katakana conversion via karukan_engine
    let katakana_candidate = karukan_engine::hiragana_to_katakana(&input_str);

    // 4. Combine candidates according to priority order:
    //    1) Date Candidates -> 2) User Dictionaries -> 3) AI Model Candidates -> 4) Katakana -> 5) Emoji Candidates -> 6) System Dictionary
    let mut candidates = Vec::with_capacity(16);

    for dc in date_candidates {
        candidates.push(dc);
    }
    for udc in user_dict_candidates {
        candidates.push(udc);
    }
    for mc in model_candidates {
        if seen_candidates.insert(mc.clone()) {
            candidates.push(mc);
        }
    }
    if !katakana_candidate.is_empty() && seen_candidates.insert(katakana_candidate.clone()) {
        candidates.push(katakana_candidate);
    }
    for ec in emoji_candidates {
        if seen_candidates.insert(ec.clone()) {
            candidates.push(ec);
        }
    }
    for sdc in system_dict_candidates {
        if seen_candidates.insert(sdc.clone()) {
            candidates.push(sdc);
        }
    }

    // 5. Apply learning cache lookup / reordering
    let learn_guard = LEARNING_CACHE.lock().unwrap();
    if let Some(ref cache) = *learn_guard {
        let learned = cache.lookup(&input_str);
        for (surface, _score) in learned.into_iter().rev() {
            if let Some(pos) = candidates.iter().position(|c| c == &surface) {
                let item = candidates.remove(pos);
                candidates.insert(0, item);
            } else {
                candidates.insert(0, surface);
            }
        }
    }

    // 単漢字アノテーション（解説）が存在する場合は「漢字 (解説)」を補足する処理を追加
    let mut final_candidates = Vec::with_capacity(candidates.len());
    for cand in candidates {
        if cand.chars().count() == 1 {
            if let Some(desc) = karukan_engine::single_kanji_description(&cand) {
                final_candidates.push(format!("{} ({})", cand, desc));
            } else {
                final_candidates.push(cand);
            }
        } else {
            final_candidates.push(cand);
        }
    }

    // タブ区切り (`\t`) で1本の JString として結合し、JNI 境界のオブジェクト作成・GC オーバーヘッドを極小化
    let result_str = final_candidates.join("\t");
    env.new_string(result_str)
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

    let mut guard = LEARNING_CACHE.lock().unwrap();
    if guard.is_none() {
        *guard = Some(LearningCache::new(LearningConfig::default()));
    }
    if let Some(ref mut cache) = *guard {
        cache.record(&reading_str, &surface_str);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_kachaya_karukan_KarukanEngine_nativeClearLearning<'a>(
    _env: JNIEnv<'a>,
    _class: JClass<'a>,
) {
    let mut guard = LEARNING_CACHE.lock().unwrap();
    *guard = Some(LearningCache::new(LearningConfig::default()));
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
pub extern "system" fn Java_io_github_kachaya_karukan_KarukanEngine_nativeUpdateRomajiConfig<'a>(
    mut env: JNIEnv<'a>,
    _this: JObject<'a>,
    punctuation: JString<'a>,
    bracket: JString<'a>,
    slash: JString<'a>,
    _space: JString<'a>,
    kana_symbol_width: JString<'a>,
    ascii_symbol_width: JString<'a>,
    digit_width: JString<'a>,
) {
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

    let mut guard = ROMAJI_CONVERTER.lock().unwrap();
    *guard = Some(RomajiConverter::with_rules(symbol_style, width_rules));
}
