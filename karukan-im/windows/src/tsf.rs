use std::cell::{Cell, RefCell};
use std::ffi::c_void;
use std::mem::ManuallyDrop;
use std::ptr;
use std::rc::Rc;
use std::sync::{Arc, Mutex, Once};

use karukan_im::config::Settings;
use karukan_im::core::candidate::CandidateList;
use karukan_im::core::engine::{EngineAction, EngineConfig, InputMethodEngine};
use karukan_im::core::keycode::{KeyEvent, KeyModifiers, Keysym};
use windows::Win32::Foundation::{
    CLASS_E_CLASSNOTAVAILABLE, CLASS_E_NOAGGREGATION, E_INVALIDARG, E_POINTER, HWND, POINT, RECT,
    S_FALSE,
};
use windows::Win32::Graphics::Gdi::{
    COLOR_WINDOW, GetMonitorInfoW, GetSysColorBrush, HBITMAP, MonitorFromPoint, MONITORINFO,
    MONITOR_DEFAULTTONEAREST,
};
use windows::Win32::System::Com::{IClassFactory, IClassFactory_Impl};
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::System::Variant::{VARIANT, VARIANT_0, VARIANT_0_0, VARIANT_0_0_0, VT_I4};
use windows::Win32::UI::Input::KeyboardAndMouse::{
    GetKeyState, GetKeyboardLayout, GetKeyboardState, ToUnicodeEx, VK_CONTROL, VK_CONVERT,
    VK_DBE_ALPHANUMERIC, VK_KANA, VK_LWIN, VK_MENU, VK_NONCONVERT, VK_RWIN, VK_SHIFT,
};
use windows::Win32::UI::TextServices::{
    GUID_COMPARTMENT_KEYBOARD_OPENCLOSE, GUID_LBI_INPUTMODE, GUID_PROP_COMPOSING,
    INSERT_TEXT_AT_SELECTION_FLAGS, ITfClientId, ITfCompartmentEventSink, ITfCompartmentEventSink_Impl, ITfComposition,
    ITfCompositionSink, ITfCompositionSink_Impl, ITfContext, ITfContextComposition, ITfEditSession,
    ITfEditSession_Impl, ITfInsertAtSelection, ITfKeyEventSink, ITfKeyEventSink_Impl,
    ITfKeystrokeMgr, ITfLangBarItem, ITfLangBarItemButton, ITfLangBarItemButton_Impl,
    ITfLangBarItemMgr, ITfLangBarItemSink, ITfLangBarItem_Impl, ITfMenu, ITfSource,
    ITfSource_Impl, ITfTextInputProcessor, ITfTextInputProcessor_Impl, ITfThreadMgr, TF_AE_END,
    TF_ANCHOR_END, TF_ANCHOR_START, TF_CONTEXT_EDIT_CONTEXT_FLAGS, TF_LANGBARITEMINFO,
    TF_LBI_CLK_LEFT, TF_LBI_CLK_RIGHT, TF_LBI_STYLE_BTN_BUTTON,
    TF_LBI_STYLE_SHOWNINTRAY, TF_LBMENUF_RADIOCHECKED, TF_LBMENUF_SEPARATOR, TF_SELECTION,
    TF_SELECTIONSTYLE, TfLBIClick,
};
use windows::Win32::UI::WindowsAndMessaging::{
    AppendMenuW, CreatePopupMenu, CreateWindowExW, DefWindowProcW,
    DestroyMenu, GetForegroundWindow, HICON, HWND_TOPMOST, LB_ADDSTRING,
    LB_RESETCONTENT, LB_SETCURSEL, MA_NOACTIVATE, MF_CHECKED, MF_SEPARATOR, MF_STRING,
    RegisterClassW, SW_HIDE, SW_SHOWNOACTIVATE, SWP_NOACTIVATE, SWP_SHOWWINDOW,
    SendMessageW, SetForegroundWindow, SetWindowPos, SetWindowTextW, ShowWindow,
    TPM_BOTTOMALIGN, TPM_LEFTALIGN, TPM_NONOTIFY, TPM_RETURNCMD, TrackPopupMenu,
    WINDOW_STYLE, WM_MOUSEACTIVATE, WNDCLASSW, WS_BORDER, WS_CHILD, WS_EX_NOACTIVATE,
    WS_EX_TOOLWINDOW, WS_EX_TOPMOST, WS_POPUP, WS_VISIBLE, WS_VSCROLL,
};
use windows::core::{BSTR, BOOL, GUID, HRESULT, IUnknown, Interface, PCWSTR, Ref, implement, w};

use crate::{
    CLSID_KARUKAN_TIP, GUID_COMPARTMENT_KEYBOARD_INPUTMODE_CONVERSION,
    RES_ID_ICON_ALPHA, RES_ID_ICON_HIRAGANA, RES_ID_ICON_KATAKANA,
    TF_CONVERSIONMODE_ALPHANUMERIC, TF_CONVERSIONMODE_FULLSHAPE, TF_CONVERSIONMODE_KATAKANA,
    TF_CONVERSIONMODE_NATIVE, TF_CONVERSIONMODE_ROMAN,
};

const EDIT_SESSION_FLAGS: TF_CONTEXT_EDIT_CONTEXT_FLAGS = TF_CONTEXT_EDIT_CONTEXT_FLAGS(7);
static INIT_LOGGING: Once = Once::new();

fn init_logging() {
    INIT_LOGGING.call_once(|| {
        let log_file = std::env::var_os("LOCALAPPDATA").and_then(|p| {
            let dir = std::path::PathBuf::from(p)
                .join("karukan")
                .join("karukan-im")
                .join("logs");
            std::fs::create_dir_all(&dir).ok()?;
            std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open(dir.join("windows-tsf.log"))
                .ok()
        });
        if let Some(file) = log_file {
            let _ = tracing_subscriber::fmt()
                .with_env_filter(
                    tracing_subscriber::EnvFilter::try_from_default_env()
                        .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
                )
                .with_writer(std::sync::Mutex::new(file))
                .with_ansi(false)
                .try_init();
        } else {
            let _ = tracing_subscriber::fmt()
                .with_env_filter(
                    tracing_subscriber::EnvFilter::try_from_default_env()
                        .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
                )
                .with_writer(std::io::stderr)
                .try_init();
        }
        let pid = std::process::id();
        let exe = std::env::current_exe()
            .map(|p| p.display().to_string())
            .unwrap_or_default();
        tracing::info!("=== Karukan TSF Logger Initialized (PID: {pid}, Process: {exe}) ===");
    });
}

#[implement(IClassFactory)]
struct ClassFactory;

impl IClassFactory_Impl for ClassFactory_Impl {
    fn CreateInstance(
        &self,
        outer: Ref<IUnknown>,
        iid: *const GUID,
        output: *mut *mut c_void,
    ) -> windows::core::Result<()> {
        if output.is_null() || iid.is_null() {
            return Err(E_POINTER.into());
        }
        unsafe {
            *output = ptr::null_mut();
        }
        if outer.as_ref().is_some() {
            return Err(CLASS_E_NOAGGREGATION.into());
        }

        let instance: ITfTextInputProcessor = TextService::new()?.into();
        unsafe { instance.query(iid, output).ok() }
    }

    fn LockServer(&self, _lock: BOOL) -> windows::core::Result<()> {
        Ok(())
    }
}

#[implement(ITfCompartmentEventSink)]
struct CompartmentSink {
    engine: Arc<Mutex<InputMethodEngine>>,
    thread_mgr: ITfThreadMgr,
    lang_bar_sink: Rc<RefCell<Option<ITfLangBarItemSink>>>,
}

impl ITfCompartmentEventSink_Impl for CompartmentSink_Impl {
    fn OnChange(&self, rguid: *const GUID) -> windows::core::Result<()> {
        if rguid.is_null() {
            return Ok(());
        }
        let guid = unsafe { *rguid };
        tracing::info!("CompartmentSink::OnChange called, guid={guid:?}");
        if guid == GUID_COMPARTMENT_KEYBOARD_OPENCLOSE {
            if let Ok(open) = get_ime_openclose(&self.thread_mgr) {
                tracing::info!("CompartmentSink: OPENCLOSE changed to {open}");
                if !open {
                    if let Ok(mut engine) = self.engine.lock() {
                        engine.reset();
                    }
                }
            }
        } else if guid == GUID_COMPARTMENT_KEYBOARD_INPUTMODE_CONVERSION {
            if let Ok(conversion) = get_input_mode_conversion(&self.thread_mgr) {
                tracing::info!("CompartmentSink: INPUTMODE_CONVERSION changed to 0x{conversion:04X}");
                if (conversion & TF_CONVERSIONMODE_KATAKANA) != 0 {
                    if let Ok(mut engine) = self.engine.lock() {
                        let event = KeyEvent::new(
                            Keysym::KEY_K,
                            KeyModifiers {
                                control_key: true,
                                ..Default::default()
                            },
                            true,
                        );
                        let _ = engine.process_key(&event);
                    }
                } else if (conversion & TF_CONVERSIONMODE_NATIVE) != 0 {
                    if let Ok(mut engine) = self.engine.lock() {
                        let event = KeyEvent::new(Keysym::HENKAN, KeyModifiers::default(), true);
                        let _ = engine.process_key(&event);
                    }
                }
            }
        }
        if let Some(sink) = self.lang_bar_sink.borrow().as_ref() {
            tracing::info!("CompartmentSink notifying LangBarItemSink OnUpdate");
            let _ = unsafe { sink.OnUpdate(0x001F) };
        }
        Ok(())
    }
}

