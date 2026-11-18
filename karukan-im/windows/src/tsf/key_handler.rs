use std::cell::RefCell;
use std::rc::Rc;
use std::sync::{Arc, Mutex};

use karukan_im::core::engine::{EngineAction, InputMethodEngine};
use karukan_im::core::keycode::{KeyEvent, KeyModifiers, Keysym};
use windows::core::{implement, BOOL, GUID, HRESULT, Ref};
use windows::Win32::UI::Input::KeyboardAndMouse::{
    GetKeyState, GetKeyboardLayout, GetKeyboardState, ToUnicodeEx, VK_CONTROL, VK_CONVERT,
    VK_DBE_ALPHANUMERIC, VK_KANA, VK_LWIN, VK_MENU, VK_NONCONVERT, VK_RWIN, VK_SHIFT,
};
use windows::Win32::UI::TextServices::{
    ITfComposition, ITfContext, ITfEditSession, ITfKeyEventSink, ITfKeyEventSink_Impl,
    ITfThreadMgr,
};

use crate::{
    TF_CONVERSIONMODE_ALPHANUMERIC, TF_CONVERSIONMODE_FULLSHAPE, TF_CONVERSIONMODE_KATAKANA,
    TF_CONVERSIONMODE_NATIVE, TF_CONVERSIONMODE_ROMAN,
};

use super::candidate_window::CandidatePopup;
use super::compartment::{
    get_ime_openclose, get_input_mode_conversion, set_ime_openclose, set_input_mode_conversion,
};
use super::edit_session::{capture_surrounding_text, EditSession, EDIT_SESSION_FLAGS};

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

pub(crate) fn convert_function_key(text: &str, vk: u16) -> String {
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

pub(crate) fn current_modifiers() -> KeyModifiers {
    unsafe {
        KeyModifiers {
            shift_key: GetKeyState(VK_SHIFT.0 as i32) < 0,
            control_key: GetKeyState(VK_CONTROL.0 as i32) < 0,
            alt_key: GetKeyState(VK_MENU.0 as i32) < 0,
            super_key: GetKeyState(VK_LWIN.0 as i32) < 0 || GetKeyState(VK_RWIN.0 as i32) < 0,
        }
    }
}

pub(crate) fn translate_key(virtual_key: u16, lparam: isize) -> Option<Keysym> {
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

#[implement(ITfKeyEventSink)]
pub(crate) struct KeyHandler {
    pub(crate) engine: Arc<Mutex<InputMethodEngine>>,
    pub(crate) thread_mgr: ITfThreadMgr,
    pub(crate) client_id: u32,
    pub(crate) thread_id: u32,
    pub(crate) composition: Rc<RefCell<Option<ITfComposition>>>,
    pub(crate) candidate_popup: Rc<RefCell<CandidatePopup>>,
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

        // 4. かな/変換キー（fcitx5と同様、未変換時でも消費してひらがなモード復帰等に使用）
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
        if keysym == Keysym::HENKAN {
            let _ = set_ime_openclose(&self.thread_mgr, self.client_id, true);
            let _ = set_input_mode_conversion(
                &self.thread_mgr,
                self.client_id,
                TF_CONVERSIONMODE_NATIVE | TF_CONVERSIONMODE_FULLSHAPE | TF_CONVERSIONMODE_ROMAN,
            );
        } else if modifiers.control_key
            && (keysym == Keysym::KEY_K || keysym == Keysym::KEY_K_UPPER)
        {
            let _ = set_ime_openclose(&self.thread_mgr, self.client_id, true);
            let _ = set_input_mode_conversion(
                &self.thread_mgr,
                self.client_id,
                TF_CONVERSIONMODE_NATIVE
                    | TF_CONVERSIONMODE_KATAKANA
                    | TF_CONVERSIONMODE_FULLSHAPE
                    | TF_CONVERSIONMODE_ROMAN,
            );
        }
        let event = KeyEvent::new(keysym, modifiers, true);
        let mut result = {
            let mut engine = self
                .engine
                .lock()
                .map_err(|_| windows::core::Error::from_hresult(HRESULT(0x80004005u32 as i32)))?;
            engine.process_key(&event)
        };

        let is_katakana_mode = get_input_mode_conversion(&self.thread_mgr)
            .map(|c| (c & TF_CONVERSIONMODE_KATAKANA) != 0)
            .unwrap_or(false);

        if is_katakana_mode
            && !(modifiers.control_key
                && (keysym == Keysym::KEY_K || keysym == Keysym::KEY_K_UPPER))
        {
            let mut engine = self
                .engine
                .lock()
                .map_err(|_| windows::core::Error::from_hresult(HRESULT(0x80004005u32 as i32)))?;
            if let Some(preedit) = engine.state().preedit() {
                if preedit
                    .text()
                    .chars()
                    .any(|c| ('\u{3041}'..='\u{3096}').contains(&c))
                {
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
                    let k_res = engine.process_key(&ctrl_k);
                    for action in k_res.actions {
                        if let EngineAction::UpdatePreedit(_) = &action {
                            if let Some(pos) = result
                                .actions
                                .iter()
                                .position(|a| matches!(a, EngineAction::UpdatePreedit(_)))
                            {
                                result.actions[pos] = action;
                            } else {
                                result.actions.push(action);
                            }
                        } else {
                            result.actions.push(action);
                        }
                    }
                }
            }
        }
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

#[cfg(test)]
mod tests {
    use super::{
        control_keysym, is_english_mode_key, is_ime_close_key, is_ime_open_key,
    };
    use karukan_im::core::keycode::KeyModifiers;
    use windows::Win32::UI::Input::KeyboardAndMouse::{
        VK_CONVERT, VK_DBE_ALPHANUMERIC, VK_KANA, VK_NONCONVERT,
    };

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
