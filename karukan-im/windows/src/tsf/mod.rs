use std::ffi::c_void;
use std::ptr;
use std::sync::Once;

use windows::core::{implement, Interface, BOOL, GUID, HRESULT, Ref};
use windows::Win32::Foundation::{
    CLASS_E_CLASSNOTAVAILABLE, CLASS_E_NOAGGREGATION, E_POINTER, S_FALSE,
};
use windows::Win32::System::Com::{IClassFactory, IClassFactory_Impl};
use windows::Win32::UI::TextServices::ITfTextInputProcessor;

use crate::CLSID_KARUKAN_TIP;
use self::text_service::TextService;

pub mod candidate_window;
pub mod compartment;
pub mod edit_session;
pub mod key_handler;
pub mod lang_bar;
pub mod text_service;

static INIT_LOGGING: Once = Once::new();

pub(crate) fn init_logging() {
    INIT_LOGGING.call_once(|| {
        let log_file = std::env::var_os("LOCALAPPDATA").and_then(|p| {
            let dir = std::path::PathBuf::from(p)
                .join("karukan")
                .join("karukan-im")
                .join("logs");
            std::fs::create_dir_all(&dir).ok()?;
            std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open(dir.join("windows-tsf.log"))
                .ok()
        });
        if let Some(file) = log_file {
            let _ = tracing_subscriber::fmt()
                .with_env_filter(
                    tracing_subscriber::EnvFilter::try_from_default_env()
                        .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
                )
                .with_writer(std::sync::Mutex::new(file))
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
        let pid = std::process::id();
        let exe = std::env::current_exe()
            .map(|p| p.display().to_string())
            .unwrap_or_default();
        tracing::info!("=== Karukan TSF Logger Initialized (PID: {pid}, Process: {exe}) ===");
    });
}

#[implement(IClassFactory)]
struct ClassFactory;

impl IClassFactory_Impl for ClassFactory_Impl {
    fn CreateInstance(
        &self,
        outer: Ref<windows::core::IUnknown>,
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

#[unsafe(no_mangle)]
pub unsafe extern "system" fn DllGetClassObject(
    class_id: *const GUID,
    iid: *const GUID,
    output: *mut *mut c_void,
) -> HRESULT {
    init_logging();
    if class_id.is_null() || iid.is_null() || output.is_null() {
        return E_POINTER;
    }
    unsafe {
        *output = ptr::null_mut();
    }
    let req_clsid = unsafe { *class_id };
    let req_iid = unsafe { *iid };
    tracing::info!("DllGetClassObject: clsid={req_clsid:?}, iid={req_iid:?}");
    if req_clsid != CLSID_KARUKAN_TIP {
        tracing::warn!("DllGetClassObject: requested clsid is not CLSID_KARUKAN_TIP");
        return CLASS_E_CLASSNOTAVAILABLE;
    }

    let interface: IClassFactory = ClassFactory.into();
    unsafe { interface.query(iid, output) }
}

#[unsafe(no_mangle)]
pub extern "system" fn DllCanUnloadNow() -> HRESULT {
    S_FALSE
}
