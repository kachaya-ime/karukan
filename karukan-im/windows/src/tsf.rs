use std::cell::RefCell;
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
    CLASS_E_CLASSNOTAVAILABLE, CLASS_E_NOAGGREGATION, E_POINTER, HWND, RECT, S_FALSE,
};
use windows::Win32::System::Com::{IClassFactory, IClassFactory_Impl};
use windows::Win32::System::Variant::{VARIANT, VARIANT_0, VARIANT_0_0, VARIANT_0_0_0, VT_I4};
use windows::Win32::UI::Input::KeyboardAndMouse::{
    GetKeyState, GetKeyboardLayout, GetKeyboardState, ToUnicodeEx, VK_CONTROL, VK_CONVERT,
    VK_DBE_ALPHANUMERIC, VK_LWIN, VK_MENU, VK_NONCONVERT, VK_RWIN, VK_SHIFT,
};
use windows::Win32::UI::TextServices::{
    GUID_COMPARTMENT_KEYBOARD_OPENCLOSE, INSERT_TEXT_AT_SELECTION_FLAGS, ITfClientId,
    ITfComposition, ITfCompositionSink, ITfCompositionSink_Impl, ITfContext, ITfContextComposition,
    ITfEditSession, ITfEditSession_Impl, ITfInsertAtSelection, ITfKeyEventSink,
    ITfKeyEventSink_Impl, ITfKeystrokeMgr, ITfTextInputProcessor, ITfTextInputProcessor_Impl,
    ITfThreadMgr, TF_AE_END, TF_ANCHOR_START, TF_CONTEXT_EDIT_CONTEXT_FLAGS, TF_SELECTION,
    TF_SELECTIONSTYLE,
};
use windows::Win32::UI::WindowsAndMessaging::{
    CreateWindowExW, HWND_TOPMOST, LB_ADDSTRING, LB_RESETCONTENT, LB_SETCURSEL, SW_HIDE,
    SW_SHOWNOACTIVATE, SWP_NOACTIVATE, SWP_SHOWWINDOW, SendMessageW, SetWindowPos, SetWindowTextW,
    ShowWindow, WINDOW_STYLE, WS_BORDER, WS_CHILD, WS_EX_NOACTIVATE, WS_EX_TOOLWINDOW,
    WS_EX_TOPMOST, WS_POPUP, WS_VISIBLE, WS_VSCROLL,
};
use windows::core::{BOOL, GUID, HRESULT, IUnknown, Interface, PCWSTR, Ref, implement, w};

use crate::CLSID_KARUKAN_TIP;

const EDIT_SESSION_FLAGS: TF_CONTEXT_EDIT_CONTEXT_FLAGS = TF_CONTEXT_EDIT_CONTEXT_FLAGS(7);
static INIT_LOGGING: Once = Once::new();

