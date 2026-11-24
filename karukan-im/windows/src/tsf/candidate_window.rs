use std::cell::RefCell;
use std::rc::Rc;
use std::sync::atomic::{AtomicIsize, Ordering};
use std::sync::Once;

use karukan_im::core::candidate::CandidateList;
use windows::core::{w, BOOL, HRESULT, PCWSTR};
use windows::Win32::Foundation::{COLORREF, HWND, LPARAM, LRESULT, POINT, RECT, WPARAM};
use windows::Win32::Graphics::Direct2D::Common::{
    D2D1_COLOR_F, D2D_RECT_F,
};
use windows::Win32::Graphics::Direct2D::{
    D2D1CreateFactory, ID2D1DCRenderTarget, ID2D1Factory, D2D1_DRAW_TEXT_OPTIONS_ENABLE_COLOR_FONT,
    D2D1_FACTORY_TYPE_SINGLE_THREADED, D2D1_RENDER_TARGET_PROPERTIES,
    D2D1_RENDER_TARGET_TYPE_DEFAULT, D2D1_RENDER_TARGET_USAGE_NONE,
};
use windows::Win32::Graphics::DirectWrite::{
    DWriteCreateFactory, IDWriteFactory, IDWriteTextFormat, DWRITE_FACTORY_TYPE_SHARED,
    DWRITE_FONT_STRETCH_NORMAL, DWRITE_FONT_STYLE_NORMAL, DWRITE_FONT_WEIGHT_NORMAL,
};
use windows::Win32::Graphics::Gdi::{
    BeginPaint, CreateFontW, CreateSolidBrush, DeleteObject, EndPaint, FillRect, FrameRect,
    GetMonitorInfoW, GetSysColorBrush, MonitorFromPoint, SetBkMode, SetTextColor, TextOutW,
    COLOR_WINDOW, FONT_CHARSET, FONT_CLIP_PRECISION, FONT_OUTPUT_PRECISION, FONT_QUALITY, HBRUSH,
    HDC, HFONT, HGDIOBJ, MONITORINFO, MONITOR_DEFAULTTONEAREST, PAINTSTRUCT, TRANSPARENT,
};
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::System::Threading::GetCurrentThreadId;
use windows::Win32::UI::Controls::{DRAWITEMSTRUCT, MEASUREITEMSTRUCT};
use windows::Win32::UI::Input::KeyboardAndMouse::{GetActiveWindow, GetFocus};
use windows::Win32::UI::TextServices::{ITfComposition, ITfContext};
use windows::Win32::UI::WindowsAndMessaging::{
    CreateWindowExW, DefWindowProcW, DestroyWindow, GetAncestor, GetClientRect,
    GetForegroundWindow, GetWindow, GetWindowLongPtrW, GetWindowThreadProcessId, IsChild,
    IsWindow, IsWindowVisible, KillTimer, LB_ADDSTRING, LB_GETTEXT, LB_RESETCONTENT, LB_SETCURSEL,
    MA_NOACTIVATE, RegisterClassW, SendMessageW, SetTimer, SetWindowLongPtrW, SetWindowPos,
    SetWindowTextW, ShowWindow, GA_ROOT, GWLP_USERDATA, GW_OWNER, HWND_TOPMOST, SW_HIDE,
    SW_SHOWNOACTIVATE, SWP_NOACTIVATE, SWP_SHOWWINDOW, WINDOW_STYLE, WM_ACTIVATEAPP,
    WM_CTLCOLORLISTBOX, WM_CTLCOLORSTATIC, WM_DRAWITEM, WM_MEASUREITEM, WM_MOUSEACTIVATE,
    WM_PAINT, WM_TIMER, WNDCLASSW, WS_CHILD, WS_EX_NOACTIVATE, WS_EX_TOOLWINDOW, WS_EX_TOPMOST,
    WS_POPUP, WS_VISIBLE,
};

