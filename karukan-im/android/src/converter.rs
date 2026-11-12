use crate::config::{
    get_current_learning_config, is_query_cancelled, ADAPTIVE_USE_GREEDY, ENGINE_CONFIG,
    IS_LEARNING_ENABLED, MODEL_PATH, SYSTEM_DICT_PATH,
};
use crate::dict_engine::{
    get_date_candidates, get_primary_rewriter_candidates, get_secondary_rewriter_candidates,
    get_system_dict_candidates, get_user_dict_candidates, is_date_candidate,
};
use karukan_engine::{KanaKanjiConverter, LearningCache, RomajiConverter};
use karukan_im::{EngineAction, InputMethodEngine, KeyEvent, KeyModifiers, Keysym};
use std::collections::HashSet;
use std::path::Path;
use std::sync::atomic::Ordering;
use std::sync::Mutex;

// -------------------------------------------------------------------------------------------------
// Region: Global Engine Mutex Instances
// -------------------------------------------------------------------------------------------------

pub static CONVERTER: Mutex<Option<KanaKanjiConverter>> = Mutex::new(None);
pub static ROMAJI_CONVERTER: Mutex<Option<RomajiConverter>> = Mutex::new(None);
pub static LEARNING_CACHE: Mutex<Option<LearningCache>> = Mutex::new(None);
pub static ENGINE: Mutex<Option<InputMethodEngine>> = Mutex::new(None);

// -------------------------------------------------------------------------------------------------
// Region: Utility Helpers
// -------------------------------------------------------------------------------------------------

fn clean_model_candidate(s: &str) -> String {
    let mut cleaned = s.trim();
    if let Some(pos) = cleaned.find('(').or_else(|| cleaned.find('（')).or_else(|| cleaned.find('[')).or_else(|| cleaned.find('【')) {
        if pos > 0 {
            cleaned = &cleaned[..pos];
        }
    }
    cleaned.trim().to_string()
}

