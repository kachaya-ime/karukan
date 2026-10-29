use crate::config::IS_DATE_CONVERSION_ENABLED;
use karukan_engine::{
    AlphabetRewriter, DateConfig, DateRewriter, Dictionary, EmojiRewriter,
    HalfWidthKatakanaRewriter, NumberRewriter, Rewriter, SymbolRewriter,
};
use std::collections::HashSet;
use std::sync::atomic::Ordering;
use std::sync::Mutex;

pub static SYSTEM_DICT: Mutex<Option<Dictionary>> = Mutex::new(None);
pub static USER_DICTS: Mutex<Vec<Dictionary>> = Mutex::new(Vec::new());
pub static DATE_REWRITER: Mutex<Option<DateRewriter>> = Mutex::new(None);

pub fn get_primary_rewriter_candidates(input_str: &str, seen_candidates: &mut HashSet<String>) -> Vec<String> {
    let mut primary_rewriter_candidates = Vec::new();

    // 日付・時刻リライター
    if IS_DATE_CONVERSION_ENABLED.load(Ordering::Relaxed) {
        let mut guard = DATE_REWRITER.lock().unwrap();
        let rewriter = guard.get_or_insert_with(|| DateRewriter::new(DateConfig::default()));
        for (text, _desc) in rewriter.rewrite(input_str) {
            if seen_candidates.insert(text.clone()) {
                primary_rewriter_candidates.push(text);
            }
        }
    }

    // 数値リライター (123 -> 百二十三, 壱百弐拾参, Ⅻ, ⑫, 0x7B 等)
    for (text, _desc) in NumberRewriter.rewrite(input_str) {
        if seen_candidates.insert(text.clone()) {
            primary_rewriter_candidates.push(text);
        }
    }

    // 記号リライター (かっこ -> 「」, やじるし -> → 等)
    for (text, _desc) in SymbolRewriter.rewrite(input_str) {
        if seen_candidates.insert(text.clone()) {
            primary_rewriter_candidates.push(text);
        }
    }

    // 絵文字リライター
    for (emoji, _desc) in EmojiRewriter.rewrite(input_str) {
        if seen_candidates.insert(emoji.clone()) {
            primary_rewriter_candidates.push(emoji);
        }
    }

    primary_rewriter_candidates
}

pub fn get_user_dict_candidates(input_str: &str, seen_candidates: &mut HashSet<String>) -> Vec<String> {
    let mut user_dict_candidates = Vec::new();
    let dict_guard = USER_DICTS.lock().unwrap();
    for dict in dict_guard.iter() {
        if let Some(res) = dict.exact_match_search(input_str) {
            for cand in res.candidates {
                if seen_candidates.insert(cand.surface.clone()) {
                    user_dict_candidates.push(cand.surface.clone());
                }
            }
        }
    }
    user_dict_candidates
}

pub fn get_system_dict_candidates(input_str: &str, seen_candidates: &HashSet<String>) -> Vec<String> {
    let mut system_dict_candidates = Vec::new();
    let dict_guard = SYSTEM_DICT.lock().unwrap();
    if let Some(ref dict) = *dict_guard {
        if let Some(res) = dict.exact_match_search(input_str) {
            for cand in res.candidates {
                if !seen_candidates.contains(&cand.surface) {
                    system_dict_candidates.push(cand.surface.clone());
                }
            }
        }
    }
    system_dict_candidates
}

pub fn get_secondary_rewriter_candidates(input_str: &str) -> (Vec<String>, Vec<String>) {
    let mut half_katakana_list = Vec::new();
    for (text, _desc) in HalfWidthKatakanaRewriter.rewrite(input_str) {
        half_katakana_list.push(text);
    }

    let mut alphabet_list = Vec::new();
    for (text, _desc) in AlphabetRewriter.rewrite(input_str) {
        alphabet_list.push(text);
    }

    (half_katakana_list, alphabet_list)
}
