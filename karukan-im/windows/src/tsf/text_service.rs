use std::cell::{Cell, RefCell};
use std::rc::Rc;
use std::sync::{Arc, Mutex};

use karukan_im::config::Settings;
use karukan_im::core::engine::{EngineConfig, InputMethodEngine};
use windows::core::{implement, Interface, Ref};
use windows::Win32::Foundation::E_POINTER;
use windows::Win32::UI::TextServices::{
    GUID_COMPARTMENT_KEYBOARD_OPENCLOSE, ITfClientId, ITfCompartmentEventSink, ITfKeyEventSink,
    ITfKeystrokeMgr, ITfLangBarItemButton, ITfLangBarItemMgr, ITfSource, ITfTextInputProcessor,
    ITfTextInputProcessor_Impl, ITfThreadMgr,
};

use crate::{
    CLSID_KARUKAN_TIP, GUID_COMPARTMENT_KEYBOARD_INPUTMODE_CONVERSION,
    TF_CONVERSIONMODE_FULLSHAPE, TF_CONVERSIONMODE_NATIVE, TF_CONVERSIONMODE_ROMAN,
};

use super::candidate_window::CandidatePopup;
use super::compartment::{
    set_ime_openclose, set_input_mode_conversion, CompartmentSink,
};
use super::init_logging;
use super::key_handler::KeyHandler;
use super::lang_bar::LangBarButton;

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
    engine: RefCell<Option<Arc<Mutex<InputMethodEngine>>>>,
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
