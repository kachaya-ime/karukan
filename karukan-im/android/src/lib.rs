use jni::JNIEnv;
use jni::objects::{JClass, JObjectArray, JString};
use jni::sys::jboolean;
use karukan_engine::{KanaKanjiConverter, LearningCache, LearningConfig, ModelSource, RomajiConverter};
use std::path::PathBuf;
use std::sync::Mutex;

static CONVERTER: Mutex<Option<KanaKanjiConverter>> = Mutex::new(None);
static ROMAJI_CONVERTER: Mutex<Option<RomajiConverter>> = Mutex::new(None);
static LEARNING_CACHE: Mutex<Option<LearningCache>> = Mutex::new(None);

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
        Err(_) => return env.new_object_array(0, env.find_class("java/lang/String").unwrap(), env.new_string("").unwrap()).unwrap(),
    };

    let context_str: String = match env.get_string(&context) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let max_cand_usize = if max_candidates > 0 { max_candidates as usize } else { 9 };

    let guard = CONVERTER.lock().unwrap();
    let mut candidates = if let Some(ref converter) = *guard {
        converter
            .convert(&input_str, &context_str, max_cand_usize)
            .unwrap_or_else(|_| vec![input_str.clone()])
    } else {
        vec![input_str.clone()]
    };

    // Apply learning cache lookup / reordering
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