#[link(name = "dwmapi")]
unsafe extern "system" {
    fn DwmSetWindowAttribute(
        hwnd: HWND,
        dwAttribute: u32,
        pvAttribute: *const std::ffi::c_void,
        cbAttribute: u32,
    ) -> windows::core::HRESULT;
}
const DWMWA_WINDOW_CORNER_PREFERENCE: u32 = 33;
const DWMWCP_ROUND: u32 = 2;

const CANDIDATE_WINDOW_CLASS: PCWSTR = w!("KarukanCandidatePopup");
static REGISTER_POPUP_CLASS: Once = Once::new();

const WM_SETFONT: u32 = 0x0030;
const TIMER_ID_FOCUS_WATCH: usize = 1;
const FOCUS_WATCH_INTERVAL_MS: u32 = 100;
const ITEM_HEIGHT: i32 = 30;
const AUX_HEIGHT: i32 = 38;
const POPUP_WIDTH: i32 = 420;
const ODS_SELECTED_FLAG: u32 = 0x0001;
const LBS_NOTIFY: u32 = 0x0001;
const LBS_OWNERDRAWFIXED: u32 = 0x0010;
const LBS_HASSTRINGS: u32 = 0x0040;
const LBS_NOINTEGRALHEIGHT: u32 = 0x0100;

static WHITE_BRUSH: AtomicIsize = AtomicIsize::new(0);
static FOOTER_BRUSH: AtomicIsize = AtomicIsize::new(0);

struct D2DResources {
    _d2d_factory: ID2D1Factory,
    dwrite_factory: IDWriteFactory,
    dc_render_target: ID2D1DCRenderTarget,
    text_format: IDWriteTextFormat,
}

thread_local! {
    static D2D_RESOURCES: RefCell<Option<D2DResources>> = const { RefCell::new(None) };
}

fn init_d2d_resources() -> Option<()> {
    D2D_RESOURCES.with(|res| {
        if res.borrow().is_some() {
            return Some(());
        }
        let d2d_factory: ID2D1Factory =
            unsafe { D2D1CreateFactory(D2D1_FACTORY_TYPE_SINGLE_THREADED, None).ok()? };
        let dwrite_factory: IDWriteFactory = unsafe {
            DWriteCreateFactory(DWRITE_FACTORY_TYPE_SHARED).ok()?
        };
        let rt_props = D2D1_RENDER_TARGET_PROPERTIES {
            r#type: D2D1_RENDER_TARGET_TYPE_DEFAULT,
            pixelFormat: windows::Win32::Graphics::Direct2D::Common::D2D1_PIXEL_FORMAT {
                format: windows::Win32::Graphics::Dxgi::Common::DXGI_FORMAT_B8G8R8A8_UNORM,
                alphaMode: windows::Win32::Graphics::Direct2D::Common::D2D1_ALPHA_MODE_PREMULTIPLIED,
            },
            dpiX: 0.0,
            dpiY: 0.0,
            usage: D2D1_RENDER_TARGET_USAGE_NONE,
            minLevel: windows::Win32::Graphics::Direct2D::D2D1_FEATURE_LEVEL_DEFAULT,
        };
        let dc_render_target: ID2D1DCRenderTarget =
            unsafe { d2d_factory.CreateDCRenderTarget(&rt_props).ok()? };

        let font_family = wide("Yu Gothic UI");
        let locale = wide("ja-jp");
        let text_format: IDWriteTextFormat = unsafe {
            dwrite_factory
                .CreateTextFormat(
                    PCWSTR(font_family.as_ptr()),
                    None,
                    DWRITE_FONT_WEIGHT_NORMAL,
                    DWRITE_FONT_STYLE_NORMAL,
                    DWRITE_FONT_STRETCH_NORMAL,
                    14.0, // フォントサイズ 14px (pt換算/96DPI基準)
                    PCWSTR(locale.as_ptr()),
                )
                .ok()?
        };

        *res.borrow_mut() = Some(D2DResources {
            _d2d_factory: d2d_factory,
            dwrite_factory,
            dc_render_target,
            text_format,
        });
        Some(())
    })
}

