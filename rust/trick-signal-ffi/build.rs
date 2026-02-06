fn main() {
    let crate_dir = std::env::var("CARGO_MANIFEST_DIR").unwrap();
    if let Ok(bindings) = cbindgen::generate(&crate_dir) {
        bindings.write_to_file(format!("{}/trick_signal_ffi.h", crate_dir));
    }
}
