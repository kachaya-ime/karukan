use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;

fn find_rc_exe() -> Option<PathBuf> {
    if let Ok(path) = which_rc() {
        return Some(path);
    }
    let kits_root = Path::new(r"C:\Program Files (x86)\Windows Kits\10\bin");
    if kits_root.is_dir() {
        if let Ok(entries) = fs::read_dir(kits_root) {
            let mut versions: Vec<_> = entries.flatten().map(|e| e.path()).collect();
            versions.sort();
            for dir in versions.into_iter().rev() {
                let candidate = dir.join("x64").join("rc.exe");
                if candidate.is_file() {
                    return Some(candidate);
                }
            }
        }
    }
    None
}

fn which_rc() -> Result<PathBuf, ()> {
    let output = Command::new("where").arg("rc.exe").output().map_err(|_| ())?;
    if output.status.success() {
        let text = String::from_utf8_lossy(&output.stdout);
        if let Some(line) = text.lines().next() {
            let p = PathBuf::from(line.trim());
            if p.is_file() {
                return Ok(p);
            }
        }
    }
    Err(())
}

fn main() {
    println!("cargo:rerun-if-changed=resources");

    if std::env::var("CARGO_CFG_TARGET_ENV").as_deref() == Ok("msvc")
        && std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows")
    {
        // Rust's COM exports trigger LNK4104; keep the workaround scoped to the DLL.
        println!("cargo:rustc-link-arg-cdylib=/IGNORE:4104");

        let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
        let res_dir = manifest_dir.join("resources");
        let res_dir_str = res_dir.to_str().unwrap().replace('\\', "/");

        let out_dir = PathBuf::from(env::var("OUT_DIR").unwrap());
        let rc_content = format!(
            "1 ICON \"{res_dir_str}/karukan.ico\"\n\
             2 ICON \"{res_dir_str}/hiragana.ico\"\n\
             3 ICON \"{res_dir_str}/alpha.ico\"\n\
             4 ICON \"{res_dir_str}/katakana.ico\"\n"
        );
        let rc_path = out_dir.join("karukan.rc");
        let _ = fs::write(&rc_path, rc_content);

        if let Some(rc_exe) = find_rc_exe() {
            let res_path = out_dir.join("karukan.res");
            let status = Command::new(rc_exe)
                .arg("/fo")
                .arg(&res_path)
                .arg(&rc_path)
                .status();
            if let Ok(status) = status && status.success() {
                println!("cargo:rustc-link-arg={}", res_path.display());
            }
        }
    }
}