fn get_white_brush() -> HBRUSH {
    let handle = WHITE_BRUSH.load(Ordering::Relaxed);
    if handle != 0 {
        return HBRUSH(handle as *mut std::ffi::c_void);
    }
    let brush = unsafe { CreateSolidBrush(COLORREF(0x00FFFFFF)) };
    WHITE_BRUSH.store(brush.0 as isize, Ordering::Relaxed);
    brush
}

fn get_footer_brush() -> HBRUSH {
    let handle = FOOTER_BRUSH.load(Ordering::Relaxed);
    if handle != 0 {
        return HBRUSH(handle as *mut std::ffi::c_void);
    }
    let brush = unsafe { CreateSolidBrush(COLORREF(0x00F8F8F8)) };
    FOOTER_BRUSH.store(brush.0 as isize, Ordering::Relaxed);
    brush
}

fn wide(value: &str) -> Vec<u16> {
    value.encode_utf16().chain(Some(0)).collect()
}

fn create_font(height: i32, weight: i32) -> HFONT {
    let font_name = wide("Yu Gothic UI");
    unsafe {
        CreateFontW(
            height,
            0,
            0,
            0,
            weight,
            0,
            0,
            0,
            FONT_CHARSET(1),
            FONT_OUTPUT_PRECISION(0),
            FONT_CLIP_PRECISION(0),
            FONT_QUALITY(5), // CLEARTYPE_QUALITY
            0,
            PCWSTR(font_name.as_ptr()),
        )
    }
}

