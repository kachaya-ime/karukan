#![cfg_attr(windows, windows_subsystem = "windows")]

#[cfg(windows)]
fn main() {
    use std::ffi::OsStr;
    use std::os::windows::ffi::OsStrExt;
    use std::sync::{Mutex, mpsc};

    use karukan_im::config::Settings;
    use karukan_im::core::engine::{EngineConfig, InputMethodEngine};
    use karukan_im_windows::ipc::{IpcRequest, IpcResponse, PipeStream};
    use windows::Win32::Foundation::{
        CloseHandle, ERROR_ALREADY_EXISTS, ERROR_PIPE_CONNECTED, GetLastError, HLOCAL, LocalFree,
    };
    use windows::Win32::Security::Authorization::{
        ConvertStringSecurityDescriptorToSecurityDescriptorW, SDDL_REVISION_1,
    };
    use windows::Win32::Security::{PSECURITY_DESCRIPTOR, SECURITY_ATTRIBUTES};
    use windows::Win32::Storage::FileSystem::PIPE_ACCESS_DUPLEX;
    use windows::Win32::System::Pipes::{
        ConnectNamedPipe, CreateNamedPipeW, DisconnectNamedPipe, PIPE_READMODE_BYTE,
        PIPE_TYPE_BYTE, PIPE_UNLIMITED_INSTANCES, PIPE_WAIT,
    };
    use windows::Win32::System::Threading::CreateMutexW;
    use windows::core::{PCWSTR, w};

    struct EngineTask {
        request: IpcRequest,
        reply_tx: mpsc::Sender<IpcResponse>,
    }

    fn wide(value: &str) -> Vec<u16> {
        OsStr::new(value).encode_wide().chain(Some(0)).collect()
    }

    fn create_initialized_engine() -> InputMethodEngine {
        let settings = Settings::load_or_default();
        let mut engine = InputMethodEngine::with_config(EngineConfig::from_settings(&settings));
        if let Err(error) = engine.init_from_settings(&settings) {
            tracing::error!("Failed to initialize InputMethodEngine: {error:#}");
        }
        engine
    }

    fn engine_status(engine: &InputMethodEngine) -> (bool, String) {
        let is_empty = engine.state().is_empty();
        let preedit_text = engine
            .preedit()
            .map(|p| p.text().to_string())
            .unwrap_or_default();
        (is_empty, preedit_text)
    }

    fn init_server_logging() {
        let log_file = std::env::var_os("LOCALAPPDATA").and_then(|p| {
            let dir = std::path::PathBuf::from(p)
                .join("karukan")
                .join("karukan-im")
                .join("logs");
            std::fs::create_dir_all(&dir).ok()?;
            std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open(dir.join("windows-server.log"))
                .ok()
        });
        if let Some(file) = log_file {
            let _ = tracing_subscriber::fmt()
                .with_env_filter(
                    tracing_subscriber::EnvFilter::try_from_default_env()
                        .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
                )
                .with_writer(Mutex::new(file))
                .with_ansi(false)
                .try_init();
        } else {
            let _ = tracing_subscriber::fmt()
                .with_env_filter(
                    tracing_subscriber::EnvFilter::try_from_default_env()
                        .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
                )
                .with_writer(std::io::stderr)
                .try_init();
        }
    }

    init_server_logging();

    let _ = karukan_im_windows::configure_model_cache();

    let mutex_name = wide(&karukan_im_windows::server_mutex_name());
    let mutex = match unsafe { CreateMutexW(None, true, PCWSTR(mutex_name.as_ptr())) } {
        Ok(handle) => handle,
        Err(error) => {
            tracing::error!("Failed to create server single-instance mutex: {error}");
            return;
        }
    };
    if unsafe { GetLastError() } == ERROR_ALREADY_EXISTS {
        tracing::info!("karukan-server is already running; exiting duplicate instance");
        unsafe {
            let _ = CloseHandle(mutex);
        }
        return;
    }

    let pipe_path = karukan_im_windows::server_pipe_name();
    let pipe_name = wide(&pipe_path);

    // Allow both standard desktop processes (Authenticated Users: AU, System: SY, Local Admin: BA)
    // and UWP / WinUI 3 AppContainer processes (ALL APPLICATION PACKAGES: AC) + Low Mandatory Level (LW)
    // to connect to the IME engine pipe.
    let mut security_descriptor = PSECURITY_DESCRIPTOR::default();
    let has_sd = unsafe {
        ConvertStringSecurityDescriptorToSecurityDescriptorW(
            w!("D:(A;;GA;;;SY)(A;;GA;;;BA)(A;;GA;;;AU)(A;;GA;;;AC)S:(ML;;NWNR;;;LW)"),
            SDDL_REVISION_1,
            &mut security_descriptor,
            None,
        )
        .is_ok()
    };

    let (task_tx, task_rx) = mpsc::channel::<EngineTask>();
    let mutex_raw = mutex.0 as usize;

    // Dedicated engine owner thread: holds `InputMethodEngine` (system dictionary, user dictionaries,
    // learning cache, and conversion models) directly on a single thread and processes typed IPC requests.
    std::thread::Builder::new()
        .name("karukan-engine-worker".to_string())
        .spawn(move || {
            let mut engine = create_initialized_engine();
            while let Ok(EngineTask { request, reply_tx }) = task_rx.recv() {
                match request {
                    IpcRequest::Init => {
                        let (is_empty, preedit_text) = engine_status(&engine);
                        let _ = reply_tx.send(IpcResponse::Ack {
                            is_empty,
                            preedit_text,
                        });
                    }
                    IpcRequest::ProcessKey { .. } => {
                        if let Some(event) = request.to_key_event() {
                            let result = engine.process_key(&event);
                            let (is_empty, preedit_text) = engine_status(&engine);
                            let _ = reply_tx.send(IpcResponse::from_engine_result(
                                result,
                                is_empty,
                                preedit_text,
                            ));
                        }
                    }
                    IpcRequest::Commit => {
                        let result = engine.commit_result();
                        let (is_empty, preedit_text) = engine_status(&engine);
                        let _ = reply_tx.send(IpcResponse::from_engine_result(
                            result,
                            is_empty,
                            preedit_text,
                        ));
                    }
                    IpcRequest::Reset => {
                        engine.reset();
                        let (is_empty, preedit_text) = engine_status(&engine);
                        let _ = reply_tx.send(IpcResponse::Ack {
                            is_empty,
                            preedit_text,
                        });
                    }
                    IpcRequest::SetSurroundingContext { left, right } => {
                        engine.set_surrounding_context(&left, &right);
                        let (is_empty, preedit_text) = engine_status(&engine);
                        let _ = reply_tx.send(IpcResponse::Ack {
                            is_empty,
                            preedit_text,
                        });
                    }
                    IpcRequest::SaveLearning => {
                        engine.save_learning();
                        let (is_empty, preedit_text) = engine_status(&engine);
                        let _ = reply_tx.send(IpcResponse::Ack {
                            is_empty,
                            preedit_text,
                        });
                    }
                    IpcRequest::ReloadSettings => {
                        engine.save_learning();
                        engine = create_initialized_engine();
                        let (is_empty, preedit_text) = engine_status(&engine);
                        let _ = reply_tx.send(IpcResponse::Ack {
                            is_empty,
                            preedit_text,
                        });
                    }
                    IpcRequest::ReloadLearning => {
                        // Do not save before reloading: disk already contains the newly edited learning.tsv
                        engine = create_initialized_engine();
                        let (is_empty, preedit_text) = engine_status(&engine);
                        let _ = reply_tx.send(IpcResponse::Ack {
                            is_empty,
                            preedit_text,
                        });
                    }
                    IpcRequest::ReloadUserDictionaries => {
                        engine.save_learning();
                        let (is_empty, preedit_text) = engine_status(&engine);
                        let _ = reply_tx.send(IpcResponse::Ack {
                            is_empty,
                            preedit_text,
                        });
                        // `USER_DICT_CACHE` in `karukan-im` is a process-global `OnceLock`.
                        // Release the single-instance mutex, spawn a fresh service process, and exit.
                        let h = windows::Win32::Foundation::HANDLE(mutex_raw as *mut std::ffi::c_void);
                        unsafe {
                            let _ = CloseHandle(h);
                        }
                        karukan_im_windows::engine_client::EngineClient::spawn_server_process();
                        std::process::exit(0);
                    }
                    IpcRequest::Shutdown => {
                        engine.save_learning();
                        let _ = reply_tx.send(IpcResponse::Ack {
                            is_empty: true,
                            preedit_text: String::new(),
                        });
                        break;
                    }
                }
            }
        })
        .expect("failed to spawn karukan-engine-worker thread");

    tracing::info!(
        "=== Karukan Engine Service started (PID: {}, Version: {}, Pipe: {}) ===",
        std::process::id(),
        karukan_engine::VERSION,
        pipe_path
    );

    let create_pipe_instance = || {
        let sa = SECURITY_ATTRIBUTES {
            nLength: std::mem::size_of::<SECURITY_ATTRIBUTES>() as u32,
            lpSecurityDescriptor: security_descriptor.0,
            bInheritHandle: false.into(),
        };
        let sa_ptr = if has_sd {
            Some(&sa as *const SECURITY_ATTRIBUTES)
        } else {
            None
        };
        unsafe {
            CreateNamedPipeW(
                PCWSTR(pipe_name.as_ptr()),
                PIPE_ACCESS_DUPLEX,
                PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT,
                PIPE_UNLIMITED_INSTANCES,
                65536,
                65536,
                0,
                sa_ptr,
            )
        }
    };

    // Always create the next listening pipe instance BEFORE waiting or spawning,
    // so concurrent clients never hit ERROR_FILE_NOT_FOUND between accepts.
    let mut current_handle = create_pipe_instance();

    loop {
        if current_handle.is_invalid() {
            tracing::error!("CreateNamedPipeW failed: {:?}", unsafe { GetLastError() });
            std::thread::sleep(std::time::Duration::from_millis(200));
            current_handle = create_pipe_instance();
            continue;
        }

        let connected = match unsafe { ConnectNamedPipe(current_handle, None) } {
            Ok(()) => true,
            Err(error) => {
                if unsafe { GetLastError() } == ERROR_PIPE_CONNECTED {
                    true
                } else {
                    tracing::warn!("ConnectNamedPipe failed: {error}");
                    false
                }
            }
        };

        if !connected {
            unsafe {
                let _ = CloseHandle(current_handle);
            }
            current_handle = create_pipe_instance();
            continue;
        }

        let accepted_handle = current_handle;
        // Immediately create the next listening pipe instance before spawning the handler thread!
        current_handle = create_pipe_instance();

        let tx = task_tx.clone();
        let raw_handle = accepted_handle.0 as usize;
        std::thread::spawn(move || {
            let handle = windows::Win32::Foundation::HANDLE(raw_handle as *mut std::ffi::c_void);
            let mut stream = PipeStream::from_raw_handle(handle);

            while let Ok(request) = stream.recv_json::<IpcRequest>() {
                let is_shutdown = matches!(request, IpcRequest::Shutdown);
                let is_respawn = matches!(request, IpcRequest::ReloadUserDictionaries);

                let (reply_tx, reply_rx) = mpsc::channel();
                if tx.send(EngineTask { request, reply_tx }).is_err() {
                    break;
                }
                let Ok(response) = reply_rx.recv() else {
                    break;
                };
                if stream.send_json(&response).is_err() {
                    break;
                }
                if is_shutdown {
                    tracing::info!("Shutdown requested via IPC; exiting service");
                    std::process::exit(0);
                }
                if is_respawn {
                    break;
                }
            }

            let (reply_tx, _reply_rx) = mpsc::channel();
            let _ = tx.send(EngineTask {
                request: IpcRequest::SaveLearning,
                reply_tx,
            });
            unsafe {
                let _ = DisconnectNamedPipe(stream.as_raw_handle());
            }
            // `stream` drops here and calls `CloseHandle` once.
        });
    }

    #[allow(unreachable_code)]
    if has_sd && !security_descriptor.0.is_null() {
        unsafe {
            let _ = LocalFree(Some(HLOCAL(security_descriptor.0)));
        }
    }
}

#[cfg(not(windows))]
fn main() {
    eprintln!("karukan-server is available only on Windows.");
}