#[implement(ITfTextInputProcessor)]
struct TextService {
    thread_id: RefCell<Option<u32>>,
    keystroke_mgr: RefCell<Option<ITfKeystrokeMgr>>,
    key_sink: RefCell<Option<ITfKeyEventSink>>,
    engine: RefCell<Option<Arc<Mutex<InputMethodEngine>>>>,
    compartment_sources: RefCell<Vec<(ITfSource, u32)>>,
    lang_bar_item: RefCell<Option<ITfLangBarItemButton>>,
    thread_mgr: RefCell<Option<ITfThreadMgr>>,
}

impl TextService {
    fn new() -> windows::core::Result<Self> {
        init_logging();
        Ok(Self {
            thread_id: RefCell::new(None),
            keystroke_mgr: RefCell::new(None),
            key_sink: RefCell::new(None),
            engine: RefCell::new(None),
            compartment_sources: RefCell::new(Vec::new()),
            lang_bar_item: RefCell::new(None),
            thread_mgr: RefCell::new(None),
        })
    }
}

#[implement(ITfLangBarItem, ITfLangBarItemButton, ITfSource)]
struct LangBarButton {
    engine: Arc<Mutex<InputMethodEngine>>,
    thread_mgr: ITfThreadMgr,
    client_id: u32,
    sink: Rc<RefCell<Option<ITfLangBarItemSink>>>,
    cookie: Cell<u32>,
}

fn get_current_module_handle() -> windows::Win32::Foundation::HMODULE {
    use windows::Win32::System::LibraryLoader::{
        GetModuleHandleExW, GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS,
        GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
    };
    let mut hmodule = windows::Win32::Foundation::HMODULE::default();
    unsafe {
        let _ = GetModuleHandleExW(
            GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
            windows::core::PCWSTR(get_current_module_handle as *const () as _),
            &mut hmodule,
        );
    }
    hmodule
}

fn load_resource_icon(res_id: u16) -> windows::core::Result<HICON> {
    use windows::Win32::Foundation::HINSTANCE;
    use windows::Win32::UI::WindowsAndMessaging::{
        GetSystemMetrics, LoadImageW, IMAGE_ICON, LR_DEFAULTCOLOR, LR_SHARED, SM_CXSMICON, SM_CYSMICON,
    };
    let hinstance = HINSTANCE(get_current_module_handle().0);
    let cx = unsafe { GetSystemMetrics(SM_CXSMICON) };
    let cy = unsafe { GetSystemMetrics(SM_CYSMICON) };
    unsafe {
        let handle = LoadImageW(
            Some(hinstance),
            windows::core::PCWSTR(res_id as usize as *const u16),
            IMAGE_ICON,
            cx,
            cy,
            LR_DEFAULTCOLOR | LR_SHARED,
        )?;
        Ok(HICON(handle.0))
    }
}

impl ITfLangBarItem_Impl for LangBarButton_Impl {
    fn GetInfo(&self, pinfo: *mut TF_LANGBARITEMINFO) -> windows::core::Result<()> {
        if pinfo.is_null() {
            return Err(E_POINTER.into());
        }
        let mut desc = [0u16; 32];
        let text = "Input Mode";
        for (i, c) in text.encode_utf16().enumerate().take(31) {
            desc[i] = c;
        }
        unsafe {
            *pinfo = TF_LANGBARITEMINFO {
                clsidService: CLSID_KARUKAN_TIP,
                guidItem: GUID_LBI_INPUTMODE,
                dwStyle: TF_LBI_STYLE_BTN_BUTTON | TF_LBI_STYLE_SHOWNINTRAY,
                ulSort: 0,
                szDescription: desc,
            };
        }
        Ok(())
    }

    fn GetStatus(&self) -> windows::core::Result<u32> {
        Ok(0)
    }

    fn Show(&self, _fshow: BOOL) -> windows::core::Result<()> {
        Ok(())
    }

    fn GetTooltipString(&self) -> windows::core::Result<BSTR> {
        Ok(BSTR::from("Karukan Japanese Input"))
    }
}

impl LangBarButton_Impl {
    fn handle_menu_select(&self, wid: u32) -> windows::core::Result<()> {
        tracing::info!("LangBarButton::handle_menu_select called, wid={wid}");
        match wid {
            1 => {
                set_ime_openclose(&self.thread_mgr, self.client_id, true)?;
                set_input_mode_conversion(
                    &self.thread_mgr,
                    self.client_id,
                    TF_CONVERSIONMODE_NATIVE | TF_CONVERSIONMODE_FULLSHAPE | TF_CONVERSIONMODE_ROMAN,
                )?;
                if let Ok(mut engine) = self.engine.lock() {
                    let event = KeyEvent::new(Keysym::HENKAN, current_modifiers(), true);
                    let _ = engine.process_key(&event);
                }
            }
            2 => {
                set_ime_openclose(&self.thread_mgr, self.client_id, true)?;
                set_input_mode_conversion(
                    &self.thread_mgr,
                    self.client_id,
                    TF_CONVERSIONMODE_NATIVE
                        | TF_CONVERSIONMODE_KATAKANA
                        | TF_CONVERSIONMODE_FULLSHAPE
                        | TF_CONVERSIONMODE_ROMAN,
                )?;
                if let Ok(mut engine) = self.engine.lock() {
                    let event = KeyEvent::new(
                        Keysym::KEY_K,
                        KeyModifiers {
                            control_key: true,
                            ..Default::default()
                        },
                        true,
                    );
                    let _ = engine.process_key(&event);
                }
            }
            3 => {
                set_ime_openclose(&self.thread_mgr, self.client_id, false)?;
                set_input_mode_conversion(
                    &self.thread_mgr,
                    self.client_id,
                    TF_CONVERSIONMODE_ALPHANUMERIC,
                )?;
                if let Ok(mut engine) = self.engine.lock() {
                    engine.reset();
                }
            }
            5 => {
                spawn_settings_app();
            }
            _ => {}
        }
        if let Some(sink) = self.sink.borrow().as_ref() {
            let _ = unsafe { sink.OnUpdate(0x001F) };
        }
        Ok(())
    }
}

impl ITfLangBarItemButton_Impl for LangBarButton_Impl {
    fn OnClick(
        &self,
        click: TfLBIClick,
        pt: &POINT,
        _prcarea: *const RECT,
    ) -> windows::core::Result<()> {
        tracing::info!("LangBarButton::OnClick called, click={click:?}, pt=({}, {})", pt.x, pt.y);
        if click == TF_LBI_CLK_LEFT {
            toggle_ime_openclose(&self.thread_mgr, self.client_id)?;
            if let Some(sink) = self.sink.borrow().as_ref() {
                let _ = unsafe { sink.OnUpdate(0x001F) };
            }
        } else if click == TF_LBI_CLK_RIGHT {
            tracing::info!("LangBarButton context menu opening at ({}, {})", pt.x, pt.y);
            unsafe {
                let hwnd = GetForegroundWindow();
                if let Ok(hmenu) = CreatePopupMenu() {
                    let is_open = get_ime_openclose(&self.thread_mgr).unwrap_or(true);
                    let conversion = get_input_mode_conversion(&self.thread_mgr).unwrap_or(
                        TF_CONVERSIONMODE_NATIVE | TF_CONVERSIONMODE_FULLSHAPE | TF_CONVERSIONMODE_ROMAN,
                    );
                    let is_katakana = is_open && (conversion & TF_CONVERSIONMODE_KATAKANA != 0);
                    let is_hiragana = is_open && !is_katakana && (conversion & TF_CONVERSIONMODE_NATIVE != 0);
                    let is_alpha = !is_open || (!is_katakana && !is_hiragana);

                    let flag_hiragana = if is_hiragana { MF_CHECKED } else { windows::Win32::UI::WindowsAndMessaging::MENU_ITEM_FLAGS(0) };
                    let flag_katakana = if is_katakana { MF_CHECKED } else { windows::Win32::UI::WindowsAndMessaging::MENU_ITEM_FLAGS(0) };
                    let flag_alpha = if is_alpha { MF_CHECKED } else { windows::Win32::UI::WindowsAndMessaging::MENU_ITEM_FLAGS(0) };

                    let _ = AppendMenuW(hmenu, MF_STRING | flag_hiragana, 1, w!("ひらがな (Hiragana)"));
                    let _ = AppendMenuW(hmenu, MF_STRING | flag_katakana, 2, w!("全角カタカナ (Katakana)"));
                    let _ = AppendMenuW(hmenu, MF_STRING | flag_alpha, 3, w!("直接入力 / 半角英数 (Direct)"));
                    let _ = AppendMenuW(hmenu, MF_SEPARATOR, 0, PCWSTR::null());
                    let _ = AppendMenuW(hmenu, MF_STRING, 5, w!("設定 (Settings)..."));

                    let _ = SetForegroundWindow(hwnd);
                    let cmd = TrackPopupMenu(
                        hmenu,
                        TPM_LEFTALIGN | TPM_BOTTOMALIGN | TPM_RETURNCMD | TPM_NONOTIFY,
                        pt.x,
                        pt.y,
                        Some(0),
                        hwnd,
                        None,
                    );
                    let _ = DestroyMenu(hmenu);

                    tracing::info!("LangBarButton context menu result cmd: {}", cmd.0);
                    if cmd.0 != 0 {
                        self.handle_menu_select(cmd.0 as u32)?;
                    }
                }
            }
        }
        Ok(())
    }