fn set_font(hwnd: HWND, font: HFONT) {
    if hwnd.0 != 0 as _ {
        unsafe {
            SendMessageW(
                hwnd,
                WM_SETFONT,
                Some(WPARAM(font.0 as usize)),
                Some(LPARAM(1)),
            );
        }
    }
}

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
    wparam: WPARAM,
    lparam: LPARAM,
) -> LRESULT {
    match msg {
        WM_MOUSEACTIVATE => LRESULT(MA_NOACTIVATE as isize),
        WM_ACTIVATEAPP => {
            if wparam.0 == 0 {
                unsafe {
                    let _ = KillTimer(Some(hwnd), TIMER_ID_FOCUS_WATCH);
                    let _ = ShowWindow(hwnd, SW_HIDE);
                }
            }
            LRESULT(0)
        }
        WM_TIMER => {
            if wparam.0 == TIMER_ID_FOCUS_WATCH {
                unsafe {
                    if !IsWindowVisible(hwnd).as_bool() {
                        let _ = KillTimer(Some(hwnd), TIMER_ID_FOCUS_WATCH);
                    } else {
                        let fg = GetForegroundWindow();
                        let tracked_raw = GetWindowLongPtrW(hwnd, GWLP_USERDATA);
                        let tracked_hwnd = HWND(tracked_raw as *mut std::ffi::c_void);
                        let owner = GetWindow(hwnd, GW_OWNER).unwrap_or_default();

                        let mut should_hide = false;
                        if fg.0.is_null() {
                            should_hide = true;
                        } else if !tracked_hwnd.0.is_null() {
                            let fg_root = GetAncestor(fg, GA_ROOT);
                            let tracked_root = GetAncestor(tracked_hwnd, GA_ROOT);
                            let same_window = fg == tracked_hwnd
                                || (!fg_root.0.is_null() && fg_root == tracked_root)
                                || IsChild(fg, tracked_hwnd).as_bool()
                                || IsChild(tracked_hwnd, fg).as_bool();
                            if !same_window {
                                should_hide = true;
                            }
                        } else if !owner.0.is_null() {
                            let fg_root = GetAncestor(fg, GA_ROOT);
                            let owner_root = GetAncestor(owner, GA_ROOT);
                            let same_window = fg == owner
                                || (!fg_root.0.is_null() && fg_root == owner_root)
                                || IsChild(fg, owner).as_bool()
                                || IsChild(owner, fg).as_bool();
                            if !same_window {
                                should_hide = true;
                            }
                        } else {
                            let popup_tid = GetCurrentThreadId();
                            let mut popup_pid = 0u32;
                            let _ = GetWindowThreadProcessId(hwnd, Some(&mut popup_pid));
                            let mut fg_pid = 0u32;
                            let fg_tid = GetWindowThreadProcessId(fg, Some(&mut fg_pid));
                            if (fg_pid != 0 && popup_pid != 0 && fg_pid != popup_pid)
                                || (fg_tid != 0 && fg_tid != popup_tid)
                            {
                                should_hide = true;
                            }
                        }

                        if should_hide {
                            let _ = KillTimer(Some(hwnd), TIMER_ID_FOCUS_WATCH);
                            let _ = ShowWindow(hwnd, SW_HIDE);
                        }
                    }
                }
                return LRESULT(0);
            }
            unsafe { DefWindowProcW(hwnd, msg, wparam, lparam) }
        }
        WM_MEASUREITEM => {
            if lparam.0 != 0 {
                let mis = unsafe { &mut *(lparam.0 as *mut MEASUREITEMSTRUCT) };
                mis.itemHeight = ITEM_HEIGHT as u32;
                return LRESULT(1);
            }
            LRESULT(0)
        }
        WM_DRAWITEM => {
            if lparam.0 != 0 {
                let dis = unsafe { &*(lparam.0 as *const DRAWITEMSTRUCT) };
                if dis.itemID != u32::MAX {
                    let index = dis.itemID as usize;
                    let is_selected = (dis.itemState.0 & ODS_SELECTED_FLAG) != 0;
                    unsafe {
                        // 背景描画
                        let bg_color = if is_selected {
                            COLORREF(0x00FBF3EB) // 淡いアクセントブルー #EBF3FB
                        } else {
                            COLORREF(0x00FFFFFF) // 白
                        };
                        let bg_brush = CreateSolidBrush(bg_color);
                        let item_rect = if is_selected {
                            RECT {
                                left: dis.rcItem.left + 4,
                                top: dis.rcItem.top + 2,
                                right: dis.rcItem.right - 4,
                                bottom: dis.rcItem.bottom - 2,
                            }
                        } else {
                            dis.rcItem
                        };
                        FillRect(dis.hDC, &item_rect, bg_brush);
                        let _ = DeleteObject(HGDIOBJ(bg_brush.0));

                        // 選択時の左端アクセントバー (#0067C0)
                        if is_selected {
                            let bar_rect = RECT {
                                left: item_rect.left + 2,
                                top: item_rect.top + 5,
                                right: item_rect.left + 5,
                                bottom: item_rect.bottom - 5,
                            };
                            let bar_brush = CreateSolidBrush(COLORREF(0x00C06700));
                            FillRect(dis.hDC, &bar_rect, bar_brush);
                            let _ = DeleteObject(HGDIOBJ(bar_brush.0));
                        }

                        // テキスト取得 (LB_GETTEXT)
                        let mut buffer = [0u16; 256];
                        let len = SendMessageW(
                            dis.hwndItem,
                            LB_GETTEXT,
                            Some(WPARAM(index)),
                            Some(LPARAM(buffer.as_mut_ptr() as isize)),
                        )
                        .0;

                        if len > 0 {
                            let full_text = String::from_utf16_lossy(&buffer[..len as usize]);
                            let (num_part, rest) = match full_text.split_once(". ") {
                                Some((n, r)) => (format!("{n}."), r),
                                None => (String::new(), full_text.as_str()),
                            };
                            let (word_part, desc_part) = match rest.split_once("  ") {
                                Some((w, d)) => (w, Some(d)),
                                None => (rest, None),
                            };

                            // Direct2D / DirectWrite によるテキスト描画（カラー絵文字サポート）
                            let mut d2d_rendered = false;
                            init_d2d_resources();
                            D2D_RESOURCES.with(|res| {
                                let mut res_mut = res.borrow_mut();
                                if let Some(d2d) = res_mut.as_mut() {
                                    if d2d.dc_render_target.BindDC(dis.hDC, &dis.rcItem).is_ok() {
                                        d2d.dc_render_target.BeginDraw();

                                        let item_w = (dis.rcItem.right - dis.rcItem.left) as f32;
                                        let item_h = (dis.rcItem.bottom - dis.rcItem.top) as f32;

                                        // 番号 ("1.")
                                        let num_color = if is_selected {
                                            D2D1_COLOR_F { r: 0.0, g: 0.373, b: 0.722, a: 1.0 } // #005FB8
                                        } else {
                                            D2D1_COLOR_F { r: 0.439, g: 0.439, b: 0.439, a: 1.0 } // #707070
                                        };
                                        if let Ok(brush) = d2d.dc_render_target.CreateSolidColorBrush(&num_color, None) {
                                            let wide_num = wide(&num_part);
                                            let num_rect = D2D_RECT_F {
                                                left: 14.0,
                                                top: 5.0,
                                                right: 38.0,
                                                bottom: item_h,
                                            };
                                            d2d.dc_render_target.DrawText(
                                                &wide_num[..wide_num.len().saturating_sub(1)],
                                                &d2d.text_format,
                                                &num_rect,
                                                &brush,
                                                D2D1_DRAW_TEXT_OPTIONS_ENABLE_COLOR_FONT,
                                                windows::Win32::Graphics::DirectWrite::DWRITE_MEASURING_MODE_NATURAL,
                                            );
                                        }

                                        // 単語 ("漢字" / 絵文字含む)
                                        let word_color = if is_selected {
                                            D2D1_COLOR_F { r: 0.059, g: 0.059, b: 0.059, a: 1.0 } // #0F0F0F
                                        } else {
                                            D2D1_COLOR_F { r: 0.122, g: 0.122, b: 0.122, a: 1.0 } // #1F1F1F
                                        };
                                        let wide_word = wide(word_part);
                                        let word_slice = &wide_word[..wide_word.len().saturating_sub(1)];
                                        let mut word_width = (word_part.chars().count() as f32) * 15.0;

                                        // 単語の幅を計測して説明部分の配置に利用
                                        if let Ok(layout) = d2d.dwrite_factory.CreateTextLayout(
                                            word_slice,
                                            &d2d.text_format,
                                            (item_w - 48.0).max(10.0),
                                            item_h,
                                        ) {
                                            let mut metrics = std::mem::zeroed();
                                            if layout.GetMetrics(&mut metrics).is_ok() {
                                                word_width = metrics.width;
                                            }
                                            if let Ok(brush) = d2d.dc_render_target.CreateSolidColorBrush(&word_color, None) {
                                                let word_rect = D2D_RECT_F {
                                                    left: 40.0,
                                                    top: 5.0,
                                                    right: item_w - 8.0,
                                                    bottom: item_h,
                                                };
                                                d2d.dc_render_target.DrawText(
                                                    word_slice,
                                                    &d2d.text_format,
                                                    &word_rect,
                                                    &brush,
                                                    D2D1_DRAW_TEXT_OPTIONS_ENABLE_COLOR_FONT,
                                                    windows::Win32::Graphics::DirectWrite::DWRITE_MEASURING_MODE_NATURAL,
                                                );
                                            }
                                        }

                                        // 説明 ("  [名詞]"): 補足グレー
                                        if let Some(desc) = desc_part {
                                            let desc_color = D2D1_COLOR_F { r: 0.502, g: 0.502, b: 0.502, a: 1.0 }; // #808080
                                            if let Ok(brush) = d2d.dc_render_target.CreateSolidColorBrush(&desc_color, None) {
                                                let wide_desc = wide(desc);
                                                let desc_x = (48.0 + word_width).min(item_w - 120.0);
                                                let desc_rect = D2D_RECT_F {
                                                    left: desc_x,
                                                    top: 6.0,
                                                    right: item_w - 8.0,
                                                    bottom: item_h,
                                                };
                                                d2d.dc_render_target.DrawText(
                                                    &wide_desc[..wide_desc.len().saturating_sub(1)],
                                                    &d2d.text_format,
                                                    &desc_rect,
                                                    &brush,
                                                    D2D1_DRAW_TEXT_OPTIONS_ENABLE_COLOR_FONT,
                                                    windows::Win32::Graphics::DirectWrite::DWRITE_MEASURING_MODE_NATURAL,
                                                );
                                            }
                                        }

                                        if let Err(error) = d2d.dc_render_target.EndDraw(None, None) {
                                            tracing::warn!("Direct2D EndDraw failed: {error:?}");
                                            // エラー時はレンダリングターゲットを破棄し次回再生成
                                            *res_mut = None;
                                        } else {
                                            d2d_rendered = true;
                                        }
                                    }
                                }
                            });

                            // フォールバック: D2D が使用できない場合は従来の GDI TextOutW
                            if !d2d_rendered {
                                let _ = SetBkMode(dis.hDC, TRANSPARENT);

                                // 番号 ("1."): グレー
                                let num_color = if is_selected {
                                    COLORREF(0x00005FB8)
                                } else {
                                    COLORREF(0x00707070)
                                };
                                let _ = SetTextColor(dis.hDC, num_color);
                                let wide_num = wide(&num_part);
                                let _ = TextOutW(
                                    dis.hDC,
                                    dis.rcItem.left + 14,
                                    dis.rcItem.top + 5,
                                    &wide_num[..wide_num.len().saturating_sub(1)],
                                );

                                // 単語 ("漢字"): 読みやすい黒
                                let word_color = if is_selected {
                                    COLORREF(0x000F0F0F)
                                } else {
                                    COLORREF(0x001F1F1F)
                                };
                                let _ = SetTextColor(dis.hDC, word_color);
                                let wide_word = wide(word_part);
                                let _ = TextOutW(
                                    dis.hDC,
                                    dis.rcItem.left + 40,
                                    dis.rcItem.top + 5,
                                    &wide_word[..wide_word.len().saturating_sub(1)],
                                );

                                // 説明 ("  [名詞]"): 補足グレー
                                if let Some(desc) = desc_part {
                                    let _ = SetTextColor(dis.hDC, COLORREF(0x00808080));
                                    let wide_desc = wide(desc);
                                    let word_px = (word_part.chars().count() as i32) * 15;
                                    let desc_x = (dis.rcItem.left + 48 + word_px).min(dis.rcItem.right - 120);
                                    let _ = TextOutW(
                                        dis.hDC,
                                        desc_x,
                                        dis.rcItem.top + 6,
                                        &wide_desc[..wide_desc.len().saturating_sub(1)],
                                    );
                                }
                            }
                        }
                    }
                    return LRESULT(1);
                }
            }
            LRESULT(0)
        }
        WM_CTLCOLORSTATIC => {
            let hdc = HDC(wparam.0 as *mut std::ffi::c_void);
            unsafe {
                let _ = SetBkMode(hdc, TRANSPARENT);
                let _ = SetTextColor(hdc, COLORREF(0x00555555));
                LRESULT(get_footer_brush().0 as isize)
            }
        }
        WM_CTLCOLORLISTBOX => LRESULT(get_white_brush().0 as isize),
        WM_PAINT => {
            let mut ps = PAINTSTRUCT::default();
            let hdc = unsafe { BeginPaint(hwnd, &mut ps) };
            if hdc.0 != 0 as _ {
                let mut rc = RECT::default();
                if unsafe { GetClientRect(hwnd, &mut rc) }.is_ok() {
                    unsafe {
                        // 外枠境界線 (#D0D0D0)
                        let border_brush = CreateSolidBrush(COLORREF(0x00D0D0D0));
                        let _ = FrameRect(hdc, &rc, border_brush);
                        let _ = DeleteObject(HGDIOBJ(border_brush.0));

                        // フッターとの区切り線 (#E5E5E5)
                        let footer_y = rc.bottom - AUX_HEIGHT;
                        let div_rect = RECT {
                            left: 0,
                            top: footer_y,
                            right: rc.right,
                            bottom: footer_y + 1,
                        };
                        let div_brush = CreateSolidBrush(COLORREF(0x00E5E5E5));
                        FillRect(hdc, &div_rect, div_brush);
                        let _ = DeleteObject(HGDIOBJ(div_brush.0));

                        // フッター領域の背景 (#F8F8F8)
                        let footer_rect = RECT {
                            left: 1,
                            top: footer_y + 1,
                            right: rc.right - 1,
                            bottom: rc.bottom - 1,
                        };
                        FillRect(hdc, &footer_rect, get_footer_brush());
                    }
                }
                unsafe {
                    let _ = EndPaint(hwnd, &ps);
                }
            }
            LRESULT(0)
        }
        _ => unsafe { DefWindowProcW(hwnd, msg, wparam, lparam) },
    }
}

