use crate::config::{
    get_current_learning_config, is_query_cancelled, ADAPTIVE_USE_GREEDY, ENGINE_CONFIG,
    IS_LEARNING_ENABLED, MODEL_PATH, SYSTEM_DICT_PATH,
};
use crate::dict_engine::{
    get_primary_rewriter_candidates, get_secondary_rewriter_candidates, get_system_dict_candidates,
    get_user_dict_candidates,
};
use karukan_engine::{KanaKanjiConverter, LearningCache, RomajiConverter};
use karukan_im::{EngineAction, InputMethodEngine, KeyEvent, KeyModifiers, Keysym};
use std::collections::HashSet;
use std::path::Path;
use std::sync::atomic::Ordering;
use std::sync::Mutex;

pub static CONVERTER: Mutex<Option<KanaKanjiConverter>> = Mutex::new(None);
pub static ROMAJI_CONVERTER: Mutex<Option<RomajiConverter>> = Mutex::new(None);
pub static LEARNING_CACHE: Mutex<Option<LearningCache>> = Mutex::new(None);
pub static ENGINE: Mutex<Option<InputMethodEngine>> = Mutex::new(None);

#[allow(dead_code)]
fn android_log(tag: &str, msg: &str) {
    use std::ffi::CString;
    unsafe extern "C" {
        fn __android_log_print(
            prio: std::os::raw::c_int,
            tag: *const std::os::raw::c_char,
            fmt: *const std::os::raw::c_char,
            ...
        ) -> std::os::raw::c_int;
    }
    if let (Ok(c_tag), Ok(c_msg)) = (CString::new(tag), CString::new("%s")) {
        if let Ok(c_val) = CString::new(msg) {
            unsafe {
                __android_log_print(3, c_tag.as_ptr(), c_msg.as_ptr(), c_val.as_ptr());
            }
        }
    }
}

fn clean_model_candidate(s: &str) -> String {
    let mut cleaned = s.trim();
    if let Some(pos) = cleaned.find('(').or_else(|| cleaned.find('（')).or_else(|| cleaned.find('[')).or_else(|| cleaned.find('【')) {
        if pos > 0 {
            cleaned = &cleaned[..pos];
        }
    }
    cleaned.trim().to_string()
}

