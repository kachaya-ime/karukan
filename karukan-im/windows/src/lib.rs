#![cfg_attr(not(windows), allow(dead_code))]

#[cfg(windows)]
pub const CLSID_KARUKAN_TIP: windows_core::GUID =
    windows_core::GUID::from_u128(0x7d7a2c6e_4d65_4ab8_8f30_4a1f2c83f81b);
#[cfg(windows)]
pub const GUID_KARUKAN_PROFILE: windows_core::GUID =
    windows_core::GUID::from_u128(0x9d8e6e3a_8f6d_45d3_a0b1_9b1e391f01a1);

#[cfg(windows)]
pub fn configure_model_cache() -> Result<(), String> {
    if [
        "HF_HUB_CACHE",
        "HUGGINGFACE_HUB_CACHE",
        "HF_HOME",
        "XDG_CACHE_HOME",
    ]
    .iter()
    .any(|key| std::env::var_os(key).is_some())
    {
        return Ok(());
    }

    let local_app_data = std::env::var_os("LOCALAPPDATA").ok_or_else(|| {
        "LOCALAPPDATA is not set; cannot choose the model cache directory".to_string()
    })?;
    let cache_dir = std::path::PathBuf::from(local_app_data)
        .join("huggingface")
        .join("hub");
    unsafe {
        std::env::set_var("HF_HUB_CACHE", cache_dir);
    }
    Ok(())
}

#[cfg(windows)]
mod tsf;
