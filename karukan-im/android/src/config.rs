use karukan_engine::LearningConfig;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::Mutex;

pub static MODEL_PATH: Mutex<Option<String>> = Mutex::new(None);
pub static SYSTEM_DICT_PATH: Mutex<Option<String>> = Mutex::new(None);
pub static USER_DICT_DIR: Mutex<Option<String>> = Mutex::new(None);

pub static LATEST_CANCELLED_QUERY_ID: AtomicU64 = AtomicU64::new(0);
pub static CURRENT_MAX_LEARNING_ENTRIES: AtomicUsize =
    AtomicUsize::new(LearningConfig::DEFAULT_MAX_ENTRIES);
pub static IS_DATE_CONVERSION_ENABLED: AtomicBool = AtomicBool::new(true);
pub static IS_LEARNING_ENABLED: AtomicBool = AtomicBool::new(true);
pub static ADAPTIVE_USE_GREEDY: AtomicBool = AtomicBool::new(false);

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

pub static ENGINE_CONFIG: Mutex<Option<AndroidEngineConfig>> = Mutex::new(None);

pub fn get_current_learning_config() -> LearningConfig {
    let max_entries = CURRENT_MAX_LEARNING_ENTRIES.load(Ordering::Relaxed);
    LearningConfig {
        max_entries,
        ..LearningConfig::default()
    }
}

pub fn is_query_cancelled(query_id: u64) -> bool {
    if query_id == 0 {
        return false;
    }
    LATEST_CANCELLED_QUERY_ID.load(Ordering::Relaxed) >= query_id
}