    fn InitMenu(&self, pmenu: Ref<ITfMenu>) -> windows::core::Result<()> {
        tracing::info!("LangBarButton::InitMenu called");
        let Some(pmenu) = pmenu.as_ref() else {
            return Ok(());
        };
        let is_open = get_ime_openclose(&self.thread_mgr).unwrap_or(true);
        let conversion = get_input_mode_conversion(&self.thread_mgr).unwrap_or(
            TF_CONVERSIONMODE_NATIVE | TF_CONVERSIONMODE_FULLSHAPE | TF_CONVERSIONMODE_ROMAN,
        );
        let is_katakana = is_open && (conversion & TF_CONVERSIONMODE_KATAKANA != 0);
        let is_hiragana = is_open && !is_katakana && (conversion & TF_CONVERSIONMODE_NATIVE != 0);
        let is_alpha = !is_open || (!is_katakana && !is_hiragana);

        let flag_hiragana = if is_hiragana {
            TF_LBMENUF_RADIOCHECKED
        } else {
            0
        };
        let flag_katakana = if is_katakana {
            TF_LBMENUF_RADIOCHECKED
        } else {
            0
        };
        let flag_alpha = if is_alpha {
            TF_LBMENUF_RADIOCHECKED
        } else {
            0
        };

        let hiragana_text: Vec<u16> = "ひらがな (Hiragana)".encode_utf16().collect();
        let katakana_text: Vec<u16> = "全角カタカナ (Katakana)".encode_utf16().collect();
        let alpha_text: Vec<u16> = "直接入力 / 半角英数 (Direct)".encode_utf16().collect();
        let settings_text: Vec<u16> = "設定 (Settings)...".encode_utf16().collect();

        unsafe {
            pmenu.AddMenuItem(
                1,
                flag_hiragana,
                HBITMAP::default(),
                HBITMAP::default(),
                &hiragana_text,
                ptr::null_mut(),
            )?;
            pmenu.AddMenuItem(
                2,
                flag_katakana,
                HBITMAP::default(),
                HBITMAP::default(),
                &katakana_text,
                ptr::null_mut(),
            )?;
            pmenu.AddMenuItem(
                3,
                flag_alpha,
                HBITMAP::default(),
                HBITMAP::default(),
                &alpha_text,
                ptr::null_mut(),
            )?;
            pmenu.AddMenuItem(
                4,
                TF_LBMENUF_SEPARATOR,
                HBITMAP::default(),
                HBITMAP::default(),
                &[],
                ptr::null_mut(),
            )?;
            pmenu.AddMenuItem(
                5,
                0,
                HBITMAP::default(),
                HBITMAP::default(),
                &settings_text,
                ptr::null_mut(),
            )?;
        }
        tracing::info!("LangBarButton::InitMenu added 5 menu items");
        Ok(())
    }

    fn OnMenuSelect(&self, wid: u32) -> windows::core::Result<()> {
        tracing::info!("LangBarButton::OnMenuSelect called, wid={wid}");
        self.handle_menu_select(wid)
    }

    fn GetIcon(&self) -> windows::core::Result<HICON> {
        let is_open = get_ime_openclose(&self.thread_mgr).unwrap_or(true);
        let conversion = get_input_mode_conversion(&self.thread_mgr).unwrap_or(
            TF_CONVERSIONMODE_NATIVE | TF_CONVERSIONMODE_FULLSHAPE | TF_CONVERSIONMODE_ROMAN,
        );
        let res_id = if !is_open {
            RES_ID_ICON_ALPHA
        } else if (conversion & TF_CONVERSIONMODE_KATAKANA) != 0 {
            RES_ID_ICON_KATAKANA
        } else {
            RES_ID_ICON_HIRAGANA
        };
        load_resource_icon(res_id)
    }

    fn GetText(&self) -> windows::core::Result<BSTR> {
        let is_open = get_ime_openclose(&self.thread_mgr).unwrap_or(true);
        let conversion = get_input_mode_conversion(&self.thread_mgr).unwrap_or(
            TF_CONVERSIONMODE_NATIVE | TF_CONVERSIONMODE_FULLSHAPE | TF_CONVERSIONMODE_ROMAN,
        );
        let text = if !is_open {
            "A"
        } else if (conversion & TF_CONVERSIONMODE_KATAKANA) != 0 {
            "カ"
        } else {
            "あ"
        };
        Ok(BSTR::from(text))
    }
}

impl ITfSource_Impl for LangBarButton_Impl {
    fn AdviseSink(&self, riid: *const GUID, punk: Ref<IUnknown>) -> windows::core::Result<u32> {
        if riid.is_null() {
            tracing::warn!("LangBarButton::AdviseSink called with null riid");
            return Err(E_INVALIDARG.into());
        }
        let guid = unsafe { *riid };
        tracing::info!("LangBarButton::AdviseSink called, riid={guid:?}");
        if guid == ITfLangBarItemSink::IID {
            let Some(punk) = punk.as_ref() else {
                tracing::warn!("LangBarButton::AdviseSink punk is None");
                return Err(E_INVALIDARG.into());
            };
            let sink: ITfLangBarItemSink = punk.cast()?;
            let _ = unsafe { sink.OnUpdate(0x0001 | 0x0002 | 0x0004) };
            *self.sink.borrow_mut() = Some(sink);
            self.cookie.set(1);
            tracing::info!("LangBarButton::AdviseSink successfully registered ITfLangBarItemSink (cookie=1)");
            Ok(1)
        } else {
            tracing::warn!("LangBarButton::AdviseSink unsupported riid={guid:?}");
            Err(windows::core::Error::from_hresult(HRESULT(0x80040202u32 as i32)))
        }
    }

    fn UnadviseSink(&self, pdwcookie: u32) -> windows::core::Result<()> {
        tracing::info!("LangBarButton::UnadviseSink called, cookie={pdwcookie}");
        if pdwcookie == self.cookie.get() {
            *self.sink.borrow_mut() = None;
            self.cookie.set(0);
            Ok(())
        } else {
            Err(windows::core::Error::from_hresult(HRESULT(0x80040200u32 as i32)))
        }
    }
}

fn spawn_settings_app() {
    let local_app_data = std::env::var_os("LOCALAPPDATA");
    let candidate = local_app_data.map(|p| {
        std::path::PathBuf::from(p)
            .join("Programs")
            .join("Karukan")
            .join("karukan-settings.exe")
    });
    let exe = if let Some(path) = candidate && path.exists() {
        path
    } else {
        std::path::PathBuf::from("karukan-settings.exe")
    };
    let _ = std::process::Command::new(exe).spawn();
}

