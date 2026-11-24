//! Shared IPC protocol types and raw Win32 Named Pipe I/O between the TSF
//! frontend (`karukan_im_windows.dll`), the settings GUI (`karukan-settings.exe`),
//! the installer (`install.exe`), and the background engine service (`karukan-server.exe`).
//!
//! IMPORTANT: On Windows, synchronous (non-`FILE_FLAG_OVERLAPPED`) Named Pipe
//! handles serialize `ReadFile` and `WriteFile` inside the kernel on the same
//! file object. Wrapping a pipe handle in `std::fs::File` and calling
//! `try_clone()` to read on one handle while the peer waits causes a kernel
//! deadlock (`WaitNamedPipeW` / `CreateFileW` blocks forever).
//! Instead, [`PipeStream`] uses single-handle half-duplex Win32 `WriteFile` →
//! `ReadFile` framing (`write_frame` followed by `read_frame`).

use karukan_im::core::candidate::{Candidate, CandidateList};
use karukan_im::core::engine::{EngineAction, EngineResult};
use karukan_im::core::keycode::{KeyEvent, KeyModifiers, Keysym};
use karukan_im::core::preedit::{AttributeType, Preedit, PreeditAttribute};
use serde::{Deserialize, Serialize};
use windows::Win32::Foundation::{CloseHandle, GENERIC_READ, GENERIC_WRITE, HANDLE};
use windows::Win32::Storage::FileSystem::{
    CreateFileW, FILE_ATTRIBUTE_NORMAL, FILE_SHARE_NONE, OPEN_EXISTING, ReadFile, WriteFile,
};
use windows::core::PCWSTR;

/// Single-handle synchronous half-duplex Named Pipe stream using length-prefixed
/// (`u32` little-endian byte length + JSON payload) framing.
///
/// Using length-prefixed framing with raw `ReadFile` / `WriteFile` on a single
/// `HANDLE` avoids both `DuplicateHandle` kernel serialization locks and
/// byte-by-byte `BufReader` overhead.
pub struct PipeStream {
    handle: HANDLE,
}

unsafe impl Send for PipeStream {}

impl PipeStream {
    pub fn from_raw_handle(handle: HANDLE) -> Self {
        Self { handle }
    }

    pub fn as_raw_handle(&self) -> HANDLE {
        self.handle
    }

    /// Connect to an existing Named Pipe server as a client (non-overlapped).
    pub fn connect(pipe_path: &str) -> std::io::Result<Self> {
        use std::os::windows::ffi::OsStrExt;
        let wide: Vec<u16> = std::ffi::OsStr::new(pipe_path)
            .encode_wide()
            .chain(Some(0))
            .collect();
        let handle = unsafe {
            CreateFileW(
                PCWSTR(wide.as_ptr()),
                (GENERIC_READ | GENERIC_WRITE).0,
                FILE_SHARE_NONE,
                None,
                OPEN_EXISTING,
                FILE_ATTRIBUTE_NORMAL,
                None,
            )
        }
        .map_err(|e| std::io::Error::from_raw_os_error(e.code().0))?;

        if handle.is_invalid() {
            return Err(std::io::Error::last_os_error());
        }
        Ok(Self { handle })
    }

    pub fn write_frame(&mut self, payload: &[u8]) -> std::io::Result<()> {
        let len = u32::try_from(payload.len())
            .map_err(|_| std::io::Error::new(std::io::ErrorKind::InvalidInput, "frame too large"))?;
        self.write_all(&len.to_le_bytes())?;
        self.write_all(payload)
    }