fn model_context(base_context: &str, preceding: &str, persona: &str, max_chars: usize) -> String {
    let combined = format!("{base_context}{preceding}");
    let char_count = combined.chars().count();
    let context = combined
        .chars()
        .skip(char_count.saturating_sub(max_chars))
        .collect::<String>();
    let context = if context.trim().is_empty() {
        String::new()
    } else {
        context
    };

    if persona.is_empty() {
        context
    } else if context.is_empty() {
        persona.to_string()
    } else {
        format!("{persona} {context}")
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum ChunkTokenKind {
    Japanese,
    Digit,
    Symbol,
    Alphabet,
}

#[derive(Clone, Copy)]
struct ChunkToken {
    ch: char,
    kind: ChunkTokenKind,
}

fn is_japanese(c: char) -> bool {
    c != '\u{30FB}'
        && matches!(
            c,
            '\u{3040}'..='\u{309F}'
                | '\u{30A0}'..='\u{30FF}'
                | '\u{3400}'..='\u{9FFF}'
        )
}

fn classify_chunk_token(ch: char) -> ChunkToken {
    let kind = if is_japanese(ch) {
        ChunkTokenKind::Japanese
    } else if karukan_engine::kana::is_digit(ch) {
        ChunkTokenKind::Digit
    } else if ch.is_alphabetic() {
        ChunkTokenKind::Alphabet
    } else {
        ChunkTokenKind::Symbol
    };
    ChunkToken { ch, kind }
}

fn split_reading_into_chunks(
    reading: &str,
    max_chars: usize,
    max_symbols: usize,
    max_digits: usize,
    max_alphabets: usize,
) -> Vec<String> {
    let mut chunks = Vec::new();
    let mut chunk: Vec<ChunkToken> = Vec::new();

    for token in reading.chars().map(classify_chunk_token) {
        let has_japanese = chunk
            .iter()
            .any(|existing| existing.kind == ChunkTokenKind::Japanese);
        let budget = match token.kind {
            ChunkTokenKind::Japanese => None,
            ChunkTokenKind::Digit => Some(max_digits),
            ChunkTokenKind::Symbol => Some(max_symbols),
            ChunkTokenKind::Alphabet => Some(max_alphabets),
        };
        let fits = chunk.len() < max_chars.max(1)
            && match budget {
                None => has_japanese,
                Some(_) if !has_japanese => true,
                Some(budget) => chunk
                    .iter()
                    .filter(|existing| existing.kind == token.kind)
                    .count()
                    < budget,
            };

        if !chunk.is_empty() && !fits {
            chunks.push(chunk.iter().map(|token| token.ch).collect());
            chunk.clear();
        }
        chunk.push(token);
    }

    if !chunk.is_empty() {
        chunks.push(chunk.iter().map(|token| token.ch).collect());
    }
    chunks
}

fn chunk_contains_japanese(chunk: &str) -> bool {
    chunk.chars().any(is_japanese)
}

fn trailing_japanese_chunks_start(chunks: &[String], beam_chars: usize) -> usize {
    let mut start = chunks.len();
    let mut taken = 0;
    for chunk in chunks.iter().rev() {
        if !chunk_contains_japanese(chunk) {
            break;
        }
        let len = chunk.chars().count();
        if taken > 0 && taken + len > beam_chars {
            break;
        }
        taken += len;
        start -= 1;
    }
    start
}

fn convert_chunked_model_candidates(
    input: &str,
    base_context: &str,
    persona: &str,
    max_candidates: usize,
    chunk_chars: usize,
    chunk_symbols: usize,
    chunk_digits: usize,
    chunk_alphabets: usize,
    beam_chars: usize,
    context_chars: usize,
    query_id: u64,
    mut convert: impl FnMut(&str, &str, usize) -> Vec<String>,
) -> Option<Vec<String>> {
    let chunks = split_reading_into_chunks(
        input,
        chunk_chars,
        chunk_symbols,
        chunk_digits,
        chunk_alphabets,
    );
    let beam_start = trailing_japanese_chunks_start(&chunks, beam_chars);
    let mut prefix = String::new();

    for chunk in &chunks[..beam_start] {
        if is_query_cancelled(query_id) {
            return None;
        }
        let converted = if chunk_contains_japanese(chunk) {
            let lctx = model_context(base_context, &prefix, persona, context_chars);
            let katakana = karukan_engine::hiragana_to_katakana(chunk);
            convert(&katakana, &lctx, 1)
                .into_iter()
                .next()
                .unwrap_or_else(|| chunk.clone())
        } else {
            chunk.clone()
        };
        prefix.push_str(&converted);
    }

    if is_query_cancelled(query_id) {
        return None;
    }
    if beam_start == chunks.len() {
        return Some(if prefix == input {
            Vec::new()
        } else {
            vec![prefix]
        });
    }

    let beam_reading = chunks[beam_start..].concat();
    let lctx = model_context(base_context, &prefix, persona, context_chars);
    let katakana = karukan_engine::hiragana_to_katakana(&beam_reading);
    let tails = convert(&katakana, &lctx, max_candidates);
    Some(
        tails
            .into_iter()
            .map(|tail| format!("{prefix}{tail}"))
            .collect(),
    )
}

// -------------------------------------------------------------------------------------------------
// Region: Conversion Pipeline (convert_query)
// -------------------------------------------------------------------------------------------------

pub fn convert_query(
    input_str: &str,
    max_candidates: i32,
    context_str: &str,
    q_id: u64,
    source_filter: i32,
) -> Option<String> {
    if is_query_cancelled(q_id) || input_str.is_empty() {
        return Some(String::new());
    }

    let has_left_context = !context_str.trim().is_empty();

    // Chunk および Beam 設定パラメータの取得
    let (
        chunk_chars,
        chunk_symbols,
        chunk_digits,
        chunk_alphabets,
        beam_chars,
        context_chars,
        persona,
    ) = {
        let guard = ENGINE_CONFIG.lock().unwrap();
        if let Some(ref config) = *guard {
            (
                config.chunk_chars.max(1) as usize,
                config.chunk_symbols,
                config.chunk_digits,
                config.chunk_alphabets,
                config.beam_chars.max(0) as usize,
                config.context_chars.max(0) as usize,
                config.persona.clone(),
            )
        } else {
            (30, 1, 0, 0, 30, 10, String::new())
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
            let is_greedy_active = ADAPTIVE_USE_GREEDY.load(Ordering::Relaxed);

            let cand = match config.strategy.as_str() {
                "light" => 1,
                "main" => requested_cand.max(1),
                _ => {
                    if lat_ms > 0 && is_greedy_active && requested_cand > 1
                    {
                        requested_cand.min(5)
                    } else {
                        requested_cand.max(1)
                    }
                }
            };
            tracing::info!(
                "[KarukanConverter] strategy={}, requested_cand={}, max_lat_ms={}, is_greedy_active={}, effective_max_cand={}",
                config.strategy, requested_cand, lat_ms, is_greedy_active, cand
            );
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

    // 2. Convert the chunk grid and beam only over its trailing Japanese span.
    let start_time = std::time::Instant::now();

    let raw_model_candidates = {
        let guard = CONVERTER.lock().unwrap();
        if let Some(ref converter) = *guard {
            let raw = convert_chunked_model_candidates(
                input_str,
                context_str,
                &persona,
                effective_max_cand,
                chunk_chars,
                chunk_symbols.max(0) as usize,
                chunk_digits.max(0) as usize,
                chunk_alphabets.max(0) as usize,
                beam_chars,
                context_chars,
                q_id,
                |reading, context, candidates| converter
                    .convert(reading, context, candidates)
                    .unwrap_or_default(),
            )
            .unwrap_or_default();

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
            tracing::warn!(
                "[KarukanConverter] Adaptive latency exceeded: elapsed={}ms > max_lat={}ms. Setting ADAPTIVE_USE_GREEDY = true",
                elapsed_ms, max_lat_ms
            );
            ADAPTIVE_USE_GREEDY.store(true, Ordering::Relaxed);
        } else {
            tracing::info!(
                "[KarukanConverter] Adaptive latency OK: elapsed={}ms <= max_lat={}ms. Setting ADAPTIVE_USE_GREEDY = false",
                elapsed_ms, max_lat_ms
            );
            ADAPTIVE_USE_GREEDY.store(false, Ordering::Relaxed);
        }
    }

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
            cache
                .lookup(input_str)
                .into_iter()
                .filter(|(s, _)| !is_date_candidate(input_str, s))
                .collect()
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
        Date,
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
        if is_date_candidate(input_str, surface) {
            (CandidateSourceKind::Date, None)
        } else if learned_surfaces.contains(surface) {
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
        for mc in &raw_model_candidates {
            push_cand!(mc.clone(), None, None, true);
        }

        // ユーザー辞書
        for udc in &user_dict_candidates {
            push_cand!(udc.surface.clone(), Some(CandidateSourceKind::UserDict), udc.description.clone(), false);
        }

        // 残りの学習履歴候補
        for (surface, _) in &learned_entries {
            push_cand!(surface.clone(), Some(CandidateSourceKind::Learning), None, false);
        }
    } else {
        // 【左文脈が存在しない場合】
        // 文脈がない場合はユーザーの過去の学習履歴を最優先にする。

        // 学習履歴 (最大3件)
        for (surface, _) in learned_entries.iter().take(3) {
            push_cand!(surface.clone(), Some(CandidateSourceKind::Learning), None, false);
        }

        // ユーザー辞書
        for udc in &user_dict_candidates {
            push_cand!(udc.surface.clone(), Some(CandidateSourceKind::UserDict), udc.description.clone(), false);
        }

        // AIモデル変換結果
        for mc in &raw_model_candidates {
            push_cand!(mc.clone(), None, None, true);
        }
    }

    // 4. 日付・時刻リライター候補 (📅 日付)
    let date_candidates = get_date_candidates(input_str, &mut seen_candidates);
    for dc in date_candidates {
        candidates.push(CandidateAnnotated {
            surface: dc.surface,
            source: CandidateSourceKind::Date,
            description: dc.description,
        });
    }

    // 5. リライター候補 (数値・記号・絵文字)
    let primary_rewriter_candidates =
        get_primary_rewriter_candidates(input_str, &mut seen_candidates);
    for rc in primary_rewriter_candidates {
        candidates.push(CandidateAnnotated {
            surface: rc.surface,
            source: CandidateSourceKind::Rewriter,
            description: rc.description,
        });
    }

    // 6. システム辞書候補
    for sdc in &system_dict_candidates {
        push_cand!(sdc.surface.clone(), Some(CandidateSourceKind::SystemDict), sdc.description.clone(), false);
    }

    // 7. 原文ひらがな / 全角カタカナ (Fallback)
    push_cand!(input_str.to_string(), Some(CandidateSourceKind::Fallback), None, false);

    if !full_katakana.is_empty() {
        push_cand!(full_katakana, Some(CandidateSourceKind::Fallback), None, false);
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

    // 8. ソースフィルターの適用 & アノテーション整形
    let mut final_candidates = Vec::with_capacity(candidates.len());
    for item in candidates {
        // ソースフィルター判定 (0: ALL, 1: LEARNING, 2: DICTIONARY, 3: AI, 4: SYMBOL)
        let is_match = match source_filter {
            1 => item.source == CandidateSourceKind::Learning,
            2 => item.source == CandidateSourceKind::UserDict || item.source == CandidateSourceKind::SystemDict,
            3 => item.source == CandidateSourceKind::Model,
            4 => item.source == CandidateSourceKind::Rewriter || item.source == CandidateSourceKind::Date,
            _ => true,
        };

        if !is_match {
            continue;
        }

        let surface = item.surface;

        let kanji_desc = if surface.chars().count() == 1 {
            karukan_engine::single_kanji_description(&surface)
        } else {
            None
        };
        let effective_desc = item.description.or_else(|| kanji_desc.map(|s| s.to_string()));

        let badge = match item.source {
            CandidateSourceKind::Learning => {
                if let Some(desc) = effective_desc {
                    format!(" (📝 {})", desc)
                } else {
                    " (📝)".to_string()
                }
            }
            CandidateSourceKind::UserDict => {
                if let Some(desc) = effective_desc {
                    format!(" (👤 {})", desc)
                } else {
                    " (👤)".to_string()
                }
            }
            CandidateSourceKind::Model => {
                if let Some(desc) = effective_desc {
                    format!(" (🤖 {})", desc)
                } else {
                    " (🤖)".to_string()
                }
            }
            CandidateSourceKind::SystemDict => {
                if let Some(desc) = effective_desc {
                    format!(" ({})", desc)
                } else {
                    String::new()
                }
            }
            CandidateSourceKind::Date => {
                if let Some(desc) = effective_desc {
                    format!(" (📅 {})", desc)
                } else {
                    " (📅)".to_string()
                }
            }
            CandidateSourceKind::Rewriter => {
                if let Some(desc) = effective_desc {
                    format!(" (🔄 {})", desc)
                } else {
                    " (🔄)".to_string()
                }
            }
            CandidateSourceKind::Fallback => {
                if let Some(desc) = effective_desc {
                    format!(" ({})", desc)
                } else {
                    String::new()
                }
            }
        };

        let formatted = if badge.is_empty() {
            surface
        } else {
            format!("{}\x1f{}", surface, badge)
        };

        final_candidates.push(formatted);
    }

    if final_candidates.is_empty() && source_filter != 0 {
        // 該当フィルターで候補がない場合はそのまま空を返す
        Some(String::new())
    } else {
        Some(final_candidates.join("\t"))
    }
}

// -------------------------------------------------------------------------------------------------
// Region: Learning Persistence Functions
// -------------------------------------------------------------------------------------------------

pub fn record_learning(reading_str: &str, surface_str: &str) {
    if is_date_candidate(reading_str, surface_str) {
        return;
    }
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

// -------------------------------------------------------------------------------------------------
// Region: Engine Configuration Factory
// -------------------------------------------------------------------------------------------------

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
            settings.conversion.context_chars = config.context_chars as usize;
            settings.conversion.use_context = config.use_context;
            settings.conversion.persona = config.persona.clone();
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