impl ITfTextInputProcessor_Impl for TextService_Impl {
    fn Activate(&self, thread_mgr: Ref<ITfThreadMgr>, thread_id: u32) -> windows::core::Result<()> {
        let pid = std::process::id();
        let exe = std::env::current_exe()
            .map(|p| p.display().to_string())
            .unwrap_or_default();
        tracing::info!("Activate called (PID: {pid}, Process: {exe}, thread_id: {thread_id})");
        let thread_mgr = thread_mgr.as_ref().ok_or(E_POINTER)?;
        if let Err(error) = crate::configure_model_cache() {
            tracing::error!("failed to configure Hugging Face model cache: {error}");
        }
        let keystroke_mgr: ITfKeystrokeMgr = thread_mgr.cast()?;
        let client_ids: ITfClientId = thread_mgr.cast()?;
        let client_id = unsafe { client_ids.GetClientId(&CLSID_KARUKAN_TIP)? };

        let settings = Settings::load_or_default();
        let mut engine = InputMethodEngine::with_config(EngineConfig::from_settings(&settings));
        if let Err(error) = engine.init_from_settings(&settings) {
            tracing::error!("failed to initialize Karukan engine: {error:#}");
        }

        let composition = Rc::new(RefCell::new(None));
        let candidate_popup = Rc::new(RefCell::new(CandidatePopup::default()));
        let engine = Arc::new(Mutex::new(engine));
        let key_sink: ITfKeyEventSink = KeyHandler {
            engine: engine.clone(),
            thread_mgr: thread_mgr.clone(),
            client_id,
            thread_id,
            composition,
            candidate_popup,
        }
        .into();

        unsafe {
            keystroke_mgr.AdviseKeyEventSink(thread_id, &key_sink, true)?;
        }
        let lang_bar_sink = Rc::new(RefCell::new(None));
        let sink: ITfCompartmentEventSink = CompartmentSink {
            engine: engine.clone(),
            thread_mgr: thread_mgr.clone(),
            lang_bar_sink: lang_bar_sink.clone(),
        }
        .into();
        if let Ok(global) = unsafe { thread_mgr.GetGlobalCompartment() } {
            for guid in &[
                GUID_COMPARTMENT_KEYBOARD_OPENCLOSE,
                GUID_COMPARTMENT_KEYBOARD_INPUTMODE_CONVERSION,
            ] {
                if let Ok(compartment) = unsafe { global.GetCompartment(guid) }
                    && let Ok(source) = compartment.cast::<ITfSource>()
                    && let Ok(cookie) =
                        unsafe { source.AdviseSink(&ITfCompartmentEventSink::IID, &sink) }
                {
                    self.compartment_sources.borrow_mut().push((source, cookie));
                }
            }
        }
        let _ = set_ime_openclose(&thread_mgr, client_id, true);
        let _ = set_input_mode_conversion(
            &thread_mgr,
            client_id,
            TF_CONVERSIONMODE_NATIVE | TF_CONVERSIONMODE_FULLSHAPE | TF_CONVERSIONMODE_ROMAN,
        );

        match thread_mgr.cast::<ITfLangBarItemMgr>() {
            Ok(item_mgr) => {
                let button: ITfLangBarItemButton = LangBarButton {
                    engine: engine.clone(),
                    thread_mgr: thread_mgr.clone(),
                    client_id,
                    sink: lang_bar_sink,
                    cookie: Cell::new(0),
                }
                .into();
                let add_result = unsafe { item_mgr.AddItem(&button) };
                tracing::info!("ITfLangBarItemMgr::AddItem result: {add_result:?}");
                if add_result.is_ok() {
                    *self.lang_bar_item.borrow_mut() = Some(button);
                } else {
                    tracing::error!("ITfLangBarItemMgr::AddItem failed with {add_result:?}");
                }
            }
            Err(error) => {
                tracing::error!("failed to query ITfLangBarItemMgr from thread_mgr: {error:?}");
            }
        }
        *self.thread_id.borrow_mut() = Some(thread_id);
        *self.thread_mgr.borrow_mut() = Some(thread_mgr.clone());
        *self.keystroke_mgr.borrow_mut() = Some(keystroke_mgr);
        *self.key_sink.borrow_mut() = Some(key_sink);
        *self.engine.borrow_mut() = Some(engine);
        tracing::info!("Karukan TSF text service activated");
        Ok(())
    }

    fn Deactivate(&self) -> windows::core::Result<()> {
        if let (Some(thread_id), Some(keystroke_mgr)) = (
            self.thread_id.borrow_mut().take(),
            self.keystroke_mgr.borrow_mut().take(),
        ) {
            if let Err(error) = unsafe { keystroke_mgr.UnadviseKeyEventSink(thread_id) } {
                tracing::warn!("failed to unadvise Karukan key sink: {error}");
            }
        }
        for (source, cookie) in self.compartment_sources.borrow_mut().drain(..) {
            let _ = unsafe { source.UnadviseSink(cookie) };
        }
        if let (Some(button), Some(thread_mgr)) = (
            self.lang_bar_item.borrow_mut().take(),
            self.thread_mgr.borrow_mut().take(),
        ) {
            if let Ok(item_mgr) = thread_mgr.cast::<ITfLangBarItemMgr>() {
                let _ = unsafe { item_mgr.RemoveItem(&button) };
            }
        }
        self.key_sink.borrow_mut().take();
        if let Some(engine) = self.engine.borrow_mut().take() {
            match engine.lock() {
                Ok(mut engine) => engine.save_learning(),
                Err(error) => tracing::error!("failed to save learning cache: {error}"),
            }
        }
        Ok(())
    }
}

#[implement(ITfKeyEventSink)]
struct KeyHandler {
    engine: Arc<Mutex<InputMethodEngine>>,
    thread_mgr: ITfThreadMgr,
    client_id: u32,
    thread_id: u32,
    composition: Rc<RefCell<Option<ITfComposition>>>,
    candidate_popup: Rc<RefCell<CandidatePopup>>,
}

impl ITfKeyEventSink_Impl for KeyHandler_Impl {
    fn OnSetFocus(&self, _foreground: BOOL) -> windows::core::Result<()> {
        if let Ok(mut engine) = self.engine.lock() {
            engine.set_surrounding_context("", "");
        }
        Ok(())
    }

    fn OnTestKeyDown(
        &self,
        _context: Ref<ITfContext>,
        wparam: windows::Win32::Foundation::WPARAM,
        lparam: windows::Win32::Foundation::LPARAM,
    ) -> windows::core::Result<BOOL> {
        let vk = wparam.0 as u16;
        let modifiers = current_modifiers();

        // 1. 直接入力（英数）切り替えキー（無変換、英数など）
        if is_ime_close_key(vk, &modifiers) {
            return Ok(BOOL(1));
        }

        // 3. IMEが閉じている（直接入力）状態の判定
        let is_open = get_ime_openclose(&self.thread_mgr).unwrap_or(true);
        if !is_open {
            // IMEを開くキー（変換、かな等）のみ消費し、それ以外の英数字などはアプリへ直接通す
            if is_ime_open_key(vk, &modifiers) {
                return Ok(BOOL(1));
            }
            return Ok(BOOL(0));
        }

        // 4. かな/変換キー（fcitx5と同様、未変換時でも消費してひらがなモード復旧等に使用）
        if vk == VK_CONVERT.0 || vk == VK_KANA.0 || vk == VK_DBE_HIRAGANA_CODE || vk == VK_IME_ON_CODE {
            return Ok(BOOL((!modifiers.alt_key && !modifiers.super_key) as i32));
        }

        // 5. ファンクションキー（F6〜F10: 変換中のみ）
        if (0x75..=0x79).contains(&vk) {
            let engine = self
                .engine
                .lock()
                .map_err(|_| windows::core::Error::from_hresult(HRESULT(0x80004005u32 as i32)))?;
            if !engine.state().is_empty() {
                return Ok(BOOL(1));
            }
        }

        let Some(keysym) = translate_key(vk, lparam.0) else {
            return Ok(BOOL(0));
        };
        if modifiers.alt_key || modifiers.super_key {
            return Ok(BOOL(0));
        }
        let engine = self
            .engine
            .lock()
            .map_err(|_| windows::core::Error::from_hresult(HRESULT(0x80004005u32 as i32)))?;
        let active = !engine.state().is_empty();
        let control_binding = modifiers.control_key
            && (matches!(
                keysym.0,
                0x0049
                    | 0x0069
                    | 0x004b
                    | 0x006b
                    | 0x0052
                    | 0x0072
                    | 0x0054
                    | 0x0074
                    | 0x004a
                    | 0x006a
            ) || (modifiers.shift_key
                && matches!(keysym.0, 0x004c | 0x006c | 0x0056 | 0x0076)));
        Ok(BOOL(
            (keysym.is_printable() || active || control_binding) as i32,
        ))
    }

    fn OnTestKeyUp(
        &self,
        _context: Ref<ITfContext>,
        wparam: windows::Win32::Foundation::WPARAM,
        _lparam: windows::Win32::Foundation::LPARAM,
    ) -> windows::core::Result<BOOL> {
        let vk = wparam.0 as u16;
        let modifiers = current_modifiers();
        if is_ime_close_key(vk, &modifiers) || is_ime_open_key(vk, &modifiers) {
            return Ok(BOOL(1));
        }
        Ok(BOOL(0))
    }