fn init_logging() {
    INIT_LOGGING.call_once(|| {
        let _ = tracing_subscriber::fmt()
            .with_env_filter(
                tracing_subscriber::EnvFilter::try_from_default_env()
                    .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
            )
            .with_writer(std::io::stderr)
            .try_init();
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

#[implement(ITfTextInputProcessor)]
struct TextService {
    thread_id: RefCell<Option<u32>>,
    keystroke_mgr: RefCell<Option<ITfKeystrokeMgr>>,
    key_sink: RefCell<Option<ITfKeyEventSink>>,
    engine: RefCell<Option<Arc<Mutex<InputMethodEngine>>>>,
}

impl TextService {
    fn new() -> windows::core::Result<Self> {
        init_logging();
        Ok(Self {
            thread_id: RefCell::new(None),
            keystroke_mgr: RefCell::new(None),
            key_sink: RefCell::new(None),
            engine: RefCell::new(None),
        })
    }
}

impl ITfTextInputProcessor_Impl for TextService_Impl {
    fn Activate(&self, thread_mgr: Ref<ITfThreadMgr>, thread_id: u32) -> windows::core::Result<()> {
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
        *self.thread_id.borrow_mut() = Some(thread_id);
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
        Ok(())
    }

    fn OnTestKeyDown(
        &self,
        _context: Ref<ITfContext>,
        wparam: windows::Win32::Foundation::WPARAM,
        lparam: windows::Win32::Foundation::LPARAM,
    ) -> windows::core::Result<BOOL> {
        // fcitx5 receives Henkan key events even with an empty composition.
        // The engine consumes it only when switching back to Hiragana.
        if wparam.0 as u16 == VK_CONVERT.0 {
            let modifiers = current_modifiers();
            return Ok(BOOL((!modifiers.alt_key && !modifiers.super_key) as i32));
        }
        if wparam.0 as u16 == 0x19 {
            let modifiers = current_modifiers();
            return Ok(BOOL((!modifiers.alt_key && !modifiers.super_key) as i32));
        }
        let Some(keysym) = translate_key(wparam.0 as u16, lparam.0) else {
            return Ok(BOOL(0));
        };
        let modifiers = current_modifiers();
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
        _wparam: windows::Win32::Foundation::WPARAM,
        _lparam: windows::Win32::Foundation::LPARAM,
    ) -> windows::core::Result<BOOL> {
        Ok(BOOL(0))
    }

    fn OnKeyDown(
        &self,
        context: Ref<ITfContext>,
        wparam: windows::Win32::Foundation::WPARAM,
        lparam: windows::Win32::Foundation::LPARAM,
    ) -> windows::core::Result<BOOL> {
        if is_english_mode_key(wparam.0 as u16) {
            let modifiers = current_modifiers();
            if modifiers.alt_key || modifiers.super_key {
                return Ok(BOOL(0));
            }
            let Some(context) = context.as_ref() else {
                return Ok(BOOL(0));
            };
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
                context
                    .RequestEditSession(self.thread_id, &session, EDIT_SESSION_FLAGS)?
                    .ok()?;
            }
            set_ime_openclose(&self.thread_mgr, self.client_id, false)?;
            return Ok(BOOL(1));
        }
        if wparam.0 as u16 == 0x19 {
            let modifiers = current_modifiers();
            if modifiers.alt_key || modifiers.super_key {
                return Ok(BOOL(0));
            }
            toggle_ime_openclose(&self.thread_mgr, self.client_id)?;
            return Ok(BOOL(1));
        }
        let Some(context) = context.as_ref() else {
            return Ok(BOOL(0));
        };
        let Some(keysym) = translate_key(wparam.0 as u16, lparam.0) else {
            return Ok(BOOL(0));
        };
        let event = KeyEvent::new(keysym, current_modifiers(), true);
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
        _wparam: windows::Win32::Foundation::WPARAM,
        _lparam: windows::Win32::Foundation::LPARAM,
    ) -> windows::core::Result<BOOL> {
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

fn is_english_mode_key(virtual_key: u16) -> bool {
    virtual_key == VK_NONCONVERT.0 || virtual_key == VK_DBE_ALPHANUMERIC.0
}

fn set_ime_openclose(
    thread_mgr: &ITfThreadMgr,
    client_id: u32,
    open: bool,
) -> windows::core::Result<()> {
    let global = unsafe { thread_mgr.GetGlobalCompartment()? };
    let compartment = unsafe { global.GetCompartment(&GUID_COMPARTMENT_KEYBOARD_OPENCLOSE)? };
    let current = unsafe { compartment.GetValue()? };
    let value = unsafe { &*current.Anonymous.Anonymous };
    if value.vt != VT_I4 {
        return Err(windows::core::Error::from_hresult(HRESULT(
            0x80004005u32 as i32,
        )));
    }
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

fn toggle_ime_openclose(thread_mgr: &ITfThreadMgr, client_id: u32) -> windows::core::Result<()> {
    let global = unsafe { thread_mgr.GetGlobalCompartment()? };
    let compartment = unsafe { global.GetCompartment(&GUID_COMPARTMENT_KEYBOARD_OPENCLOSE)? };
    let current = unsafe { compartment.GetValue()? };
    let value = unsafe { &*current.Anonymous.Anonymous };
    if value.vt != VT_I4 {
        return Err(windows::core::Error::from_hresult(HRESULT(
            0x80004005u32 as i32,
        )));
    }
    let is_open = unsafe { value.Anonymous.lVal != 0 };
    set_ime_openclose(thread_mgr, client_id, !is_open)
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
        let window = match self.window {
            Some(window) => window,
            None => {
                let window = unsafe {
                    CreateWindowExW(
                        WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW | WS_EX_TOPMOST,
                        w!("STATIC"),
                        PCWSTR::null(),
                        WS_POPUP | WS_BORDER,
                        rect.left,
                        rect.bottom,
                        440,
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
                        440,
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
                        424,
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
                SetWindowPos(list, None, 0, 0, 440, list_height, SWP_NOACTIVATE)?;
            }
            if let Some(auxiliary) = self.auxiliary {
                SetWindowPos(
                    auxiliary,
                    None,
                    8,
                    list_height + 4,
                    424,
                    auxiliary_height,
                    SWP_NOACTIVATE,
                )?;
            }
            SetWindowPos(
                window,
                Some(HWND_TOPMOST),
                rect.left,
                rect.bottom,
                440,
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
    unsafe { range.SetText(edit_cookie, 0, &wide)? };
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
        unsafe {
            range.SetText(edit_cookie, 0, &wide)?;
            active.EndComposition(edit_cookie)?;
        }
        set_caret(context, edit_cookie, &range, text, text.chars().count())?;
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
        selection_range.ShiftStart(edit_cookie, offset, &mut shifted, ptr::null())?;
        selection_range.ShiftEnd(edit_cookie, shifted, &mut shifted, ptr::null())?;
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
    use super::{control_keysym, is_english_mode_key};
    use windows::Win32::UI::Input::KeyboardAndMouse::{VK_DBE_ALPHANUMERIC, VK_NONCONVERT};

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
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn DllGetClassObject(
    class_id: *const GUID,
    iid: *const GUID,
    output: *mut *mut c_void,
) -> HRESULT {
    if class_id.is_null() || iid.is_null() || output.is_null() {
        return E_POINTER;
    }
    unsafe {
        *output = ptr::null_mut();
    }
    if unsafe { *class_id } != CLSID_KARUKAN_TIP {
        return CLASS_E_CLASSNOTAVAILABLE;
    }

    let interface: IClassFactory = ClassFactory.into();
    unsafe { interface.query(iid, output) }
}

#[unsafe(no_mangle)]
pub extern "system" fn DllCanUnloadNow() -> HRESULT {
    S_FALSE
}