pub fn convert_query(
    input_str: &str,
    max_candidates: i32,
    context_str: &str,
    q_id: u64,
) -> Option<String> {
    if is_query_cancelled(q_id) || input_str.is_empty() {
        return Some(String::new());
    }

    let has_left_context = !context_str.trim().is_empty();

    android_log(
        "KarukanNative",
        &format!(
            "[convert_query] input='{}', context='{}', query_id={}, has_left_context={}",
            input_str, context_str, q_id, has_left_context
        ),
    );

    // Chunk および Beam 設定パラメータの取得
    let (max_ai_len, chunk_symbols, chunk_digits, chunk_alphabets) = {
        let guard = ENGINE_CONFIG.lock().unwrap();
        if let Some(ref config) = *guard {
            (
                config.chunk_chars.min(config.beam_chars).max(1) as usize,
                config.chunk_symbols,
                config.chunk_digits,
                config.chunk_alphabets,
            )
        } else {
            (30, 1, 0, 0)
        }
    };

    // 1. 数字のみ・英字のみ・記号のみ等のAI変換非対象 Chunk の判定・保護 (ハルシネーション防止)
    let is_pure_digits = !input_str.is_empty() && input_str.chars().all(|c| c.is_ascii_digit());
    let is_pure_alphabets =
        !input_str.is_empty() && input_str.chars().all(|c| c.is_ascii_alphabetic());
    let is_pure_symbols = !input_str.is_empty()
        && input_str
            .chars()
            .all(|c| !c.is_alphanumeric() && !c.is_whitespace());

    if (is_pure_digits && chunk_digits == 0)
        || (is_pure_alphabets && chunk_alphabets == 0)
        || (is_pure_symbols && chunk_symbols == 0)
    {
        return Some(input_str.to_string());
    }

    // 2. chunk_chars / beam_chars による最大AI変換入力長の調整（長文入力時の安全な切り出しとプレフィックス保持）
    let effective_max_ai_len = max_ai_len.min(128);
    let char_count = input_str.chars().count();
    let (prefix_str, query_for_ai) = if char_count > effective_max_ai_len {
        let skip_count = char_count - effective_max_ai_len;
        let prefix = input_str.chars().take(skip_count).collect::<String>();
        let suffix = input_str.chars().skip(skip_count).collect::<String>();
        (prefix, suffix)
    } else {
        (String::new(), input_str.to_string())
    };

    if is_query_cancelled(q_id) {
        return Some(String::new());
    }

    // strategy, beam_width および max_latency_ms に応じた実効AIモデル候補数の決定
    let (effective_max_cand, max_lat_ms) = {
        let guard = ENGINE_CONFIG.lock().unwrap();
        if let Some(ref config) = *guard {
            let bw = config.beam_width.max(1) as usize;
            let requested_cand = if max_candidates > 0 {
                (max_candidates as usize).max(bw).min(16)
            } else {
                bw.max(9)
            };
            let lat_ms = config.max_latency_ms;

            let cand = match config.strategy.as_str() {
                "light" => 1,
                "main" => requested_cand.max(1),
                _ => {
                    if lat_ms > 0 && ADAPTIVE_USE_GREEDY.load(Ordering::Relaxed) && requested_cand > 1
                    {
                        requested_cand.min(5)
                    } else {
                        requested_cand.max(1)
                    }
                }
            };
            (cand, lat_ms)
        } else {
            (
                if max_candidates > 0 {
                    (max_candidates as usize).max(9)
                } else {
                    9
                },
                300,
            )
        }
    };

    // 3. AIモデル推論 (文脈 context_str を考慮した推論)
    let start_time = std::time::Instant::now();

    let raw_model_candidates = {
        let guard = CONVERTER.lock().unwrap();
        if let Some(ref converter) = *guard {
            let res = converter
                .convert(&query_for_ai, context_str, effective_max_cand)
                .unwrap_or_else(|_| vec![query_for_ai.clone()]);
            let raw = if prefix_str.is_empty() {
                res
            } else {
                res.into_iter().map(|cand| format!("{}{}", prefix_str, cand)).collect()
            };

            let mut cleaned_res = Vec::with_capacity(raw.len());
            let mut seen = HashSet::new();
            for cand in raw {
                let cleaned = clean_model_candidate(&cand);
                if !cleaned.is_empty() && seen.insert(cleaned.clone()) {
                    cleaned_res.push(cleaned);
                }
            }

            // 短いかな入力 (3文字以下) で、モデルの上位3件以内にかな原文が含まれている場合は、
            // ライブ変換中の不要な漢字フリッカー (例: 「はい」→「灰」) を防ぐため原文を1番目に配置する
            if input_str.chars().count() <= 3 {
                if let Some(pos) = cleaned_res.iter().take(3).position(|c| c == input_str) {
                    if pos > 0 {
                        let item = cleaned_res.remove(pos);
                        cleaned_res.insert(0, item);
                    }
                }
            }

            cleaned_res
        } else {
            vec![input_str.to_string()]
        }
    };

    let elapsed_ms = start_time.elapsed().as_millis() as i32;

    if max_lat_ms > 0 {
        if elapsed_ms > max_lat_ms {
            ADAPTIVE_USE_GREEDY.store(true, Ordering::Relaxed);
        } else {
            ADAPTIVE_USE_GREEDY.store(false, Ordering::Relaxed);
        }
    }

    android_log(
        "KarukanNative",
        &format!("[raw_model_candidates] {:?}", raw_model_candidates),
    );

    if is_query_cancelled(q_id) {
        return Some(String::new());
    }

    // 4. セカンダリ/フォールバック候補 (全角カタカナ・平仮名・半角カナ・英数)
    let full_katakana = karukan_engine::hiragana_to_katakana(input_str);
    let (half_katakana_list, alphabet_list) = get_secondary_rewriter_candidates(input_str);

    // 5. 各辞書・候補ソースの個別検索
    let user_dict_candidates = get_user_dict_candidates(input_str, &mut HashSet::new());
    let system_dict_candidates = get_system_dict_candidates(input_str, &HashSet::new());

    let learned_entries = if IS_LEARNING_ENABLED.load(Ordering::Relaxed) {
        let learn_guard = LEARNING_CACHE.lock().unwrap();
        if let Some(ref cache) = *learn_guard {
            cache.lookup(input_str)
        } else {
            Vec::new()
        }
    } else {
        Vec::new()
    };

    let learned_surfaces: HashSet<String> = learned_entries.iter().map(|(s, _)| s.clone()).collect();
    let user_dict_map: std::collections::HashMap<String, Option<String>> = user_dict_candidates
        .iter()
        .map(|c| (c.surface.clone(), c.description.clone()))
        .collect();
    let system_dict_map: std::collections::HashMap<String, Option<String>> = system_dict_candidates
        .iter()
        .map(|c| (c.surface.clone(), c.description.clone()))
        .collect();

    #[derive(Debug, Clone, PartialEq, Eq)]
    enum CandidateSourceKind {
        Learning,
        UserDict,
        Model,
        SystemDict,
        Rewriter,
        Fallback,
    }

    #[derive(Debug, Clone)]
    struct CandidateAnnotated {
        surface: String,
        source: CandidateSourceKind,
        description: Option<String>,
    }

    let determine_source = |surface: &str, is_from_model: bool| -> (CandidateSourceKind, Option<String>) {
        if learned_surfaces.contains(surface) {
            let desc = user_dict_map.get(surface).cloned().flatten()
                .or_else(|| system_dict_map.get(surface).cloned().flatten());
            (CandidateSourceKind::Learning, desc)
        } else if let Some(desc) = user_dict_map.get(surface) {
            (CandidateSourceKind::UserDict, desc.clone())
        } else if is_from_model {
            let desc = system_dict_map.get(surface).cloned().flatten();
            (CandidateSourceKind::Model, desc)
        } else if let Some(desc) = system_dict_map.get(surface) {
            (CandidateSourceKind::SystemDict, desc.clone())
        } else {
            (CandidateSourceKind::Model, None)
        }
    };

    let mut candidates: Vec<CandidateAnnotated> = Vec::with_capacity(16);
    let mut seen_candidates = HashSet::new();

    macro_rules! push_cand {
        ($surface:expr, $forced_source:expr, $desc_override:expr, $is_model:expr) => {
            let surface_val = $surface;
            if seen_candidates.insert(surface_val.clone()) {
                let (source, default_desc) = determine_source(&surface_val, $is_model);
                let final_source = $forced_source.unwrap_or(source);
                let final_desc = $desc_override.or(default_desc);
                candidates.push(CandidateAnnotated {
                    surface: surface_val,
                    source: final_source,
                    description: final_desc,
                });
            }
        };
    }

    if has_left_context {
        // 【左文脈が存在する場合】
        // AIモデルが context_str に照らして推論した候補 (raw_model_candidates) を最優先にする。
        // （コンテキストフリーな過去の学習履歴や辞書単語が、現在の左文脈における AI 推論結果を上書きするのを防ぐ）

        // 1. AIモデルによる文脈考慮の変換候補結果 (ユーザー辞書や学習済みの語も determine_source で適切なバッジが付与される)
        for mc in &raw_model_candidates {
            push_cand!(mc.clone(), None, None, true);
        }

        // 2. ユーザー辞書 (モデル候補に含まれなかったユーザー明示登録語)
        for udc in &user_dict_candidates {
            push_cand!(udc.surface.clone(), Some(CandidateSourceKind::UserDict), udc.description.clone(), false);
        }

        // 3. 残りの学習履歴候補
        for (surface, _) in &learned_entries {
            push_cand!(surface.clone(), Some(CandidateSourceKind::Learning), None, false);
        }

        // 4. 残りのシステム辞書候補
        for sdc in &system_dict_candidates {
            push_cand!(sdc.surface.clone(), Some(CandidateSourceKind::SystemDict), sdc.description.clone(), false);
        }
    } else {
        // 【左文脈が存在しない場合】
        // 文脈がない場合はユーザーの過去の学習履歴を最優先にする。

        // 1. 学習履歴 (最大3件)
        for (surface, _) in learned_entries.iter().take(3) {
            push_cand!(surface.clone(), Some(CandidateSourceKind::Learning), None, false);
        }

        // 2. ユーザー辞書
        for udc in &user_dict_candidates {
            push_cand!(udc.surface.clone(), Some(CandidateSourceKind::UserDict), udc.description.clone(), false);
        }

        // 3. AIモデル変換結果
        for mc in &raw_model_candidates {
            push_cand!(mc.clone(), None, None, true);
        }

        // 4. システム辞書
        for sdc in &system_dict_candidates {
            push_cand!(sdc.surface.clone(), Some(CandidateSourceKind::SystemDict), sdc.description.clone(), false);
        }
    }

    // 5. 原文ひらがな / 全角カタカナ (Fallback)
    push_cand!(input_str.to_string(), Some(CandidateSourceKind::Fallback), None, false);

    if !full_katakana.is_empty() {
        push_cand!(full_katakana, Some(CandidateSourceKind::Fallback), None, false);
    }

    // 6. リライター候補 (日付・記号・数値・絵文字・半角カタカナ・アルファベット)
    let primary_rewriter_candidates =
        get_primary_rewriter_candidates(input_str, &mut seen_candidates);
    for rc in primary_rewriter_candidates {
        candidates.push(CandidateAnnotated {
            surface: rc.surface,
            source: CandidateSourceKind::Rewriter,
            description: rc.description,
        });
    }

    for hk in half_katakana_list {
        if seen_candidates.insert(hk.surface.clone()) {
            candidates.push(CandidateAnnotated {
                surface: hk.surface,
                source: CandidateSourceKind::Rewriter,
                description: hk.description,
            });
        }
    }

    for alpha in alphabet_list {
        if seen_candidates.insert(alpha.surface.clone()) {
            candidates.push(CandidateAnnotated {
                surface: alpha.surface,
                source: CandidateSourceKind::Rewriter,
                description: alpha.description,
            });
        }
    }

    // 7. アノテーション整形
    let mut final_candidates = Vec::with_capacity(candidates.len());
    for item in candidates {
        let surface = item.surface;

        let kanji_desc = if surface.chars().count() == 1 {
            karukan_engine::single_kanji_description(&surface)
        } else {
            None
        };
        let effective_desc = item.description.or_else(|| kanji_desc.map(|s| s.to_string()));

        let formatted = match item.source {
            CandidateSourceKind::Learning => {
                if let Some(desc) = effective_desc {
                    format!("{} (📝 {})", surface, desc)
                } else {
                    format!("{} (📝)", surface)
                }
            }
            CandidateSourceKind::UserDict => {
                if let Some(desc) = effective_desc {
                    format!("{} (👤 {})", surface, desc)
                } else {
                    format!("{} (👤)", surface)
                }
            }
            CandidateSourceKind::Model => {
                if let Some(desc) = effective_desc {
                    format!("{} (🤖 {})", surface, desc)
                } else {
                    format!("{} (🤖)", surface)
                }
            }
            CandidateSourceKind::SystemDict => {
                if let Some(desc) = effective_desc {
                    format!("{} ({})", surface, desc)
                } else {
                    surface
                }
            }
            CandidateSourceKind::Rewriter => {
                if let Some(desc) = effective_desc {
                    format!("{} (🔄 {})", surface, desc)
                } else {
                    format!("{} (🔄)", surface)
                }
            }
            CandidateSourceKind::Fallback => {
                if let Some(desc) = effective_desc {
                    format!("{} ({})", surface, desc)
                } else {
                    surface
                }
            }
        };

        final_candidates.push(formatted);
    }

    android_log(
        "KarukanNative",
        &format!("[final_candidates] {:?}", final_candidates),
    );

    Some(final_candidates.join("\t"))
}

