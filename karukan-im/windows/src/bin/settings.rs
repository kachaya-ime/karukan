#![cfg_attr(windows, windows_subsystem = "windows")]

#[cfg(windows)]
mod app {
    use std::cell::RefCell;
    use std::ffi::c_void;
    use std::fs::{self, OpenOptions};
    use std::io::Write;
    use std::os::windows::ffi::OsStrExt;
    use std::path::PathBuf;

    use chrono::{DateTime, Local};
    use karukan_im::config::Settings;
    use toml::Value;
    use windows::Win32::Foundation::{HINSTANCE, HWND, LPARAM, LRESULT, RECT, WPARAM};
    use windows::Win32::Graphics::Gdi::{COLOR_WINDOW, GetSysColorBrush, UpdateWindow};
    use windows::Win32::Storage::FileSystem::{
        MOVEFILE_REPLACE_EXISTING, MOVEFILE_WRITE_THROUGH, MoveFileExW,
    };
    use windows::Win32::System::LibraryLoader::GetModuleHandleW;
    use windows::Win32::UI::Controls::{
        ICC_LISTVIEW_CLASSES, INITCOMMONCONTROLSEX, InitCommonControlsEx, LVCF_TEXT, LVCF_WIDTH,
        LVCFMT_LEFT, LVCOLUMNW, LVIF_TEXT, LVIS_SELECTED, LVITEMW, LVM_DELETEALLITEMS,
        LVM_GETNEXTITEM, LVM_INSERTCOLUMNW, LVM_INSERTITEMW, LVM_SETCOLUMNW, LVM_SETCOLUMNWIDTH,
        LVM_SETEXTENDEDLISTVIEWSTYLE, LVM_SETITEMTEXTW, LVN_COLUMNCLICK, LVN_ITEMCHANGED,
        LVNI_SELECTED, LVS_EX_FULLROWSELECT, LVS_REPORT, LVS_SHOWSELALWAYS, LVS_SINGLESEL,
        NMLISTVIEW, SetScrollInfo,
    };
    use windows::Win32::UI::WindowsAndMessaging::{
        BM_GETCHECK, BM_SETCHECK, BN_CLICKED, BS_AUTOCHECKBOX, BS_AUTORADIOBUTTON, BS_PUSHBUTTON,
        CW_USEDEFAULT, CreateWindowExW, DefWindowProcW, DispatchMessageW, ES_AUTOHSCROLL,
        ES_AUTOVSCROLL, ES_MULTILINE, ES_READONLY, ES_WANTRETURN, GetClientRect, GetMessageW,
        GetScrollInfo, GetWindowTextLengthW, GetWindowTextW, IDYES, LB_ADDSTRING, LB_GETCURSEL,
        LB_SETCURSEL, LBN_SELCHANGE, LBS_NOTIFY, MB_ICONERROR, MB_ICONWARNING, MB_OK, MB_YESNO,
        MSG, MessageBoxW, MoveWindow, PostQuitMessage, RegisterClassW, SB_LINEDOWN, SB_LINEUP,
        SB_PAGEDOWN, SB_PAGEUP, SB_THUMBPOSITION, SB_THUMBTRACK, SB_TOP, SB_VERT, SCROLLINFO,
        SIF_ALL, SIF_PAGE, SIF_POS, SIF_RANGE, SendMessageW, SetWindowTextW, ShowWindow,
        TranslateMessage, WINDOW_EX_STYLE, WINDOW_STYLE, WM_COMMAND, WM_CREATE, WM_CTLCOLORBTN,
        WM_CTLCOLORSTATIC, WM_DESTROY, WM_MOUSEWHEEL, WM_NOTIFY, WM_SIZE, WM_VSCROLL, WNDCLASSW,
        WS_BORDER, WS_CHILD, WS_CLIPCHILDREN, WS_GROUP, WS_OVERLAPPEDWINDOW, WS_VISIBLE,
        WS_VSCROLL,
    };
    use windows::core::{PCWSTR, PWSTR, w};

    const CLASS_NAME: windows::core::PCWSTR = w!("KarukanSettingsWindow");
    const SETTINGS_PANEL_CLASS: windows::core::PCWSTR = w!("KarukanSettingsPanel");
    const SETTINGS_CONTENT_CLASS: windows::core::PCWSTR = w!("KarukanSettingsContent");
    const SETTINGS_PANEL_ID: usize = 1014;
    const SETTINGS_CONTENT_ID: usize = 1015;
    const CATEGORY_ID: usize = 1001;
    const VALUE_LABEL_ID: usize = 1003;
    const APPLY_ID: usize = 1007;
    const RELOAD_ID: usize = 1008;
    const SAVE_ID: usize = 1009;
    const STATUS_ID: usize = 1010;
    const DELETE_LEARNING_ID: usize = 1011;
    const CLEAR_LEARNING_ID: usize = 1012;
    const LEARNING_GRID_ID: usize = 1013;
    const USER_DICTIONARY_GRID_ID: usize = 1016;
    const USER_READING_ID: usize = 1017;
    const USER_SURFACE_ID: usize = 1018;
    const USER_POS_ID: usize = 1019;
    const USER_COMMENT_ID: usize = 1020;
    const USER_READING_LABEL_ID: usize = 1021;
    const USER_SURFACE_LABEL_ID: usize = 1022;
    const USER_POS_LABEL_ID: usize = 1023;
    const USER_COMMENT_LABEL_ID: usize = 1024;
    const LICENSE_TEXT_ID: usize = 1025;
    const SETTING_CONTROL_ID_BASE: usize = 2000;
    const SETTING_INPUT_HEIGHT: i32 = 28;
    const LICENSE_TEXT: &str = concat!(
        "Karukan is licensed under either of the following licenses, at your option.\r\n\r\n",
        "==================== MIT License ====================\r\n\r\n",
        include_str!("../../../../LICENSE-MIT"),
        "\r\n\r\n==================== Apache License 2.0 ====================\r\n\r\n",
        include_str!("../../../../LICENSE-APACHE"),
    );

    const SECTIONS: &[(&str, &str)] = &[
        ("conversion", "変換"),
        ("learning", "学習"),
        ("display", "表示"),
        ("symbol", "記号・空白"),
        ("width", "文字幅"),
        ("date", "日付・時刻"),
        ("models", "変換モデル"),
        ("learning_records", "学習履歴"),
        ("user_dictionary", "ユーザー辞書"),
        ("license", "ライセンス"),
    ];

    #[derive(Clone)]
    struct LearningRecord {
        reading: String,
        surface: String,
        frequency: u32,
        last_access: u64,
        order: usize,
    }

    #[derive(Clone)]
    struct UserDictionaryEntry {
        reading: String,
        surface: String,
        part_of_speech: String,
        comment: String,
    }

    enum SettingInput {
        Edit {
            multiline: bool,
        },
        Radio {
            options: Vec<String>,
            buttons: Vec<HWND>,
        },
        Check,
    }

    struct SettingControl {
        section: String,
        key: String,
        label: HWND,
        input: HWND,
        kind: SettingInput,
    }

    struct AppState {
        config: Value,
        category: usize,
        selected_learning: Option<usize>,
        learning_records: Vec<LearningRecord>,
        learning_sort_column: usize,
        learning_sort_descending: bool,
        user_dictionary_entries: Vec<UserDictionaryEntry>,
        selected_user_dictionary_entry: Option<usize>,
        user_dictionary_loaded: bool,
        setting_controls: Vec<SettingControl>,
        settings_panel: HWND,
        settings_content: HWND,
        categories: HWND,
        learning_grid: HWND,
        user_dictionary_grid: HWND,
        user_reading: HWND,
        user_surface: HWND,
        user_part_of_speech: HWND,
        user_comment: HWND,
        license_text: HWND,
        value_label: HWND,
        apply: HWND,
        delete_learning: HWND,
        clear_learning: HWND,
        status: HWND,
    }

    fn setting_input_height(kind: &SettingInput) -> i32 {
        match kind {
            SettingInput::Edit { multiline: true } => 96,
            _ => SETTING_INPUT_HEIGHT,
        }
    }

    fn radio_option_width(option: &str) -> i32 {
        let text_width = option
            .chars()
            .map(|character| if character.is_ascii() { 8 } else { 16 })
            .sum::<i32>();
        (text_width + 28).max(56)
    }

    thread_local! {
        static APP_STATE: RefCell<Option<AppState>> = const { RefCell::new(None) };
    }

    fn wide(value: &str) -> Vec<u16> {
        value.encode_utf16().chain(Some(0)).collect()
    }

    fn send(hwnd: HWND, message: u32, wparam: usize, lparam: isize) -> isize {
        unsafe { SendMessageW(hwnd, message, Some(WPARAM(wparam)), Some(LPARAM(lparam))).0 }
    }

    fn set_text(hwnd: HWND, text: &str) {
        let text = wide(text);
        if let Err(error) = unsafe { SetWindowTextW(hwnd, PCWSTR(text.as_ptr())) } {
            eprintln!("Could not update settings control: {error}");
        }
    }

    fn control_text(hwnd: HWND) -> Result<String, String> {
        let length = unsafe { GetWindowTextLengthW(hwnd) };
        if length < 0 {
            return Err("入力値の長さを取得できませんでした".to_string());
        }
        let mut buffer = vec![0u16; length as usize + 1];
        let copied = unsafe { GetWindowTextW(hwnd, &mut buffer) };
        if copied < 0 {
            return Err("入力値を取得できませんでした".to_string());
        }
        Ok(String::from_utf16_lossy(&buffer[..copied as usize]))
    }

    fn add_item(hwnd: HWND, message: u32, text: &str) {
        let text = wide(text);
        if send(hwnd, message, 0, text.as_ptr() as isize) < 0 {
            eprintln!("Could not add an item to a settings list.");
        }
    }

    fn add_learning_column(
        grid: HWND,
        index: usize,
        title: &str,
        width: i32,
    ) -> windows::core::Result<()> {
        let text = wide(title);
        let mut column = LVCOLUMNW {
            mask: LVCF_TEXT | LVCF_WIDTH,
            fmt: LVCFMT_LEFT,
            cx: width,
            pszText: PWSTR(text.as_ptr() as *mut u16),
            ..Default::default()
        };
        if send(
            grid,
            LVM_INSERTCOLUMNW,
            index,
            &mut column as *mut LVCOLUMNW as isize,
        ) < 0
        {
            return Err(windows::core::Error::new(
                windows::core::HRESULT(0x80004005u32 as i32),
                format!("学習履歴の列「{title}」を作成できませんでした"),
            ));
        }
        Ok(())
    }

    fn sort_learning_records(records: &mut [LearningRecord], column: usize, descending: bool) {
        records.sort_by(|left, right| {
            let ordering = match column {
                0 => left.reading.cmp(&right.reading),
                1 => left.surface.cmp(&right.surface),
                2 => left.frequency.cmp(&right.frequency),
                3 => left.last_access.cmp(&right.last_access),
                _ => std::cmp::Ordering::Equal,
            };
            let ordering = if descending {
                ordering.reverse()
            } else {
                ordering
            };
            ordering.then_with(|| left.order.cmp(&right.order))
        });
    }

