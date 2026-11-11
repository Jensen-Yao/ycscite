pub mod engine;
mod server;
mod workspace;
use clap::{Parser, Subcommand};
use serde_json::{Value, json};
use std::{
    ffi::OsString,
    io::{BufRead, BufReader},
    path::PathBuf,
    process::Stdio,
    thread,
};

#[derive(Parser)]
#[command(
    name = "ycscite",
    version,
    about = "Ycscite bibliometric workbench — CLI, headless NDJSON, Web and desktop"
)]
struct Cli {
    /// 标准输出只返回 JSON；进度写入标准错误
    #[arg(long, global = true)]
    json: bool,
    #[command(subcommand)]
    command: Option<Action>,
}
#[derive(clap::Args)]
struct AnalysisArgs {
    #[arg(required = true)]
    inputs: Vec<PathBuf>,
    #[arg(short, long, default_value = "ycscite-report")]
    out: PathBuf,
    /// JSON settings shared with Web/headless
    #[arg(long)]
    config: Option<PathBuf>,
    #[arg(long,value_parser=["zh","en"])]
    lang: Option<String>,
    #[arg(long, conflicts_with = "logo")]
    no_logo: bool,
    #[arg(long, conflicts_with = "no_logo")]
    logo: bool,
    #[arg(long, value_delimiter = ',')]
    charts: Vec<String>,
    #[arg(long = "set", value_name = "KEY=JSON")]
    settings: Vec<String>,
    #[arg(long)]
    network: Option<String>,
    #[arg(long)]
    format: Option<String>,
    #[arg(long)]
    from: Option<u16>,
    #[arg(long)]
    to: Option<u16>,
    #[arg(long)]
    slice_years: Option<u16>,
    #[arg(long)]
    g_index_k: Option<f64>,
    #[arg(long)]
    pruning: Option<String>,
    #[arg(long)]
    clustering: Option<String>,
    #[arg(long)]
    labeling: Option<String>,
}
#[derive(Subcommand)]
enum ProjectAction {
    /// Read a project network and its saved visual state without recomputation
    Inspect { path: PathBuf },
    /// Export the saved layout and visual settings without opening a GUI
    Export {
        path: PathBuf,
        #[arg(long)]
        out: PathBuf,
        #[arg(long, default_value = "saved")]
        view: String,
        #[arg(long,value_delimiter=',',default_value="png",value_parser=["png","svg","pdf","tsv"])]
        formats: Vec<String>,
        #[arg(long, default_value_t = 1600)]
        width: u16,
        #[arg(long, default_value_t = 1000)]
        height: u16,
        #[arg(long,value_parser=["zh","en"])]
        lang: Option<String>,
        #[arg(long, conflicts_with = "no_logo")]
        logo: bool,
        #[arg(long, conflicts_with = "logo")]
        no_logo: bool,
    },
}
#[derive(Subcommand)]
enum Action {
    /// Configurable publication report (default: nine charts)
    Report(AnalysisArgs),
    /// Analyze a chosen view (default: network; use --charts to select any view)
    Analyze(AnalysisArgs),
    /// Inspect input records, detected formats, coverage and year distribution
    Inspect {
        #[arg(required = true)]
        inputs: Vec<PathBuf>,
        #[arg(long, default_value = "auto")]
        format: String,
    },
    /// Inspect or export an editable project without GUI or analysis recomputation
    Project {
        #[command(subcommand)]
        command: ProjectAction,
    },
    /// Query supported formats, charts, methods and default settings
    Capabilities,
    /// Print or save a reusable settings file
    Config {
        #[arg(long)]
        out: Option<PathBuf>,
    },
    /// NDJSON service on stdin/stdout; --request reads a JSON request file
    Headless {
        #[arg(long)]
        request: Option<PathBuf>,
    },
    /// Local Web workbench
    Serve {
        #[arg(long, default_value_t = 0)]
        port: u16,
        #[arg(long)]
        no_open: bool,
        #[arg(long)]
        report: Option<PathBuf>,
    },
    /// Interactive legacy workbench with editable nodes and the full analysis menus
    Workbench {
        inputs: Vec<PathBuf>,
        #[arg(long,value_parser=["zh","en"])]
        lang: Option<String>,
        #[arg(long, conflicts_with = "project")]
        config: Option<PathBuf>,
        #[arg(long,conflicts_with_all=["inputs","config"])]
        project: Option<PathBuf>,
        #[arg(long, conflicts_with = "no_logo")]
        logo: bool,
        #[arg(long, conflicts_with = "logo")]
        no_logo: bool,
    },
    /// Execute a batch JSON file (array of requests or a batch request object)
    Batch { request: PathBuf },
    /// Windows desktop application
    Desktop,
    /// Runtime and engine diagnostics
    Doctor,
    /// Full legacy analysis CLI escape hatch (87+ options)
    Engine {
        #[arg(required = true, trailing_var_arg = true, allow_hyphen_values = true)]
        args: Vec<OsString>,
    },
}
fn fail(error: String, structured: bool) -> i32 {
    if structured {
        println!("{}", json!({"ok":false,"error":error}));
    } else {
        eprintln!("错误：{error}");
    }
    1
}
pub fn cli() -> i32 {
    let cli = Cli::parse();
    let custom_analysis = matches!(&cli.command, Some(Action::Analyze(_)));
    match cli.command.unwrap_or(Action::Doctor) {
        Action::Capabilities => match engine::Engine::find().and_then(|e| e.capabilities()) {
            Ok(value) => {
                println!("{}", serde_json::to_string_pretty(&value).unwrap());
                0
            }
            Err(e) => fail(e, cli.json),
        },
        Action::Config { out } => match engine::Engine::find().and_then(|e| e.capabilities()) {
            Ok(value) => {
                let text = serde_json::to_string_pretty(&value["default_settings"]).unwrap();
                if let Some(out) = out {
                    match std::fs::write(&out, text) {
                        Ok(()) => {
                            println!("{}", json!({"ok":true,"config":out}));
                            0
                        }
                        Err(e) => fail(e.to_string(), cli.json),
                    }
                } else {
                    println!("{text}");
                    0
                }
            }
            Err(e) => fail(e, cli.json),
        },
        Action::Inspect { inputs, format } => match engine::Engine::find().and_then(|e| {
            e.request(json!({"id":1,"method":"inspect","params":{"inputs":inputs,"format":format}}))
        }) {
            Ok(value) => {
                println!("{}", serde_json::to_string_pretty(&value).unwrap());
                if value["ok"] == true { 0 } else { 1 }
            }
            Err(e) => fail(e, cli.json),
        },
        Action::Project { command } => {
            let params = match command {
                ProjectAction::Inspect { path } => json!({"action":"inspect","path":path}),
                ProjectAction::Export {
                    path,
                    out,
                    view,
                    formats,
                    width,
                    height,
                    lang,
                    logo,
                    no_logo,
                } => {
                    let mut value = json!({"action":"export","path":path,"output":out,"view":view,"formats":formats,"width":width,"height":height});
                    if let Some(language) = lang {
                        value["language"] = json!(language);
                    }
                    if logo || no_logo {
                        value["logo"] = json!(logo);
                    }
                    value
                }
            };
            match engine::Engine::find().and_then(|engine| {
                engine.request(json!({"id":1,"method":"project","params":params}))
            }) {
                Ok(response) => {
                    println!("{response}");
                    if response["ok"] == true { 0 } else { 1 }
                }
                Err(error) => fail(error, cli.json),
            }
        }
        Action::Headless { request } => {
            let result = (|| -> Result<i32, String> {
                let e = engine::Engine::find()?;
                if let Some(path) = request {
                    let path = engine::absolute(&path)?;
                    let value: Value = serde_json::from_str(
                        &std::fs::read_to_string(&path).map_err(|e| e.to_string())?,
                    )
                    .map_err(|e| e.to_string())?;
                    let response = e.request_in(value, path.parent())?;
                    println!("{response}");
                    Ok(if response["ok"] == true { 0 } else { 1 })
                } else {
                    let status = e
                        .command()
                        .arg("headless")
                        .status()
                        .map_err(|e| e.to_string())?;
                    Ok(status.code().unwrap_or(1))
                }
            })();
            match result {
                Ok(code) => code,
                Err(e) => fail(e, true),
            }
        }
        Action::Workbench {
            inputs,
            lang,
            config,
            project,
            logo,
            no_logo,
        } => {
            match engine::Engine::find().and_then(|e| {
                e.workbench(
                    inputs,
                    lang.as_deref(),
                    config,
                    project,
                    if logo {
                        Some(true)
                    } else if no_logo {
                        Some(false)
                    } else {
                        None
                    },
                )
            }) {
                Ok(pid) => {
                    println!("{}", json!({"ok":true,"pid":pid}));
                    0
                }
                Err(e) => fail(e, cli.json),
            }
        }
        Action::Batch { request } => {
            let result = (|| -> Result<Value, String> {
                let base = engine::absolute(&request)?;
                let value: Value = serde_json::from_str(
                    &std::fs::read_to_string(&base).map_err(|e| e.to_string())?,
                )
                .map_err(|e| e.to_string())?;
                let request = if value.is_array() {
                    json!({"id":1,"method":"batch","params":{"jobs":value}})
                } else {
                    value
                };
                engine::Engine::find()?.request_in(request, base.parent())
            })();
            match result {
                Ok(value) => {
                    let ok =
                        value["ok"] == true && value["result"]["failed"].as_u64().unwrap_or(0) == 0;
                    println!("{value}");
                    if ok { 0 } else { 1 }
                }
                Err(e) => fail(e, true),
            }
        }
        Action::Desktop => match desktop() {
            Ok(()) => 0,
            Err(e) => fail(e, cli.json),
        },
        Action::Doctor => match engine::Engine::find() {
            Ok(e) => {
                let info = e.doctor();
                let ok = info["engine_available"].as_bool().unwrap_or(false);
                if cli.json {
                    println!("{info}");
                } else {
                    println!(
                        "Ycscite {} · Rust 客户端\n分析引擎：{}\n运行时：{}\n便携运行时：{}",
                        env!("CARGO_PKG_VERSION"),
                        if ok { "可用" } else { "不可用" },
                        e.java.display(),
                        info["bundled_runtime"]
                    );
                }
                if ok { 0 } else { 1 }
            }
            Err(e) => fail(e, cli.json),
        },
        Action::Report(args) | Action::Analyze(args) => {
            let has_config = args
                .config
                .as_ref()
                .and_then(|path| std::fs::read_to_string(path).ok())
                .and_then(|text| serde_json::from_str::<Value>(&text).ok())
                .is_some_and(|value| value.get("charts").is_some());
            let mut extra: Vec<OsString> = Vec::new();
            if let Some(config) = args.config {
                extra.push("--config".into());
                extra.push(config.into_os_string());
            }
            if let Some(lang) = args.lang {
                extra.extend(["--lang".into(), lang.into()]);
            }
            if args.no_logo {
                extra.push("--no-logo".into());
            } else if args.logo {
                extra.push("--logo".into());
            }
            if !args.charts.is_empty() {
                extra.extend(["--charts".into(), args.charts.join(",").into()]);
            } else if custom_analysis && !has_config {
                extra.extend(["--charts".into(), "network".into()]);
            }
            let mut pairs = args.settings;
            for (key, value) in [
                ("network", args.network),
                ("format", args.format),
                ("pruning", args.pruning),
                ("clustering", args.clustering),
                ("labeling", args.labeling),
            ] {
                if let Some(value) = value {
                    pairs.push(format!("{key}={value}"));
                }
            }
            for (key, value) in [
                ("from_year", args.from),
                ("to_year", args.to),
                ("slice_years", args.slice_years),
            ] {
                if let Some(value) = value {
                    pairs.push(format!("{key}={value}"));
                }
            }
            if let Some(value) = args.g_index_k {
                pairs.push(format!("g_index_k={value}"));
            }
            for pair in pairs {
                extra.extend(["--set".into(), pair.into()]);
            }
            let inputs = args.inputs;
            let out = args.out;
            let result = (|| -> Result<Value, String> {
                let e = engine::Engine::find()?;
                for p in &inputs {
                    if !p.exists() {
                        return Err(format!("输入不存在：{}", p.display()));
                    }
                }
                let out = engine::absolute(&out)?;
                let mut child = e
                    .command()
                    .arg("report")
                    .args(inputs)
                    .arg("--out")
                    .arg(&out)
                    .args(extra)
                    .stdout(Stdio::piped())
                    .stderr(Stdio::piped())
                    .spawn()
                    .map_err(|e| e.to_string())?;
                let stderr = child.stderr.take().unwrap();
                let reader = thread::spawn(move || {
                    for line in BufReader::new(stderr).lines().map_while(Result::ok) {
                        eprintln!("{line}");
                    }
                });
                for line in BufReader::new(child.stdout.take().unwrap())
                    .lines()
                    .map_while(Result::ok)
                {
                    if cli.json {
                        eprintln!("{line}");
                    } else {
                        println!("{line}");
                    }
                }
                let status = child.wait().map_err(|e| e.to_string())?;
                let _ = reader.join();
                if !status.success() {
                    return Err(format!("分析引擎退出：{status}"));
                }
                Ok(
                    json!({"ok":true,"output":out,"report":out.join("index.html"),"manifest":engine::manifest(&out)?}),
                )
            })();
            match result {
                Ok(value) => {
                    if cli.json {
                        println!("{value}");
                    }
                    0
                }
                Err(e) => fail(e, cli.json),
            }
        }
        Action::Serve {
            port,
            no_open,
            report,
        } => {
            let result = (|| -> Result<(), String> {
                let handle = server::start(port, report)?;
                if cli.json {
                    println!(
                        "{}",
                        json!({"ok":true,"url":handle.url,"event":"listening"})
                    );
                } else {
                    println!("Ycscite 工作台：{}\n按 Ctrl+C 关闭", handle.url);
                }
                if !no_open {
                    webbrowser::open(&handle.url).map_err(|e| e.to_string())?;
                }
                let shared = handle.shared.clone();
                ctrlc::set_handler(move || {
                    shared.shutdown();
                })
                .map_err(|e| e.to_string())?;
                handle.join();
                Ok(())
            })();
            match result {
                Ok(()) => 0,
                Err(e) => fail(e, cli.json),
            }
        }
        Action::Engine { args } => match engine::Engine::find() {
            Ok(e) if cli.json => match e.command().args(args).output() {
                Ok(output) => {
                    let code = output.status.code().unwrap_or(1);
                    println!(
                        "{}",
                        json!({"ok":output.status.success(),"exit_code":code,
                        "stdout":String::from_utf8_lossy(&output.stdout),"stderr":String::from_utf8_lossy(&output.stderr)})
                    );
                    code
                }
                Err(e) => fail(e.to_string(), true),
            },
            Ok(e) => match e.command().args(args).status() {
                Ok(s) => s.code().unwrap_or(1),
                Err(e) => fail(e.to_string(), false),
            },
            Err(e) => fail(e, cli.json),
        },
    }
}