#[derive(Default)]
pub(crate) struct CandidatePopup {
    pub(crate) window: Option<HWND>,
    pub(crate) owner_hwnd: Option<HWND>,
    pub(crate) list: Option<HWND>,
    pub(crate) auxiliary: Option<HWND>,
    pub(crate) candidates: Vec<String>,
    pub(crate) auxiliary_text: String,
    pub(crate) font: Option<HFONT>,
    pub(crate) small_font: Option<HFONT>,
}

impl CandidatePopup {
    fn reset_handles(&mut self) {
        if let Some(font) = self.font.take() {
            unsafe {
                let _ = DeleteObject(HGDIOBJ(font.0));
            }
        }
        if let Some(font) = self.small_font.take() {
            unsafe {
                let _ = DeleteObject(HGDIOBJ(font.0));
            }
        }
        if let Some(window) = self.window.take() {
            unsafe {
                let _ = KillTimer(Some(window), TIMER_ID_FOCUS_WATCH);
                if IsWindow(Some(window)).as_bool() {
                    let _ = DestroyWindow(window);
                }
            }
        }
        self.owner_hwnd = None;
        self.list = None;
        self.auxiliary = None;
    }

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
        let range = {
            let composition_guard = composition.borrow();
            let active = composition_guard
                .as_ref()
                .ok_or_else(|| windows::core::Error::from_hresult(HRESULT(0x80004005u32 as i32)))?;
            unsafe { active.GetRange()? }
        };
        let view = unsafe { context.GetActiveView()? };
        let mut clipped = BOOL(0);
        unsafe {
            view.GetTextExt(edit_cookie, &range, &mut rect, &mut clipped)?;
        }