    fn update_learning_sort_headers(state: &AppState) {
        let titles = ["読み", "表記", "選択回数", "最終選択"];
        for (index, title) in titles.iter().enumerate() {
            let title = if index == state.learning_sort_column {
                format!(
                    "{title} {}",
                    if state.learning_sort_descending {
                        "▼"
                    } else {
                        "▲"
                    }
                )
            } else {
                (*title).to_string()
            };
            let text = wide(&title);
            let mut column = LVCOLUMNW {
                mask: LVCF_TEXT,
                pszText: PWSTR(text.as_ptr() as *mut u16),
                ..Default::default()
            };
            if send(
                state.learning_grid,
                LVM_SETCOLUMNW,
                index,
                &mut column as *mut LVCOLUMNW as isize,
            ) == 0
            {
                eprintln!("Could not update learning-history sort header {index}.");
            }
        }
    }

    fn selected_learning_indices(grid: HWND) -> Vec<usize> {
        let mut selected = Vec::new();
        let mut start = -1isize;
        loop {
            let index = send(
                grid,
                LVM_GETNEXTITEM,
                start as usize,
                LVNI_SELECTED as isize,
            );
            if index < 0 {
                break;
            }
            selected.push(index as usize);
            start = index;
        }
        selected
    }

    fn learning_timestamp(record: &LearningRecord) -> String {
        i64::try_from(record.last_access)
            .ok()
            .and_then(|timestamp| DateTime::from_timestamp(timestamp, 0))
            .map(|timestamp| {
                timestamp
                    .with_timezone(&Local)
                    .format("%Y-%m-%d %H:%M")
                    .to_string()
            })
            .unwrap_or_else(|| "不明".to_string())
    }

    fn populate_learning_grid(state: &AppState) {
        let _ = send(state.learning_grid, LVM_DELETEALLITEMS, 0, 0);
        for (index, record) in state.learning_records.iter().enumerate() {
            let values = [
                record.reading.clone(),
                record.surface.clone(),
                record.frequency.to_string(),
                learning_timestamp(record),
            ];
            let mut failed = false;
            for (column, value) in values.iter().enumerate() {
                let text = wide(value);
                let mut item = LVITEMW {
                    mask: LVIF_TEXT,
                    iItem: index as i32,
                    iSubItem: column as i32,
                    pszText: PWSTR(text.as_ptr() as *mut u16),
                    ..Default::default()
                };
                let result = if column == 0 {
                    send(
                        state.learning_grid,
                        LVM_INSERTITEMW,
                        0,
                        &mut item as *mut LVITEMW as isize,
                    )
                } else {
                    send(
                        state.learning_grid,
                        LVM_SETITEMTEXTW,
                        index,
                        &mut item as *mut LVITEMW as isize,
                    )
                };
                if (column == 0 && result < 0) || (column > 0 && result == 0) {
                    eprintln!("Could not add learning-history row {index}, column {column}.");
                    failed = true;
                    break;
                }
            }
            if failed {
                break;
            }
        }
    }

    fn populate_user_dictionary_grid(state: &AppState) {
        let _ = send(state.user_dictionary_grid, LVM_DELETEALLITEMS, 0, 0);
        for (index, entry) in state.user_dictionary_entries.iter().enumerate() {
            let values = [
                entry.reading.as_str(),
                entry.surface.as_str(),
                entry.part_of_speech.as_str(),
                entry.comment.as_str(),
            ];
            let mut failed = false;
            for (column, value) in values.iter().enumerate() {
                let text = wide(value);
                let mut item = LVITEMW {
                    mask: LVIF_TEXT,
                    iItem: index as i32,
                    iSubItem: column as i32,
                    pszText: PWSTR(text.as_ptr() as *mut u16),
                    ..Default::default()
                };
                let result = if column == 0 {
                    send(
                        state.user_dictionary_grid,
                        LVM_INSERTITEMW,
                        0,
                        &mut item as *mut LVITEMW as isize,
                    )
                } else {
                    send(
                        state.user_dictionary_grid,
                        LVM_SETITEMTEXTW,
                        index,
                        &mut item as *mut LVITEMW as isize,
                    )
                };
                if (column == 0 && result < 0) || (column > 0 && result == 0) {
                    eprintln!("Could not add user-dictionary row {index}, column {column}.");
                    failed = true;
                    break;
                }
            }
            if failed {
                break;
            }
        }
    }

    fn user_dictionary_path() -> Result<PathBuf, String> {
        Settings::user_dict_dir()
            .map(|dir| dir.join("karukan-user.tsv"))
            .ok_or_else(|| "ユーザー辞書の保存先を特定できません".to_string())
    }

