#![cfg_attr(windows, windows_subsystem = "windows")]
fn main() {
    if let Err(error) = ycscite_client::desktop() {
        #[cfg(windows)]
        rfd::MessageDialog::new()
            .set_title("Ycscite 启动失败")
            .set_description(&error)
            .set_level(rfd::MessageLevel::Error)
            .show();
        eprintln!("{error}");
        std::process::exit(1);
    }
}