    pub fn read_frame(&mut self) -> std::io::Result<Vec<u8>> {
        let mut len_buf = [0u8; 4];
        self.read_exact(&mut len_buf)?;
        let len = u32::from_le_bytes(len_buf) as usize;
        if len > 16 * 1024 * 1024 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                "IPC frame exceeds 16MB limit",
            ));
        }
        let mut buf = vec![0u8; len];
        self.read_exact(&mut buf)?;
        Ok(buf)
    }

    pub fn send_json<T: Serialize>(&mut self, msg: &T) -> std::io::Result<()> {
        let bytes = serde_json::to_vec(msg)
            .map_err(|e| std::io::Error::new(std::io::ErrorKind::InvalidData, e))?;
        self.write_frame(&bytes)
    }

    pub fn recv_json<T: for<'de> Deserialize<'de>>(&mut self) -> std::io::Result<T> {
        let bytes = self.read_frame()?;
        serde_json::from_slice(&bytes)
            .map_err(|e| std::io::Error::new(std::io::ErrorKind::InvalidData, e))
    }

    fn write_all(&mut self, mut buf: &[u8]) -> std::io::Result<()> {
        while !buf.is_empty() {
            let mut written = 0u32;
            unsafe {
                WriteFile(self.handle, Some(buf), Some(&mut written), None)
                    .map_err(|e| std::io::Error::from_raw_os_error(e.code().0))?;
            }
            if written == 0 {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::WriteZero,
                    "WriteFile wrote 0 bytes",
                ));
            }
            buf = &buf[written as usize..];
        }
        Ok(())
    }

    fn read_exact(&mut self, mut buf: &mut [u8]) -> std::io::Result<()> {
        while !buf.is_empty() {
            let mut read = 0u32;
            unsafe {
                ReadFile(self.handle, Some(buf), Some(&mut read), None)
                    .map_err(|e| std::io::Error::from_raw_os_error(e.code().0))?;
            }
            if read == 0 {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::UnexpectedEof,
                    "pipe closed",
                ));
            }
            buf = &mut buf[read as usize..];
        }
        Ok(())
    }
}

impl Drop for PipeStream {
    fn drop(&mut self) {
        if !self.handle.is_invalid() && !self.handle.0.is_null() {
            unsafe {
                let _ = CloseHandle(self.handle);
            }
        }
    }
}

/// IPC request sent from a client (TSF DLL, settings app, or installer) to `karukan-server.exe`.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum IpcRequest {
    /// Ensure the engine is initialized.
    Init,
    /// Process a key event and return the resulting UI actions.
    ProcessKey {
        keysym: u32,
        modifiers: IpcKeyModifiers,
        is_press: bool,
    },
    /// Commit any active composition and return cleanup/commit actions.
    Commit,
    /// Reset the engine state to `Empty`.
    Reset,
    /// Update surrounding text context around the cursor.
    SetSurroundingContext {
        left: String,
        right: String,
    },
    /// Persist any dirty learning cache entries to disk.
    SaveLearning,
    /// Reload `config.toml` and learning cache in the service.
    ReloadSettings,
    /// Reload `learning.tsv` from disk in the service.
    ReloadLearning,
    /// Reload `user_dicts/*.tsv` by restarting the service process cleanly.
    ReloadUserDictionaries,
    /// Save learning cache and terminate the service process.
    Shutdown,
}

impl IpcRequest {
    pub fn from_key_event(event: &KeyEvent) -> Self {
        Self::ProcessKey {
            keysym: event.keysym.0,
            modifiers: IpcKeyModifiers::from(event.modifiers),
            is_press: event.is_press,
        }
    }
}

/// IPC response returned from `karukan-server.exe` to a client.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum IpcResponse {
    /// Acknowledge request without payload (`Init`, `Reset`, `SaveLearning`, `Reload*`, `Shutdown`).
    Ack {
        is_empty: bool,
        preedit_text: String,
    },
    /// Result of `ProcessKey` or `Commit`.
    EngineResult {
        consumed: bool,
        actions: Vec<IpcAction>,
        is_empty: bool,
        preedit_text: String,
    },
    /// Error message if a request failed.
    Error {
        message: String,
    },
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct IpcKeyModifiers {
    pub shift_key: bool,
    pub control_key: bool,
    pub alt_key: bool,
    pub super_key: bool,
}

impl From<KeyModifiers> for IpcKeyModifiers {
    fn from(m: KeyModifiers) -> Self {
        Self {
            shift_key: m.shift_key,
            control_key: m.control_key,
            alt_key: m.alt_key,
            super_key: m.super_key,
        }
    }
}

impl From<IpcKeyModifiers> for KeyModifiers {
    fn from(m: IpcKeyModifiers) -> Self {
        Self {
            shift_key: m.shift_key,
            control_key: m.control_key,
            alt_key: m.alt_key,
            super_key: m.super_key,
        }
    }
}

