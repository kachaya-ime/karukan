use std::cell::RefCell;
use std::rc::Rc;
use std::sync::Once;

use karukan_im::core::candidate::CandidateList;
use windows::core::{w, BOOL, HRESULT, PCWSTR};
use windows::Win32::Foundation::{HWND, POINT, RECT};
use windows::Win32::Graphics::Gdi::{
    COLOR_WINDOW, GetMonitorInfoW, GetSysColorBrush, MonitorFromPoint, MONITORINFO,
    MONITOR_DEFAULTTONEAREST,
};
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::UI::TextServices::{ITfComposition, ITfContext};
use windows::Win32::UI::WindowsAndMessaging::{
    CreateWindowExW, DefWindowProcW, LB_ADDSTRING, LB_RESETCONTENT, LB_SETCURSEL, MA_NOACTIVATE,
    RegisterClassW, SendMessageW, SetWindowPos, SetWindowTextW, ShowWindow, SW_HIDE,
    SW_SHOWNOACTIVATE, SWP_NOACTIVATE, SWP_SHOWWINDOW, WINDOW_STYLE, WM_MOUSEACTIVATE, WNDCLASSW,
    WS_BORDER, WS_CHILD, WS_EX_NOACTIVATE, WS_EX_TOOLWINDOW, WS_EX_TOPMOST, WS_POPUP, WS_VISIBLE,
    WS_VSCROLL, HWND_TOPMOST,
};

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
pub(crate) struct CandidatePopup {
    pub(crate) window: Option<HWND>,
    pub(crate) list: Option<HWND>,
    pub(crate) auxiliary: Option<HWND>,
    pub(crate) candidates: Vec<String>,
    pub(crate) auxiliary_text: String,
}

impl CandidatePopup {
    pub(crate) fn show(
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

    pub(crate) fn set_aux(&mut self, text: &str) -> windows::core::Result<()> {
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

    pub(crate) fn hide(&mut self) {
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