    fn load_user_dictionary_entries() -> Result<Vec<UserDictionaryEntry>, String> {
        let path = user_dictionary_path()?;
        let content = match fs::read_to_string(&path) {
            Ok(content) => content,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                return Ok(Vec::new());
            }
            Err(error) => {
                return Err(format!("{} を読み込めません: {error}", path.display()));
            }
        };
        Ok(content
            .lines()
            .filter_map(|line| {
                let line = line.trim_end_matches('\r');
                if line.trim().is_empty() || line.trim_start().starts_with('#') {
                    return None;
                }
                let mut columns = line.split('\t');
                let reading = columns.next()?.trim();
                let surface = columns.next()?.trim();
                if reading.is_empty() || surface.is_empty() {
                    return None;
                }
                Some(UserDictionaryEntry {
                    reading: reading.to_string(),
                    surface: surface.to_string(),
                    part_of_speech: columns.next().unwrap_or_default().to_string(),
                    comment: columns.next().unwrap_or_default().to_string(),
                })
            })
            .collect())
    }

    fn save_user_dictionary_entries(entries: &[UserDictionaryEntry]) -> Result<(), String> {
        let path = user_dictionary_path()?;
        let parent = path
            .parent()
            .ok_or_else(|| "ユーザー辞書の保存先フォルダーが不正です".to_string())?;
        fs::create_dir_all(parent)
            .map_err(|error| format!("ユーザー辞書フォルダーを作成できません: {error}"))?;
        let temp_path = path.with_file_name(format!(
            "karukan-user.tsv.{}.{}.tmp",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map_err(|error| format!("一時ファイル名を作成できません: {error}"))?
                .as_nanos()
        ));
        let result: Result<(), String> = (|| {
            let mut file = OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&temp_path)
                .map_err(|error| format!("一時辞書ファイルを作成できません: {error}"))?;
            writeln!(file, "# karukan user dictionary (Mozc/Google IME TSV)")
                .map_err(|error| format!("一時辞書ファイルを書き込めません: {error}"))?;
            writeln!(file, "# 読み\t表記\t品詞\tコメント")
                .map_err(|error| format!("一時辞書ファイルを書き込めません: {error}"))?;
            for entry in entries {
                writeln!(
                    file,
                    "{}\t{}\t{}\t{}",
                    entry.reading, entry.surface, entry.part_of_speech, entry.comment
                )
                .map_err(|error| format!("一時辞書ファイルを書き込めません: {error}"))?;
            }
            file.flush()
                .map_err(|error| format!("一時辞書ファイルを保存できません: {error}"))?;
            file.sync_all()
                .map_err(|error| format!("一時辞書ファイルを同期できません: {error}"))?;
            let from: Vec<u16> = temp_path.as_os_str().encode_wide().chain(Some(0)).collect();
            let to: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
            unsafe {
                MoveFileExW(
                    PCWSTR(from.as_ptr()),
                    PCWSTR(to.as_ptr()),
                    MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
                )
            }
            .map_err(|error| format!("ユーザー辞書を置き換えられません: {error}"))?;
            Ok(())
        })();
        match result {
            Err(error) if temp_path.exists() => {
                if let Err(cleanup_error) = fs::remove_file(&temp_path) {
                    return Err(format!(
                        "{error}。一時ファイルも削除できませんでした ({}): {cleanup_error}",
                        temp_path.display()
                    ));
                }
                Err(error)
            }
            _ => result,
        }
    }

    fn config_path() -> Result<PathBuf, String> {
        Settings::config_file().ok_or_else(|| "設定ファイルの保存先を特定できません".to_string())
    }

    fn load_config() -> Result<Value, String> {
        let path = config_path()?;
        let content = fs::read_to_string(&path)
            .map_err(|error| format!("{} を読み込めません: {error}", path.display()))?;
        let mut config: Value = toml::from_str(&content)
            .map_err(|error| format!("設定ファイルの形式が不正です: {error}"))?;
        if let Some(conversion) = config.get_mut("conversion").and_then(Value::as_table_mut) {
            conversion
                .entry("dict_path".to_string())
                .or_insert_with(|| Value::String(String::new()));
        }
        Ok(config)
    }

    fn learning_path() -> Result<PathBuf, String> {
        Settings::learning_file()
            .ok_or_else(|| "学習履歴ファイルの保存先を特定できません".to_string())
    }

    fn load_learning_records() -> Result<Vec<LearningRecord>, String> {
        let path = learning_path()?;
        let content = match fs::read_to_string(&path) {
            Ok(content) => content,
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                return Ok(Vec::new());
            }
            Err(error) => return Err(format!("{} を読み込めません: {error}", path.display())),
        };
        let mut records = Vec::new();
        for (order, line) in content.lines().enumerate() {
            let line = line.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let mut fields = line.split('\t');
            let (Some(reading), Some(surface), Some(frequency), Some(last_access)) =
                (fields.next(), fields.next(), fields.next(), fields.next())
            else {
                continue;
            };
            let (Ok(frequency), Ok(last_access)) =
                (frequency.parse::<u32>(), last_access.parse::<u64>())
            else {
                continue;
            };
            records.push(LearningRecord {
                reading: reading.to_string(),
                surface: surface.to_string(),
                frequency,
                last_access,
                order,
            });
        }
        records.sort_by(|left, right| {
            right
                .last_access
                .cmp(&left.last_access)
                .then_with(|| left.reading.cmp(&right.reading))
                .then_with(|| left.surface.cmp(&right.surface))
        });
        Ok(records)
    }

    fn save_learning_records(
        path: &std::path::Path,
        records: &[LearningRecord],
    ) -> Result<(), String> {
        let parent = path
            .parent()
            .ok_or_else(|| "学習履歴ファイルの保存先フォルダーが不正です".to_string())?;
        fs::create_dir_all(parent)
            .map_err(|error| format!("学習履歴フォルダーを作成できません: {error}"))?;
        let temp_path = path.with_file_name(format!(
            "learning.tsv.{}.{}.tmp",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map_err(|error| format!("一時ファイル名を作成できません: {error}"))?
                .as_nanos()
        ));
        let result: Result<(), String> = (|| {
            let mut sorted = records.to_vec();
            sorted.sort_by(|left, right| {
                left.reading
                    .cmp(&right.reading)
                    .then_with(|| left.order.cmp(&right.order))
            });
            let mut file = OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&temp_path)
                .map_err(|error| format!("一時履歴ファイルを作成できません: {error}"))?;
            writeln!(file, "# karukan learning cache v1")
                .map_err(|error| format!("一時履歴ファイルを書き込めません: {error}"))?;
            for record in &sorted {
                writeln!(
                    file,
                    "{}\t{}\t{}\t{}",
                    record.reading, record.surface, record.frequency, record.last_access
                )
                .map_err(|error| format!("一時履歴ファイルを書き込めません: {error}"))?;
            }
            file.flush()
                .map_err(|error| format!("一時履歴ファイルを保存できません: {error}"))?;
            file.sync_all()
                .map_err(|error| format!("一時履歴ファイルを同期できません: {error}"))?;
            let from: Vec<u16> = temp_path.as_os_str().encode_wide().chain(Some(0)).collect();
            let to: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
            unsafe {
                MoveFileExW(
                    PCWSTR(from.as_ptr()),
                    PCWSTR(to.as_ptr()),
                    MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
                )
            }
            .map_err(|error| format!("学習履歴ファイルを置き換えられません: {error}"))?;
            Ok(())
        })();
        match result {
            Err(error) if temp_path.exists() => {
                if let Err(cleanup_error) = fs::remove_file(&temp_path) {
                    return Err(format!(
                        "{error}。一時ファイルも削除できませんでした ({}): {cleanup_error}",
                        temp_path.display()
                    ));
                }
                Err(error)
            }
            _ => result,
        }
    }

    fn save_config(config: &Value) -> Result<(), String> {
        let path = config_path()?;
        let parent = path
            .parent()
            .ok_or_else(|| "設定ファイルの保存先フォルダーが不正です".to_string())?;
        fs::create_dir_all(parent)
            .map_err(|error| format!("設定フォルダーを作成できません: {error}"))?;
        let mut config_to_save = config.clone();
        if let Some(conversion) = config_to_save
            .get_mut("conversion")
            .and_then(Value::as_table_mut)
            && conversion.get("dict_path").and_then(Value::as_str) == Some("")
        {
            conversion.remove("dict_path");
        }
        let text = toml::to_string_pretty(&config_to_save)
            .map_err(|error| format!("設定をTOMLに変換できません: {error}"))?;
        let temp_path = path.with_file_name(format!(
            "config.toml.{}.{}.tmp",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map_err(|error| format!("一時ファイル名を作成できません: {error}"))?
                .as_nanos()
        ));

        let result: Result<(), String> = (|| {
            let mut file = OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&temp_path)
                .map_err(|error| format!("一時設定ファイルを作成できません: {error}"))?;
            file.write_all(text.as_bytes())
                .map_err(|error| format!("一時設定ファイルを書き込めません: {error}"))?;
            file.sync_all()
                .map_err(|error| format!("一時設定ファイルを保存できません: {error}"))?;
            Settings::load_from(&temp_path)
                .map_err(|error| format!("設定内容に誤りがあります: {error}"))?;

            let from: Vec<u16> = temp_path.as_os_str().encode_wide().chain(Some(0)).collect();
            let to: Vec<u16> = path.as_os_str().encode_wide().chain(Some(0)).collect();
            unsafe {
                MoveFileExW(
                    PCWSTR(from.as_ptr()),
                    PCWSTR(to.as_ptr()),
                    MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
                )
            }
            .map_err(|error| format!("設定ファイルを置き換えられません: {error}"))?;
            Ok(())
        })();

        match result {
            Err(error) if temp_path.exists() => {
                if let Err(cleanup_error) = fs::remove_file(&temp_path) {
                    return Err(format!(
                        "{error}。一時ファイルも削除できませんでした ({}): {cleanup_error}",
                        temp_path.display()
                    ));
                }
                Err(error)
            }
            _ => result,
        }
    }

    fn set_status(state: &AppState, text: &str) {
        set_text(state.status, text);
    }

    fn current_section(state: &AppState) -> &'static str {
        SECTIONS[state.category].0
    }

    fn is_learning_category(state: &AppState) -> bool {
        current_section(state) == "learning_records"
    }

    fn is_user_dictionary_category(state: &AppState) -> bool {
        current_section(state) == "user_dictionary"
    }

    fn is_license_category(state: &AppState) -> bool {
        current_section(state) == "license"
    }

    fn field_label<'a>(section: &str, key: &'a str) -> &'a str {
        match (section, key) {
            ("conversion", "strategy") => "変換方式",
            ("conversion", "num_candidates") => "変換候補数",
            ("conversion", "use_context") => "周辺テキストを使う",
            ("conversion", "context_chars") => "周辺テキストの最大文字数",
            ("conversion", "persona") => "変換ペルソナ",
            ("conversion", "chunk_chars") => "変換チャンクの最大文字数",
            ("conversion", "chunk_symbols") => "チャンクに含める記号数",
            ("conversion", "chunk_digits") => "チャンクに含める数字数",
            ("conversion", "chunk_alphabets") => "チャンクに含める英字数",
            ("conversion", "beam_chars") => "別候補を作る文字数",
            ("conversion", "beam_width") => "別候補の数",
            ("conversion", "max_latency_ms") => "自動切替の時間 (ms)",
            ("conversion", "model") => "メインモデル",
            ("conversion", "light_model") => "軽量モデル",
            ("conversion", "n_threads") => "推論スレッド数",
            ("conversion", "live_conversion") => "ライブ変換を有効にする",
            ("conversion", "dict_path") => "辞書ファイルのパス（空欄で自動設定）",
            ("learning", "enabled") => "変換学習を有効にする",
            ("learning", "max_entries") => "学習履歴の最大件数",
            ("learning", "max_surface_chars") => "学習する変換結果の最大文字数",
            ("display", "verbose") => "詳細情報を表示する",
            ("display", "candidate_window") => "候補ウィンドウの表示タイミング",
            ("symbol", "punctuation") => "句読点の種類",
            ("symbol", "bracket") => "括弧の種類",
            ("symbol", "slash") => "スラッシュキーの記号",
            ("symbol", "space") => "空白の幅",
            ("width", "kana_symbol") => "かな記号の幅",
            ("width", "ascii_symbol") => "ASCII記号の幅",
            ("width", "digit") => "数字の幅",
            ("date", "formats") => "日付の表示形式",
            ("date", "phrases") => "日付変換フレーズ",
            _ => key,
        }
    }

    fn enum_options(section: &str, key: &str) -> Option<&'static [&'static str]> {
        match (section, key) {
            ("conversion", "strategy") => Some(&["adaptive", "light", "main"]),
            ("display", "candidate_window") => Some(&["always", "conversion"]),
            ("symbol", "punctuation") => Some(&["、。", "，．", "、．", "，。"]),
            ("symbol", "bracket") => Some(&["「」", "[]"]),
            ("symbol", "slash") => Some(&["・", "/"]),
            ("symbol", "space") => Some(&["half", "full"]),
            ("width", "kana_symbol" | "ascii_symbol" | "digit") => Some(&["half", "full"]),
            _ => None,
        }
    }

    fn display_value(value: &Value) -> String {
        match value {
            Value::String(value) => value.clone(),
            _ => value.to_string(),
        }
    }

    fn setting_keys(config: &Value, section: &str) -> Vec<String> {
        const CONVERSION_ORDER: &[&str] = &[
            "strategy",
            "model",
            "light_model",
            "n_threads",
            "num_candidates",
            "use_context",
            "context_chars",
            "persona",
            "live_conversion",
            "chunk_chars",
            "chunk_symbols",
            "chunk_digits",
            "chunk_alphabets",
            "beam_chars",
            "beam_width",
            "max_latency_ms",
            "dict_path",
        ];
        let Some(table) = config.get(section).and_then(Value::as_table) else {
            return Vec::new();
        };
        let mut keys = if section == "conversion" {
            CONVERSION_ORDER
                .iter()
                .filter(|key| table.contains_key(**key))
                .map(|key| (*key).to_string())
                .collect()
        } else {
            Vec::new()
        };
        let extra: Vec<String> = table
            .keys()
            .filter(|key| !keys.contains(key))
            .cloned()
            .collect();
        keys.extend(extra);
        keys
    }

    fn create_setting_controls(
        hwnd: HWND,
        config: &Value,
    ) -> windows::core::Result<Vec<SettingControl>> {
        let mut controls = Vec::new();
        let mut next_id = SETTING_CONTROL_ID_BASE;
        for (section, _) in SECTIONS
            .iter()
            .filter(|(section, _)| *section != "learning_records")
        {
            for key in setting_keys(config, section) {
                let Some(value) = config.get(section).and_then(|table| table.get(&key)) else {
                    continue;
                };
                let label_id = next_id;
                next_id += 1;
                let label = add_control(
                    hwnd,
                    w!("STATIC"),
                    PCWSTR(wide(field_label(section, &key)).as_ptr()),
                    WS_CHILD | WS_VISIBLE,
                    label_id,
                )?;
                let (input, kind) = if let Some(options) = enum_options(section, &key) {
                    let options: Vec<String> =
                        options.iter().map(|option| (*option).to_string()).collect();
                    let mut buttons = Vec::with_capacity(options.len());
                    for (index, option) in options.iter().enumerate() {
                        let text = wide(option);
                        let style = WS_CHILD
                            | WS_VISIBLE
                            | WINDOW_STYLE(BS_AUTORADIOBUTTON as u32)
                            | if index == 0 {
                                WS_GROUP
                            } else {
                                WINDOW_STYLE(0)
                            };
                        buttons.push(add_control(
                            hwnd,
                            w!("BUTTON"),
                            PCWSTR(text.as_ptr()),
                            style,
                            next_id,
                        )?);
                        next_id += 1;
                    }
                    let input = *buttons
                        .first()
                        .ok_or_else(windows::core::Error::from_thread)?;
                    (input, SettingInput::Radio { options, buttons })
                } else if value.is_bool() {
                    let input = add_control(
                        hwnd,
                        w!("BUTTON"),
                        w!("有効"),
                        WS_CHILD | WS_VISIBLE | WINDOW_STYLE(BS_AUTOCHECKBOX as u32),
                        next_id,
                    )?;
                    next_id += 1;
                    (input, SettingInput::Check)
                } else {
                    let multiline = value.is_array() || value.is_table();
                    let (style, text) = if multiline {
                        (
                            WS_CHILD
                                | WS_VISIBLE
                                | WS_BORDER
                                | WS_VSCROLL
                                | WINDOW_STYLE(
                                    (ES_MULTILINE | ES_AUTOVSCROLL | ES_WANTRETURN) as u32,
                                ),
                            w!(""),
                        )
                    } else {
                        (
                            WS_CHILD | WS_VISIBLE | WS_BORDER | WINDOW_STYLE(ES_AUTOHSCROLL as u32),
                            w!(""),
                        )
                    };
                    let input = add_control(hwnd, w!("EDIT"), text, style, next_id)?;
                    next_id += 1;
                    (input, SettingInput::Edit { multiline })
                };
                let control = SettingControl {
                    section: (*section).to_string(),
                    key,
                    label,
                    input,
                    kind,
                };
                set_setting_control(&control, value);
                controls.push(control);
            }
        }
        Ok(controls)
    }

    fn set_setting_control(control: &SettingControl, value: &Value) {
        match &control.kind {
            SettingInput::Check => {
                send(
                    control.input,
                    BM_SETCHECK,
                    if value.as_bool() == Some(true) { 1 } else { 0 },
                    0,
                );
            }
            SettingInput::Radio { options, buttons } => {
                let selected = value
                    .as_str()
                    .and_then(|value| options.iter().position(|option| option == value));
                for (index, button) in buttons.iter().enumerate() {
                    send(
                        *button,
                        BM_SETCHECK,
                        usize::from(Some(index) == selected),
                        0,
                    );
                }
            }
            SettingInput::Edit { .. } => set_text(control.input, &display_value(value)),
        }
    }

    fn set_setting_controls_visible(state: &AppState, section: Option<&str>) {
        for control in &state.setting_controls {
            let visibility = if section == Some(control.section.as_str()) {
                windows::Win32::UI::WindowsAndMessaging::SW_SHOW
            } else {
                windows::Win32::UI::WindowsAndMessaging::SW_HIDE
            };
            unsafe {
                let _ = ShowWindow(control.label, visibility);
                let _ = ShowWindow(control.input, visibility);
                if let SettingInput::Radio { buttons, .. } = &control.kind {
                    for button in buttons {
                        let _ = ShowWindow(*button, visibility);
                    }
                }
            }
        }
    }

    fn refresh_setting_controls(state: &AppState) {
        for control in &state.setting_controls {
            if let Some(value) = state
                .config
                .get(&control.section)
                .and_then(|table| table.get(&control.key))
            {
                set_setting_control(control, value);
            }
        }
    }

    fn populate_category(state: &mut AppState) {
        state.selected_learning = None;
        if is_license_category(state) {
            hide_action_controls(state);
            hide_user_dictionary_controls(state);
            set_setting_controls_visible(state, None);
            for control in [state.learning_grid, state.user_dictionary_grid] {
                unsafe {
                    let _ = ShowWindow(control, windows::Win32::UI::WindowsAndMessaging::SW_HIDE);
                }
            }
            unsafe {
                let _ = ShowWindow(
                    state.license_text,
                    windows::Win32::UI::WindowsAndMessaging::SW_SHOW,
                );
            }
            set_text(
                state.value_label,
                "Karukan は MIT License または Apache License 2.0 のいずれかを選択して利用できます。",
            );
            return;
        }
        unsafe {
            let _ = ShowWindow(
                state.license_text,
                windows::Win32::UI::WindowsAndMessaging::SW_HIDE,
            );
        }
        if is_user_dictionary_category(state) {
            state.selected_user_dictionary_entry = None;
            set_user_dictionary_form(state, None);
            hide_user_dictionary_controls(state);
            set_setting_controls_visible(state, None);
            unsafe {
                let _ = ShowWindow(
                    state.learning_grid,
                    windows::Win32::UI::WindowsAndMessaging::SW_HIDE,
                );
            }
            show_user_dictionary_controls(state);
            populate_user_dictionary_grid(state);
            set_text(
                state.value_label,
                "読み・表記・品詞・コメントを登録できます。変更後は「辞書を保存」を押してください。",
            );
            return;
        }
        hide_user_dictionary_controls(state);
        if !is_learning_category(state) {
            hide_action_controls(state);
            set_setting_controls_visible(state, Some(current_section(state)));
            unsafe {
                let _ = ShowWindow(
                    state.learning_grid,
                    windows::Win32::UI::WindowsAndMessaging::SW_HIDE,
                );
            }
            set_text(state.value_label, SECTIONS[state.category].1);
            refresh_setting_controls(state);
            return;
        }
        set_setting_controls_visible(state, None);
        unsafe {
            let _ = ShowWindow(
                state.learning_grid,
                windows::Win32::UI::WindowsAndMessaging::SW_SHOW,
            );
        }
        populate_learning_grid(state);
        show_learning_record(state);
    }

    fn hide_action_controls(state: &AppState) {
        for hwnd in [state.delete_learning, state.clear_learning] {
            unsafe {
                let _ = ShowWindow(hwnd, windows::Win32::UI::WindowsAndMessaging::SW_HIDE);
            }
        }
        unsafe {
            let _ = ShowWindow(
                state.apply,
                windows::Win32::UI::WindowsAndMessaging::SW_HIDE,
            );
        }
    }

    fn set_user_dictionary_form(state: &AppState, entry: Option<&UserDictionaryEntry>) {
        set_text(
            state.user_reading,
            entry.map_or("", |entry| entry.reading.as_str()),
        );
        set_text(
            state.user_surface,
            entry.map_or("", |entry| entry.surface.as_str()),
        );
        set_text(
            state.user_part_of_speech,
            entry.map_or("名詞", |entry| entry.part_of_speech.as_str()),
        );
        set_text(
            state.user_comment,
            entry.map_or("", |entry| entry.comment.as_str()),
        );
    }

    fn show_user_dictionary_entry(state: &mut AppState, index: usize) {
        let Some(entry) = state.user_dictionary_entries.get(index) else {
            return;
        };
        state.selected_user_dictionary_entry = Some(index);
        set_user_dictionary_form(state, Some(entry));
    }

    fn reload_user_dictionary(state: &mut AppState) {
        match load_user_dictionary_entries() {
            Ok(entries) => {
                state.user_dictionary_entries = entries;
                state.selected_user_dictionary_entry = None;
                state.user_dictionary_loaded = true;
                populate_user_dictionary_grid(state);
                set_user_dictionary_form(state, None);
                match user_dictionary_path() {
                    Ok(path) => set_status(
                        state,
                        &format!(
                            "ユーザー辞書 {} 件　{}",
                            state.user_dictionary_entries.len(),
                            path.display()
                        ),
                    ),
                    Err(error) => set_status(state, &error),
                }
            }
            Err(error) => set_status(state, &format!("ユーザー辞書の読み込みエラー: {error}")),
        }
    }

    fn apply_user_dictionary_entry(state: &mut AppState) {
        let result = (|| {
            let reading = control_text(state.user_reading)?.trim().to_string();
            let surface = control_text(state.user_surface)?.trim().to_string();
            let part_of_speech = control_text(state.user_part_of_speech)?.trim().to_string();
            let comment = control_text(state.user_comment)?.trim().to_string();
            if reading.is_empty() || surface.is_empty() {
                return Err("読みと表記は必須です。".to_string());
            }
            if [&reading, &surface, &part_of_speech, &comment]
                .iter()
                .any(|value| value.contains(['\t', '\n', '\r']))
            {
                return Err("タブや改行は入力できません。".to_string());
            }
            Ok(UserDictionaryEntry {
                reading,
                surface,
                part_of_speech,
                comment,
            })
        })();
        let entry = match result {
            Ok(entry) => entry,
            Err(error) => {
                set_status(state, &error);
                return;
            }
        };
        if let Some(index) = state.selected_user_dictionary_entry {
            if index >= state.user_dictionary_entries.len() {
                set_status(
                    state,
                    "選択した単語が見つかりません。再読み込みしてください。",
                );
                return;
            }
            if state
                .user_dictionary_entries
                .iter()
                .enumerate()
                .any(|(other_index, existing)| {
                    other_index != index
                        && existing.reading == entry.reading
                        && existing.surface == entry.surface
                })
            {
                set_status(state, "同じ読みと表記の単語が既に登録されています。");
                return;
            }
            state.user_dictionary_entries[index] = entry;
            set_status(state, "単語を更新しました。「辞書を保存」で反映します。");
        } else {
            if state.user_dictionary_entries.iter().any(|existing| {
                existing.reading == entry.reading && existing.surface == entry.surface
            }) {
                set_status(state, "同じ読みと表記の単語が既に登録されています。");
                return;
            }
            state.user_dictionary_entries.push(entry);
            set_status(state, "単語を追加しました。「辞書を保存」で反映します。");
        }
        state.selected_user_dictionary_entry = None;
        set_user_dictionary_form(state, None);
        populate_user_dictionary_grid(state);
    }

    fn delete_user_dictionary_entry(state: &mut AppState) {
        let Some(index) = state.selected_user_dictionary_entry else {
            set_status(state, "削除する単語を一覧から選択してください。");
            return;
        };
        if index >= state.user_dictionary_entries.len() {
            set_status(
                state,
                "選択した単語が見つかりません。再読み込みしてください。",
            );
            return;
        }
        state.user_dictionary_entries.remove(index);
        state.selected_user_dictionary_entry = None;
        set_user_dictionary_form(state, None);
        populate_user_dictionary_grid(state);
        set_status(state, "単語を削除しました。「辞書を保存」で反映します。");
    }

    fn save_user_dictionary(state: &AppState) {
        match save_user_dictionary_entries(&state.user_dictionary_entries) {
            Ok(()) => set_status(
                state,
                "ユーザー辞書を保存しました。変換への反映はIMEの再起動後です。",
            ),
            Err(error) => set_status(state, &format!("ユーザー辞書の保存エラー: {error}")),
        }
    }

    fn hide_user_dictionary_controls(state: &AppState) {
        for control in [
            state.user_dictionary_grid,
            state.user_reading,
            state.user_surface,
            state.user_part_of_speech,
            state.user_comment,
        ] {
            unsafe {
                let _ = ShowWindow(control, windows::Win32::UI::WindowsAndMessaging::SW_HIDE);
            }
        }
        for id in [
            USER_READING_LABEL_ID,
            USER_SURFACE_LABEL_ID,
            USER_POS_LABEL_ID,
            USER_COMMENT_LABEL_ID,
        ] {
            if let Ok(control) = unsafe {
                windows::Win32::UI::WindowsAndMessaging::GetDlgItem(
                    Some(state.settings_content),
                    id as i32,
                )
            } {
                unsafe {
                    let _ = ShowWindow(control, windows::Win32::UI::WindowsAndMessaging::SW_HIDE);
                }
            }
        }
    }

    fn show_user_dictionary_controls(state: &AppState) {
        for control in [
            state.user_dictionary_grid,
            state.user_reading,
            state.user_surface,
            state.user_part_of_speech,
            state.user_comment,
        ] {
            unsafe {
                let _ = ShowWindow(control, windows::Win32::UI::WindowsAndMessaging::SW_SHOW);
            }
        }
        for id in [
            USER_READING_LABEL_ID,
            USER_SURFACE_LABEL_ID,
            USER_POS_LABEL_ID,
            USER_COMMENT_LABEL_ID,
        ] {
            if let Ok(control) = unsafe {
                windows::Win32::UI::WindowsAndMessaging::GetDlgItem(
                    Some(state.settings_content),
                    id as i32,
                )
            } {
                unsafe {
                    let _ = ShowWindow(control, windows::Win32::UI::WindowsAndMessaging::SW_SHOW);
                }
            }
        }
    }

    fn update_footer(hwnd: HWND, learning: bool, user_dictionary: bool, license: bool) {
        let controls = [
            (APPLY_ID, "追加・更新", user_dictionary),
            (
                DELETE_LEARNING_ID,
                if user_dictionary {
                    "選択項目を削除"
                } else {
                    "選択した履歴を削除"
                },
                learning || user_dictionary,
            ),
            (
                CLEAR_LEARNING_ID,
                if user_dictionary {
                    "入力をクリア"
                } else {
                    "すべて削除"
                },
                learning || user_dictionary,
            ),
            (
                RELOAD_ID,
                if user_dictionary {
                    "辞書を再読み込み"
                } else if learning {
                    "履歴を再読み込み"
                } else {
                    "再読み込み"
                },
                !license,
            ),
            (
                SAVE_ID,
                if user_dictionary {
                    "辞書を保存"
                } else {
                    "保存"
                },
                !learning && !license,
            ),
        ];
        for (id, label, is_visible) in controls {
            if let Ok(control) = unsafe {
                windows::Win32::UI::WindowsAndMessaging::GetDlgItem(Some(hwnd), id as i32)
            } {
                set_text(control, label);
                let command = if is_visible {
                    windows::Win32::UI::WindowsAndMessaging::SW_SHOW
                } else {
                    windows::Win32::UI::WindowsAndMessaging::SW_HIDE
                };
                unsafe {
                    let _ = ShowWindow(control, command);
                }
            }
        }
    }

    fn show_learning_record(state: &AppState) {
        let Some(index) = state.selected_learning else {
            set_text(
                state.value_label,
                if state.learning_records.is_empty() {
                    "学習履歴はありません。IMEで変換候補を確定すると記録されます。"
                } else {
                    "履歴を選択すると詳細を表示します。"
                },
            );
            unsafe {
                let _ = ShowWindow(
                    state.delete_learning,
                    windows::Win32::UI::WindowsAndMessaging::SW_HIDE,
                );
                let _ = ShowWindow(
                    state.clear_learning,
                    windows::Win32::UI::WindowsAndMessaging::SW_SHOW,
                );
            }
            return;
        };
        let Some(record) = state.learning_records.get(index) else {
            set_text(state.value_label, "履歴項目を選択してください。");
            return;
        };
        let selected_count = selected_learning_indices(state.learning_grid).len();
        set_text(
            state.value_label,
            &format!(
                "{}読み: {}　候補: {}　選択回数: {}　最終選択: {}",
                if selected_count > 1 {
                    format!("{selected_count} 件選択中　")
                } else {
                    String::new()
                },
                record.reading,
                record.surface,
                record.frequency,
                learning_timestamp(record)
            ),
        );
        unsafe {
            let _ = ShowWindow(
                state.delete_learning,
                windows::Win32::UI::WindowsAndMessaging::SW_SHOW,
            );
            let _ = ShowWindow(
                state.clear_learning,
                windows::Win32::UI::WindowsAndMessaging::SW_SHOW,
            );
        }
    }

    fn reload_learning_records(state: &mut AppState) {
        match load_learning_records() {
            Ok(records) => {
                state.learning_records = records;
                sort_learning_records(
                    &mut state.learning_records,
                    state.learning_sort_column,
                    state.learning_sort_descending,
                );
                populate_category(state);
                match learning_path() {
                    Ok(path) => set_status(
                        state,
                        &format!(
                            "学習履歴 {} 件　{}",
                            state.learning_records.len(),
                            path.display()
                        ),
                    ),
                    Err(error) => set_status(state, &error),
                }
            }
            Err(error) => {
                state.learning_records.clear();
                populate_category(state);
                set_status(state, &format!("履歴読み込みエラー: {error}"));
            }
        }
    }

    fn delete_learning_record(state: &mut AppState, hwnd: HWND) {
        let selected_indices = selected_learning_indices(state.learning_grid);
        if selected_indices.is_empty() {
            set_status(state, "削除する履歴を選択してください。");
            return;
        }
        let selected_records: Vec<LearningRecord> = selected_indices
            .iter()
            .filter_map(|index| state.learning_records.get(*index).cloned())
            .collect();
        if selected_records.len() != selected_indices.len() {
            set_status(
                state,
                "選択した履歴が見つかりません。再読み込みしてください。",
            );
            return;
        }
        let prompt = wide(&format!(
            "選択した {} 件の学習履歴を削除しますか？\n各履歴と、同じ候補がより長い読みで学習されている履歴も削除されます。\n実行前にKarukan IMEを終了してください。",
            selected_records.len()
        ));
        if unsafe {
            MessageBoxW(
                Some(hwnd),
                PCWSTR(prompt.as_ptr()),
                w!("学習履歴の削除"),
                MB_YESNO | MB_ICONWARNING,
            )
        }
        .0 != IDYES.0
        {
            return;
        }

        let result: Result<usize, String> = (|| {
            let path = learning_path()?;
            let mut records = load_learning_records()?;
            let before = records.len();
            records.retain(|candidate| {
                !selected_records.iter().any(|selected| {
                    candidate.reading.starts_with(&selected.reading)
                        && candidate.surface == selected.surface
                })
            });
            let removed = before - records.len();
            if removed == 0 {
                return Err(
                    "選択した履歴は既に削除されています。再読み込みしてください。".to_string(),
                );
            }
            save_learning_records(&path, &records)?;
            Ok(removed)
        })();
        match result {
            Ok(removed) => {
                reload_learning_records(state);
                set_status(state, &format!("{removed} 件の関連履歴を削除しました。"));
            }
            Err(error) => set_status(state, &format!("履歴削除エラー: {error}")),
        }
    }

    fn clear_learning_records(state: &mut AppState, hwnd: HWND) {
        if state.learning_records.is_empty() {
            set_status(state, "削除する学習履歴はありません。");
            return;
        }
        let prompt = wide(&format!(
            "学習履歴 {} 件をすべて削除します。この操作は元に戻せません。\n実行前にKarukan IMEを終了してください。",
            state.learning_records.len()
        ));
        if unsafe {
            MessageBoxW(
                Some(hwnd),
                PCWSTR(prompt.as_ptr()),
                w!("学習履歴をすべて削除"),
                MB_YESNO | MB_ICONWARNING,
            )
        }
        .0 != IDYES.0
        {
            return;
        }
        let result: Result<usize, String> = (|| {
            let path = learning_path()?;
            let records = load_learning_records()?;
            let removed = records.len();
            if removed > 0 {
                save_learning_records(&path, &[])?;
            }
            Ok(removed)
        })();
        match result {
            Ok(removed) => {
                reload_learning_records(state);
                set_status(state, &format!("学習履歴 {removed} 件を削除しました。"));
            }
            Err(error) => set_status(state, &format!("履歴削除エラー: {error}")),
        }
    }

    fn parse_value(text: &str, current: &Value) -> Result<Value, String> {
        match current {
            Value::String(_) => Ok(Value::String(text.to_string())),
            Value::Integer(_) | Value::Float(_) => {
                let parsed: Value = toml::from_str(&format!("value = {text}"))
                    .map_err(|error| format!("数値を正しく入力してください: {error}"))?;
                let value = parsed
                    .get("value")
                    .ok_or_else(|| "入力値を解析できません".to_string())?;
                if matches!(current, Value::Integer(_)) && !value.is_integer()
                    || matches!(current, Value::Float(_)) && !value.is_float()
                {
                    return Err("現在の項目と同じ数値形式で入力してください".to_string());
                }
                Ok(value.clone())
            }
            Value::Array(_) | Value::Table(_) => {
                let parsed: Value = toml::from_str(&format!("value = {text}"))
                    .map_err(|error| format!("TOML値を正しく入力してください: {error}"))?;
                let value = parsed
                    .get("value")
                    .ok_or_else(|| "入力値を解析できません".to_string())?;
                if matches!(current, Value::Array(_)) && !value.is_array()
                    || matches!(current, Value::Table(_)) && !value.is_table()
                {
                    return Err("現在の項目と同じ種類の値を入力してください".to_string());
                }
                Ok(value.clone())
            }
            Value::Boolean(_) | Value::Datetime(_) => {
                let parsed: Value = toml::from_str(&format!("value = {text}"))
                    .map_err(|error| format!("TOML値を正しく入力してください: {error}"))?;
                Ok(parsed
                    .get("value")
                    .ok_or_else(|| "入力値を解析できません".to_string())?
                    .clone())
            }
        }
    }

    fn apply_setting_fields(state: &mut AppState) -> bool {
        let section = current_section(state);
        let Some(table) = state.config.get(section).and_then(Value::as_table) else {
            set_status(state, "設定項目を取得できません。");
            return false;
        };
        let controls: Vec<&SettingControl> = state
            .setting_controls
            .iter()
            .filter(|control| control.section == section)
            .collect();
        let mut updates = Vec::with_capacity(controls.len());
        for control in controls {
            let Some(current) = table.get(&control.key) else {
                set_status(
                    state,
                    &format!("設定項目「{}」が見つかりません。", control.key),
                );
                return false;
            };
            let updated = match &control.kind {
                SettingInput::Check => Value::Boolean(send(control.input, BM_GETCHECK, 0, 0) == 1),
                SettingInput::Radio { options, buttons } => {
                    let Some(index) = buttons
                        .iter()
                        .position(|button| send(*button, BM_GETCHECK, 0, 0) == 1)
                    else {
                        set_status(
                            state,
                            &format!("「{}」の値を選択してください。", control.key),
                        );
                        return false;
                    };
                    Value::String(options[index].clone())
                }
                SettingInput::Edit { .. } => {
                    match control_text(control.input).and_then(|text| parse_value(&text, current)) {
                        Ok(value) => value,
                        Err(error) => {
                            set_status(state, &format!("「{}」: {error}", control.key));
                            return false;
                        }
                    }
                }
            };
            updates.push((control.key.clone(), updated));
        }
        let Some(table) = state.config.get_mut(section).and_then(Value::as_table_mut) else {
            set_status(state, "設定項目を更新できません。");
            return false;
        };
        for (key, value) in updates {
            table.insert(key, value);
        }
        true
    }

    fn add_control(
        parent: HWND,
        class: PCWSTR,
        text: PCWSTR,
        style: WINDOW_STYLE,
        id: usize,
    ) -> windows::core::Result<HWND> {
        unsafe {
            CreateWindowExW(
                WINDOW_EX_STYLE::default(),
                class,
                text,
                style,
                0,
                0,
                0,
                0,
                Some(parent),
                Some(windows::Win32::UI::WindowsAndMessaging::HMENU(
                    id as *mut c_void,
                )),
                None,
                None,
            )
        }
    }

    fn create_controls(hwnd: HWND) -> windows::core::Result<()> {
        let category_style =
            WS_CHILD | WS_VISIBLE | WS_BORDER | WS_VSCROLL | WINDOW_STYLE(LBS_NOTIFY as u32);
        let categories = add_control(hwnd, w!("LISTBOX"), w!(""), category_style, CATEGORY_ID)?;
        let settings_panel = add_control(
            hwnd,
            SETTINGS_PANEL_CLASS,
            w!(""),
            WS_CHILD | WS_VISIBLE | WS_VSCROLL | WS_CLIPCHILDREN,
            SETTINGS_PANEL_ID,
        )?;
        let settings_content = add_control(
            settings_panel,
            SETTINGS_CONTENT_CLASS,
            w!(""),
            WS_CHILD | WS_VISIBLE | WS_CLIPCHILDREN,
            SETTINGS_CONTENT_ID,
        )?;
        let learning_grid = add_control(
            settings_content,
            w!("SysListView32"),
            w!(""),
            WS_CHILD
                | WS_VISIBLE
                | WS_BORDER
                | WS_VSCROLL
                | WINDOW_STYLE(LVS_REPORT | LVS_SHOWSELALWAYS),
            LEARNING_GRID_ID,
        )?;
        let _ = send(
            learning_grid,
            LVM_SETEXTENDEDLISTVIEWSTYLE,
            LVS_EX_FULLROWSELECT as usize,
            LVS_EX_FULLROWSELECT as isize,
        );
        for (index, (title, width)) in [
            ("読み", 100),
            ("表記", 160),
            ("選択回数", 75),
            ("最終選択", 155),
        ]
        .into_iter()
        .enumerate()
        {
            add_learning_column(learning_grid, index, title, width)?;
        }
        let user_dictionary_grid = add_control(
            settings_content,
            w!("SysListView32"),
            w!(""),
            WS_CHILD
                | WS_BORDER
                | WS_VSCROLL
                | WINDOW_STYLE(LVS_REPORT | LVS_SHOWSELALWAYS | LVS_SINGLESEL),
            USER_DICTIONARY_GRID_ID,
        )?;
        let _ = send(
            user_dictionary_grid,
            LVM_SETEXTENDEDLISTVIEWSTYLE,
            LVS_EX_FULLROWSELECT as usize,
            LVS_EX_FULLROWSELECT as isize,
        );
        for (index, (title, width)) in [
            ("読み", 140),
            ("表記", 200),
            ("品詞", 130),
            ("コメント", 300),
        ]
        .into_iter()
        .enumerate()
        {
            add_learning_column(user_dictionary_grid, index, title, width)?;
        }
        for (_, label) in SECTIONS {
            add_item(categories, LB_ADDSTRING, label);
        }
        send(categories, LB_SETCURSEL, 0, 0);

        let value_label = add_control(
            settings_content,
            w!("STATIC"),
            w!(""),
            WS_CHILD | WS_VISIBLE,
            VALUE_LABEL_ID,
        )?;
        let user_reading_label = add_control(
            settings_content,
            w!("STATIC"),
            w!("読み"),
            WS_CHILD | WS_VISIBLE,
            USER_READING_LABEL_ID,
        )?;
        let user_reading = add_control(
            settings_content,
            w!("EDIT"),
            w!(""),
            WS_CHILD | WS_VISIBLE | WS_BORDER | WINDOW_STYLE(ES_AUTOHSCROLL as u32),
            USER_READING_ID,
        )?;
        let user_surface_label = add_control(
            settings_content,
            w!("STATIC"),
            w!("表記"),
            WS_CHILD | WS_VISIBLE,
            USER_SURFACE_LABEL_ID,
        )?;
        let user_surface = add_control(
            settings_content,
            w!("EDIT"),
            w!(""),
            WS_CHILD | WS_VISIBLE | WS_BORDER | WINDOW_STYLE(ES_AUTOHSCROLL as u32),
            USER_SURFACE_ID,
        )?;
        let user_pos_label = add_control(
            settings_content,
            w!("STATIC"),
            w!("品詞"),
            WS_CHILD | WS_VISIBLE,
            USER_POS_LABEL_ID,
        )?;
        let user_part_of_speech = add_control(
            settings_content,
            w!("EDIT"),
            w!(""),
            WS_CHILD | WS_VISIBLE | WS_BORDER | WINDOW_STYLE(ES_AUTOHSCROLL as u32),
            USER_POS_ID,
        )?;
        let user_comment_label = add_control(
            settings_content,
            w!("STATIC"),
            w!("コメント"),
            WS_CHILD | WS_VISIBLE,
            USER_COMMENT_LABEL_ID,
        )?;
        let user_comment = add_control(
            settings_content,
            w!("EDIT"),
            w!(""),
            WS_CHILD | WS_VISIBLE | WS_BORDER | WINDOW_STYLE(ES_AUTOHSCROLL as u32),
            USER_COMMENT_ID,
        )?;
        let license_text = add_control(
            settings_content,
            w!("EDIT"),
            PCWSTR(wide(LICENSE_TEXT).as_ptr()),
            WS_CHILD
                | WS_BORDER
                | WS_VSCROLL
                | WINDOW_STYLE(
                    (ES_MULTILINE | ES_AUTOVSCROLL | ES_READONLY | ES_WANTRETURN) as u32,
                ),
            LICENSE_TEXT_ID,
        )?;
        for control in [
            user_dictionary_grid,
            user_reading_label,
            user_reading,
            user_surface_label,
            user_surface,
            user_pos_label,
            user_part_of_speech,
            user_comment_label,
            user_comment,
            license_text,
        ] {
            let _ =
                unsafe { ShowWindow(control, windows::Win32::UI::WindowsAndMessaging::SW_HIDE) };
        }
        let apply = add_control(
            hwnd,
            w!("BUTTON"),
            w!("追加・更新"),
            WS_CHILD | WS_VISIBLE | WINDOW_STYLE(BS_PUSHBUTTON as u32),
            APPLY_ID,
        )?;
        let delete_learning = add_control(
            hwnd,
            w!("BUTTON"),
            w!("選択した履歴を削除"),
            WS_CHILD | WS_VISIBLE | WINDOW_STYLE(BS_PUSHBUTTON as u32),
            DELETE_LEARNING_ID,
        )?;
        let clear_learning = add_control(
            hwnd,
            w!("BUTTON"),
            w!("すべて削除"),
            WS_CHILD | WS_VISIBLE | WINDOW_STYLE(BS_PUSHBUTTON as u32),
            CLEAR_LEARNING_ID,
        )?;
        add_control(
            hwnd,
            w!("BUTTON"),
            w!("再読み込み"),
            WS_CHILD | WS_VISIBLE | WINDOW_STYLE(BS_PUSHBUTTON as u32),
            RELOAD_ID,
        )?;
        add_control(
            hwnd,
            w!("BUTTON"),
            w!("保存"),
            WS_CHILD | WS_VISIBLE | WINDOW_STYLE(BS_PUSHBUTTON as u32),
            SAVE_ID,
        )?;
        let status = add_control(hwnd, w!("STATIC"), w!(""), WS_CHILD | WS_VISIBLE, STATUS_ID)?;

        let config = match load_config() {
            Ok(config) => config,
            Err(error) => {
                let message = wide(&error);
                unsafe {
                    let _ = MessageBoxW(
                        Some(hwnd),
                        PCWSTR(message.as_ptr()),
                        w!("設定読み込みエラー"),
                        MB_OK | MB_ICONERROR,
                    );
                }
                return Err(windows::core::Error::new(
                    windows::core::HRESULT(0x80004005u32 as i32),
                    error,
                ));
            }
        };
        let setting_controls = create_setting_controls(settings_content, &config)?;
        APP_STATE.with(|slot| {
            *slot.borrow_mut() = Some(AppState {
                config,
                category: 0,
                selected_learning: None,
                learning_records: Vec::new(),
                learning_sort_column: 3,
                learning_sort_descending: true,
                user_dictionary_entries: Vec::new(),
                selected_user_dictionary_entry: None,
                user_dictionary_loaded: false,
                setting_controls,
                settings_panel,
                settings_content,
                categories,
                learning_grid,
                user_dictionary_grid,
                user_reading,
                user_surface,
                user_part_of_speech,
                user_comment,
                license_text,
                value_label,
                apply,
                delete_learning,
                clear_learning,
                status,
            });
        });

        APP_STATE.with(|slot| {
            if let Some(state) = slot.borrow_mut().as_mut() {
                update_learning_sort_headers(state);
                populate_category(state);
                update_footer(hwnd, false, false, false);
                if let Ok(path) = config_path() {
                    set_status(state, &format!("設定ファイル: {}", path.display()));
                }
            }
        });
        let mut rect = RECT::default();
        unsafe { GetClientRect(hwnd, &mut rect) }?;
        layout(hwnd, rect.right - rect.left, rect.bottom - rect.top);
        Ok(())
    }

    fn layout(hwnd: HWND, width: i32, height: i32) {
        let margin = 12;
        let footer_height = 92;
        let bottom = (height - footer_height).max(margin);
        let content_height = (bottom - margin * 2).max(0);
        let category_width = 145;
        let gap = 12;
        let settings_x = margin + category_width + gap;
        let settings_width = (width - settings_x - margin).max(0);
        let button_y = (height - margin - 30).max(0);
        let status_y = (bottom + 4).max(0);
        let button_gap = 8;
        let button_widths = [
            (APPLY_ID, 112),
            (DELETE_LEARNING_ID, 142),
            (CLEAR_LEARNING_ID, 100),
            (RELOAD_ID, 132),
            (SAVE_ID, 86),
        ];
        let buttons_width = button_widths
            .iter()
            .map(|(_, button_width)| button_width)
            .sum::<i32>()
            + button_gap * (button_widths.len() as i32 - 1);
        let mut button_x = (width - margin - buttons_width).max(settings_x);
        let controls = [
            (CATEGORY_ID, margin, margin, category_width, content_height),
            (
                SETTINGS_PANEL_ID,
                settings_x,
                margin,
                settings_width,
                content_height,
            ),
        ];
        for (id, x, y, w, h) in controls {
            if let Ok(control) = unsafe {
                windows::Win32::UI::WindowsAndMessaging::GetDlgItem(Some(hwnd), id as i32)
            } {
                if let Err(error) = unsafe { MoveWindow(control, x, y, w, h, true) } {
                    eprintln!("MoveWindow failed: {error}");
                }
            }
        }
        if let Ok(status) = unsafe {
            windows::Win32::UI::WindowsAndMessaging::GetDlgItem(Some(hwnd), STATUS_ID as i32)
        } {
            if let Err(error) =
                unsafe { MoveWindow(status, settings_x, status_y, settings_width, 24, true) }
            {
                eprintln!("MoveWindow failed: {error}");
            }
        }
        for (id, button_width) in button_widths {
            if let Ok(button) = unsafe {
                windows::Win32::UI::WindowsAndMessaging::GetDlgItem(Some(hwnd), id as i32)
            } {
                if let Err(error) =
                    unsafe { MoveWindow(button, button_x, button_y, button_width, 30, true) }
                {
                    eprintln!("MoveWindow failed: {error}");
                }
            }
            button_x += button_width + button_gap;
        }
    }

    fn layout_settings_panel(panel: HWND, width: i32, height: i32) {
        APP_STATE.with(|slot| {
            let slot = slot.borrow();
            let Some(state) = slot.as_ref() else {
                return;
            };
            let learning = is_learning_category(state);
            let user_dictionary = is_user_dictionary_category(state);
            let license = is_license_category(state);
            let controls: Vec<&SettingControl> = if learning || user_dictionary || license {
                Vec::new()
            } else {
                state
                    .setting_controls
                    .iter()
                    .filter(|control| control.section == current_section(state))
                    .collect()
            };
            let content_height = if learning || user_dictionary || license {
                height
            } else {
                38 + controls
                    .iter()
                    .map(|control| 24 + setting_input_height(&control.kind) + 14)
                    .sum::<i32>()
            };
            let mut scroll_info = SCROLLINFO {
                cbSize: std::mem::size_of::<SCROLLINFO>() as u32,
                fMask: SIF_ALL,
                nMin: 0,
                nMax: content_height.saturating_sub(1).max(0),
                nPage: height.max(0) as u32,
                ..Default::default()
            };
            if let Err(error) = unsafe { GetScrollInfo(panel, SB_VERT, &mut scroll_info) } {
                eprintln!("Could not read settings scroll position: {error}");
                scroll_info.nPos = 0;
            }
            let position = if learning || user_dictionary || license {
                0
            } else {
                scroll_info.nPos.clamp(0, (content_height - height).max(0))
            };
            scroll_info.fMask = SIF_RANGE | SIF_PAGE | SIF_POS;
            scroll_info.nMax = content_height.saturating_sub(1).max(0);
            scroll_info.nPage = height.max(0) as u32;
            scroll_info.nPos = position;
            unsafe {
                let _ = SetScrollInfo(panel, SB_VERT, &scroll_info, true);
            }
            if let Err(error) = unsafe {
                MoveWindow(
                    state.settings_content,
                    0,
                    -position,
                    width,
                    content_height.max(height),
                    true,
                )
            } {
                eprintln!("MoveWindow failed: {error}");
            }

            if let Err(error) = unsafe { MoveWindow(state.value_label, 0, 0, width, 28, true) } {
                eprintln!("MoveWindow failed: {error}");
            }
            if learning {
                if let Err(error) = unsafe {
                    MoveWindow(
                        state.learning_grid,
                        0,
                        34,
                        width,
                        (height - 42).max(0),
                        true,
                    )
                } {
                    eprintln!("MoveWindow failed: {error}");
                }
            } else if user_dictionary {
                let grid_height = (height - 150).max(80);
                if let Err(error) = unsafe {
                    MoveWindow(state.user_dictionary_grid, 0, 34, width, grid_height, true)
                } {
                    eprintln!("MoveWindow failed: {error}");
                }
                let column_widths = [
                    width * 19 / 100,
                    width * 25 / 100,
                    width * 18 / 100,
                    width * 38 / 100,
                ];
                for (column, column_width) in column_widths.iter().enumerate() {
                    let _ = send(
                        state.user_dictionary_grid,
                        LVM_SETCOLUMNWIDTH,
                        column,
                        *column_width as isize,
                    );
                }
                let label_y = 44 + grid_height;
                let input_y = label_y + 20;
                let widths = [
                    width * 20 / 100,
                    width * 25 / 100,
                    width * 20 / 100,
                    width * 35 / 100,
                ];
                let ids = [
                    (USER_READING_LABEL_ID, state.user_reading),
                    (USER_SURFACE_LABEL_ID, state.user_surface),
                    (USER_POS_LABEL_ID, state.user_part_of_speech),
                    (USER_COMMENT_LABEL_ID, state.user_comment),
                ];
                let mut x = 0;
                for ((label_id, input), field_width) in ids.into_iter().zip(widths) {
                    if let Ok(label) = unsafe {
                        windows::Win32::UI::WindowsAndMessaging::GetDlgItem(
                            Some(state.settings_content),
                            label_id as i32,
                        )
                    } {
                        if let Err(error) =
                            unsafe { MoveWindow(label, x, label_y, field_width.max(0), 18, true) }
                        {
                            eprintln!("MoveWindow failed: {error}");
                        }
                    }
                    if let Err(error) =
                        unsafe { MoveWindow(input, x, input_y, field_width.max(0), 26, true) }
                    {
                        eprintln!("MoveWindow failed: {error}");
                    }
                    x += field_width;
                }
            } else if license {
                if let Err(error) = unsafe {
                    MoveWindow(state.license_text, 0, 34, width, (height - 42).max(0), true)
                } {
                    eprintln!("MoveWindow failed: {error}");
                }
            } else {
                let mut y = 38;
                for control in controls {
                    if let Err(error) = unsafe { MoveWindow(control.label, 0, y, width, 22, true) }
                    {
                        eprintln!("MoveWindow failed: {error}");
                    }
                    if let SettingInput::Radio { options, buttons } = &control.kind {
                        let mut x = 0;
                        for (option, button) in options.iter().zip(buttons) {
                            let option_width = radio_option_width(option);
                            if let Err(error) = unsafe {
                                MoveWindow(
                                    *button,
                                    x,
                                    y + 24,
                                    option_width,
                                    SETTING_INPUT_HEIGHT,
                                    true,
                                )
                            } {
                                eprintln!("MoveWindow failed: {error}");
                            }
                            x += option_width;
                        }
                    } else if let Err(error) = unsafe {
                        MoveWindow(
                            control.input,
                            0,
                            y + 24,
                            width,
                            setting_input_height(&control.kind),
                            true,
                        )
                    } {
                        eprintln!("MoveWindow failed: {error}");
                    }
                    y += 24 + setting_input_height(&control.kind) + 14;
                }
            }
        });
    }

    fn scroll_settings_panel(panel: HWND, command: i32, wheel_delta: Option<i16>) {
        let mut info = SCROLLINFO {
            cbSize: std::mem::size_of::<SCROLLINFO>() as u32,
            fMask: SIF_ALL,
            ..Default::default()
        };
        if let Err(error) = unsafe { GetScrollInfo(panel, SB_VERT, &mut info) } {
            eprintln!("Could not read settings scrollbar: {error}");
            return;
        }
        let page = info.nPage.max(1) as i32;
        let mut position = info.nPos;
        if let Some(delta) = wheel_delta {
            position -= i32::from(delta.signum()) * 72;
        } else {
            match command {
                value if value == SB_LINEUP.0 => position -= 36,
                value if value == SB_LINEDOWN.0 => position += 36,
                value if value == SB_PAGEUP.0 => position -= page,
                value if value == SB_PAGEDOWN.0 => position += page,
                value if value == SB_THUMBTRACK.0 || value == SB_THUMBPOSITION.0 => {
                    position = info.nTrackPos;
                }
                value if value == SB_TOP.0 => position = 0,
                _ => return,
            }
        }
        let maximum = (info.nMax - page + 1).max(0);
        info.fMask = SIF_POS;
        info.nPos = position.clamp(0, maximum);
        unsafe {
            let _ = SetScrollInfo(panel, SB_VERT, &info, true);
        }
        let mut rect = RECT::default();
        match unsafe { GetClientRect(panel, &mut rect) } {
            Ok(()) => layout_settings_panel(panel, rect.right - rect.left, rect.bottom - rect.top),
            Err(error) => eprintln!("Could not size settings content: {error}"),
        }
    }

    fn white_control_background() -> LRESULT {
        let brush = unsafe { GetSysColorBrush(COLOR_WINDOW) };
        LRESULT(brush.0 as isize)
    }

    unsafe extern "system" fn settings_panel_proc(
        hwnd: HWND,
        message: u32,
        wparam: WPARAM,
        lparam: LPARAM,
    ) -> LRESULT {
        match message {
            WM_CTLCOLORSTATIC | WM_CTLCOLORBTN => white_control_background(),
            WM_SIZE => {
                let mut rect = RECT::default();
                if let Err(error) = unsafe { GetClientRect(hwnd, &mut rect) } {
                    eprintln!("Could not size settings panel: {error}");
                } else {
                    layout_settings_panel(hwnd, rect.right - rect.left, rect.bottom - rect.top);
                }
                LRESULT(0)
            }
            WM_VSCROLL => {
                scroll_settings_panel(hwnd, (wparam.0 & 0xffff) as u16 as i16 as i32, None);
                LRESULT(0)
            }
            WM_MOUSEWHEEL => {
                scroll_settings_panel(hwnd, 0, Some((wparam.0 >> 16) as u16 as i16));
                LRESULT(0)
            }
            WM_NOTIFY => {
                if lparam.0 != 0 {
                    let notification = unsafe { &*(lparam.0 as *const NMLISTVIEW) };
                    if notification.hdr.idFrom == USER_DICTIONARY_GRID_ID
                        && notification.hdr.code == LVN_ITEMCHANGED
                        && notification.uNewState & LVIS_SELECTED.0 != 0
                        && notification.iItem >= 0
                    {
                        APP_STATE.with(|slot| {
                            let mut slot = slot.borrow_mut();
                            if let Some(state) = slot.as_mut() {
                                show_user_dictionary_entry(state, notification.iItem as usize);
                            }
                        });
                    } else if notification.hdr.idFrom == LEARNING_GRID_ID
                        && notification.hdr.code == LVN_COLUMNCLICK
                        && (0..4).contains(&notification.iSubItem)
                    {
                        APP_STATE.with(|slot| {
                            let mut slot = slot.borrow_mut();
                            if let Some(state) = slot.as_mut() {
                                let column = notification.iSubItem as usize;
                                if state.learning_sort_column == column {
                                    state.learning_sort_descending =
                                        !state.learning_sort_descending;
                                } else {
                                    state.learning_sort_column = column;
                                    state.learning_sort_descending = false;
                                }
                                sort_learning_records(
                                    &mut state.learning_records,
                                    state.learning_sort_column,
                                    state.learning_sort_descending,
                                );
                                state.selected_learning = None;
                                populate_category(state);
                                update_learning_sort_headers(state);
                            }
                        });
                    } else if notification.hdr.idFrom == LEARNING_GRID_ID
                        && notification.hdr.code == LVN_ITEMCHANGED
                        && (notification.uOldState ^ notification.uNewState) & LVIS_SELECTED.0 != 0
                        && notification.iItem >= 0
                    {
                        APP_STATE.with(|slot| {
                            let mut slot = slot.borrow_mut();
                            if let Some(state) = slot.as_mut() {
                                let selected = selected_learning_indices(state.learning_grid);
                                if notification.uNewState & LVIS_SELECTED.0 != 0 {
                                    state.selected_learning = Some(notification.iItem as usize);
                                } else if state.selected_learning
                                    == Some(notification.iItem as usize)
                                {
                                    state.selected_learning = selected.first().copied();
                                }
                                show_learning_record(state);
                            }
                        });
                    }
                }
                LRESULT(0)
            }
            _ => unsafe { DefWindowProcW(hwnd, message, wparam, lparam) },
        }
    }

    unsafe extern "system" fn settings_content_proc(
        hwnd: HWND,
        message: u32,
        wparam: WPARAM,
        lparam: LPARAM,
    ) -> LRESULT {
        match message {
            WM_CTLCOLORSTATIC | WM_CTLCOLORBTN => white_control_background(),
            WM_NOTIFY | WM_MOUSEWHEEL => {
                let parent = unsafe { windows::Win32::UI::WindowsAndMessaging::GetParent(hwnd) };
                match parent {
                    Ok(parent) => unsafe {
                        SendMessageW(parent, message, Some(wparam), Some(lparam))
                    },
                    Err(error) => {
                        eprintln!("Could not forward settings notification: {error}");
                        LRESULT(0)
                    }
                }
            }
            _ => unsafe { DefWindowProcW(hwnd, message, wparam, lparam) },
        }
    }

    unsafe extern "system" fn window_proc(
        hwnd: HWND,
        message: u32,
        wparam: WPARAM,
        lparam: LPARAM,
    ) -> LRESULT {
        match message {
            WM_CTLCOLORSTATIC | WM_CTLCOLORBTN => white_control_background(),
            WM_CREATE => {
                if let Err(error) = create_controls(hwnd) {
                    eprintln!("Could not create settings controls: {error}");
                    return LRESULT(-1);
                }
                LRESULT(0)
            }
            WM_SIZE => {
                let dimensions = lparam.0 as u32;
                layout(
                    hwnd,
                    (dimensions & 0xffff) as i32,
                    (dimensions >> 16) as i32,
                );
                LRESULT(0)
            }
            WM_COMMAND => {
                let id = wparam.0 & 0xffff;
                let notification = (wparam.0 >> 16) as u32;
                let mut relayout = false;
                let handled = matches!(
                    (id, notification),
                    (CATEGORY_ID, LBN_SELCHANGE)
                        | (APPLY_ID, BN_CLICKED)
                        | (DELETE_LEARNING_ID, BN_CLICKED)
                        | (CLEAR_LEARNING_ID, BN_CLICKED)
                        | (RELOAD_ID, BN_CLICKED)
                        | (SAVE_ID, BN_CLICKED)
                );
                if !handled {
                    return LRESULT(0);
                }
                APP_STATE.with(|slot| {
                    let mut slot = slot.borrow_mut();
                    let Some(state) = slot.as_mut() else {
                        return;
                    };
                    match (id, notification) {
                        (CATEGORY_ID, LBN_SELCHANGE) => {
                            let selected = send(state.categories, LB_GETCURSEL, 0, 0);
                            if selected >= 0 && (selected as usize) < SECTIONS.len() {
                                if !is_learning_category(state)
                                    && !is_user_dictionary_category(state)
                                    && !is_license_category(state)
                                    && !apply_setting_fields(state)
                                {
                                    send(state.categories, LB_SETCURSEL, state.category, 0);
                                    return;
                                }
                                state.category = selected as usize;
                                relayout = true;
                                let learning = is_learning_category(state);
                                let user_dictionary = is_user_dictionary_category(state);
                                let license = is_license_category(state);
                                update_footer(hwnd, learning, user_dictionary, license);
                                if learning {
                                    reload_learning_records(state);
                                } else if user_dictionary {
                                    populate_category(state);
                                    if state.user_dictionary_loaded {
                                        populate_user_dictionary_grid(state);
                                    } else {
                                        reload_user_dictionary(state);
                                    }
                                } else if license {
                                    populate_category(state);
                                    set_status(
                                        state,
                                        "ライセンス本文はこの画面で確認できます。配布ZIPにもライセンスファイルを同梱します。",
                                    );
                                } else {
                                    populate_category(state);
                                    set_status(
                                        state,
                                        "設定を編集できます。「保存」でファイルに反映します。",
                                    );
                                }
                            }
                        }
                        (APPLY_ID, BN_CLICKED) if is_user_dictionary_category(state) => {
                            apply_user_dictionary_entry(state);
                        }
                        (DELETE_LEARNING_ID, BN_CLICKED) => {
                            if is_user_dictionary_category(state) {
                                delete_user_dictionary_entry(state);
                            } else {
                                delete_learning_record(state, hwnd);
                            }
                        }
                        (CLEAR_LEARNING_ID, BN_CLICKED) => {
                            if is_user_dictionary_category(state) {
                                state.selected_user_dictionary_entry = None;
                                set_user_dictionary_form(state, None);
                                set_status(state, "入力欄をクリアしました。");
                            } else {
                                clear_learning_records(state, hwnd);
                            }
                        }
                        (RELOAD_ID, BN_CLICKED) if is_user_dictionary_category(state) => {
                            reload_user_dictionary(state);
                        }
                        (RELOAD_ID, BN_CLICKED) if is_learning_category(state) => {
                            reload_learning_records(state);
                        }
                        (RELOAD_ID, BN_CLICKED) => match load_config() {
                            Ok(config) => {
                                state.config = config;
                                populate_category(state);
                                set_status(state, "設定ファイルを再読み込みしました。");
                            }
                            Err(error) => set_status(state, &format!("読み込みエラー: {error}")),
                        },
                        (SAVE_ID, BN_CLICKED) if is_user_dictionary_category(state) => {
                            save_user_dictionary(state);
                        }
                        (SAVE_ID, BN_CLICKED)
                            if !is_learning_category(state)
                                && !is_user_dictionary_category(state)
                                && !is_license_category(state)
                                && apply_setting_fields(state) =>
                        {
                            match save_config(&state.config) {
                                Ok(()) => set_status(
                                    state,
                                    "設定を保存しました。IMEの設定は次回起動時に反映されます。",
                                ),
                                Err(error) => set_status(state, &format!("保存エラー: {error}")),
                            }
                        }
                        (SAVE_ID, BN_CLICKED) => {}
                        _ => {}
                    }
                });
                if relayout {
                    let panel = APP_STATE
                        .with(|slot| slot.borrow().as_ref().map(|state| state.settings_panel));
                    if let Some(panel) = panel {
                        send(panel, WM_VSCROLL, SB_TOP.0 as u16 as usize, 0);
                    }
                }
                LRESULT(0)
            }
            WM_NOTIFY => {
                if lparam.0 != 0 {
                    let notification = unsafe { &*(lparam.0 as *const NMLISTVIEW) };
                    if notification.hdr.idFrom == LEARNING_GRID_ID
                        && notification.hdr.code == LVN_ITEMCHANGED
                        && notification.uNewState & LVIS_SELECTED.0 != 0
                        && notification.iItem >= 0
                    {
                        APP_STATE.with(|slot| {
                            let mut slot = slot.borrow_mut();
                            if let Some(state) = slot.as_mut() {
                                state.selected_learning = Some(notification.iItem as usize);
                                show_learning_record(state);
                            }
                        });
                    }
                }
                LRESULT(0)
            }
            WM_DESTROY => {
                APP_STATE.with(|slot| *slot.borrow_mut() = None);
                unsafe { PostQuitMessage(0) };
                LRESULT(0)
            }
            _ => unsafe { DefWindowProcW(hwnd, message, wparam, lparam) },
        }
    }

    pub fn run() -> Result<(), Box<dyn std::error::Error>> {
        let controls = INITCOMMONCONTROLSEX {
            dwSize: std::mem::size_of::<INITCOMMONCONTROLSEX>() as u32,
            dwICC: ICC_LISTVIEW_CLASSES,
        };
        if !unsafe { InitCommonControlsEx(&controls) }.as_bool() {
            return Err(windows::core::Error::from_thread().into());
        }
        let instance = HINSTANCE(unsafe { GetModuleHandleW(None)? }.0);
        let panel_class = WNDCLASSW {
            lpfnWndProc: Some(settings_panel_proc),
            hInstance: instance,
            hbrBackground: unsafe { GetSysColorBrush(COLOR_WINDOW) },
            lpszClassName: SETTINGS_PANEL_CLASS,
            ..Default::default()
        };
        if unsafe { RegisterClassW(&panel_class) } == 0 {
            return Err(windows::core::Error::from_thread().into());
        }
        let content_class = WNDCLASSW {
            lpfnWndProc: Some(settings_content_proc),
            hInstance: instance,
            hbrBackground: unsafe { GetSysColorBrush(COLOR_WINDOW) },
            lpszClassName: SETTINGS_CONTENT_CLASS,
            ..Default::default()
        };
        if unsafe { RegisterClassW(&content_class) } == 0 {
            return Err(windows::core::Error::from_thread().into());
        }
        let class = WNDCLASSW {
            lpfnWndProc: Some(window_proc),
            hInstance: instance,
            hbrBackground: unsafe { GetSysColorBrush(COLOR_WINDOW) },
            lpszClassName: CLASS_NAME,
            ..Default::default()
        };
        if unsafe { RegisterClassW(&class) } == 0 {
            return Err(windows::core::Error::from_thread().into());
        }

        let hwnd = unsafe {
            CreateWindowExW(
                WINDOW_EX_STYLE::default(),
                CLASS_NAME,
                w!("Karukan IME 設定"),
                WS_OVERLAPPEDWINDOW | WS_VISIBLE,
                CW_USEDEFAULT,
                CW_USEDEFAULT,
                920,
                800,
                None,
                None,
                Some(instance),
                None,
            )?
        };
        unsafe {
            let _ = ShowWindow(hwnd, windows::Win32::UI::WindowsAndMessaging::SW_SHOW);
            if UpdateWindow(hwnd).0 == 0 {
                return Err(windows::core::Error::from_thread().into());
            }
        }

        let mut message = MSG::default();
        loop {
            let result = unsafe { GetMessageW(&mut message, None, 0, 0) };
            if result.0 == 0 {
                break;
            }
            if result.0 < 0 {
                return Err(windows::core::Error::from_thread().into());
            }
            unsafe {
                let _ = TranslateMessage(&message);
                DispatchMessageW(&message);
            }
        }
        Ok(())
    }
}

#[cfg(windows)]
fn main() -> Result<(), Box<dyn std::error::Error>> {
    app::run()
}

#[cfg(not(windows))]
fn main() {
    eprintln!("karukan-settings is available only on Windows.");
}