        let item_count = self.candidates.len().max(1) as i32;
        let list_height = (item_count * ITEM_HEIGHT).clamp(ITEM_HEIGHT, 360);
        let auxiliary_height = AUX_HEIGHT;
        let height = list_height + auxiliary_height + 4;
        let width = POPUP_WIDTH;

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

        let mut x = rect.left;
        if x + width > rc_work_right {
            x = rc_work_right - width;
        }
        if x < rc_work_left {
            x = rc_work_left;
        }

        let mut y = rect.bottom + 4;
        // If popup overflows bottom of work area, flip above the caret if there is room
        if y + height > rc_work_bottom {
            if rect.top - height - 4 >= rc_work_top {
                y = rect.top - height - 4;
            } else {
                y = (rc_work_bottom - height).max(rc_work_top);
            }
        }

        let owner_hwnd = if let Ok(hwnd) = unsafe { view.GetWnd() }
            && !hwnd.0.is_null()
        {
            Some(hwnd)
        } else {
            let focus_hwnd = unsafe { GetFocus() };
            let active_hwnd = unsafe { GetActiveWindow() };
            let fg_hwnd = unsafe { GetForegroundWindow() };
            if !focus_hwnd.0.is_null() {
                Some(focus_hwnd)
            } else if !active_hwnd.0.is_null() {
                Some(active_hwnd)
            } else if !fg_hwnd.0.is_null() {
                Some(fg_hwnd)
            } else {
                None
            }
        };