    fn OnKeyDown(
        &self,
        context: Ref<ITfContext>,
        wparam: windows::Win32::Foundation::WPARAM,
        lparam: windows::Win32::Foundation::LPARAM,
    ) -> windows::core::Result<BOOL> {
        let vk = wparam.0 as u16;
        let modifiers = current_modifiers();

        // 1. 直接入力（英数）切り替えキー（無変換、英数など）
        if is_ime_close_key(vk, &modifiers) {
            if let Some(context) = context.as_ref() {
                let session: ITfEditSession = EditSession {
                    context: context.clone(),
                    actions: vec![EngineAction::HideCandidates, EngineAction::HideAuxText],
                    composition: self.composition.clone(),
                    candidate_popup: self.candidate_popup.clone(),
                    end_composition: true,
                    reset_engine: Some(self.engine.clone()),
                }
                .into();
                unsafe {
                    let _ = context.RequestEditSession(self.thread_id, &session, EDIT_SESSION_FLAGS);
                }
            }
            let _ = set_input_mode_conversion(
                &self.thread_mgr,
                self.client_id,
                TF_CONVERSIONMODE_ALPHANUMERIC,
            );
            set_ime_openclose(&self.thread_mgr, self.client_id, false)?;
            if let Ok(mut engine) = self.engine.lock() {
                engine.set_surrounding_context("", "");
            }
            return Ok(BOOL(1));
        }

        // 3. IMEが閉じている状態でのキー処理
        let is_open = get_ime_openclose(&self.thread_mgr).unwrap_or(true);
        if !is_open {
            if is_ime_open_key(vk, &modifiers) {
                set_ime_openclose(&self.thread_mgr, self.client_id, true)?;
                let _ = set_input_mode_conversion(
                    &self.thread_mgr,
                    self.client_id,
                    TF_CONVERSIONMODE_NATIVE | TF_CONVERSIONMODE_FULLSHAPE | TF_CONVERSIONMODE_ROMAN,
                );
                return Ok(BOOL(1));
            }
            return Ok(BOOL(0));
        }
        let Some(context) = context.as_ref() else {
            return Ok(BOOL(0));
        };
        let vk = wparam.0 as u16;
        if vk == VK_KANA.0 {
            let _ = set_ime_openclose(&self.thread_mgr, self.client_id, true);
            let _ = set_input_mode_conversion(
                &self.thread_mgr,
                self.client_id,
                TF_CONVERSIONMODE_NATIVE | TF_CONVERSIONMODE_FULLSHAPE | TF_CONVERSIONMODE_ROMAN,
            );
            let mut engine = self
                .engine
                .lock()
                .map_err(|_| windows::core::Error::from_hresult(HRESULT(0x80004005u32 as i32)))?;
            let event = KeyEvent::new(Keysym::HENKAN, current_modifiers(), true);
            let result = engine.process_key(&event);
            if !result.actions.is_empty() {
                let session: ITfEditSession = EditSession {
                    context: context.clone(),
                    actions: result.actions,
                    composition: self.composition.clone(),
                    candidate_popup: self.candidate_popup.clone(),
                    end_composition: false,
                    reset_engine: None,
                }
                .into();
                unsafe {
                    context
                        .RequestEditSession(self.thread_id, &session, EDIT_SESSION_FLAGS)?
                        .ok()?;
                }
            }
            return Ok(BOOL(1));
        }
        if (0x75..=0x79).contains(&vk) {
            let mut engine = self
                .engine
                .lock()
                .map_err(|_| windows::core::Error::from_hresult(HRESULT(0x80004005u32 as i32)))?;
            if !engine.state().is_empty() {
                if let Some(preedit) = engine.state().preedit() {
                    let text = preedit.text().to_string();
                    let converted = convert_function_key(&text, vk);
                    engine.reset();
                    let session: ITfEditSession = EditSession {
                        context: context.clone(),
                        actions: vec![
                            EngineAction::Commit(converted),
                            EngineAction::HideCandidates,
                            EngineAction::HideAuxText,
                        ],
                        composition: self.composition.clone(),
                        candidate_popup: self.candidate_popup.clone(),
                        end_composition: true,
                        reset_engine: None,
                    }
                    .into();
                    unsafe {
                        context
                            .RequestEditSession(self.thread_id, &session, EDIT_SESSION_FLAGS)?
                            .ok()?;
                    }
                    return Ok(BOOL(1));
                }
            }
        }
        let Some(keysym) = translate_key(wparam.0 as u16, lparam.0) else {
            return Ok(BOOL(0));
        };
        if self.composition.borrow().is_none() && (keysym.is_printable() || keysym.0 < 0xff00) {
            let surrounding = capture_surrounding_text(context, self.thread_id);
            if let Ok(mut engine) = self.engine.lock() {
                engine.set_surrounding_context(&surrounding, "");
            }
        }
        let modifiers = current_modifiers();
        if modifiers.control_key && (keysym == Keysym::KEY_K || keysym == Keysym::KEY_K_UPPER) {
            let _ = set_input_mode_conversion(
                &self.thread_mgr,
                self.client_id,
                TF_CONVERSIONMODE_NATIVE
                    | TF_CONVERSIONMODE_KATAKANA
                    | TF_CONVERSIONMODE_FULLSHAPE
                    | TF_CONVERSIONMODE_ROMAN,
            );
        } else if keysym == Keysym::HENKAN {
            let _ = set_ime_openclose(&self.thread_mgr, self.client_id, true);
            let _ = set_input_mode_conversion(
                &self.thread_mgr,
                self.client_id,
                TF_CONVERSIONMODE_NATIVE | TF_CONVERSIONMODE_FULLSHAPE | TF_CONVERSIONMODE_ROMAN,
            );
        }
        let event = KeyEvent::new(keysym, modifiers, true);
        let result = {
            let mut engine = self
                .engine
                .lock()
                .map_err(|_| windows::core::Error::from_hresult(HRESULT(0x80004005u32 as i32)))?;
            engine.process_key(&event)
        };
        let committed = result
            .actions
            .iter()
            .any(|action| matches!(action, EngineAction::Commit(_)));
        if !result.actions.is_empty() {
            let session: ITfEditSession = EditSession {
                context: context.clone(),
                actions: result.actions,
                composition: self.composition.clone(),
                candidate_popup: self.candidate_popup.clone(),
                end_composition: false,
                reset_engine: None,
            }
            .into();
            unsafe {
                context
                    .RequestEditSession(self.thread_id, &session, EDIT_SESSION_FLAGS)?
                    .ok()?;
            }
        }
        if committed {
            self.engine
                .lock()
                .map_err(|_| windows::core::Error::from_hresult(HRESULT(0x80004005u32 as i32)))?
                .save_learning();
        }
        Ok(BOOL(result.consumed as i32))
    }

    fn OnKeyUp(
        &self,
        _context: Ref<ITfContext>,
        wparam: windows::Win32::Foundation::WPARAM,
        _lparam: windows::Win32::Foundation::LPARAM,
    ) -> windows::core::Result<BOOL> {
        let vk = wparam.0 as u16;
        let modifiers = current_modifiers();
        if is_ime_close_key(vk, &modifiers) || is_ime_open_key(vk, &modifiers) {
            return Ok(BOOL(1));
        }
        Ok(BOOL(0))
    }

    fn OnPreservedKey(
        &self,
        _context: Ref<ITfContext>,
        _guid: *const GUID,
    ) -> windows::core::Result<BOOL> {
        Ok(BOOL(0))
    }
}

#[implement(ITfEditSession)]
struct EditSession {
    context: ITfContext,
    actions: Vec<EngineAction>,
    composition: Rc<RefCell<Option<ITfComposition>>>,
    candidate_popup: Rc<RefCell<CandidatePopup>>,
    end_composition: bool,
    reset_engine: Option<Arc<Mutex<InputMethodEngine>>>,
}

impl ITfEditSession_Impl for EditSession_Impl {
    fn DoEditSession(&self, edit_cookie: u32) -> windows::core::Result<()> {
        if self.end_composition
            && let Some(active) = self.composition.borrow_mut().take()
        {
            unsafe { active.EndComposition(edit_cookie)? };
        }
        if let Some(engine) = &self.reset_engine {
            engine
                .lock()
                .map_err(|_| windows::core::Error::from_hresult(HRESULT(0x80004005u32 as i32)))?
                .reset();
        }
        for action in &self.actions {
            match action {
                EngineAction::UpdatePreedit(preedit) => {
                    update_preedit(
                        &self.context,
                        edit_cookie,
                        &self.composition,
                        preedit.text(),
                        preedit.caret(),
                    )?;
                }
                EngineAction::Commit(text) => {
                    commit_text(&self.context, edit_cookie, self.composition.as_ref(), text)?;
                }
                EngineAction::ShowCandidates(candidates) => {
                    if let Err(error) = self.candidate_popup.borrow_mut().show(
                        &self.context,
                        edit_cookie,
                        &self.composition,
                        candidates,
                    ) {
                        tracing::error!("failed to show Karukan candidate window: {error}");
                    }
                }
                EngineAction::HideCandidates => self.candidate_popup.borrow_mut().hide(),
                EngineAction::UpdateAuxText(text) => {
                    if let Err(error) = self.candidate_popup.borrow_mut().set_aux(text) {
                        tracing::error!("failed to update Karukan candidate window: {error}");
                    }
                }
                EngineAction::HideAuxText => {
                    if let Err(error) = self.candidate_popup.borrow_mut().set_aux("") {
                        tracing::error!("failed to update Karukan candidate window: {error}");
                    }
                }
            }
        }
        Ok(())
    }
}

#[implement(ITfEditSession)]
struct CaptureContextSession {
    context: ITfContext,
    left_context: Rc<RefCell<String>>,
}

impl ITfEditSession_Impl for CaptureContextSession_Impl {
    fn DoEditSession(&self, edit_cookie: u32) -> windows::core::Result<()> {
        let mut selected = [TF_SELECTION::default()];
        let mut fetched = 0;
        unsafe { self.context.GetSelection(edit_cookie, 0, &mut selected, &mut fetched)? };
        if fetched == 0 {
            return Ok(());
        }
        let Some(range) = (unsafe { ManuallyDrop::take(&mut selected[0].range) }) else {
            return Ok(());
        };
        let left_range = unsafe { range.Clone()? };
        unsafe {
            left_range.Collapse(edit_cookie, TF_ANCHOR_START)?;
            let mut shifted = 0;
            // ShiftStart with negative count moves the start anchor towards document start.
            left_range.ShiftStart(edit_cookie, -64, &mut shifted, ptr::null())?;
        }
        let mut buffer = [0u16; 64];
        let mut read_chars = 0;
        unsafe {
            left_range.GetText(edit_cookie, 0, &mut buffer, &mut read_chars)?;
        }
        if read_chars > 0 {
            let len = (read_chars as usize).min(buffer.len());
            let text = String::from_utf16_lossy(&buffer[..len]);
            *self.left_context.borrow_mut() = text;
        }
        Ok(())
    }
}