pub fn record_learning(reading_str: &str, surface_str: &str) {
    let mut guard = LEARNING_CACHE.lock().unwrap();
    if guard.is_none() {
        *guard = Some(LearningCache::new(get_current_learning_config()));
    }
    if let Some(ref mut cache) = *guard {
        cache.record(reading_str, surface_str);
    }
}

pub fn remove_learning(reading_str: &str, surface_str: &str) -> bool {
    let mut guard = LEARNING_CACHE.lock().unwrap();
    if let Some(ref mut cache) = *guard {
        cache.remove_suggestion(reading_str, surface_str)
    } else {
        false
    }
}

pub fn save_learning(path: &Path) -> bool {
    let mut guard = LEARNING_CACHE.lock().unwrap();
    if let Some(ref mut cache) = *guard {
        cache.save(path).is_ok()
    } else {
        false
    }
}

pub fn load_learning(path: &Path) -> bool {
    if !path.exists() {
        return false;
    }
    let config = get_current_learning_config();
    if let Ok(cache) = LearningCache::load(path, config) {
        let mut guard = LEARNING_CACHE.lock().unwrap();
        *guard = Some(cache);
        true
    } else {
        false
    }
}

pub fn clear_learning() {
    let mut guard = LEARNING_CACHE.lock().unwrap();
    *guard = Some(LearningCache::new(get_current_learning_config()));
}

