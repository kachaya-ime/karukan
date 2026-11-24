//! Named Pipe IPC client for communicating with the out-of-process
//! `karukan-server.exe` IME engine service.
//!
//! Like standard Windows IMEs (Mozc / Google Japanese Input), heavy resources
//! such as system dictionaries, user dictionaries, learning caches, and neural
//! conversion models live in a single user-session background service process
//! (`karukan-server.exe`) rather than being loaded into every target application
//! process via the TSF in-proc DLL.

use std::cell::RefCell;
use std::os::windows::ffi::OsStrExt;
use std::os::windows::process::CommandExt;
use std::path::PathBuf;
use std::process::Command;
use std::time::{Duration, Instant};

use karukan_im::core::engine::{EngineAction, EngineResult};
use karukan_im::core::keycode::KeyEvent;
use windows::Win32::Foundation::{CloseHandle, ERROR_ALREADY_EXISTS, GetLastError};
use windows::Win32::System::Pipes::WaitNamedPipeW;
use windows::Win32::System::Threading::CreateMutexW;
use windows::core::PCWSTR;

use crate::ipc::{IpcRequest, IpcResponse, PipeStream};

const CREATE_NO_WINDOW: u32 = 0x08000000;

/// Client handle to `karukan-server.exe` over a Named Pipe.
///
/// Connections are established lazily on the first real key input and kept
/// open across keystrokes. Lightweight state (`is_empty`, `preedit_text`,
/// `dirty_context`) is tracked locally so `Activate`, `Deactivate`,
/// `OnTestKeyDown`, and IME Open/Close toggles while idle perform **zero**
/// blocking IPC round-trips.
pub struct EngineClient {
    conn: RefCell<Option<PipeStream>>,
    is_empty: RefCell<bool>,
    preedit_text: RefCell<String>,
    has_dirty_learning: RefCell<bool>,
    has_surrounding_context: RefCell<bool>,
    last_spawn_failure: RefCell<Option<Instant>>,
}

impl Default for EngineClient {
    fn default() -> Self {
        Self::new()
    }
}

impl EngineClient {
    pub fn new() -> Self {
        Self {
            conn: RefCell::new(None),
            is_empty: RefCell::new(true),
            preedit_text: RefCell::new(String::new()),
            has_dirty_learning: RefCell::new(false),
            has_surrounding_context: RefCell::new(false),
            last_spawn_failure: RefCell::new(None),
        }
    }

    /// Ensure `karukan-server.exe` is running in the background without blocking
    /// the UI thread waiting for a pipe connection during TSF `Activate`.
    pub fn init(&self) {
        if !Self::is_server_running() {
            Self::spawn_server_process();
        }
    }

    /// Fast local check whether the engine is currently in the `Empty` state.
    pub fn is_empty(&self) -> bool {
        *self.is_empty.borrow()
    }

    /// Current preedit text cached from the last engine update.
    pub fn preedit_text(&self) -> String {
        self.preedit_text.borrow().clone()
    }

    pub fn process_key(&self, event: &KeyEvent) -> EngineResult {
        let req = IpcRequest::from_key_event(event);
        match self.call(&req) {
            Some(IpcResponse::EngineResult {
                consumed,
                actions,
                is_empty,
                preedit_text,
            }) => {
                *self.is_empty.borrow_mut() = is_empty;
                *self.preedit_text.borrow_mut() = preedit_text;
                let engine_actions: Vec<EngineAction> =
                    actions.into_iter().map(EngineAction::from).collect();
                if engine_actions
                    .iter()
                    .any(|a| matches!(a, EngineAction::Commit(_)))
                {
                    *self.has_dirty_learning.borrow_mut() = true;
                }
                EngineResult {
                    consumed,
                    actions: engine_actions,
                }
            }
            _ => EngineResult::not_consumed(),
        }
    }

    pub fn commit(&self) -> EngineResult {
        if self.is_empty() {
            return EngineResult::consumed()
                .with_action(EngineAction::HideCandidates)
                .with_action(EngineAction::HideAuxText);
        }
        match self.call(&IpcRequest::Commit) {
            Some(IpcResponse::EngineResult {
                consumed,
                actions,
                is_empty,
                preedit_text,
            }) => {
                *self.is_empty.borrow_mut() = is_empty;
                *self.preedit_text.borrow_mut() = preedit_text;
                *self.has_dirty_learning.borrow_mut() = true;
                EngineResult {
                    consumed,
                    actions: actions.into_iter().map(EngineAction::from).collect(),
                }
            }
            _ => {
                *self.is_empty.borrow_mut() = true;
                self.preedit_text.borrow_mut().clear();
                EngineResult::consumed()
                    .with_action(EngineAction::HideCandidates)
                    .with_action(EngineAction::HideAuxText)
            }
        }
    }

    pub fn reset(&self) {
        // Avoid unnecessary IPC if already in Empty state and no connection is open
        let was_empty = *self.is_empty.borrow();
        *self.is_empty.borrow_mut() = true;
        self.preedit_text.borrow_mut().clear();
        if !was_empty || self.conn.borrow().is_some() {
            let _ = self.call(&IpcRequest::Reset);
        }
    }

    pub fn set_surrounding_context(&self, left: &str, right: &str) {
        if left.is_empty() && right.is_empty() {
            if !*self.has_surrounding_context.borrow() {
                // Already empty; skip redundant IPC on focus changes / mode toggles.
                return;
            }
            *self.has_surrounding_context.borrow_mut() = false;
            if self.conn.borrow().is_none() {
                return;
            }
        } else {
            *self.has_surrounding_context.borrow_mut() = true;
        }
        let _ = self.call(&IpcRequest::SetSurroundingContext {
            left: left.to_string(),
            right: right.to_string(),
        });
    }