fn capture_surrounding_text(context: &ITfContext, thread_id: u32) -> String {
    let left_context = Rc::new(RefCell::new(String::new()));
    let session: ITfEditSession = CaptureContextSession {
        context: context.clone(),
        left_context: left_context.clone(),
    }
    .into();
    unsafe {
        let _ = context.RequestEditSession(thread_id, &session, EDIT_SESSION_FLAGS);
    }
    let text = left_context.borrow().clone();
    text
}

fn convert_function_key(text: &str, vk: u16) -> String {
    match vk {
        0x75 => karukan_engine::kana::katakana_to_hiragana(text), // F6: ひらがな
        0x76 => karukan_engine::kana::hiragana_to_katakana(text), // F7: 全角カタカナ
        0x77 => {
            // F8: 半角カタカナ
            let katakana = karukan_engine::kana::hiragana_to_katakana(text);
            karukan_engine::kana::katakana_to_half_width(&katakana)
        }
        0x78 => text
            .chars()
            .map(karukan_engine::kana::ascii_to_fullwidth_char)
            .collect(), // F9: 全角英数
        0x79 => text
            .chars()
            .map(karukan_engine::kana::fullwidth_to_ascii_char)
            .collect(), // F10: 半角英数
        _ => text.to_string(),
    }
}

const VK_IME_ON_CODE: u16 = 0x16;
const VK_IME_OFF_CODE: u16 = 0x1A;
const VK_DBE_HIRAGANA_CODE: u16 = 0xF2;

pub(crate) fn is_ime_open_key(vk: u16, modifiers: &KeyModifiers) -> bool {
    if modifiers.alt_key || modifiers.super_key {
        return false;
    }
    vk == VK_CONVERT.0 || vk == VK_KANA.0 || vk == VK_DBE_HIRAGANA_CODE || vk == VK_IME_ON_CODE
}

pub(crate) fn is_english_mode_key(virtual_key: u16) -> bool {
    virtual_key == VK_NONCONVERT.0
        || virtual_key == VK_DBE_ALPHANUMERIC.0
        || virtual_key == VK_IME_OFF_CODE
}

pub(crate) fn is_ime_close_key(vk: u16, modifiers: &KeyModifiers) -> bool {
    if modifiers.alt_key || modifiers.super_key {
        return false;
    }
    is_english_mode_key(vk)
}

fn get_ime_openclose(thread_mgr: &ITfThreadMgr) -> windows::core::Result<bool> {
    let global = unsafe { thread_mgr.GetGlobalCompartment()? };
    let compartment = unsafe { global.GetCompartment(&GUID_COMPARTMENT_KEYBOARD_OPENCLOSE)? };
    let current = unsafe { compartment.GetValue()? };
    let value = unsafe { &*current.Anonymous.Anonymous };
    if value.vt != VT_I4 {
        return Ok(true);
    }
    Ok(unsafe { value.Anonymous.lVal != 0 })
}

fn set_ime_openclose(
    thread_mgr: &ITfThreadMgr,
    client_id: u32,
    open: bool,
) -> windows::core::Result<()> {
    let global = unsafe { thread_mgr.GetGlobalCompartment()? };
    let compartment = unsafe { global.GetCompartment(&GUID_COMPARTMENT_KEYBOARD_OPENCLOSE)? };
    let next = VARIANT {
        Anonymous: VARIANT_0 {
            Anonymous: ManuallyDrop::new(VARIANT_0_0 {
                vt: VT_I4,
                wReserved1: 0,
                wReserved2: 0,
                wReserved3: 0,
                Anonymous: VARIANT_0_0_0 { lVal: open as i32 },
            }),
        },
    };
    unsafe { compartment.SetValue(client_id, &next) }
}

fn get_input_mode_conversion(thread_mgr: &ITfThreadMgr) -> windows::core::Result<u32> {
    let global = unsafe { thread_mgr.GetGlobalCompartment()? };
    let compartment =
        unsafe { global.GetCompartment(&GUID_COMPARTMENT_KEYBOARD_INPUTMODE_CONVERSION)? };
    let current = unsafe { compartment.GetValue()? };
    let value = unsafe { &*current.Anonymous.Anonymous };
    if value.vt != VT_I4 {
        return Ok(TF_CONVERSIONMODE_NATIVE | TF_CONVERSIONMODE_FULLSHAPE | TF_CONVERSIONMODE_ROMAN);
    }
    Ok(unsafe { value.Anonymous.lVal as u32 })
}

fn set_input_mode_conversion(
    thread_mgr: &ITfThreadMgr,
    client_id: u32,
    mode: u32,
) -> windows::core::Result<()> {
    let global = unsafe { thread_mgr.GetGlobalCompartment()? };
    let compartment =
        unsafe { global.GetCompartment(&GUID_COMPARTMENT_KEYBOARD_INPUTMODE_CONVERSION)? };
    let next = VARIANT {
        Anonymous: VARIANT_0 {
            Anonymous: ManuallyDrop::new(VARIANT_0_0 {
                vt: VT_I4,
                wReserved1: 0,
                wReserved2: 0,
                wReserved3: 0,
                Anonymous: VARIANT_0_0_0 { lVal: mode as i32 },
            }),
        },
    };
    unsafe { compartment.SetValue(client_id, &next) }
}

fn toggle_ime_openclose(thread_mgr: &ITfThreadMgr, client_id: u32) -> windows::core::Result<()> {
    let is_open = get_ime_openclose(thread_mgr).unwrap_or(false);
    if is_open {
        set_input_mode_conversion(thread_mgr, client_id, TF_CONVERSIONMODE_ALPHANUMERIC)?;
        set_ime_openclose(thread_mgr, client_id, false)
    } else {
        set_ime_openclose(thread_mgr, client_id, true)?;
        set_input_mode_conversion(
            thread_mgr,
            client_id,
            TF_CONVERSIONMODE_NATIVE | TF_CONVERSIONMODE_FULLSHAPE | TF_CONVERSIONMODE_ROMAN,
        )
    }
}

const CANDIDATE_WINDOW_CLASS: PCWSTR = w!("KarukanCandidatePopup");
static REGISTER_POPUP_CLASS: Once = Once::new();

fn register_candidate_window_class() {
    REGISTER_POPUP_CLASS.call_once(|| {
        let class = WNDCLASSW {
            style: Default::default(),
            lpfnWndProc: Some(candidate_wnd_proc),
            cbClsExtra: 0,
            cbWndExtra: 0,
            hInstance: unsafe { GetModuleHandleW(PCWSTR::null()) }
                .unwrap_or_default()
                .into(),
            hIcon: Default::default(),
            hCursor: Default::default(),
            hbrBackground: unsafe { GetSysColorBrush(COLOR_WINDOW) },
            lpszMenuName: PCWSTR::null(),
            lpszClassName: CANDIDATE_WINDOW_CLASS,
        };
        unsafe {
            let _ = RegisterClassW(&class);
        }
    });
}

unsafe extern "system" fn candidate_wnd_proc(
    hwnd: HWND,
    msg: u32,
    wparam: windows::Win32::Foundation::WPARAM,
    lparam: windows::Win32::Foundation::LPARAM,
) -> windows::Win32::Foundation::LRESULT {
    match msg {
        WM_MOUSEACTIVATE => windows::Win32::Foundation::LRESULT(MA_NOACTIVATE as isize),
        _ => unsafe { DefWindowProcW(hwnd, msg, wparam, lparam) },
    }
}

#[derive(Default)]
struct CandidatePopup {
    window: Option<HWND>,
    list: Option<HWND>,
    auxiliary: Option<HWND>,
    candidates: Vec<String>,
    auxiliary_text: String,
}

