use std::cell::{Cell, RefCell};
use std::ptr;
use std::rc::Rc;
use std::sync::{Arc, Mutex};

use karukan_im::core::engine::InputMethodEngine;
use karukan_im::core::keycode::KeyEvent;
use karukan_im::core::keycode::KeyModifiers;
use karukan_im::core::keycode::Keysym;
use windows::core::{implement, w, BSTR, BOOL, GUID, HRESULT, IUnknown, Interface, PCWSTR, Ref};
use windows::Win32::Foundation::{E_INVALIDARG, E_POINTER, HWND, POINT, RECT};
use windows::Win32::Graphics::Gdi::HBITMAP;
use windows::Win32::System::Threading::{AttachThreadInput, GetCurrentThreadId};
use windows::Win32::UI::Input::KeyboardAndMouse::{GetActiveWindow, GetFocus};
use windows::Win32::UI::TextServices::{
    GUID_LBI_INPUTMODE, ITfLangBarItem, ITfLangBarItemButton, ITfLangBarItemButton_Impl,
    ITfLangBarItemSink, ITfLangBarItem_Impl, ITfMenu, ITfSource, ITfSource_Impl, ITfThreadMgr,
    TF_LANGBARITEMINFO, TF_LBI_CLK_LEFT, TF_LBI_CLK_RIGHT, TF_LBI_STYLE_BTN_BUTTON,
    TF_LBI_STYLE_SHOWNINTRAY, TF_LBMENUF_RADIOCHECKED, TF_LBMENUF_SEPARATOR, TfLBIClick,
};
use windows::Win32::UI::WindowsAndMessaging::{
    AppendMenuW, CreatePopupMenu, CreateWindowExW, DestroyMenu, DestroyWindow, GetCursorPos,
    GetForegroundWindow, GetWindowThreadProcessId, HICON, MF_CHECKED, MF_SEPARATOR, MF_STRING,
    PostMessageW, SetForegroundWindow, TrackPopupMenu, TPM_BOTTOMALIGN, TPM_LEFTALIGN,
    TPM_RETURNCMD, TPM_RIGHTBUTTON, WM_NULL, WS_POPUP,
};

use crate::{
    CLSID_KARUKAN_TIP, RES_ID_ICON_ALPHA, RES_ID_ICON_HIRAGANA, RES_ID_ICON_KATAKANA,
    TF_CONVERSIONMODE_ALPHANUMERIC, TF_CONVERSIONMODE_FULLSHAPE, TF_CONVERSIONMODE_KATAKANA,
    TF_CONVERSIONMODE_NATIVE, TF_CONVERSIONMODE_ROMAN,
};

use super::compartment::{
    get_ime_openclose, get_input_mode_conversion, set_ime_openclose, set_input_mode_conversion,
    toggle_ime_openclose,
};
use super::key_handler::current_modifiers;
use super::text_service::spawn_settings_app;

