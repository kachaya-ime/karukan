use std::cell::{Cell, RefCell};
use std::rc::Rc;

use windows::core::{implement, Interface, Ref};
use windows::Win32::Foundation::E_POINTER;
use windows::Win32::UI::TextServices::{
    GUID_COMPARTMENT_KEYBOARD_OPENCLOSE, ITfClientId, ITfCompartmentEventSink, ITfKeyEventSink,
    ITfKeystrokeMgr, ITfLangBarItemButton, ITfLangBarItemMgr, ITfSource, ITfTextInputProcessor,
    ITfTextInputProcessor_Impl, ITfThreadFocusSink, ITfThreadMgr, ITfThreadMgrEventSink,
    TF_MOD_ALT, TF_MOD_IGNORE_ALL_MODIFIER, TF_PRESERVEDKEY,
};

use crate::engine_client::EngineClient;
use crate::{
    CLSID_KARUKAN_TIP, GUID_COMPARTMENT_KEYBOARD_INPUTMODE_CONVERSION, GUID_PRESERVEDKEY_TOGGLE,
    TF_CONVERSIONMODE_FULLSHAPE, TF_CONVERSIONMODE_NATIVE, TF_CONVERSIONMODE_ROMAN,
};

use super::candidate_window::CandidatePopup;
use super::compartment::{
    set_ime_openclose, set_input_mode_conversion, CompartmentSink,
};
use super::init_logging;
use super::key_handler::{
    KeyHandler, VK_KANJI_CODE, VK_OEM_3_CODE, VK_OEM_AUTO_CODE, VK_OEM_ENLW_CODE,
};
use super::lang_bar::LangBarButton;

const PRESERVED_TOGGLE_KEYS: [(u32, u32); 4] = [
    (VK_KANJI_CODE as u32, TF_MOD_IGNORE_ALL_MODIFIER),
    (VK_OEM_AUTO_CODE as u32, TF_MOD_IGNORE_ALL_MODIFIER),
    (VK_OEM_ENLW_CODE as u32, TF_MOD_IGNORE_ALL_MODIFIER),
    (VK_OEM_3_CODE as u32, TF_MOD_ALT),
];

pub(crate) fn spawn_settings_app() {
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

#[implement(ITfTextInputProcessor)]
pub(crate) struct TextService {
    thread_id: RefCell<Option<u32>>,
    keystroke_mgr: RefCell<Option<ITfKeystrokeMgr>>,
    key_sink: RefCell<Option<ITfKeyEventSink>>,
    engine: RefCell<Option<Rc<EngineClient>>>,
    compartment_sources: RefCell<Vec<(ITfSource, u32)>>,
    lang_bar_item: RefCell<Option<ITfLangBarItemButton>>,
    thread_mgr: RefCell<Option<ITfThreadMgr>>,
}

impl TextService {
    pub(crate) fn new() -> windows::core::Result<Self> {
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

        let engine = Rc::new(EngineClient::new());
        engine.init();

        let composition = Rc::new(RefCell::new(None));
        let candidate_popup = Rc::new(RefCell::new(CandidatePopup::default()));
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
        if let Ok(source) = thread_mgr.cast::<ITfSource>() {
            if let Ok(focus_sink) = key_sink.cast::<ITfThreadFocusSink>()
                && let Ok(cookie) =
                    unsafe { source.AdviseSink(&ITfThreadFocusSink::IID, &focus_sink) }
            {
                self.compartment_sources
                    .borrow_mut()
                    .push((source.clone(), cookie));
            }
            if let Ok(mgr_sink) = key_sink.cast::<ITfThreadMgrEventSink>()
                && let Ok(cookie) =
                    unsafe { source.AdviseSink(&ITfThreadMgrEventSink::IID, &mgr_sink) }
            {
                self.compartment_sources
                    .borrow_mut()
                    .push((source, cookie));
            }
        }
        for (vkey, modifiers) in PRESERVED_TOGGLE_KEYS {
            let prekey = TF_PRESERVEDKEY {
                uVKey: vkey,
                uModifiers: modifiers,
            };
            if let Err(error) = unsafe {
                keystroke_mgr.PreserveKey(client_id, &GUID_PRESERVEDKEY_TOGGLE, &prekey, &[])
            } {
                tracing::warn!(
                    "failed to preserve toggle key ({vkey:#x}, {modifiers:#x}): {error}"
                );
            }
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
            for (vkey, modifiers) in PRESERVED_TOGGLE_KEYS {
                let prekey = TF_PRESERVEDKEY {
                    uVKey: vkey,
                    uModifiers: modifiers,
                };
                let _ = unsafe {
                    keystroke_mgr.UnpreserveKey(&GUID_PRESERVEDKEY_TOGGLE, &prekey)
                };
            }
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
        crate::tsf::candidate_window::clear_thread_d2d_resources();
        if let Some(engine) = self.engine.borrow_mut().take() {
            engine.save_learning();
        }
        Ok(())
    }
}