impl CandidatePopup {
    fn show(
        &mut self,
        context: &ITfContext,
        edit_cookie: u32,
        composition: &Rc<RefCell<Option<ITfComposition>>>,
        candidates: &CandidateList,
    ) -> windows::core::Result<()> {
        self.candidates = candidates
            .page_candidates()
            .iter()
            .enumerate()
            .map(|(index, candidate)| {
                let description = candidate
                    .description
                    .as_deref()
                    .map(|value| format!("  {value}"))
                    .unwrap_or_default();
                format!(
                    "{}. {}{description}",
                    candidates.page_start() + index + 1,
                    candidate.text
                )
            })
            .collect();

        let mut rect = RECT::default();
        let composition = composition.borrow();
        let active = composition
            .as_ref()
            .ok_or_else(|| windows::core::Error::from_hresult(HRESULT(0x80004005u32 as i32)))?;
        let range = unsafe { active.GetRange()? };
        let view = unsafe { context.GetActiveView()? };
        let mut clipped = BOOL(0);
        unsafe {
            view.GetTextExt(edit_cookie, &range, &mut rect, &mut clipped)?;
        }

        let list_height = (self.candidates.len() as i32 * 24).clamp(24, 288);
        let auxiliary_height = 28;
        let height = (list_height + auxiliary_height + 8).clamp(48, 320);

        let pt = POINT {
            x: rect.left,
            y: rect.bottom,
        };
        let hmonitor = unsafe { MonitorFromPoint(pt, MONITOR_DEFAULTTONEAREST) };
        let mut mi = MONITORINFO {
            cbSize: std::mem::size_of::<MONITORINFO>() as u32,
            ..Default::default()
        };
        let (rc_work_left, rc_work_top, rc_work_right, rc_work_bottom) = if unsafe {
            GetMonitorInfoW(hmonitor, &mut mi).as_bool()
        } {
            (
                mi.rcWork.left,
                mi.rcWork.top,
                mi.rcWork.right,
                mi.rcWork.bottom,
            )
        } else {
            (0, 0, 1920, 1080)
        };

        let width = 440;
        let mut x = rect.left;
        if x + width > rc_work_right {
            x = rc_work_right - width;
        }
        if x < rc_work_left {
            x = rc_work_left;
        }

        let mut y = rect.bottom;
        // If popup overflows bottom of work area, flip above the caret if there is room
        if y + height > rc_work_bottom {
            if rect.top - height >= rc_work_top {
                y = rect.top - height;
            } else {
                y = (rc_work_bottom - height).max(rc_work_top);
            }
        }

        let window = match self.window {
            Some(window) => window,
            None => {
                register_candidate_window_class();
                let window = unsafe {
                    CreateWindowExW(
                        WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW | WS_EX_TOPMOST,
                        CANDIDATE_WINDOW_CLASS,
                        PCWSTR::null(),
                        WS_POPUP | WS_BORDER,
                        x,
                        y,
                        width,
                        height,
                        None,
                        None,
                        None,
                        None,
                    )?
                };
                self.window = Some(window);
                let list = unsafe {
                    CreateWindowExW(
                        Default::default(),
                        w!("LISTBOX"),
                        PCWSTR::null(),
                        WS_CHILD
                            | WS_VISIBLE
                            | WS_VSCROLL
                            | WS_BORDER
                            | WINDOW_STYLE(0x0001 | 0x0100),
                        0,
                        0,
                        width,
                        list_height,
                        Some(window),
                        None,
                        None,
                        None,
                    )?
                };
                let auxiliary = unsafe {
                    CreateWindowExW(
                        Default::default(),
                        w!("STATIC"),
                        PCWSTR::null(),
                        WS_CHILD | WS_VISIBLE | WINDOW_STYLE(0x0000),
                        8,
                        list_height + 4,
                        width - 16,
                        auxiliary_height,
                        Some(window),
                        None,
                        None,
                        None,
                    )?
                };
                self.list = Some(list);
                self.auxiliary = Some(auxiliary);
                window
            }
        };
        unsafe {
            self.refresh_text(candidates.page_cursor())?;
            if let Some(list) = self.list {
                SetWindowPos(list, None, 0, 0, width, list_height, SWP_NOACTIVATE)?;
            }
            if let Some(auxiliary) = self.auxiliary {
                SetWindowPos(
                    auxiliary,
                    None,
                    8,
                    list_height + 4,
                    width - 16,
                    auxiliary_height,
                    SWP_NOACTIVATE,
                )?;
            }
            SetWindowPos(
                window,
                Some(HWND_TOPMOST),
                x,
                y,
                width,
                height,
                SWP_NOACTIVATE | SWP_SHOWWINDOW,
            )?;
            let _ = ShowWindow(window, SW_SHOWNOACTIVATE);
        }
        Ok(())
    }

    fn set_aux(&mut self, text: &str) -> windows::core::Result<()> {
        self.auxiliary_text = text.to_owned();
        if let Some(auxiliary) = self.auxiliary {
            let wide: Vec<u16> = self.auxiliary_text.encode_utf16().chain(Some(0)).collect();
            unsafe { SetWindowTextW(auxiliary, PCWSTR(wide.as_ptr()))? };
        }
        Ok(())
    }

    fn refresh_text(&self, selected: usize) -> windows::core::Result<()> {
        let Some(list) = self.list else {
            return Ok(());
        };
        unsafe {
            let reset = SendMessageW(list, LB_RESETCONTENT, None, None);
            if reset.0 != 0 {
                return Err(windows::core::Error::new(
                    HRESULT(0x80004005u32 as i32),
                    "failed to clear candidate list",
                ));
            }
            for candidate in &self.candidates {
                let wide: Vec<u16> = candidate.encode_utf16().chain(Some(0)).collect();
                let result = SendMessageW(
                    list,
                    LB_ADDSTRING,
                    None,
                    Some(windows::Win32::Foundation::LPARAM(wide.as_ptr() as isize)),
                );
                if result.0 < 0 {
                    return Err(windows::core::Error::new(
                        HRESULT(0x80004005u32 as i32),
                        "failed to add candidate to list",
                    ));
                }
            }
            if !self.candidates.is_empty() {
                let result = SendMessageW(
                    list,
                    LB_SETCURSEL,
                    Some(windows::Win32::Foundation::WPARAM(selected)),
                    None,
                );
                if result.0 < 0 {
                    return Err(windows::core::Error::new(
                        HRESULT(0x80004005u32 as i32),
                        "failed to select current candidate",
                    ));
                }
            }
        }
        if let Some(auxiliary) = self.auxiliary {
            let wide: Vec<u16> = self.auxiliary_text.encode_utf16().chain(Some(0)).collect();
            unsafe { SetWindowTextW(auxiliary, PCWSTR(wide.as_ptr()))? };
        }
        Ok(())
    }

    fn hide(&mut self) {
        if let Some(window) = self.window {
            unsafe {
                let _ = ShowWindow(window, SW_HIDE);
            }
        }
    }
}

impl Drop for CandidatePopup {
    fn drop(&mut self) {
        if let Some(window) = self.window.take() {
            unsafe {
                if let Err(error) = windows::Win32::UI::WindowsAndMessaging::DestroyWindow(window) {
                    tracing::warn!("failed to destroy Karukan candidate window: {error}");
                }
            }
        }
    }
}

#[implement(ITfCompositionSink)]
struct CompositionSink {
    composition: Rc<RefCell<Option<ITfComposition>>>,
}

impl ITfCompositionSink_Impl for CompositionSink_Impl {
    fn OnCompositionTerminated(
        &self,
        _edit_cookie: u32,
        terminated: Ref<ITfComposition>,
    ) -> windows::core::Result<()> {
        if let (Some(current), Some(terminated)) =
            (self.composition.borrow().as_ref(), terminated.as_ref())
        {
            if current.as_raw() == terminated.as_raw() {
                self.composition.borrow_mut().take();
            }
        }
        Ok(())
    }
}

fn update_preedit(
    context: &ITfContext,
    edit_cookie: u32,
    composition: &Rc<RefCell<Option<ITfComposition>>>,
    text: &str,
    caret: usize,
) -> windows::core::Result<()> {
    if text.is_empty() {
        if let Some(active) = composition.borrow_mut().take() {
            unsafe { active.EndComposition(edit_cookie)? };
        }
        return Ok(());
    }

    let active = if let Some(active) = composition.borrow().clone() {
        active
    } else {
        let mut selected = [TF_SELECTION::default()];
        let mut fetched = 0;
        unsafe { context.GetSelection(edit_cookie, 0, &mut selected, &mut fetched)? };
        if fetched == 0 {
            return Ok(());
        }
        let Some(range) = (unsafe { ManuallyDrop::take(&mut selected[0].range) }) else {
            return Ok(());
        };
        let sink: ITfCompositionSink = CompositionSink {
            composition: composition.clone(),
        }
        .into();
        let context_composition: ITfContextComposition = context.cast()?;
        let composition_result =
            unsafe { context_composition.StartComposition(edit_cookie, &range, &sink)? };
        *composition.borrow_mut() = Some(composition_result.clone());
        composition_result
    };

    let range = unsafe { active.GetRange()? };
    let wide: Vec<u16> = text.encode_utf16().collect();
    unsafe {
        range.SetText(edit_cookie, 0, &wide)?;
        if let Ok(prop) = context.GetProperty(&GUID_PROP_COMPOSING) {
            let var = VARIANT {
                Anonymous: VARIANT_0 {
                    Anonymous: ManuallyDrop::new(VARIANT_0_0 {
                        vt: VT_I4,
                        wReserved1: 0,
                        wReserved2: 0,
                        wReserved3: 0,
                        Anonymous: VARIANT_0_0_0 { lVal: 1 },
                    }),
                },
            };
            let _ = prop.SetValue(edit_cookie, &range, &var);
        }
    };
    set_caret(context, edit_cookie, &range, text, caret)
}

