fn main() {
    println!("cargo:rerun-if-changed=assets/ycscite.ico");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") {
        winresource::WindowsResource::new()
            .set_icon("assets/ycscite.ico")
            .set("ProductName", "Ycscite")
            .set("FileDescription", "Ycscite 文献分析工作台")
            .set("CompanyName", "Ycscite")
            .compile()
            .expect("compile Windows icon resource");
    }
}
