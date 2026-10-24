use jni::JNIEnv;
use jni::objects::{JClass, JObject, JObjectArray, JString};
use jni::sys::jboolean;
use karukan_engine::{Dictionary, KanaKanjiConverter, LearningCache, LearningConfig, ModelSource, RomajiConverter};
use std::path::PathBuf;
use std::sync::Mutex;

static CONVERTER: Mutex<Option<KanaKanjiConverter>> = Mutex::new(None);
static ROMAJI_CONVERTER: Mutex<Option<RomajiConverter>> = Mutex::new(None);
static LEARNING_CACHE: Mutex<Option<LearningCache>> = Mutex::new(None);
static SYSTEM_DICT: Mutex<Option<Dictionary>> = Mutex::new(None);
static USER_DICTS: Mutex<Vec<Dictionary>> = Mutex::new(Vec::new());

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
pub extern "system" fn Java_io_github_kachaya_karukan_KarukanEngine_nativeConvert<'a>(
    mut env: JNIEnv<'a>,
    _class: JClass<'a>,
    input: JString<'a>,
    max_candidates: jni::sys::jint,
    context: JString<'a>,
) -> JObjectArray<'a> {
    let input_str: String = match env.get_string(&input) {
        Ok(s) => s.into(),
        Err(_) => {
            let cls = env.find_class("java/lang/String").unwrap();
            let init_str = env.new_string("").unwrap();
            return env.new_object_array(0, cls, init_str).unwrap();
        }
    };

    let context_str: String = match env.get_string(&context) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let max_cand_usize = if max_candidates > 0 { max_candidates as usize } else { 9 };

    // 1. Search in user dictionaries (additional dictionaries)
    let mut user_dict_candidates = Vec::new();
    {
        let dict_guard = USER_DICTS.lock().unwrap();
        for dict in dict_guard.iter() {
            if let Some(res) = dict.exact_match_search(&input_str) {
                for cand in res.candidates {
                    if !user_dict_candidates.contains(&cand.surface) {
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
                    if !user_dict_candidates.contains(&cand.surface) && !system_dict_candidates.contains(&cand.surface) {
                        system_dict_candidates.push(cand.surface.clone());
                    }
                }
            }
        }
    }

    // 3. Search via AI model
    let guard = CONVERTER.lock().unwrap();
    let model_candidates = if let Some(ref converter) = *guard {
        converter
            .convert(&input_str, &context_str, max_cand_usize)
            .unwrap_or_else(|_| vec![input_str.clone()])
    } else {
        vec![input_str.clone()]
    };

    // 4. Combine candidates according to priority order:
    //    1) User Dictionaries -> 2) AI Model Candidates -> 3) System Dictionary
    let mut candidates = Vec::new();
    for udc in user_dict_candidates {
        candidates.push(udc);
    }
    for mc in model_candidates {
        if !candidates.contains(&mc) {
            candidates.push(mc);
        }
    }
    for sdc in system_dict_candidates {
        if !candidates.contains(&sdc) {
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

    let string_class = env.find_class("java/lang/String").unwrap();
    let initial_element = env.new_string("").unwrap();
    let result_array = env
        .new_object_array(candidates.len() as i32, string_class, initial_element)
        .unwrap();

    for (i, cand) in candidates.iter().enumerate() {
        let jstr = env.new_string(cand).unwrap();
        env.set_object_array_element(&result_array, i as i32, jstr)
            .unwrap();
    }

    result_array
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