fn commit_text(
    context: &ITfContext,
    edit_cookie: u32,
    composition: &RefCell<Option<ITfComposition>>,
    text: &str,
) -> windows::core::Result<()> {
    if let Some(active) = composition.borrow_mut().take() {
        let range = unsafe { active.GetRange()? };
        let wide: Vec<u16> = text.encode_utf16().collect();
        unsafe { range.SetText(edit_cookie, 0, &wide)? };
        set_caret_at_end(context, edit_cookie, &range)?;
        unsafe { active.EndComposition(edit_cookie)? };
    } else {
        let wide: Vec<u16> = text.encode_utf16().collect();
        unsafe {
            let insert_at_selection: ITfInsertAtSelection = context.cast()?;
            insert_at_selection.InsertTextAtSelection(
                edit_cookie,
                INSERT_TEXT_AT_SELECTION_FLAGS(0),
                &wide,
            )?;
        }
    }
    Ok(())
}

fn set_caret_at_end(
    context: &ITfContext,
    edit_cookie: u32,
    range: &windows::Win32::UI::TextServices::ITfRange,
) -> windows::core::Result<()> {
    let selection_range = unsafe { range.Clone()? };
    unsafe { selection_range.Collapse(edit_cookie, TF_ANCHOR_END)? };

    let mut selection = [TF_SELECTION {
        range: ManuallyDrop::new(Some(selection_range)),
        style: TF_SELECTIONSTYLE {
            ase: TF_AE_END,
            fInterimChar: BOOL(0),
        },
    }];
    let result = unsafe { context.SetSelection(edit_cookie, &selection) };
    unsafe {
        ManuallyDrop::drop(&mut selection[0].range);
    }
    result
}

fn set_caret(
    context: &ITfContext,
    edit_cookie: u32,
    range: &windows::Win32::UI::TextServices::ITfRange,
    text: &str,
    caret: usize,
) -> windows::core::Result<()> {
    let offset = text
        .encode_utf16()
        .take(text.chars().take(caret).map(char::len_utf16).sum())
        .count() as i32;
    let selection_range = unsafe { range.Clone()? };
    unsafe {
        selection_range.Collapse(edit_cookie, TF_ANCHOR_START)?;
        let mut shifted = 0;
        // Shifting a collapsed range's start also moves its end to that position.
        selection_range.ShiftStart(edit_cookie, offset, &mut shifted, ptr::null())?;
    }

    let mut selection = [TF_SELECTION {
        range: ManuallyDrop::new(Some(selection_range)),
        style: TF_SELECTIONSTYLE {
            ase: TF_AE_END,
            fInterimChar: BOOL(0),
        },
    }];
    let result = unsafe { context.SetSelection(edit_cookie, &selection) };
    unsafe {
        ManuallyDrop::drop(&mut selection[0].range);
    }
    result
}

fn current_modifiers() -> KeyModifiers {
    unsafe {
        KeyModifiers {
            shift_key: GetKeyState(VK_SHIFT.0 as i32) < 0,
            control_key: GetKeyState(VK_CONTROL.0 as i32) < 0,
            alt_key: GetKeyState(VK_MENU.0 as i32) < 0,
            super_key: GetKeyState(VK_LWIN.0 as i32) < 0 || GetKeyState(VK_RWIN.0 as i32) < 0,
        }
    }
}

fn translate_key(virtual_key: u16, lparam: isize) -> Option<Keysym> {
    let modifiers = current_modifiers();
    let keysym = match virtual_key {
        0x08 => 0xff08,
        0x09 if modifiers.shift_key => 0xfe20,
        0x09 => 0xff09,
        0x0d => 0xff0d,
        0x1b => 0xff1b,
        key if key == VK_CONVERT.0 => Keysym::HENKAN.0,
        0x20 => 0x0020,
        0x21 => 0xff55,
        0x22 => 0xff56,
        0x23 => 0xff57,
        0x24 => 0xff50,
        0x25 => 0xff51,
        0x26 => 0xff52,
        0x27 => 0xff53,
        0x28 => 0xff54,
        0x2e => 0xffff,
        // ToUnicodeEx may return a control character for Ctrl+Shift+V;
        // normalize it so the engine can handle its verbose-display binding.
        0x56 if modifiers.control_key && modifiers.shift_key => 0x0056,
        _ => translated_printable(virtual_key, lparam)?,
    };
    Some(Keysym(keysym))
}

fn translated_printable(virtual_key: u16, lparam: isize) -> Option<u32> {
    let modifiers = current_modifiers();
    if modifiers.control_key {
        return control_keysym(virtual_key);
    }

    let mut keyboard_state = [0u8; 256];
    unsafe { GetKeyboardState(&mut keyboard_state).ok()? };
    let mut output = [0u16; 8];
    let layout = unsafe { GetKeyboardLayout(0) };
    let scan_code = ((lparam as usize >> 16) & 0xff) as u32;
    let count = unsafe {
        ToUnicodeEx(
            virtual_key as u32,
            scan_code,
            &keyboard_state,
            &mut output,
            4,
            Some(layout),
        )
    };
    if count <= 0 {
        return None;
    }
    let scalar = char::from_u32(output[0] as u32)?;
    if scalar.is_ascii() && !scalar.is_ascii_control() {
        Some(scalar as u32)
    } else {
        None
    }
}

fn control_keysym(virtual_key: u16) -> Option<u32> {
    match virtual_key {
        0x30..=0x39 | 0x41..=0x5a => Some(virtual_key as u32),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::{
        control_keysym, is_english_mode_key, is_ime_close_key, is_ime_open_key,
    };
    use karukan_im::core::keycode::KeyModifiers;
    use windows::Win32::UI::Input::KeyboardAndMouse::{VK_CONVERT, VK_DBE_ALPHANUMERIC, VK_KANA, VK_NONCONVERT};

    #[test]
    fn ime_open_and_close_keys_recognized() {
        let no_mods = KeyModifiers::default();
        let alt_mod = KeyModifiers {
            alt_key: true,
            ..Default::default()
        };

        assert!(is_ime_open_key(VK_CONVERT.0, &no_mods));
        assert!(is_ime_open_key(VK_KANA.0, &no_mods));
        assert!(!is_ime_open_key(VK_CONVERT.0, &alt_mod));

        assert!(is_ime_close_key(VK_NONCONVERT.0, &no_mods));
        assert!(is_ime_close_key(VK_DBE_ALPHANUMERIC.0, &no_mods));
        assert!(!is_ime_close_key(VK_NONCONVERT.0, &alt_mod));
    }

    #[test]
    fn nonconvert_and_alphanumeric_keys_select_direct_input() {
        assert!(is_english_mode_key(VK_NONCONVERT.0));
        assert!(is_english_mode_key(VK_DBE_ALPHANUMERIC.0));
        assert!(!is_english_mode_key(0x19));
    }

    #[test]
    fn control_letters_keep_ascii_keysyms() {
        assert_eq!(control_keysym(0x41), Some('A' as u32));
        assert_eq!(control_keysym(0x4e), Some('N' as u32));
        assert_eq!(control_keysym(0x5a), Some('Z' as u32));
    }

    #[test]
    fn control_digits_keep_ascii_keysyms() {
        assert_eq!(control_keysym(0x31), Some('1' as u32));
        assert_eq!(control_keysym(0x39), Some('9' as u32));
    }

    #[test]
    fn unsupported_control_keys_have_no_printable_keysym() {
        assert_eq!(control_keysym(0x20), None);
        assert_eq!(control_keysym(0x70), None);
    }

    #[test]
    fn function_key_converts_reading_variants() {
        use super::convert_function_key;
        assert_eq!(convert_function_key("かな", 0x75), "かな"); // F6
        assert_eq!(convert_function_key("かな", 0x76), "カナ"); // F7
        assert_eq!(convert_function_key("かな", 0x77), "ｶﾅ");   // F8
        assert_eq!(convert_function_key("abc123", 0x78), "ａｂｃ１２３"); // F9
        assert_eq!(convert_function_key("ａｂｃ１２３", 0x79), "abc123"); // F10
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn DllGetClassObject(
    class_id: *const GUID,
    iid: *const GUID,
    output: *mut *mut c_void,
) -> HRESULT {
    init_logging();
    if class_id.is_null() || iid.is_null() || output.is_null() {
        return E_POINTER;
    }
    unsafe {
        *output = ptr::null_mut();
    }
    let req_clsid = unsafe { *class_id };
    let req_iid = unsafe { *iid };
    tracing::info!("DllGetClassObject: clsid={req_clsid:?}, iid={req_iid:?}");
    if req_clsid != CLSID_KARUKAN_TIP {
        tracing::warn!("DllGetClassObject: requested clsid is not CLSID_KARUKAN_TIP");
        return CLASS_E_CLASSNOTAVAILABLE;
    }

    let interface: IClassFactory = ClassFactory.into();
    unsafe { interface.query(iid, output) }
}

#[unsafe(no_mangle)]
pub extern "system" fn DllCanUnloadNow() -> HRESULT {
    S_FALSE
}
