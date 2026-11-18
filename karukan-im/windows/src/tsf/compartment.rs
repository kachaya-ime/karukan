use std::cell::RefCell;
use std::mem::ManuallyDrop;
use std::rc::Rc;
use std::sync::{Arc, Mutex};

use karukan_im::core::engine::InputMethodEngine;
use karukan_im::core::keycode::{KeyEvent, KeyModifiers, Keysym};
use windows::core::{implement, GUID};
use windows::Win32::System::Variant::{VARIANT, VARIANT_0, VARIANT_0_0, VARIANT_0_0_0, VT_I4};
use windows::Win32::UI::TextServices::{
    GUID_COMPARTMENT_KEYBOARD_OPENCLOSE, ITfCompartmentEventSink, ITfCompartmentEventSink_Impl,
    ITfLangBarItemSink, ITfThreadMgr,
};

use crate::{
    GUID_COMPARTMENT_KEYBOARD_INPUTMODE_CONVERSION, TF_CONVERSIONMODE_ALPHANUMERIC,
    TF_CONVERSIONMODE_FULLSHAPE, TF_CONVERSIONMODE_KATAKANA, TF_CONVERSIONMODE_NATIVE,
    TF_CONVERSIONMODE_ROMAN,
};

#[implement(ITfCompartmentEventSink)]
pub(crate) struct CompartmentSink {
    pub(crate) engine: Arc<Mutex<InputMethodEngine>>,
    pub(crate) thread_mgr: ITfThreadMgr,
    pub(crate) lang_bar_sink: Rc<RefCell<Option<ITfLangBarItemSink>>>,
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

pub(crate) fn get_ime_openclose(thread_mgr: &ITfThreadMgr) -> windows::core::Result<bool> {
    let global = unsafe { thread_mgr.GetGlobalCompartment()? };
    let compartment = unsafe { global.GetCompartment(&GUID_COMPARTMENT_KEYBOARD_OPENCLOSE)? };
    let current = unsafe { compartment.GetValue()? };
    let value = unsafe { &*current.Anonymous.Anonymous };
    if value.vt != VT_I4 {
        return Ok(true);
    }
    Ok(unsafe { value.Anonymous.lVal != 0 })
}

pub(crate) fn set_ime_openclose(
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

pub(crate) fn get_input_mode_conversion(thread_mgr: &ITfThreadMgr) -> windows::core::Result<u32> {
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

pub(crate) fn set_input_mode_conversion(
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

pub(crate) fn toggle_ime_openclose(thread_mgr: &ITfThreadMgr, client_id: u32) -> windows::core::Result<()> {
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
