use crate::config::IS_DATE_CONVERSION_ENABLED;
use karukan_engine::{
    AlphabetRewriter, DateConfig, DateRewriter, Dictionary, EmojiRewriter,
    HalfWidthKatakanaRewriter, Rewriter, SymbolRewriter,
};
use karukan_engine::rewriter::NumberRewriter;
use std::collections::HashSet;
use std::sync::atomic::Ordering;
use std::sync::Mutex;

// -------------------------------------------------------------------------------------------------
// Region: Global Dictionary & Rewriter Mutex Instances
// -------------------------------------------------------------------------------------------------

pub static SYSTEM_DICT: Mutex<Option<Dictionary>> = Mutex::new(None);
pub static USER_DICTS: Mutex<Vec<Dictionary>> = Mutex::new(Vec::new());
pub static DATE_REWRITER: Mutex<Option<DateRewriter>> = Mutex::new(None);

// -------------------------------------------------------------------------------------------------
// Region: Date Config Updater
// -------------------------------------------------------------------------------------------------

pub fn update_date_config(json_str: &str) {
    let json_to_parse = if json_str.is_empty() {
        r#"{
            "formats": [
                "{YEAR}/{MONTH}/{DATE}",
                "{YEAR}-{MONTH}-{DATE}",
                "{YEAR}年{MONTH:bare}月{DATE:bare}日",
                "{ERA}{ERA_YEAR}年{MONTH:bare}月{DATE:bare}日",
                "{MONTH:bare}月{DATE:bare}日",
                "{MONTH:bare}月{DATE:bare}日({WEEKDAY})",
                "{WEEKDAY}曜日"
            ],
            "phrases": [
                { "reading": "きょう", "offset_days": 0 },
                { "reading": "きのう", "offset_days": -1 },
                { "reading": "おととい", "offset_days": -2 },
                { "reading": "あした", "offset_days": 1 },
                { "reading": "あす", "offset_days": 1 },
                { "reading": "あさって", "offset_days": 2 },
                { "reading": "しあさって", "offset_days": 3 },
                { "reading": "いま", "formats": ["{HOUR}:{MINUTE}", "{HOUR:bare}時{MINUTE:bare}分", "{AMPM}{HOUR12}時{MINUTE:bare}分"] },
                { "reading": "にちじ", "formats": ["{YEAR}/{MONTH}/{DATE} {HOUR}:{MINUTE}", "{YEAR}年{MONTH:bare}月{DATE:bare}日{HOUR:bare}時{MINUTE:bare}分"] }
            ]
        }"#
    } else {
        json_str
    };

    match serde_json::from_str::<DateConfig>(json_to_parse) {
        Ok(config) => {
            let mut guard = DATE_REWRITER.lock().unwrap();
            *guard = Some(DateRewriter::new(config));
        }
        Err(e) => {
            tracing::error!("Failed to parse DateConfig JSON: {:?}", e);
        }
    }
}

// -------------------------------------------------------------------------------------------------
// Region: Rewriter & Dictionary Candidate Providers
// -------------------------------------------------------------------------------------------------

#[derive(Debug, Clone)]
pub struct DictCandidateItem {
    pub surface: String,
    pub description: Option<String>,
}

pub fn get_date_candidates(input_str: &str, seen_candidates: &mut HashSet<String>) -> Vec<DictCandidateItem> {
    let mut date_candidates = Vec::new();
    if IS_DATE_CONVERSION_ENABLED.load(Ordering::Relaxed) {
        let mut guard = DATE_REWRITER.lock().unwrap();
        let rewriter = guard.get_or_insert_with(|| DateRewriter::new(DateConfig::default()));
        for (text, desc) in rewriter.rewrite(input_str) {
            if seen_candidates.insert(text.clone()) {
                date_candidates.push(DictCandidateItem {
                    surface: text,
                    description: desc.filter(|s| !s.is_empty()),
                });
            }
        }
    }
    date_candidates
}

