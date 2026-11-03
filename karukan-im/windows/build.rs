fn main() {
    if std::env::var("CARGO_CFG_TARGET_ENV").as_deref() == Ok("msvc")
        && std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows")
    {
        // Rust's COM exports trigger LNK4104; keep the workaround scoped to the DLL.
        println!("cargo:rustc-link-arg-cdylib=/IGNORE:4104");
    }
}