/// Serializable mirror of [`EngineAction`].
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum IpcAction {
    UpdatePreedit {
        text: String,
        caret: usize,
        attributes: Vec<IpcPreeditAttribute>,
    },
    ShowCandidates {
        candidates: Vec<IpcCandidate>,
        cursor: usize,
    },
    HideCandidates,
    Commit(String),
    UpdateAuxText(String),
    HideAuxText,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum IpcAttributeType {
    Underline,
    UnderlineDouble,
    Highlight,
    Reverse,
}

impl From<AttributeType> for IpcAttributeType {
    fn from(t: AttributeType) -> Self {
        match t {
            AttributeType::Underline => Self::Underline,
            AttributeType::UnderlineDouble => Self::UnderlineDouble,
            AttributeType::Highlight => Self::Highlight,
            AttributeType::Reverse => Self::Reverse,
        }
    }
}

impl From<IpcAttributeType> for AttributeType {
    fn from(t: IpcAttributeType) -> Self {
        match t {
            IpcAttributeType::Underline => Self::Underline,
            IpcAttributeType::UnderlineDouble => Self::UnderlineDouble,
            IpcAttributeType::Highlight => Self::Highlight,
            IpcAttributeType::Reverse => Self::Reverse,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct IpcPreeditAttribute {
    pub start: usize,
    pub end: usize,
    pub attr_type: IpcAttributeType,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct IpcCandidate {
    pub text: String,
    pub reading: Option<String>,
    pub description: Option<String>,
}

impl From<EngineAction> for IpcAction {
    fn from(action: EngineAction) -> Self {
        match action {
            EngineAction::UpdatePreedit(preedit) => Self::UpdatePreedit {
                text: preedit.text().to_string(),
                caret: preedit.caret(),
                attributes: preedit
                    .attributes()
                    .iter()
                    .map(|a| IpcPreeditAttribute {
                        start: a.start,
                        end: a.end,
                        attr_type: a.attr_type.into(),
                    })
                    .collect(),
            },
            EngineAction::ShowCandidates(list) => Self::ShowCandidates {
                candidates: list
                    .candidates()
                    .iter()
                    .map(|c| IpcCandidate {
                        text: c.text.clone(),
                        reading: c.reading.clone(),
                        description: c.description.clone(),
                    })
                    .collect(),
                cursor: list.cursor(),
            },
            EngineAction::HideCandidates => Self::HideCandidates,
            EngineAction::Commit(text) => Self::Commit(text),
            EngineAction::UpdateAuxText(text) => Self::UpdateAuxText(text),
            EngineAction::HideAuxText => Self::HideAuxText,
        }
    }
}

impl From<IpcAction> for EngineAction {
    fn from(action: IpcAction) -> Self {
        match action {
            IpcAction::UpdatePreedit {
                text,
                caret,
                attributes,
            } => {
                let mut preedit = Preedit::with_text(text);
                preedit.set_caret(caret);
                preedit.set_attributes(
                    attributes
                        .into_iter()
                        .map(|a| PreeditAttribute::new(a.start, a.end, a.attr_type.into()))
                        .collect(),
                );
                Self::UpdatePreedit(preedit)
            }
            IpcAction::ShowCandidates { candidates, cursor } => {
                let items = candidates
                    .into_iter()
                    .map(|c| {
                        let mut cand = Candidate::new(c.text);
                        cand.reading = c.reading;
                        cand.description = c.description;
                        cand
                    })
                    .collect();
                let mut list = CandidateList::new(items);
                if !list.is_empty() {
                    list.set_cursor(cursor);
                }
                Self::ShowCandidates(list)
            }
            IpcAction::HideCandidates => Self::HideCandidates,
            IpcAction::Commit(text) => Self::Commit(text),
            IpcAction::UpdateAuxText(text) => Self::UpdateAuxText(text),
            IpcAction::HideAuxText => Self::HideAuxText,
        }
    }
}

impl IpcRequest {
    pub fn to_key_event(&self) -> Option<KeyEvent> {
        match *self {
            Self::ProcessKey {
                keysym,
                modifiers,
                is_press,
            } => Some(KeyEvent::new(Keysym(keysym), modifiers.into(), is_press)),
            _ => None,
        }
    }
}

impl IpcResponse {
    pub fn from_engine_result(
        result: EngineResult,
        is_empty: bool,
        preedit_text: String,
    ) -> Self {
        Self::EngineResult {
            consumed: result.consumed,
            actions: result.actions.into_iter().map(IpcAction::from).collect(),
            is_empty,
            preedit_text,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ipc_request_and_response_roundtrip() {
        let event = KeyEvent::new(
            Keysym::KEY_A,
            KeyModifiers {
                shift_key: true,
                control_key: false,
                alt_key: false,
                super_key: false,
            },
            true,
        );
        let req = IpcRequest::from_key_event(&event);
        let json = serde_json::to_string(&req).unwrap();
        let decoded: IpcRequest = serde_json::from_str(&json).unwrap();
        let roundtripped = decoded.to_key_event().unwrap();
        assert_eq!(roundtripped.keysym, Keysym::KEY_A);
        assert!(roundtripped.modifiers.shift_key);
        assert!(roundtripped.is_press);

        let mut cand = Candidate::with_reading("漢字", "かんじ");
        cand.description = Some("名詞".to_string());
        let mut list = CandidateList::new(vec![Candidate::new("感じ"), cand]);
        list.set_cursor(1);

        let engine_res = EngineResult::consumed()
            .with_action(EngineAction::UpdatePreedit(Preedit::with_text_underlined("漢字")))
            .with_action(EngineAction::ShowCandidates(list))
            .with_action(EngineAction::UpdateAuxText("[あ] かんじ 3/30".to_string()));

        let resp = IpcResponse::from_engine_result(engine_res, false, "漢字".to_string());
        let resp_json = serde_json::to_string(&resp).unwrap();
        let decoded_resp: IpcResponse = serde_json::from_str(&resp_json).unwrap();

        match decoded_resp {
            IpcResponse::EngineResult {
                consumed,
                actions,
                is_empty,
                preedit_text,
            } => {
                assert!(consumed);
                assert!(!is_empty);
                assert_eq!(preedit_text, "漢字");
                assert_eq!(actions.len(), 3);
                let action1: EngineAction = actions[1].clone().into();
                if let EngineAction::ShowCandidates(restored_list) = action1 {
                    assert_eq!(restored_list.len(), 2);
                    assert_eq!(restored_list.cursor(), 1);
                    assert_eq!(restored_list.selected_text(), Some("漢字"));
                    assert_eq!(
                        restored_list.selected().unwrap().description.as_deref(),
                        Some("名詞")
                    );
                } else {
                    panic!("expected ShowCandidates");
                }
            }
            other => panic!("unexpected response: {other:?}"),
        }
    }

    #[test]
    fn pipe_stream_win32_named_pipe_roundtrip() {
        use std::os::windows::ffi::OsStrExt;
        use windows::Win32::Foundation::{ERROR_PIPE_CONNECTED, GetLastError};
        use windows::Win32::Storage::FileSystem::PIPE_ACCESS_DUPLEX;
        use windows::Win32::System::Pipes::{
            ConnectNamedPipe, CreateNamedPipeW, DisconnectNamedPipe, PIPE_READMODE_BYTE,
            PIPE_TYPE_BYTE, PIPE_UNLIMITED_INSTANCES, PIPE_WAIT,
        };

        let pipe_name = format!(r"\\.\pipe\karukan-test-pipe-{}", std::process::id());
        let wide_name: Vec<u16> = std::ffi::OsStr::new(&pipe_name)
            .encode_wide()
            .chain(Some(0))
            .collect();

        let server_handle = unsafe {
            CreateNamedPipeW(
                PCWSTR(wide_name.as_ptr()),
                PIPE_ACCESS_DUPLEX,
                PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT,
                PIPE_UNLIMITED_INSTANCES,
                65536,
                65536,
                0,
                None,
            )
        };
        assert!(!server_handle.is_invalid());
        let raw_server = server_handle.0 as usize;

        let server_thread = std::thread::spawn(move || {
            let h = HANDLE(raw_server as *mut std::ffi::c_void);
            let connected = match unsafe { ConnectNamedPipe(h, None) } {
                Ok(()) => true,
                Err(_) => unsafe { GetLastError() == ERROR_PIPE_CONNECTED },
            };
            assert!(connected);
            let mut stream = PipeStream::from_raw_handle(h);
            for i in 0..5 {
                let req: IpcRequest = stream.recv_json().unwrap();
                match req {
                    IpcRequest::ProcessKey { keysym, .. } => {
                        let resp = IpcResponse::Ack {
                            is_empty: false,
                            preedit_text: format!("key-{keysym}-{i}"),
                        };
                        stream.send_json(&resp).unwrap();
                    }
                    _ => panic!("unexpected request"),
                }
            }
            unsafe {
                let _ = DisconnectNamedPipe(stream.as_raw_handle());
            }
        });

        let mut client = PipeStream::connect(&pipe_name).unwrap();
        for i in 0..5 {
            let req = IpcRequest::ProcessKey {
                keysym: 0x61 + i,
                modifiers: IpcKeyModifiers::default(),
                is_press: true,
            };
            client.send_json(&req).unwrap();
            let resp: IpcResponse = client.recv_json().unwrap();
            match resp {
                IpcResponse::Ack {
                    is_empty,
                    preedit_text,
                } => {
                    assert!(!is_empty);
                    assert_eq!(preedit_text, format!("key-{}-{i}", 0x61 + i));
                }
                other => panic!("unexpected response: {other:?}"),
            }
        }
        drop(client);
        server_thread.join().unwrap();
    }
}
