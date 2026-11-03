#[cfg(windows)]
fn main() -> Result<(), Box<dyn std::error::Error>> {
    use std::ffi::OsStr;
    use std::io::{self, Write};
    use std::os::windows::ffi::OsStrExt;
    use std::path::{Path, PathBuf};
    use std::process::Command;
    use std::time::{SystemTime, UNIX_EPOCH};
    use windows::Win32::Foundation::{ERROR_FILE_NOT_FOUND, ERROR_PATH_NOT_FOUND};
    use windows::Win32::System::Com::{
        CLSCTX_INPROC_SERVER, COINIT_APARTMENTTHREADED, CoCreateInstance, CoInitializeEx,
        CoUninitialize,
    };
    use windows::Win32::System::Registry::{
        HKEY, HKEY_CURRENT_USER, HKEY_LOCAL_MACHINE, KEY_WRITE, REG_OPTION_NON_VOLATILE, REG_SZ,
        RegCloseKey, RegCreateKeyExW, RegDeleteTreeW, RegSetValueExW,
    };
    use windows_core::Interface;
    use windows::Win32::UI::Input::KeyboardAndMouse::{HKL, KLF_NOTELLSHELL, LoadKeyboardLayoutW};
    use windows::Win32::UI::TextServices::{
        CLSID_TF_CategoryMgr, CLSID_TF_InputProcessorProfiles, GUID_TFCAT_DISPLAYATTRIBUTEPROVIDER,
        GUID_TFCAT_TIP_KEYBOARD, ITfCategoryMgr, ITfInputProcessorProfileMgr,
        ITfInputProcessorProfiles, TF_IPPMF_FORSESSION, TF_PROFILETYPE_INPUTPROCESSOR,
    };
    use windows::core::{GUID, PCWSTR, w};

    use karukan_engine::ModelSource;
    use karukan_im::config::{Settings, settings::StrategyMode};
    use karukan_im_windows::{
        CLSID_KARUKAN_TIP, GUID_KARUKAN_PROFILE, GUID_TFCAT_TIPCAP_COMLESS,
        GUID_TFCAT_TIPCAP_IMMERSIVESUPPORT, GUID_TFCAT_TIPCAP_INPUTMODECOMPARTMENT,
        GUID_TFCAT_TIPCAP_SECUREMODE, GUID_TFCAT_TIPCAP_SYSTRAYSUPPORT,
        GUID_TFCAT_TIPCAP_UIELEMENTENABLED, OLD_BOGUS_IMMERSIVESUPPORT,
        OLD_BOGUS_SYSTRAYSUPPORT, OLD_BOGUS_UIELEMENTENABLED,
    };

    const LANGUAGE_ID_JAPANESE: u16 = 0x0411;
    const INSTALL_DIRECTORY: &str = "Programs\\Karukan";
    const DICTIONARY_URL: &str =
        "https://github.com/togatoga/karukan/releases/latest/download/dict.tgz";
    const DEFAULT_CONFIG: &str = include_str!("../../../core/config/default.toml");
    const TIP_REGISTRY_KEY: &str = "Software\\Classes\\CLSID";
    const TSF_TIP_REGISTRY_KEY: &str = "Software\\Microsoft\\CTF\\TIP";

    struct ComApartment;

    impl Drop for ComApartment {
        fn drop(&mut self) {
            unsafe { CoUninitialize() };
        }
    }

    struct RegistryKey(HKEY);

    impl Drop for RegistryKey {
        fn drop(&mut self) {
            unsafe {
                let result = RegCloseKey(self.0);
                if result.is_err() {
                    eprintln!("warning: RegCloseKey failed: {result:?}");
                }
            }
        }
    }

    fn wide(value: &OsStr) -> Vec<u16> {
        value.encode_wide().chain(Some(0)).collect()
    }

    fn guid_key(guid: &GUID) -> String {
        format!(
            "{{{:08x}-{:04x}-{:04x}-{:02x}{:02x}-{:02x}{:02x}{:02x}{:02x}{:02x}{:02x}}}",
            guid.data1,
            guid.data2,
            guid.data3,
            guid.data4[0],
            guid.data4[1],
            guid.data4[2],
            guid.data4[3],
            guid.data4[4],
            guid.data4[5],
            guid.data4[6],
            guid.data4[7]
        )
    }

    fn set_registry_string(key: HKEY, name: &str, value: &OsStr) -> windows::core::Result<()> {
        let name = wide(OsStr::new(name));
        let value = wide(value);
        let bytes = unsafe {
            std::slice::from_raw_parts(value.as_ptr().cast::<u8>(), value.len() * size_of::<u16>())
        };
        unsafe { RegSetValueExW(key, PCWSTR(name.as_ptr()), None, REG_SZ, Some(bytes)).ok() }
    }

    fn create_registry_key(path: &str) -> windows::core::Result<RegistryKey> {
        let path = wide(OsStr::new(path));
        let mut key = HKEY::default();
        unsafe {
            RegCreateKeyExW(
                HKEY_LOCAL_MACHINE,
                PCWSTR(path.as_ptr()),
                None,
                PCWSTR::null(),
                REG_OPTION_NON_VOLATILE,
                KEY_WRITE,
                None,
                &mut key,
                None,
            )
            .ok()?;
        }
        Ok(RegistryKey(key))
    }

    fn with_step<T>(step: &str, result: windows::core::Result<T>) -> Result<T, std::io::Error> {
        result.map_err(|error| {
            std::io::Error::other(format!(
                "{step} (HRESULT 0x{:08X}): {error}",
                error.code().0
            ))
        })
    }

    fn register_com_server(dll_path: &Path) -> Result<(), Box<dyn std::error::Error>> {
        let clsid = guid_key(&CLSID_KARUKAN_TIP);
        let clsid_path = format!("{TIP_REGISTRY_KEY}\\{clsid}");
        let class_key = with_step(
            "COMクラスのレジストリキー作成に失敗",
            create_registry_key(&clsid_path),
        )?;
        with_step(
            "COMクラス名の登録に失敗",
            set_registry_string(class_key.0, "", OsStr::new("Karukan Japanese Input")),
        )?;

        let server_key = with_step(
            "COMサーバーのレジストリキー作成に失敗",
            create_registry_key(&format!("{clsid_path}\\InprocServer32")),
        )?;
        with_step(
            "IME DLLのパス登録に失敗",
            set_registry_string(server_key.0, "", dll_path.as_os_str()),
        )?;
        with_step(
            "COMスレッドモデルの登録に失敗",
            set_registry_string(server_key.0, "ThreadingModel", OsStr::new("Apartment")),
        )?;
        Ok(())
    }

    fn delete_com_server_from(root: HKEY) -> windows::core::Result<()> {
        let path = wide(OsStr::new(&format!(
            "{TIP_REGISTRY_KEY}\\{}",
            guid_key(&CLSID_KARUKAN_TIP)
        )));
        let result = unsafe { RegDeleteTreeW(root, PCWSTR(path.as_ptr())) };
        if result.is_ok() || result == ERROR_FILE_NOT_FOUND || result == ERROR_PATH_NOT_FOUND {
            Ok(())
        } else {
            Err(result.into())
        }
    }

    fn install_default_config() -> Result<(), Box<dyn std::error::Error>> {
        let app_data =
            std::env::var_os("APPDATA").ok_or("APPDATA environment variable is not set")?;
        let config_file = PathBuf::from(app_data)
            .join("karukan")
            .join("karukan-im")
            .join("config")
            .join("config.toml");
        let config_dir = config_file
            .parent()
            .ok_or("configuration file has no parent directory")?;
        std::fs::create_dir_all(config_dir)?;

        match std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&config_file)
        {
            Ok(mut file) => {
                file.write_all(DEFAULT_CONFIG.as_bytes())?;
                println!("Default configuration created at {}", config_file.display());
            }
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
                if !config_file.is_file() {
                    return Err(format!(
                        "configuration path exists but is not a file: {}",
                        config_file.display()
                    )
                    .into());
                }
                println!(
                    "Existing configuration preserved at {}",
                    config_file.display()
                );
            }
            Err(error) => return Err(error.into()),
        }
        Ok(())
    }

    fn install_system_dictionary() -> io::Result<()> {
        let settings = Settings::load().map_err(io::Error::other)?;
        let dictionary = match settings.conversion.dict_path.as_deref() {
            Some(path) => PathBuf::from(path),
            None => Settings::data_dir()
                .ok_or_else(|| io::Error::other("could not determine the data directory"))?
                .join("dict.bin"),
        };
        if dictionary.is_file() {
            println!(
                "System dictionary already installed at {}",
                dictionary.display()
            );
            return Ok(());
        }

        let data_dir = dictionary
            .parent()
            .filter(|path| !path.as_os_str().is_empty())
            .unwrap_or_else(|| Path::new("."));
        std::fs::create_dir_all(data_dir)?;
        println!(
            "Downloading system dictionary from {DICTIONARY_URL} to {}",
            dictionary.display()
        );
        let timestamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_err(io::Error::other)?
            .as_nanos();
        let temp_dir =
            std::env::temp_dir().join(format!("karukan-dict-{}-{timestamp}", std::process::id()));
        std::fs::create_dir(&temp_dir)?;

        let result = (|| {
            let archive = temp_dir.join("dict.tgz");
            let download = Command::new("curl.exe")
                .args(["--fail", "--location", "--show-error", "--output"])
                .arg(&archive)
                .arg(DICTIONARY_URL)
                .status()?;
            if !download.success() {
                return Err(io::Error::other(format!(
                    "curl.exe exited with status {download}"
                )));
            }

            let extract = Command::new("tar.exe")
                .arg("-xzf")
                .arg(&archive)
                .arg("-C")
                .arg(&temp_dir)
                .arg("dict.bin")
                .status()?;
            if !extract.success() {
                return Err(io::Error::other(format!(
                    "tar.exe exited with status {extract}"
                )));
            }

            let extracted = temp_dir.join("dict.bin");
            if !extracted.is_file() {
                return Err(io::Error::other(
                    "downloaded archive did not contain dict.bin",
                ));
            }
            println!("Extracted dict.bin; installing to {}", dictionary.display());

            let mut staged_name = dictionary
                .file_name()
                .ok_or_else(|| io::Error::other("dictionary path has no file name"))?
                .to_os_string();
            staged_name.push(format!(".{timestamp}.tmp"));
            let staged = data_dir.join(staged_name);
            if let Err(error) = std::fs::copy(&extracted, &staged) {
                let _ = std::fs::remove_file(&staged);
                return Err(error);
            }
            match std::fs::rename(&staged, &dictionary) {
                Ok(()) => {}
                Err(_error) if dictionary.is_file() => {
                    std::fs::remove_file(&staged)?;
                    println!(
                        "System dictionary was installed by another process at {}",
                        dictionary.display()
                    );
                    return Ok(());
                }
                Err(error) => {
                    let _ = std::fs::remove_file(&staged);
                    return Err(error);
                }
            }
            println!("Installed system dictionary to {}", dictionary.display());
            Ok(())
        })();

        if let Err(error) = std::fs::remove_dir_all(&temp_dir) {
            eprintln!(
                "warning: failed to remove temporary directory {}: {error}",
                temp_dir.display()
            );
        }
        result
    }

    fn prefetch_models() -> Result<(), Box<dyn std::error::Error>> {
        let settings = Settings::load()?;
        let conversion = &settings.conversion;
        let model_keys: Vec<&str> = match conversion.strategy {
            StrategyMode::Light => vec![&conversion.light_model],
            StrategyMode::Main => vec![&conversion.model],
            StrategyMode::Adaptive => vec![&conversion.model, &conversion.light_model],
        };
        let mut prefetched = std::collections::HashSet::new();
        for key in model_keys {
            if !prefetched.insert(key) {
                continue;
            }
            let source: ModelSource = settings.model_source(key)?;
            println!("Downloading or checking model '{key}' and tokenizer.json...");
            let (model, tokenizer) = source.resolve().map_err(|error| {
                io::Error::other(format!("failed to prepare model '{key}': {error}"))
            })?;
            println!("Model '{key}' ready: {}", model.display());
            println!("Tokenizer for '{key}' ready: {}", tokenizer.display());
        }
        Ok(())
    }

    fn copy_dll_to_install_dir(source: &Path, install_dir: &Path) -> io::Result<PathBuf> {
        if let Ok(entries) = std::fs::read_dir(install_dir) {
            for entry in entries.flatten() {
                let path = entry.path();
                if let Some(name) = path.file_name().and_then(|n| n.to_str()) {
                    if name.starts_with("karukan_im_windows-") && name.ends_with(".dll") {
                        let _ = std::fs::remove_file(&path);
                    }
                }
            }
        }
        let mut source = std::fs::File::open(source)?;
        let timestamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_err(io::Error::other)?
            .as_nanos();
        let installed_dll = install_dir.join(format!(
            "karukan_im_windows-{}-{timestamp}.dll",
            std::process::id()
        ));
        let mut destination = std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&installed_dll)?;
        let copy_result = io::copy(&mut source, &mut destination);
        if let Err(error) = copy_result {
            drop(destination);
            if let Err(cleanup_error) = std::fs::remove_file(&installed_dll) {
                eprintln!(
                    "failed to remove incomplete DLL {}: {cleanup_error}",
                    installed_dll.display()
                );
            }
            return Err(error);
        }
        Ok(installed_dll)
    }

    fn delete_com_server() -> windows::core::Result<()> {
        delete_com_server_from(HKEY_LOCAL_MACHINE)?;
        delete_com_server_from(HKEY_CURRENT_USER)
    }

    fn delete_tsf_registration() -> windows::core::Result<()> {
        let path = wide(OsStr::new(&format!(
            "{TSF_TIP_REGISTRY_KEY}\\{}",
            guid_key(&CLSID_KARUKAN_TIP)
        )));
        let result = unsafe { RegDeleteTreeW(HKEY_CURRENT_USER, PCWSTR(path.as_ptr())) };
        if result.is_ok() || result == ERROR_FILE_NOT_FOUND || result == ERROR_PATH_NOT_FOUND {
            Ok(())
        } else {
            Err(result.into())
        }
    }

    fn register_profile(dll_path: &Path) -> Result<(), Box<dyn std::error::Error>> {
        unsafe {
            let _: windows::core::IUnknown = with_step(
                "Karukan TSF DLLのCOM起動に失敗",
                CoCreateInstance(&CLSID_KARUKAN_TIP, None, CLSCTX_INPROC_SERVER),
            )?;
            let profiles: ITfInputProcessorProfiles = with_step(
                "TSFプロファイル管理サービスの起動に失敗",
                CoCreateInstance(&CLSID_TF_InputProcessorProfiles, None, CLSCTX_INPROC_SERVER),
            )?;
            let categories: ITfCategoryMgr = with_step(
                "TSFカテゴリ管理サービスの起動に失敗",
                CoCreateInstance(&CLSID_TF_CategoryMgr, None, CLSCTX_INPROC_SERVER),
            )?;
            with_step(
                "TSFへの入力プロセッサ登録に失敗",
                profiles.Register(&CLSID_KARUKAN_TIP),
            )?;
            with_step(
                "キーボード入力カテゴリの登録に失敗",
                categories.RegisterCategory(
                    &CLSID_KARUKAN_TIP,
                    &GUID_TFCAT_TIP_KEYBOARD,
                    &CLSID_KARUKAN_TIP,
                ),
            )?;
            with_step(
                "システムトレイサポートカテゴリの登録に失敗",
                categories.RegisterCategory(
                    &CLSID_KARUKAN_TIP,
                    &GUID_TFCAT_TIPCAP_SYSTRAYSUPPORT,
                    &CLSID_KARUKAN_TIP,
                ),
            )?;
            with_step(
                "イマーシブサポートカテゴリの登録に失敗",
                categories.RegisterCategory(
                    &CLSID_KARUKAN_TIP,
                    &GUID_TFCAT_TIPCAP_IMMERSIVESUPPORT,
                    &CLSID_KARUKAN_TIP,
                ),
            )?;
            with_step(
                "入力モードコンパートメントカテゴリの登録に失敗",
                categories.RegisterCategory(
                    &CLSID_KARUKAN_TIP,
                    &GUID_TFCAT_TIPCAP_INPUTMODECOMPARTMENT,
                    &CLSID_KARUKAN_TIP,
                ),
            )?;
            with_step(
                "UIエレメントカテゴリの登録に失敗",
                categories.RegisterCategory(
                    &CLSID_KARUKAN_TIP,
                    &GUID_TFCAT_TIPCAP_UIELEMENTENABLED,
                    &CLSID_KARUKAN_TIP,
                ),
            )?;
            with_step(
                "セキュアモードカテゴリの登録に失敗",
                categories.RegisterCategory(
                    &CLSID_KARUKAN_TIP,
                    &GUID_TFCAT_TIPCAP_SECUREMODE,
                    &CLSID_KARUKAN_TIP,
                ),
            )?;
            with_step(
                "COMレスカテゴリの登録に失敗",
                categories.RegisterCategory(
                    &CLSID_KARUKAN_TIP,
                    &GUID_TFCAT_TIPCAP_COMLESS,
                    &CLSID_KARUKAN_TIP,
                ),
            )?;
            with_step(
                "表示属性プロバイダカテゴリの登録に失敗",
                categories.RegisterCategory(
                    &CLSID_KARUKAN_TIP,
                    &GUID_TFCAT_DISPLAYATTRIBUTEPROVIDER,
                    &CLSID_KARUKAN_TIP,
                ),
            )?;
            let description: Vec<u16> = "Karukan Japanese Input"
                .encode_utf16()
                .chain(Some(0))
                .collect();
            let icon_file = wide(dll_path.as_os_str());
            let mgr: ITfInputProcessorProfileMgr = profiles.cast()?;
            let hkl = LoadKeyboardLayoutW(w!("00000411"), KLF_NOTELLSHELL)
                .unwrap_or(HKL(std::ptr::null_mut()));
            with_step(
                "日本語の言語プロファイル登録 (RegisterProfile) に失敗",
                mgr.RegisterProfile(
                    &CLSID_KARUKAN_TIP,
                    LANGUAGE_ID_JAPANESE,
                    &GUID_KARUKAN_PROFILE,
                    &description,
                    &icon_file,
                    0,
                    hkl,
                    0,
                    true,
                    0,
                ),
            )?;
            with_step(
                "日本語の言語プロファイル有効化に失敗",
                profiles.EnableLanguageProfile(
                    &CLSID_KARUKAN_TIP,
                    LANGUAGE_ID_JAPANESE,
                    &GUID_KARUKAN_PROFILE,
                    true,
                ),
            )?;
            let _ = mgr.ActivateProfile(
                TF_PROFILETYPE_INPUTPROCESSOR,
                LANGUAGE_ID_JAPANESE,
                &CLSID_KARUKAN_TIP,
                &GUID_KARUKAN_PROFILE,
                hkl,
                TF_IPPMF_FORSESSION,
            );
            let _ = profiles.ActivateLanguageProfile(
                &CLSID_KARUKAN_TIP,
                LANGUAGE_ID_JAPANESE,
                &GUID_KARUKAN_PROFILE,
            );
        }
        Ok(())
    }

    fn warn_profile_error(step: &str, result: windows::core::Result<()>) {
        if let Err(error) = result {
            eprintln!("警告: {step} (HRESULT 0x{:08X}): {error}", error.code().0);
        }
    }

    fn unregister_profile() -> Result<(), Box<dyn std::error::Error>> {
        unsafe {
            let profiles: ITfInputProcessorProfiles = with_step(
                "TSFプロファイル管理サービスの起動に失敗",
                CoCreateInstance(&CLSID_TF_InputProcessorProfiles, None, CLSCTX_INPROC_SERVER),
            )?;
            let categories: ITfCategoryMgr = with_step(
                "TSFカテゴリ管理サービスの起動に失敗",
                CoCreateInstance(&CLSID_TF_CategoryMgr, None, CLSCTX_INPROC_SERVER),
            )?;
            warn_profile_error(
                "日本語の言語プロファイル無効化に失敗",
                profiles.EnableLanguageProfile(
                    &CLSID_KARUKAN_TIP,
                    LANGUAGE_ID_JAPANESE,
                    &GUID_KARUKAN_PROFILE,
                    false,
                ),
            );
            if let Ok(mgr) = profiles.cast::<ITfInputProcessorProfileMgr>() {
                warn_profile_error(
                    "日本語の言語プロファイル登録解除 (UnregisterProfile) に失敗",
                    mgr.UnregisterProfile(&CLSID_KARUKAN_TIP, LANGUAGE_ID_JAPANESE, &GUID_KARUKAN_PROFILE, 0),
                );
            }
            warn_profile_error(
                "日本語の言語プロファイル削除に失敗",
                profiles.RemoveLanguageProfile(
                    &CLSID_KARUKAN_TIP,
                    LANGUAGE_ID_JAPANESE,
                    &GUID_KARUKAN_PROFILE,
                ),
            );
            warn_profile_error(
                "キーボード入力カテゴリの解除に失敗",
                categories.UnregisterCategory(
                    &CLSID_KARUKAN_TIP,
                    &GUID_TFCAT_TIP_KEYBOARD,
                    &CLSID_KARUKAN_TIP,
                ),
            );
            warn_profile_error(
                "システムトレイサポートカテゴリの解除に失敗",
                categories.UnregisterCategory(
                    &CLSID_KARUKAN_TIP,
                    &GUID_TFCAT_TIPCAP_SYSTRAYSUPPORT,
                    &CLSID_KARUKAN_TIP,
                ),
            );
            warn_profile_error(
                "イマーシブサポートカテゴリの解除に失敗",
                categories.UnregisterCategory(
                    &CLSID_KARUKAN_TIP,
                    &GUID_TFCAT_TIPCAP_IMMERSIVESUPPORT,
                    &CLSID_KARUKAN_TIP,
                ),
            );
            warn_profile_error(
                "入力モードコンパートメントカテゴリの解除に失敗",
                categories.UnregisterCategory(
                    &CLSID_KARUKAN_TIP,
                    &GUID_TFCAT_TIPCAP_INPUTMODECOMPARTMENT,
                    &CLSID_KARUKAN_TIP,
                ),
            );
            warn_profile_error(
                "UIエレメントカテゴリの解除に失敗",
                categories.UnregisterCategory(
                    &CLSID_KARUKAN_TIP,
                    &GUID_TFCAT_TIPCAP_UIELEMENTENABLED,
                    &CLSID_KARUKAN_TIP,
                ),
            );
            warn_profile_error(
                "セキュアモードカテゴリの解除に失敗",
                categories.UnregisterCategory(
                    &CLSID_KARUKAN_TIP,
                    &GUID_TFCAT_TIPCAP_SECUREMODE,
                    &CLSID_KARUKAN_TIP,
                ),
            );
            warn_profile_error(
                "COMレスカテゴリの解除に失敗",
                categories.UnregisterCategory(
                    &CLSID_KARUKAN_TIP,
                    &GUID_TFCAT_TIPCAP_COMLESS,
                    &CLSID_KARUKAN_TIP,
                ),
            );
            // 過去バージョンで登録された誤ったGUIDのクリーンアップ
            let _ = categories.UnregisterCategory(
                &CLSID_KARUKAN_TIP,
                &OLD_BOGUS_SYSTRAYSUPPORT,
                &CLSID_KARUKAN_TIP,
            );
            let _ = categories.UnregisterCategory(
                &CLSID_KARUKAN_TIP,
                &OLD_BOGUS_IMMERSIVESUPPORT,
                &CLSID_KARUKAN_TIP,
            );
            let _ = categories.UnregisterCategory(
                &CLSID_KARUKAN_TIP,
                &OLD_BOGUS_UIELEMENTENABLED,
                &CLSID_KARUKAN_TIP,
            );
            warn_profile_error(
                "表示属性プロバイダカテゴリの解除に失敗",
                categories.UnregisterCategory(
                    &CLSID_KARUKAN_TIP,
                    &GUID_TFCAT_DISPLAYATTRIBUTEPROVIDER,
                    &CLSID_KARUKAN_TIP,
                ),
            );
            warn_profile_error(
                "TSF入力プロセッサの解除に失敗",
                profiles.Unregister(&CLSID_KARUKAN_TIP),
            );
            with_step("残存するTSF登録情報の削除に失敗", delete_tsf_registration())?;
        }
        Ok(())
    }

    unsafe { CoInitializeEx(None, COINIT_APARTMENTTHREADED).ok()? };
    let _apartment = ComApartment;

    let command = std::env::args().nth(1).unwrap_or_default();
    let local_app_data =
        std::env::var_os("LOCALAPPDATA").ok_or("LOCALAPPDATA environment variable is not set")?;
    let install_dir = PathBuf::from(local_app_data).join(INSTALL_DIRECTORY);

    match command.as_str() {
        "install" => {
            let source_dll = std::env::current_exe()?
                .parent()
                .ok_or("installer has no parent directory")?
                .join("karukan_im_windows.dll");
            if !source_dll.is_file() {
                return Err(
                    format!("DLL not found beside installer: {}", source_dll.display()).into(),
                );
            }
            let source_settings_app = source_dll
                .parent()
                .ok_or("installer has no parent directory")?
                .join("karukan-settings.exe");
            if !source_settings_app.is_file() {
                return Err(format!(
                    "Settings app not found beside installer: {}",
                    source_settings_app.display()
                )
                .into());
            }
            std::fs::create_dir_all(&install_dir)?;
            let installed_dll = copy_dll_to_install_dir(&source_dll, &install_dir)?;
            let installed_settings_app = install_dir.join("karukan-settings.exe");
            let _ = std::fs::copy(&source_settings_app, &installed_settings_app);
            install_default_config()?;
            install_system_dictionary().map_err(|error| {
                io::Error::new(
                    error.kind(),
                    format!(
                        "システム辞書をインストールできませんでした。\
                         保存先を確認して再実行してください: {error}"
                    ),
                )
            })?;
            karukan_im_windows::configure_model_cache().map_err(io::Error::other)?;
            prefetch_models().map_err(|error| {
                io::Error::other(format!(
                    "変換モデルまたはtokenizer.jsonを取得できませんでした: {error}"
                ))
            })?;
            with_step(
                "前回の不完全なTSF登録情報の削除に失敗",
                delete_tsf_registration(),
            )?;
            if let Err(error) = register_com_server(&installed_dll) {
                if let Err(cleanup_error) = delete_com_server() {
                    eprintln!("failed to roll back COM registration: {cleanup_error}");
                }
                return Err(error);
            }
            if let Err(error) = register_profile(&installed_dll) {
                if let Err(cleanup_error) = unregister_profile() {
                    eprintln!("TSFプロファイルのロールバックに失敗: {cleanup_error}");
                }
                if let Err(cleanup_error) = delete_com_server() {
                    eprintln!("COM登録のロールバックに失敗: {cleanup_error}");
                }
                return Err(error);
            }
            println!(
                "Karukan is registered for Japanese. Select it from Windows Settings > Time & language > Language & region."
            );
            println!(
                "The installed DLL is {}. Older DLLs can be deleted after signing out.",
                installed_dll.display()
            );
            println!(
                "The settings app is installed at {}.",
                installed_settings_app.display()
            );

            // Karukan を既定の入力方式に設定
            let tip = format!("0411:{{{}}}{{{}}}", guid_key(&CLSID_KARUKAN_TIP), guid_key(&GUID_KARUKAN_PROFILE));
            let _ = Command::new("powershell.exe")
                .args(["-NoProfile", "-Command", &format!("Set-WinDefaultInputMethodOverride -InputTip '{tip}'")])
                .status();
        }
        "uninstall" => {
            let _ = Command::new("powershell.exe")
                .args(["-NoProfile", "-Command", "Set-WinDefaultInputMethodOverride -InputTip ''"])
                .status();
            unregister_profile()?;
            delete_com_server()?;
            println!("Karukan was removed from the current user's TSF profiles.");
            println!(
                "DLL files remain in {} so they can be deleted after signing out.",
                install_dir.display()
            );
        }
        _ => {
            return Err("Usage: install.exe <install|uninstall>".into());
        }
    }
    Ok(())
}

#[cfg(not(windows))]
fn main() {
    eprintln!("The Karukan TSF installer can only run on Windows.");
    std::process::exit(1);
}
