#![cfg_attr(not(windows), allow(dead_code))]

#[cfg(windows)]
pub const CLSID_KARUKAN_TIP: windows_core::GUID =
    windows_core::GUID::from_u128(0x7d7a2c6e_4d65_4ab8_8f30_4a1f2c83f81b);
#[cfg(windows)]
pub const GUID_KARUKAN_PROFILE: windows_core::GUID =
    windows_core::GUID::from_u128(0x9d8e6e3a_8f6d_45d3_a0b1_9b1e391f01a1);
#[cfg(windows)]
pub const GUID_TFCAT_TIPCAP_SYSTRAYSUPPORT: windows_core::GUID =
    windows_core::GUID::from_u128(0x25504fb4_7bab_4bc1_9c69_cf81890f0ef5);
#[cfg(windows)]
pub const GUID_TFCAT_TIPCAP_IMMERSIVESUPPORT: windows_core::GUID =
    windows_core::GUID::from_u128(0x13a016df_560b_46cd_947a_4c3af1e0e35d);
#[cfg(windows)]
pub const GUID_TFCAT_TIPCAP_INPUTMODECOMPARTMENT: windows_core::GUID =
    windows_core::GUID::from_u128(0xccf05dd7_4a87_11d7_a6e2_00065b84435c);
#[cfg(windows)]
pub const GUID_TFCAT_TIPCAP_UIELEMENTENABLED: windows_core::GUID =
    windows_core::GUID::from_u128(0x49d2f9cf_1f5e_11d7_a6d3_00065b84435c);
#[cfg(windows)]
pub const GUID_TFCAT_TIPCAP_SECUREMODE: windows_core::GUID =
    windows_core::GUID::from_u128(0x42de4b0f_e9d9_4be5_b6df_5daaf76f7cf9);
#[cfg(windows)]
pub const GUID_TFCAT_TIPCAP_COMLESS: windows_core::GUID =
    windows_core::GUID::from_u128(0x364215d9_75bc_11d7_a6ef_00065b84435c);

// Old incorrect GUIDs previously registered, for cleanup in install --uninstall
#[cfg(windows)]
pub const OLD_BOGUS_SYSTRAYSUPPORT: windows_core::GUID =
    windows_core::GUID::from_u128(0xc2208d27_7756_4b2a_8951_b85ee4e6f48f);
#[cfg(windows)]
pub const OLD_BOGUS_IMMERSIVESUPPORT: windows_core::GUID =
    windows_core::GUID::from_u128(0x13a016cc_4397_4340_8ab7_3844b2f29391);
#[cfg(windows)]
pub const OLD_BOGUS_UIELEMENTENABLED: windows_core::GUID =
    windows_core::GUID::from_u128(0x0b09f630_9a54_4632_a0e2_60f810ed3831);
#[cfg(windows)]
pub const GUID_COMPARTMENT_KEYBOARD_INPUTMODE_CONVERSION: windows_core::GUID =
    windows_core::GUID::from_u128(0xccf05dd8_4a87_11d7_a6e2_00065b84435c);

#[cfg(windows)]
pub const TF_CONVERSIONMODE_ALPHANUMERIC: u32 = 0x0000;
#[cfg(windows)]
pub const TF_CONVERSIONMODE_NATIVE: u32 = 0x0001;
#[cfg(windows)]
pub const TF_CONVERSIONMODE_KATAKANA: u32 = 0x0002;
#[cfg(windows)]
pub const TF_CONVERSIONMODE_FULLSHAPE: u32 = 0x0008;
#[cfg(windows)]
pub const TF_CONVERSIONMODE_ROMAN: u32 = 0x0010;

#[cfg(windows)]
pub const RES_ID_ICON_BRANDING: u16 = 1;
#[cfg(windows)]
pub const RES_ID_ICON_HIRAGANA: u16 = 2;
#[cfg(windows)]
pub const RES_ID_ICON_ALPHA: u16 = 3;
#[cfg(windows)]
pub const RES_ID_ICON_KATAKANA: u16 = 4;

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