pub fn create_android_settings() -> karukan_im::config::Settings {
    let mut settings = karukan_im::config::Settings::default();

    if let Ok(guard) = MODEL_PATH.lock() {
        if let Some(ref path) = *guard {
            settings.conversion.model = "android_model".to_string();
            let model_toml = format!("\"{}\"", path.replace('\\', "/"));
            if let Ok(model_def) = toml::from_str::<karukan_im::config::settings::ModelDef>(&model_toml) {
                settings.models.insert("android_model".to_string(), model_def);
            }
        }
    }

    if let Ok(guard) = SYSTEM_DICT_PATH.lock() {
        if let Some(ref path) = *guard {
            settings.conversion.dict_path = Some(path.clone());
        }
    }

    if let Ok(guard) = ENGINE_CONFIG.lock() {
        if let Some(ref config) = *guard {
            settings.conversion.chunk_chars = config.chunk_chars as usize;
            settings.conversion.chunk_symbols = config.chunk_symbols as usize;
            settings.conversion.chunk_digits = config.chunk_digits as usize;
            settings.conversion.chunk_alphabets = config.chunk_alphabets as usize;
            settings.conversion.beam_width = config.beam_width as usize;
            settings.conversion.beam_chars = config.beam_chars as usize;
            settings.conversion.max_latency_ms = config.max_latency_ms as u64;
            settings.conversion.strategy = match config.strategy.as_str() {
                "light" => karukan_im::config::settings::StrategyMode::Light,
                "main" => karukan_im::config::settings::StrategyMode::Main,
                _ => karukan_im::config::settings::StrategyMode::Adaptive,
            };
        }
    }

    settings
}