pub fn desktop() -> Result<(), String> {
    #[cfg(windows)]
    {
        use tao::{
            dpi::LogicalSize,
            event::{Event, WindowEvent},
            event_loop::{ControlFlow, EventLoop},
            window::WindowBuilder,
        };
        let handle = server::start(0, None)?;
        let event_loop = EventLoop::new();
        let window = WindowBuilder::new()
            .with_title(format!("Ycscite {}", env!("CARGO_PKG_VERSION")))
            .with_inner_size(LogicalSize::new(1440.0, 960.0))
            .with_min_inner_size(LogicalSize::new(850.0, 640.0))
            .with_window_icon(
                tao::window::Icon::from_rgba(
                    include_bytes!("../assets/logo-128.rgba").to_vec(),
                    128,
                    128,
                )
                .ok(),
            )
            .build(&event_loop)
            .map_err(|e| e.to_string())?;
        let allowed = handle.url.clone();
        let webview_dir = handle.shared.webview_dir();
        std::fs::create_dir_all(&webview_dir).map_err(|e| e.to_string())?;
        let mut web_context = wry::WebContext::new(Some(webview_dir));
        let webview = wry::WebViewBuilder::new_with_web_context(&mut web_context)
            .with_url(&handle.url)
            .with_download_started_handler(|_url, destination| {
                let name = destination
                    .file_name()
                    .and_then(|p| p.to_str())
                    .unwrap_or("ycscite-export");
                if let Some(path) = rfd::FileDialog::new()
                    .set_title("保存导出文件 / Save export")
                    .set_file_name(name)
                    .save_file()
                {
                    *destination = path;
                    true
                } else {
                    false
                }
            })
            .with_navigation_handler(move |url| url.starts_with(&allowed))
            .build(&window)
            .map_err(|e| e.to_string())?;
        event_loop.run(move |event, _, flow| {
            let _keep_alive = &webview;
            *flow = ControlFlow::Wait;
            if let Event::WindowEvent {
                event: WindowEvent::CloseRequested,
                ..
            } = event
            {
                handle.shared.shutdown();
                *flow = ControlFlow::Exit;
            }
        });
    }
    #[cfg(not(windows))]
    {
        Err("桌面版当前面向 Windows；其他系统请运行 ycscite serve。".into())
    }
}