        if let Some(existing) = self.window {
            let valid = unsafe { IsWindow(Some(existing)).as_bool() };
            if !valid || (owner_hwnd.is_some() && self.owner_hwnd != owner_hwnd) {
                self.reset_handles();
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
                        WS_POPUP,
                        x,
                        y,
                        width,
                        height,
                        owner_hwnd,
                        None,
                        None,
                        None,
                    )?
                };
                unsafe {
                    let corner = DWMWCP_ROUND;
                    let _ = DwmSetWindowAttribute(
                        window,
                        DWMWA_WINDOW_CORNER_PREFERENCE,
                        &corner as *const _ as *const std::ffi::c_void,
                        std::mem::size_of::<u32>() as u32,
                    );
                }
                self.window = Some(window);
                self.owner_hwnd = owner_hwnd;
                let list_style = WS_CHILD
                    | WS_VISIBLE
                    | WINDOW_STYLE(
                        (LBS_NOTIFY | LBS_OWNERDRAWFIXED | LBS_HASSTRINGS | LBS_NOINTEGRALHEIGHT)
                            as u32,
                    );
                let list = unsafe {
                    CreateWindowExW(
                        Default::default(),
                        w!("LISTBOX"),
                        PCWSTR::null(),
                        list_style,
                        2,
                        2,
                        width - 4,
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
                        10,
                        list_height + 4,
                        width - 20,
                        auxiliary_height - 6,
                        Some(window),
                        None,
                        None,
                        None,
                    )?
                };
                let font = create_font(-15, 400);
                let small_font = create_font(-12, 400);
                set_font(list, font);
                set_font(auxiliary, small_font);
                self.font = Some(font);
                self.small_font = Some(small_font);
                self.list = Some(list);
                self.auxiliary = Some(auxiliary);
                window
            }
        };
        unsafe {
            self.refresh_text(candidates.page_cursor())?;
            if let Some(list) = self.list {
                SetWindowPos(list, None, 2, 2, width - 4, list_height, SWP_NOACTIVATE)?;
            }
            if let Some(auxiliary) = self.auxiliary {
                SetWindowPos(
                    auxiliary,
                    None,
                    10,
                    list_height + 4,
                    width - 20,
                    auxiliary_height - 6,
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
            let tracked = owner_hwnd.unwrap_or_else(|| GetForegroundWindow());
            let _ = SetWindowLongPtrW(window, GWLP_USERDATA, tracked.0 as isize);
            let _ = ShowWindow(window, SW_SHOWNOACTIVATE);
            let _ = SetTimer(
                Some(window),
                TIMER_ID_FOCUS_WATCH,
                FOCUS_WATCH_INTERVAL_MS,
                None,
            );
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
                let _ = KillTimer(Some(window), TIMER_ID_FOCUS_WATCH);
                if IsWindow(Some(window)).as_bool() {
                    let _ = ShowWindow(window, SW_HIDE);
                }
            }
        }
    }
}

impl Drop for CandidatePopup {
    fn drop(&mut self) {
        self.reset_handles();
    }
}