    pub fn save_learning(&self) {
        if !*self.has_dirty_learning.borrow() {
            return;
        }
        *self.has_dirty_learning.borrow_mut() = false;
        let _ = self.call(&IpcRequest::SaveLearning);
    }

    fn call(&self, request: &IpcRequest) -> Option<IpcResponse> {
        // Attempt 1: existing or fresh connection
        if let Some(resp) = self.try_send(request) {
            self.sync_cached_state(&resp);
            return Some(resp);
        }

        // Attempt 2: reconnect
        *self.conn.borrow_mut() = None;
        if let Some(resp) = self.try_send(request) {
            self.sync_cached_state(&resp);
            return Some(resp);
        }
        None
    }

    fn sync_cached_state(&self, resp: &IpcResponse) {
        match resp {
            IpcResponse::Ack {
                is_empty,
                preedit_text,
            }
            | IpcResponse::EngineResult {
                is_empty,
                preedit_text,
                ..
            } => {
                *self.is_empty.borrow_mut() = *is_empty;
                *self.preedit_text.borrow_mut() = preedit_text.clone();
            }
            IpcResponse::Error { .. } => {}
        }
    }

    fn try_send(&self, request: &IpcRequest) -> Option<IpcResponse> {
        self.ensure_connected()?;
        let mut borrow = self.conn.borrow_mut();
        let stream = borrow.as_mut()?;

        if stream.send_json(request).is_err() {
            *borrow = None;
            return None;
        }

        match stream.recv_json::<IpcResponse>() {
            Ok(resp) => Some(resp),
            Err(_) => {
                *borrow = None;
                None
            }
        }
    }

    fn ensure_connected(&self) -> Option<()> {
        if self.conn.borrow().is_some() {
            return Some(());
        }
        let pipe_path = crate::server_pipe_name();
        if let Ok(stream) = PipeStream::connect(&pipe_path) {
            *self.conn.borrow_mut() = Some(stream);
            return Some(());
        }

        // Avoid rapid retry storms if the server binary is missing or failing
        if let Some(last_fail) = *self.last_spawn_failure.borrow()
            && last_fail.elapsed() < Duration::from_secs(2)
        {
            return None;
        }

        if !Self::is_server_running() {
            Self::spawn_server_process();
        }

        let deadline = Instant::now() + Duration::from_millis(400);
        let wide_pipe: Vec<u16> = std::ffi::OsStr::new(&pipe_path)
            .encode_wide()
            .chain(Some(0))
            .collect();
        while Instant::now() < deadline {
            unsafe {
                let _ = WaitNamedPipeW(PCWSTR(wide_pipe.as_ptr()), 50);
            }
            if let Ok(stream) = PipeStream::connect(&pipe_path) {
                *self.conn.borrow_mut() = Some(stream);
                *self.last_spawn_failure.borrow_mut() = None;
                return Some(());
            }
            std::thread::sleep(Duration::from_millis(15));
        }
        *self.last_spawn_failure.borrow_mut() = Some(Instant::now());
        tracing::error!("Timed out waiting for karukan-server pipe at {pipe_path}");
        None
    }

    /// Check whether `karukan-server.exe` is already holding its single-instance mutex.
    pub fn is_server_running() -> bool {
        let mutex_name: Vec<u16> = std::ffi::OsStr::new(&crate::server_mutex_name())
            .encode_wide()
            .chain(Some(0))
            .collect();
        let Ok(handle) = (unsafe { CreateMutexW(None, false, PCWSTR(mutex_name.as_ptr())) }) else {
            return false;
        };
        let already_exists = unsafe { GetLastError() } == ERROR_ALREADY_EXISTS;
        unsafe {
            let _ = CloseHandle(handle);
        }
        already_exists
    }

    pub fn spawn_server_process() {
        let exe = Self::resolve_server_exe();
        match Command::new(&exe)
            .creation_flags(CREATE_NO_WINDOW)
            .spawn()
        {
            Ok(child) => {
                tracing::info!(
                    "Spawned karukan-server.exe (PID: {}, Path: {})",
                    child.id(),
                    exe.display()
                );
            }
            Err(error) => {
                tracing::error!(
                    "Failed to spawn karukan-server.exe at {}: {error}",
                    exe.display()
                );
            }
        }
    }

    fn resolve_server_exe() -> PathBuf {
        if let Some(override_path) = std::env::var_os("KARUKAN_SERVER_EXE") {
            let path = PathBuf::from(override_path);
            if path.is_file() {
                return path;
            }
        }
        if let Some(local_app_data) = std::env::var_os("LOCALAPPDATA") {
            let candidate = PathBuf::from(local_app_data)
                .join("Programs")
                .join("Karukan")
                .join("karukan-server.exe");
            if candidate.is_file() {
                return candidate;
            }
        }
        if let Ok(current_exe) = std::env::current_exe()
            && let Some(parent) = current_exe.parent()
        {
            let beside = parent.join("karukan-server.exe");
            if beside.is_file() {
                return beside;
            }
        }
        PathBuf::from("karukan-server.exe")
    }
}

/// Send a one-shot [`IpcRequest`] to the running `karukan-server.exe` service.
pub fn notify_server(request: &IpcRequest) -> bool {
    let pipe_path = crate::server_pipe_name();
    let Ok(mut stream) = PipeStream::connect(&pipe_path) else {
        return false;
    };
    if stream.send_json(request).is_err() {
        return false;
    }
    matches!(
        stream.recv_json::<IpcResponse>(),
        Ok(IpcResponse::Ack { .. } | IpcResponse::EngineResult { .. })
    )
}