#[cfg(test)]
mod cli_tests {
    use super::*;
    #[test]
    fn project_exports_choose_outputs_explicitly() {
        assert!(Cli::try_parse_from(["ycscite", "project", "inspect", "saved.csl-proj"]).is_ok());
        assert!(
            Cli::try_parse_from([
                "ycscite",
                "project",
                "export",
                "saved.csl-proj",
                "--out",
                "images",
                "--formats",
                "png,svg,pdf,tsv",
                "--view",
                "saved",
                "--no-logo"
            ])
            .is_ok()
        );
        assert!(Cli::try_parse_from(["ycscite", "project", "export", "saved.csl-proj"]).is_err());
        assert!(
            Cli::try_parse_from([
                "ycscite",
                "project",
                "export",
                "saved.csl-proj",
                "--out",
                "images",
                "--logo",
                "--no-logo"
            ])
            .is_err()
        );
    }
    #[test]
    fn analysis_logo_override_is_unambiguous() {
        assert!(Cli::try_parse_from(["ycscite", "report", "input.ris", "--logo"]).is_ok());
        assert!(Cli::try_parse_from(["ycscite", "analyze", "input.ris", "--no-logo"]).is_ok());
        assert!(
            Cli::try_parse_from(["ycscite", "report", "input.ris", "--logo", "--no-logo"]).is_err()
        );
    }
    #[test]
    fn editor_project_and_config_are_unambiguous() {
        for args in [
            vec!["ycscite", "workbench", "--project", "saved.csl-proj"],
            vec![
                "ycscite",
                "workbench",
                "input.ris",
                "--config",
                "settings.json",
            ],
            vec![
                "ycscite",
                "workbench",
                "--project",
                "saved.csl-proj",
                "--lang",
                "en",
            ],
        ] {
            assert!(Cli::try_parse_from(args).is_ok());
        }
        for args in [
            vec![
                "ycscite",
                "workbench",
                "input.ris",
                "--project",
                "saved.csl-proj",
            ],
            vec![
                "ycscite",
                "workbench",
                "--project",
                "saved.csl-proj",
                "--config",
                "settings.json",
            ],
            vec!["ycscite", "workbench", "--logo", "--no-logo"],
        ] {
            assert!(Cli::try_parse_from(args).is_err());
        }
    }
}
