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

pub fn convert_query(
    input_str: &str,
    max_candidates: i32,
    context_str: &str,
    q_id: u64,
) -> Option<String> {
    if is_query_cancelled(q_id) || input_str.is_empty() {
        return Some(String::new());
    }

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

    // 高速な重複防止のための HashSet
    let mut seen_candidates = HashSet::new();

    if is_query_cancelled(q_id) {
        return Some(String::new());
    }

    // 0. プライマリ・リライター (日付・数値・記号・絵文字)
    let primary_rewriter_candidates =
        get_primary_rewriter_candidates(input_str, &mut seen_candidates);

    if is_query_cancelled(q_id) {
        return Some(String::new());
    }

    // 1. ユーザー辞書
    let user_dict_candidates = get_user_dict_candidates(input_str, &mut seen_candidates);

    // 2. システム辞書
    let system_dict_candidates = get_system_dict_candidates(input_str, &seen_candidates);

    if is_query_cancelled(q_id) {
        return Some(String::new());
    }

    // strategy, beam_width および max_latency_ms / ADAPTIVE_USE_GREEDY に応じた実効AIモデル候補数 (effective_max_cand) の決定
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

    // 3. AIモデル推論 (時間計測付き) または Viterbi 探索
    let start_time = std::time::Instant::now();

    let raw_model_candidates = {
        let guard = CONVERTER.lock().unwrap();
        if let Some(ref converter) = *guard {
            let res = converter
                .convert(&query_for_ai, context_str, effective_max_cand)
                .unwrap_or_else(|_| vec![query_for_ai.clone()]);
            if prefix_str.is_empty() {
                res
            } else {
                res.into_iter().map(|cand| format!("{}{}", prefix_str, cand)).collect()
            }
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

    if is_query_cancelled(q_id) {
        return Some(String::new());
    }

    // 4. セカンダリ/フォールバック候補 (全角カタカナ・平仮名・半角カナ・英数)
    let full_katakana = karukan_engine::hiragana_to_katakana(input_str);
    let (half_katakana_list, alphabet_list) = get_secondary_rewriter_candidates(input_str);

    // 漢字変換・語彙変換候補と、単なるひらがな原形を分離
    let mut main_conversion_candidates = Vec::new();
    let mut plain_kana_candidates = Vec::new();

    for mc in raw_model_candidates {
        if mc == input_str {
            if !plain_kana_candidates.contains(&mc) {
                plain_kana_candidates.push(mc);
            }
        } else if seen_candidates.insert(mc.clone()) {
            main_conversion_candidates.push(mc);
        }
    }

    // 5. 公式コアエンジン (karukan-im/core) と完全に同じ優先順位に従って候補を結合:
    //    1) ユーザー辞書 (User Dictionary)
    //    2) AIモデル推論結果 (Model)
    //    3) システム辞書 (System Dictionary)
    //    4) 原文ひらがな / 全角カタカナ (Fallback)
    //    5) 日付・数値・記号・絵文字・半角カタカナ・アルファベット (Date / Rewriter)
    //    ※ 最終的な第 1 位最優先として学習履歴 (Learning) が先頭へ挿入される
    let mut candidates = Vec::with_capacity(16);

    for udc in user_dict_candidates {
        candidates.push(udc);
    }
    for mc in main_conversion_candidates {
        candidates.push(mc);
    }
    for sdc in system_dict_candidates {
        if sdc == input_str {
            if !plain_kana_candidates.contains(&sdc) {
                plain_kana_candidates.push(sdc);
            }
        } else if seen_candidates.insert(sdc.clone()) {
            candidates.push(sdc);
        }
    }
    if seen_candidates.insert(input_str.to_string()) {
        candidates.push(input_str.to_string());
    }
    if !full_katakana.is_empty() && seen_candidates.insert(full_katakana.clone()) {
        candidates.push(full_katakana);
    }
    for pk in plain_kana_candidates {
        if seen_candidates.insert(pk.clone()) {
            candidates.push(pk);
        }
    }
    for rc in primary_rewriter_candidates {
        if seen_candidates.insert(rc.clone()) {
            candidates.push(rc);
        }
    }
    for hk in half_katakana_list {
        if seen_candidates.insert(hk.clone()) {
            candidates.push(hk);
        }
    }
    for alpha in alphabet_list {
        if seen_candidates.insert(alpha.clone()) {
            candidates.push(alpha);
        }
    }

    // 6. 学習キャッシュ（学習履歴）による順位繰り上げ処理 (isLearningEnabled が有効な場合のみ)
    if IS_LEARNING_ENABLED.load(Ordering::Relaxed) {
        let learn_guard = LEARNING_CACHE.lock().unwrap();
        if let Some(ref cache) = *learn_guard {
            let learned = cache.lookup(input_str);
            for (surface, _score) in learned.into_iter().rev() {
                if let Some(pos) = candidates.iter().position(|c| c == &surface) {
                    let item = candidates.remove(pos);
                    candidates.insert(0, item);
                } else {
                    candidates.insert(0, surface);
                }
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
