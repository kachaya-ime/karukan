use std::cell::RefCell;
use std::mem::ManuallyDrop;
use std::ptr;
use std::rc::Rc;

use karukan_im::core::engine::EngineAction;
use windows::core::{implement, Interface, BOOL, Ref};
use windows::Win32::System::Variant::{VARIANT, VARIANT_0, VARIANT_0_0, VARIANT_0_0_0, VT_I4};
use windows::Win32::UI::TextServices::{
    GUID_PROP_COMPOSING, INSERT_TEXT_AT_SELECTION_FLAGS, ITfComposition, ITfCompositionSink,
    ITfCompositionSink_Impl, ITfContext, ITfContextComposition, ITfEditSession,
    ITfEditSession_Impl, ITfInsertAtSelection, ITfRange, TF_AE_END, TF_ANCHOR_END,
    TF_ANCHOR_START, TF_CONTEXT_EDIT_CONTEXT_FLAGS, TF_SELECTION, TF_SELECTIONSTYLE,
};

use crate::engine_client::EngineClient;
use super::candidate_window::CandidatePopup;

pub(crate) const EDIT_SESSION_FLAGS: TF_CONTEXT_EDIT_CONTEXT_FLAGS =
    TF_CONTEXT_EDIT_CONTEXT_FLAGS(7);

#[implement(ITfEditSession)]
pub(crate) struct EditSession {
    pub(crate) context: ITfContext,
    pub(crate) actions: Vec<EngineAction>,
    pub(crate) composition: Rc<RefCell<Option<ITfComposition>>>,
    pub(crate) candidate_popup: Rc<RefCell<CandidatePopup>>,
    pub(crate) engine: Rc<EngineClient>,
    pub(crate) end_composition: bool,
    pub(crate) reset_engine: Option<Rc<EngineClient>>,
}

impl ITfEditSession_Impl for EditSession_Impl {
    fn DoEditSession(&self, edit_cookie: u32) -> windows::core::Result<()> {
        if self.end_composition {
            let active = self.composition.try_borrow_mut().ok().and_then(|mut c| c.take());
            if let Some(active) = active {
                unsafe {
                    if let Ok(range) = active.GetRange() {
                        let _ = range.SetText(edit_cookie, 0, &[]);
                    }
                    let _ = active.EndComposition(edit_cookie);
                };
            }
        }
        if let Some(engine) = &self.reset_engine {
            engine.reset();
        }
        for action in &self.actions {
            match action {
                EngineAction::UpdatePreedit(preedit) => {
                    update_preedit(
                        &self.context,
                        edit_cookie,
                        &self.composition,
                        &self.candidate_popup,
                        &self.engine,
                        preedit.text(),
                        preedit.caret(),
                    )?;
                }
                EngineAction::Commit(text) => {
                    commit_text(&self.context, edit_cookie, self.composition.as_ref(), text)?;
                }
                EngineAction::ShowCandidates(candidates) => {
                    if let Ok(mut popup) = self.candidate_popup.try_borrow_mut() {
                        if let Err(error) = popup.show(
                            &self.context,
                            edit_cookie,
                            &self.composition,
                            candidates,
                        ) {
                            tracing::error!("failed to show Karukan candidate window: {error}");
                        }
                    }
                }
                EngineAction::HideCandidates => {
                    if let Ok(mut popup) = self.candidate_popup.try_borrow_mut() {
                        popup.hide();
                    }
                }
                EngineAction::UpdateAuxText(text) => {
                    if let Ok(mut popup) = self.candidate_popup.try_borrow_mut() {
                        if let Err(error) = popup.set_aux(text) {
                            tracing::error!("failed to update Karukan candidate window: {error}");
                        }
                    }
                }
                EngineAction::HideAuxText => {
                    if let Ok(mut popup) = self.candidate_popup.try_borrow_mut() {
                        if let Err(error) = popup.set_aux("") {
                            tracing::error!("failed to update Karukan candidate window: {error}");
                        }
                    }
                }
            }
        }
        Ok(())
    }
}

#[implement(ITfEditSession)]
pub(crate) struct CaptureContextSession {
    pub(crate) context: ITfContext,
    pub(crate) left_context: Rc<RefCell<String>>,
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

pub(crate) fn capture_surrounding_text(context: &ITfContext, thread_id: u32) -> String {
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

#[implement(ITfCompositionSink)]
pub(crate) struct CompositionSink {
    pub(crate) composition: Rc<RefCell<Option<ITfComposition>>>,
    pub(crate) candidate_popup: Rc<RefCell<CandidatePopup>>,
    pub(crate) engine: Rc<EngineClient>,
}

impl ITfCompositionSink_Impl for CompositionSink_Impl {
    fn OnCompositionTerminated(
        &self,
        _edit_cookie: u32,
        terminated: Ref<ITfComposition>,
    ) -> windows::core::Result<()> {
        let matches_current = match (self.composition.try_borrow().ok(), terminated.as_ref()) {
            (Some(current), Some(terminated)) => current
                .as_ref()
                .is_some_and(|c| c.as_raw() == terminated.as_raw()),
            _ => false,
        };
        if matches_current {
            if let Ok(mut comp) = self.composition.try_borrow_mut() {
                comp.take();
            }
            if let Ok(mut popup) = self.candidate_popup.try_borrow_mut() {
                popup.hide();
            }
            self.engine.reset();
            self.engine.set_surrounding_context("", "");
        }
        Ok(())
    }
}

pub(crate) fn update_preedit(
    context: &ITfContext,
    edit_cookie: u32,
    composition: &Rc<RefCell<Option<ITfComposition>>>,
    candidate_popup: &Rc<RefCell<CandidatePopup>>,
    engine: &Rc<EngineClient>,
    text: &str,
    caret: usize,
) -> windows::core::Result<()> {
    if text.is_empty() {
        let active = composition.try_borrow_mut().ok().and_then(|mut c| c.take());
        if let Some(active) = active {
            unsafe { active.EndComposition(edit_cookie)? };
        }
        if let Ok(mut popup) = candidate_popup.try_borrow_mut() {
            popup.hide();
        }
        return Ok(());
    }

    let existing = composition.try_borrow().ok().and_then(|c| c.clone());
    let active = if let Some(active) = existing {
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
            candidate_popup: candidate_popup.clone(),
            engine: engine.clone(),
        }
        .into();
        let context_composition: ITfContextComposition = context.cast()?;
        let composition_result =
            unsafe { context_composition.StartComposition(edit_cookie, &range, &sink)? };
        if let Ok(mut comp) = composition.try_borrow_mut() {
            *comp = Some(composition_result.clone());
        }
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

pub(crate) fn commit_text(
    context: &ITfContext,
    edit_cookie: u32,
    composition: &RefCell<Option<ITfComposition>>,
    text: &str,
) -> windows::core::Result<()> {
    let active = composition.try_borrow_mut().ok().and_then(|mut c| c.take());
    if let Some(active) = active {
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

pub(crate) fn set_caret_at_end(
    context: &ITfContext,
    edit_cookie: u32,
    range: &ITfRange,
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

pub(crate) fn set_caret(
    context: &ITfContext,
    edit_cookie: u32,
    range: &ITfRange,
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