#[implement(ITfLangBarItem, ITfLangBarItemButton, ITfSource)]
pub(crate) struct LangBarButton {
    pub(crate) engine: Arc<Mutex<InputMethodEngine>>,
    pub(crate) thread_mgr: ITfThreadMgr,
    pub(crate) client_id: u32,
    pub(crate) sink: Rc<RefCell<Option<ITfLangBarItemSink>>>,
    pub(crate) cookie: Cell<u32>,
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

fn get_active_doc_window(thread_mgr: &ITfThreadMgr) -> Option<HWND> {
    unsafe {
        let doc_mgr = thread_mgr.GetFocus().ok()?;
        let context = doc_mgr.GetTop().ok()?;
        let view = context.GetActiveView().ok()?;
        let hwnd = view.GetWnd().ok()?;
        if !hwnd.0.is_null() {
            Some(hwnd)
        } else {
            None
        }
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
                    if !engine.state().is_empty() {
                        let ctrl_k = KeyEvent {
                            keysym: Keysym::KEY_K,
                            modifiers: KeyModifiers {
                                control_key: true,
                                shift_key: false,
                                alt_key: false,
                                super_key: false,
                            },
                            is_press: true,
                        };
                        let _ = engine.process_key(&ctrl_k);
                    }
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
                // 1. 自スレッドまたは自ドキュメントの正規ウィンドウを探す
                let active_doc_hwnd = get_active_doc_window(&self.thread_mgr);
                let active_win = GetActiveWindow();
                let focus_win = GetFocus();

                let mut dummy_to_destroy = None;
                let owner_hwnd = if let Some(hwnd) = active_doc_hwnd {
                    hwnd
                } else if !active_win.0.is_null() {
                    active_win
                } else if !focus_win.0.is_null() {
                    focus_win
                } else {
                    // ドキュメントウィンドウが見つからない場合のフォールバックとしてダミーウィンドウを作成
                    let dummy_hwnd = CreateWindowExW(
                        windows::Win32::UI::WindowsAndMessaging::WINDOW_EX_STYLE(0),
                        w!("STATIC"),
                        w!(""),
                        WS_POPUP,
                        0,
                        0,
                        0,
                        0,
                        None,
                        None,
                        None,
                        None,
                    );
                    if let Ok(hwnd) = dummy_hwnd {
                        dummy_to_destroy = Some(hwnd);
                        hwnd
                    } else {
                        GetForegroundWindow()
                    }
                };

                // 2. フォアグラウンドスレッド（タスクバー等）と自スレッドを一時アタッチし、
                // UWP/WinUI3 (Notepad等) でも確実にフォアグラウンド権限を得てメニューを表示する
                let fg_hwnd = GetForegroundWindow();
                let cur_thread = GetCurrentThreadId();
                let fg_thread = if !fg_hwnd.0.is_null() {
                    GetWindowThreadProcessId(fg_hwnd, None)
                } else {
                    0
                };

                let attached = if fg_thread != 0 && fg_thread != cur_thread {
                    AttachThreadInput(cur_thread, fg_thread, true).as_bool()
                } else {
                    false
                };

                let sf_res = SetForegroundWindow(owner_hwnd);
                tracing::info!(
                    "LangBarButton context menu: active_doc={:?}, owner={:?}, fg={:?}, attached={}, SetForegroundWindow={}",
                    active_doc_hwnd, owner_hwnd, fg_hwnd, attached, sf_res.as_bool()
                );

                if let Ok(hmenu) = CreatePopupMenu() {
                    let is_open = get_ime_openclose(&self.thread_mgr).unwrap_or(true);
                    let is_katakana = if is_open {
                        get_input_mode_conversion(&self.thread_mgr)
                            .map(|c| (c & TF_CONVERSIONMODE_KATAKANA) != 0)
                            .unwrap_or(false)
                    } else {
                        false
                    };
                    let flag_hiragana = if is_open && !is_katakana { MF_CHECKED } else { windows::Win32::UI::WindowsAndMessaging::MENU_ITEM_FLAGS(0) };
                    let flag_katakana = if is_open && is_katakana { MF_CHECKED } else { windows::Win32::UI::WindowsAndMessaging::MENU_ITEM_FLAGS(0) };
                    let flag_alpha = if !is_open { MF_CHECKED } else { windows::Win32::UI::WindowsAndMessaging::MENU_ITEM_FLAGS(0) };

                    let _ = AppendMenuW(hmenu, MF_STRING | flag_hiragana, 1, w!("ひらがな (Hiragana)"));
                    let _ = AppendMenuW(hmenu, MF_STRING | flag_katakana, 2, w!("全角カタカナ (Katakana)"));
                    let _ = AppendMenuW(hmenu, MF_STRING | flag_alpha, 3, w!("直接入力 / 半角英数 (Direct)"));
                    let _ = AppendMenuW(hmenu, MF_SEPARATOR, 0, PCWSTR::null());
                    let _ = AppendMenuW(hmenu, MF_STRING, 5, w!("設定 (Settings)..."));

                    // カーソル位置のフォールバック
                    let mut cur_pt = POINT::default();
                    let _ = GetCursorPos(&mut cur_pt);
                    let (x, y) = if pt.x > 0 || pt.y > 0 {
                        (pt.x, pt.y)
                    } else {
                        (cur_pt.x, cur_pt.y)
                    };

                    // TPM_RIGHTBUTTON: 右クリックによるトラッキングとメニュー選択を正常化
                    let cmd = TrackPopupMenu(
                        hmenu,
                        TPM_LEFTALIGN | TPM_BOTTOMALIGN | TPM_RIGHTBUTTON | TPM_RETURNCMD,
                        x,
                        y,
                        Some(0),
                        owner_hwnd,
                        None,
                    );

                    // KB Q139526: メニュー外クリック時にメニューを正常に終了させるためのダミーメッセージ
                    let _ = PostMessageW(
                        Some(owner_hwnd),
                        WM_NULL,
                        windows::Win32::Foundation::WPARAM(0),
                        windows::Win32::Foundation::LPARAM(0),
                    );

                    let _ = DestroyMenu(hmenu);
                    if let Some(hwnd) = dummy_to_destroy {
                        let _ = DestroyWindow(hwnd);
                    }

                    if attached {
                        let _ = AttachThreadInput(cur_thread, fg_thread, false);
                    }

                    tracing::info!("LangBarButton context menu result cmd: {}", cmd.0);
                    if cmd.0 != 0 {
                        self.handle_menu_select(cmd.0 as u32)?;
                    }
                } else if attached {
                    let _ = AttachThreadInput(cur_thread, fg_thread, false);
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
        let is_katakana = if is_open {
            get_input_mode_conversion(&self.thread_mgr)
                .map(|c| (c & TF_CONVERSIONMODE_KATAKANA) != 0)
                .unwrap_or(false)
        } else {
            false
        };
        let flag_hiragana = if is_open && !is_katakana {
            TF_LBMENUF_RADIOCHECKED
        } else {
            0
        };
        let flag_katakana = if is_open && is_katakana {
            TF_LBMENUF_RADIOCHECKED
        } else {
            0
        };
        let flag_alpha = if !is_open {
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
        if !is_open {
            return load_resource_icon(RES_ID_ICON_ALPHA);
        }
        let is_katakana = if let Ok(conversion) = get_input_mode_conversion(&self.thread_mgr) {
            (conversion & TF_CONVERSIONMODE_KATAKANA) != 0
        } else {
            false
        };
        let res_id = if is_katakana {
            RES_ID_ICON_KATAKANA
        } else {
            RES_ID_ICON_HIRAGANA
        };
        load_resource_icon(res_id)
    }

    fn GetText(&self) -> windows::core::Result<BSTR> {
        let is_open = get_ime_openclose(&self.thread_mgr).unwrap_or(true);
        if !is_open {
            return Ok(BSTR::from("A"));
        }
        let is_katakana = if let Ok(conversion) = get_input_mode_conversion(&self.thread_mgr) {
            (conversion & TF_CONVERSIONMODE_KATAKANA) != 0
        } else {
            false
        };
        let text = if is_katakana { "カ" } else { "あ" };
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