pub struct ProcessKeyResultStruct {
    pub consumed: bool,
    pub commit_text: Option<String>,
    pub has_preedit: bool,
    pub preedit_text: Option<String>,
    pub caret: usize,
    pub has_candidates: bool,
    pub candidates: Option<Vec<String>>,
    pub hide_candidates: bool,
    pub aux_text: Option<String>,
}

pub fn process_key_struct(keysym: u32, modifiers: u32, is_press: bool) -> ProcessKeyResultStruct {
    let modifiers_struct = KeyModifiers::from_modifier_state(modifiers);
    let key_event = KeyEvent::new(
        Keysym(keysym),
        modifiers_struct,
        is_press,
    );

    let mut guard = ENGINE.lock().unwrap();
    if guard.is_none() {
        let settings = create_android_settings();
        let mut engine = InputMethodEngine::with_config(karukan_im::core::engine::EngineConfig::from_settings(&settings));
        let _ = engine.init_from_settings(&settings);
        *guard = Some(engine);
    }

    let engine = guard.as_mut().unwrap();
    let result = engine.process_key(&key_event);

    let mut commit_text = None;
    let mut has_preedit = false;
    let mut preedit_text = None;
    let mut caret = 0;
    let mut has_candidates = false;
    let mut candidates = None;
    let mut hide_candidates = false;
    let mut aux_text = None;

    for action in &result.actions {
        match action {
            EngineAction::Commit(text) => {
                commit_text = Some(text.clone());
            }
            EngineAction::UpdatePreedit(preedit) => {
                has_preedit = true;
                preedit_text = Some(preedit.text().to_string());
                caret = preedit.caret();
            }
            EngineAction::ShowCandidates(cand_list) => {
                has_candidates = true;
                candidates = Some(cand_list.candidates().iter().map(|c| c.text.clone()).collect());
            }
            EngineAction::HideCandidates => {
                hide_candidates = true;
            }
            EngineAction::UpdateAuxText(aux) => {
                aux_text = Some(aux.to_string());
            }
            _ => {}
        }
    }

    ProcessKeyResultStruct {
        consumed: result.consumed,
        commit_text,
        has_preedit,
        preedit_text,
        caret,
        has_candidates,
        candidates,
        hide_candidates,
        aux_text,
    }
}