pub fn is_date_candidate(reading: &str, surface: &str) -> bool {
    if !IS_DATE_CONVERSION_ENABLED.load(Ordering::Relaxed) {
        return false;
    }
    let mut guard = DATE_REWRITER.lock().unwrap();
    let rewriter = guard.get_or_insert_with(|| DateRewriter::new(DateConfig::default()));
    for (text, _) in rewriter.rewrite(reading) {
        if text == surface {
            return true;
        }
    }
    false
}

pub fn get_primary_rewriter_candidates(input_str: &str, seen_candidates: &mut HashSet<String>) -> Vec<DictCandidateItem> {
    let mut primary_rewriter_candidates = Vec::new();

    // 数値リライター (123 -> 百二十三, 壱百弐拾参, Ⅻ, ⑫, 0x7B 等)
    for (text, desc) in NumberRewriter.rewrite(input_str) {
        if seen_candidates.insert(text.clone()) {
            primary_rewriter_candidates.push(DictCandidateItem {
                surface: text,
                description: desc.filter(|s| !s.is_empty()),
            });
        }
    }

    // 記号リライター (かっこ -> 「」, やじるし -> → 等)
    for (text, desc) in SymbolRewriter.rewrite(input_str) {
        if seen_candidates.insert(text.clone()) {
            primary_rewriter_candidates.push(DictCandidateItem {
                surface: text,
                description: desc.filter(|s| !s.is_empty()),
            });
        }
    }

    // 絵文字リライター
    for (emoji, desc) in EmojiRewriter.rewrite(input_str) {
        if seen_candidates.insert(emoji.clone()) {
            primary_rewriter_candidates.push(DictCandidateItem {
                surface: emoji,
                description: desc.filter(|s| !s.is_empty()),
            });
        }
    }

    primary_rewriter_candidates
}

pub fn get_user_dict_candidates(input_str: &str, seen_candidates: &mut HashSet<String>) -> Vec<DictCandidateItem> {
    let mut user_dict_candidates = Vec::new();
    let dict_guard = USER_DICTS.lock().unwrap();
    for dict in dict_guard.iter() {
        if let Some(res) = dict.exact_match_search(input_str) {
            for cand in res.candidates {
                if seen_candidates.insert(cand.surface.clone()) {
                    user_dict_candidates.push(DictCandidateItem {
                        surface: cand.surface.clone(),
                        description: None,
                    });
                }
            }
        }
    }
    user_dict_candidates
}

pub fn get_system_dict_candidates(input_str: &str, seen_candidates: &HashSet<String>) -> Vec<DictCandidateItem> {
    let mut system_dict_candidates = Vec::new();
    let dict_guard = SYSTEM_DICT.lock().unwrap();
    if let Some(ref dict) = *dict_guard {
        if let Some(res) = dict.exact_match_search(input_str) {
            for cand in res.candidates {
                if !seen_candidates.contains(&cand.surface) {
                    system_dict_candidates.push(DictCandidateItem {
                        surface: cand.surface.clone(),
                        description: None,
                    });
                }
            }
        }
    }
    system_dict_candidates
}

pub fn get_secondary_rewriter_candidates(input_str: &str) -> (Vec<DictCandidateItem>, Vec<DictCandidateItem>) {
    let mut half_katakana_list = Vec::new();
    for (text, desc) in HalfWidthKatakanaRewriter.rewrite(input_str) {
        half_katakana_list.push(DictCandidateItem {
            surface: text,
            description: desc.filter(|s| !s.is_empty()),
        });
    }

    let mut alphabet_list = Vec::new();
    for (text, desc) in AlphabetRewriter.rewrite(input_str) {
        alphabet_list.push(DictCandidateItem {
            surface: text,
            description: desc.filter(|s| !s.is_empty()),
        });
    }

    (half_katakana_list, alphabet_list)
}
