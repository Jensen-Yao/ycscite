use crate::engine::{self, Engine};
use crate::workspace::WorkspaceStore;
use base64::Engine as _;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{
    fs,
    io::{BufRead, BufReader, Read},
    net::TcpListener,
    path::{Component, Path, PathBuf},
    process::{Child, Stdio},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    thread::{self, JoinHandle},
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tiny_http::{Header, Method, Request, Response, Server, StatusCode};

#[derive(Clone, Serialize, Deserialize)]
struct Job {
    id: String,
    status: String,
    output: PathBuf,
    inputs: Vec<PathBuf>,
    logs: Vec<String>,
    completed: u8,
    #[serde(default = "default_total")]
    total: u8,
    manifest: Option<Value>,
    error: Option<String>,
}
fn default_total() -> u8 {
    9
}
struct Control {
    id: String,
    child: Mutex<Child>,
    cancelled: AtomicBool,
}
pub(crate) struct Shared {
    engine: Engine,
    capabilities: Value,
    workspace: Mutex<WorkspaceStore>,
    token: String,
    origin: String,
    store: PathBuf,
    jobs: Mutex<Vec<Job>>,
    active: Mutex<Option<Arc<Control>>>,
    stop: AtomicBool,
}
pub(crate) struct Handle {
    pub url: String,
    pub shared: Arc<Shared>,
    worker: Option<JoinHandle<()>>,
}
impl Handle {
    pub fn join(mut self) {
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
    }
}
impl Shared {
    fn save_workspace(&self, mut patch: Value, settings_validated: bool) -> Result<Value, String> {
        let mut saved = self.workspace.lock().unwrap();
        if !settings_validated && patch.get("settings").is_some_and(|value| !value.is_null()) {
            let response = self.engine.request(json!({"id":1,"method":"validate_settings","params":{"settings":patch["settings"]}}))?;
            if response["ok"] != true {
                return Err(response["error"]["message"]
                    .as_str()
                    .unwrap_or("Invalid settings")
                    .into());
            }
            patch["settings"] = response["result"].clone();
        }
        let next = saved.current.patched(&patch, &self.capabilities)?;
        saved.write(&self.store, next)?;
        saved.initial_report_id = None;
        Ok(json!({"ok":true,"workspace":saved.current}))
    }

    pub fn webview_dir(&self) -> PathBuf {
        self.store.join("webview")
    }
    pub fn shutdown(&self) {
        self.stop.store(true, Ordering::SeqCst);
        self.cancel(None);
    }
    fn cancel(&self, id: Option<&str>) {
        if let Some(control) = self.active.lock().unwrap().as_ref() {
            if id.is_none_or(|id| id == control.id) {
                control.cancelled.store(true, Ordering::SeqCst);
                let _ = control.child.lock().unwrap().kill();
            }
        }
    }
    fn persist(&self) {
        let jobs = self.jobs.lock().unwrap();
        let done: Vec<_> = jobs
            .iter()
            .filter(|j| j.status == "complete")
            .take(20)
            .collect();
        if let Ok(data) = serde_json::to_string(&done) {
            let temp = self.store.join("history.new");
            if fs::write(&temp, data).is_ok() {
                let _ = fs::rename(temp, self.store.join("history.json"));
            }
        }
    }
    fn log(&self, id: &str, line: String) {
        if let Some(job) = self.jobs.lock().unwrap().iter_mut().find(|j| j.id == id) {
            if let Some(progress) = line
                .strip_prefix("FIGURE ")
                .and_then(|v| v.split_whitespace().next())
            {
                if let Some((done, total)) = progress.split_once('/') {
                    if let (Ok(done), Ok(total)) = (done.parse::<u8>(), total.parse::<u8>()) {
                        job.completed = done.saturating_sub(1);
                        job.total = total;
                    }
                }
            }
            if job.logs.len() >= 100 {
                job.logs.remove(0);
            }
            job.logs.push(line);
        }
    }
    fn load_report(&self, path: &Path) -> Result<String, String> {
        let path = path.canonicalize().map_err(|e| e.to_string())?;
        let path = if path.is_file() {
            path.parent().ok_or("报告路径无效")?.to_owned()
        } else {
            path
        };
        let manifest = engine::manifest(&path)?;
        if let Some(id) = self
            .jobs
            .lock()
            .unwrap()
            .iter()
            .find(|j| {
                j.status == "complete"
                    && (j.output == path
                        || j.output
                            .canonicalize()
                            .is_ok_and(|existing| existing == path))
            })
            .map(|j| j.id.clone())
        {
            return Ok(id);
        }
        let id = new_id();
        self.jobs.lock().unwrap().insert(
            0,
            Job {
                id: id.clone(),
                status: "complete".into(),
                output: path,
                inputs: vec![],
                logs: vec!["已打开本地报告".into()],
                completed: manifest["figures"]
                    .as_array()
                    .map(|a| a.len().min(255) as u8)
                    .unwrap_or(9),
                total: manifest["figures"]
                    .as_array()
                    .map(|a| a.len().min(255) as u8)
                    .unwrap_or(9),
                manifest: Some(manifest),
                error: None,
            },
        );
        self.persist();
        Ok(id)
    }
    fn run(
        self: &Arc<Self>,
        inputs: Vec<PathBuf>,
        out: Option<PathBuf>,
        settings: Value,
    ) -> Result<String, String> {
        if inputs.is_empty() {
            return Err("请选择文献目录或文件".into());
        }
        for path in &inputs {
            if !path.exists() {
                return Err(format!("输入不存在：{}", path.display()));
            }
        }
        let mut active = self.active.lock().unwrap();
        if active.is_some() {
            return Err("已有分析正在运行，请等待完成或先取消".into());
        }
        let id = new_id();
        let output_root = engine::absolute(&out.unwrap_or_else(|| self.store.join("reports")))?;
        let output = output_root.join(&id);
        fs::create_dir_all(&output).map_err(|e| format!("无法创建输出目录：{e}"))?;
        let config = output.join("settings.json");
        let settings = if settings.is_object() {
            settings
        } else {
            json!({})
        };
        let total = settings["charts"]
            .as_array()
            .map(|v| v.len().min(255) as u8)
            .unwrap_or(9);
        fs::write(
            &config,
            serde_json::to_vec(&settings).map_err(|e| e.to_string())?,
        )
        .map_err(|e| e.to_string())?;
        let mut child = self
            .engine
            .command()
            .arg("report")
            .args(&inputs)
            .arg("--out")
            .arg(&output)
            .arg("--config")
            .arg(&config)
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .map_err(|e| format!("启动分析失败：{e}"))?;
        let stdout = child.stdout.take().ok_or("无法读取分析进度")?;
        let stderr = child.stderr.take().ok_or("无法读取分析错误")?;
        let control = Arc::new(Control {
            id: id.clone(),
            child: Mutex::new(child),
            cancelled: AtomicBool::new(false),
        });
        *active = Some(control.clone());
        self.jobs.lock().unwrap().insert(
            0,
            Job {
                id: id.clone(),
                status: "running".into(),
                output: output.clone(),
                inputs,
                logs: vec!["正在读取文献…".into()],
                completed: 0,
                total,
                manifest: None,
                error: None,
            },
        );
        let shared = self.clone();
        let task_id = id.clone();
        thread::spawn(move || {
            let log_shared = shared.clone();
            let log_id = task_id.clone();
            let err_reader = thread::spawn(move || {
                for line in BufReader::new(stderr).lines().map_while(Result::ok) {
                    log_shared.log(&log_id, line);
                }
            });
            for line in BufReader::new(stdout).lines().map_while(Result::ok) {
                shared.log(&task_id, line);
            }
            let status = control.child.lock().unwrap().wait();
            let _ = err_reader.join();
            let cancelled = control.cancelled.load(Ordering::SeqCst);
            let result = match status {
                Ok(status) if status.success() && !cancelled => engine::manifest(&output),
                Ok(status) => Err(if cancelled {
                    "分析已取消".into()
                } else {
                    format!("分析失败：{status}")
                }),
                Err(error) => Err(error.to_string()),
            };
            if let Some(job) = shared
                .jobs
                .lock()
                .unwrap()
                .iter_mut()
                .find(|j| j.id == task_id)
            {
                match result {
                    Ok(manifest) => {
                        job.status = "complete".into();
                        job.completed = manifest["figures"]
                            .as_array()
                            .map(|a| a.len().min(255) as u8)
                            .unwrap_or(job.total);
                        job.total = job.completed;
                        job.manifest = Some(manifest);
                    }
                    Err(error) => {
                        job.status = if cancelled { "cancelled" } else { "failed" }.into();
                        job.error = Some(error);
                    }
                }
            }
            *shared.active.lock().unwrap() = None;
            shared.persist();
        });
        Ok(id)
    }
}
fn new_id() -> String {
    let time = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis();
    format!("{time}-{:08x}", rand::random::<u32>())
}
pub(crate) fn start(port: u16, report: Option<PathBuf>) -> Result<Handle, String> {
    let engine = Engine::find()?;
    let store = engine::absolute(&crate::workspace::data_home())?;
    fs::create_dir_all(&store).map_err(|e| e.to_string())?;
    let listener = TcpListener::bind(("127.0.0.1", port)).map_err(|e| e.to_string())?;
    let origin = format!(
        "http://{}",
        listener.local_addr().map_err(|e| e.to_string())?
    );
    let server = Server::from_listener(listener, None).map_err(|e| e.to_string())?;
    let token = (0..4)
        .map(|_| format!("{:016x}", rand::random::<u64>()))
        .collect::<String>();
    let jobs = fs::read_to_string(store.join("history.json"))
        .ok()
        .and_then(|s| serde_json::from_str::<Vec<Job>>(&s).ok())
        .unwrap_or_default()
        .into_iter()
        .filter_map(|mut job| {
            // Report files are authoritative; old history caches may contain rounded metrics or superseded metadata.
            let manifest = engine::manifest(&job.output).ok()?;
            let count = manifest["figures"].as_array()?.len().min(255) as u8;
            job.completed = count;
            job.total = count;
            job.manifest = Some(manifest);
            Some(job)
        })
        .take(20)
        .collect();
    let capabilities = engine.capabilities()?;
    let workspace = WorkspaceStore::read(&store, &capabilities);
    let shared = Arc::new(Shared {
        engine,
        capabilities,
        workspace: Mutex::new(workspace),
        store,
        token,
        origin: origin.clone(),
        jobs: Mutex::new(jobs),
        active: Mutex::new(None),
        stop: AtomicBool::new(false),
    });
    if let Some(report) = report {
        let id = shared.load_report(&report)?;
        shared.workspace.lock().unwrap().initial_report_id = Some(id);
    }
    let worker_shared = shared.clone();
    let worker = thread::spawn(move || {
        while !worker_shared.stop.load(Ordering::SeqCst) {
            match server.recv_timeout(Duration::from_millis(150)) {
                Ok(Some(request)) => {
                    let s = worker_shared.clone();
                    thread::spawn(move || route(request, s));
                }
                Ok(None) => {}
                Err(_) => break,
            }
        }
    });
    Ok(Handle {
        url: format!("{origin}/"),
        shared,
        worker: Some(worker),
    })
}
fn header(name: &str, value: &str) -> Header {
    Header::from_bytes(name.as_bytes(), value.as_bytes()).unwrap()
}
fn send(request: Request, status: u16, mime: &str, body: Vec<u8>) {
    let response=Response::from_data(body).with_status_code(StatusCode(status))
        .with_header(header("Content-Type",mime)).with_header(header("Cache-Control","no-store"))
        .with_header(header("X-Content-Type-Options","nosniff"))
        .with_header(header("Content-Security-Policy","default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self'; frame-ancestors 'self'"));
    let _ = request.respond(response);
}
fn json_response(request: Request, result: Result<Value, String>) {
    let (status, value) = match result {
        Ok(v) => (200, v),
        Err(e) => (400, json!({"ok":false,"error":e})),
    };
    send(
        request,
        status,
        "application/json; charset=utf-8",
        serde_json::to_vec(&value).unwrap(),
    );
}
fn body(request: &mut Request) -> Result<Value, String> {
    const LIMIT: u64 = 32 * 1024 * 1024;
    let mut data = Vec::new();
    request
        .as_reader()
        .take(LIMIT + 1)
        .read_to_end(&mut data)
        .map_err(|e| e.to_string())?;
    if data.len() as u64 > LIMIT {
        return Err("上传内容超过 32 MB，请使用本地路径导入".into());
    }
    serde_json::from_slice(&data).map_err(|e| format!("请求 JSON 无效：{e}"))
}
fn paths(value: &Value) -> Result<Vec<PathBuf>, String> {
    let values = value.as_array().ok_or("inputs 必须是路径数组")?;
    values
        .iter()
        .map(|v| {
            v.as_str()
                .map(PathBuf::from)
                .ok_or("路径必须是字符串".into())
        })
        .collect()
}
fn safe_file(root: &Path, relative: &str) -> Result<PathBuf, String> {
    let relative = Path::new(relative);
    if relative
        .components()
        .any(|c| !matches!(c, Component::Normal(_)))
    {
        return Err("无效的报告文件路径".into());
    }
    let root = root.canonicalize().map_err(|e| e.to_string())?;
    let file = root
        .join(relative)
        .canonicalize()
        .map_err(|e| e.to_string())?;
    if !file.starts_with(&root) || !file.is_file() {
        return Err("文件不在报告目录中".into());
    }
    Ok(file)
}
fn route(mut request: Request, shared: Arc<Shared>) {
    let path = request.url().split('?').next().unwrap_or("/").to_owned();
    let method = request.method().clone();
    // Reject DNS rebinding and browser-origin requests from other sites.
    let host = shared.origin.trim_start_matches("http://");
    if !request
        .headers()
        .iter()
        .any(|h| h.field.equiv("Host") && h.value.as_str() == host)
    {
        send(request, 403, "text/plain", b"Invalid host".to_vec());
        return;
    }
    if path.starts_with("/api/") {
        let authorized = request
            .headers()
            .iter()
            .any(|h| h.field.equiv("X-Ycscite-Token") && h.value.as_str() == shared.token);
        let origin_ok = request
            .headers()
            .iter()
            .filter(|h| h.field.equiv("Origin"))
            .all(|h| h.value.as_str() == shared.origin);
        if !authorized || !origin_ok {
            send(
                request,
                403,
                "application/json",
                b"{\"ok\":false,\"error\":\"invalid session\"}".to_vec(),
            );
            return;
        }
        let result = (|| -> Result<Value, String> {
            match (method, path.as_str()) {
                (Method::Get, "/api/state") => {
                    Ok(json!({"ok":true,"version":env!("CARGO_PKG_VERSION"),
                    "jobs":*shared.jobs.lock().unwrap(),"default_output":shared.store.join("reports")}))
                }
                (Method::Get, "/api/doctor") => Ok(shared.engine.doctor()),
                (Method::Get, "/api/capabilities") => Ok(shared.capabilities.clone()),
                (Method::Get, "/api/workspace") => {
                    let saved = shared.workspace.lock().unwrap();
                    Ok(
                        json!({"ok":true,"workspace":saved.current,"has_saved_state":saved.initialized,"warning":saved.warning,"initial_report_id":saved.initial_report_id}),
                    )
                }
                (Method::Post, "/api/workspace") => {
                    let patch = body(&mut request)?;
                    shared.save_workspace(patch, false)
                }
                (Method::Post, "/api/workbench") => {
                    let data = body(&mut request)?;
                    let inputs = if data["inputs"].is_array() {
                        paths(&data["inputs"])?
                    } else {
                        Vec::new()
                    };
                    let language = data["language"].as_str().unwrap_or("zh");
                    Ok(json!({"ok":true,"pid":shared.engine.workbench(inputs,language)?}))
                }
                (Method::Post, "/api/validate-settings") => {
                    let data = body(&mut request)?;
                    let response = shared.engine.request(json!({"id":1,"method":"validate_settings","params":{"settings":data["settings"]}}))?;
                    if response["ok"] == true && data["remember"] == true {
                        shared.save_workspace(json!({"settings":response["result"],"download_logo":response["result"]["logo"]}), true)?;
                    }
                    Ok(response)
                }
                (Method::Post, "/api/inspect") => {
                    let data = body(&mut request)?;
                    shared
                        .engine
                        .request(json!({"id":1,"method":"inspect","params":data}))
                }
                (Method::Post, "/api/run") => {
                    let data = body(&mut request)?;
                    let inputs = paths(&data["inputs"])?;
                    let out = data["output"]
                        .as_str()
                        .filter(|s| !s.trim().is_empty())
                        .map(PathBuf::from);
                    Ok(json!({"ok":true,"id":shared.run(inputs,out,data["settings"].clone())?}))
                }
                (Method::Post, "/api/demo") => {
                    let folder = shared.store.join("examples");
                    fs::create_dir_all(&folder).map_err(|e| e.to_string())?;
                    let file = folder.join("demo-records.tsv");
                    fs::write(&file, include_bytes!("../../examples/demo-records.tsv"))
                        .map_err(|e| e.to_string())?;
                    Ok(json!({"ok":true,"inputs":[file]}))
                }
                (Method::Post, "/api/upload") => {
                    let data = body(&mut request)?;
                    let files = data["files"].as_array().ok_or("缺少文件")?;
                    if files.is_empty() || files.len() > 100 {
                        return Err("请选择 1–100 个文献文件".into());
                    }
                    let folder = shared.store.join("imports").join(new_id());
                    fs::create_dir_all(&folder).map_err(|e| e.to_string())?;
                    let mut inputs = vec![];
                    for (i, file) in files.iter().enumerate() {
                        let name = file["name"].as_str().ok_or("缺少文件名")?;
                        if name.contains(['/', '\\']) || name == "." || name == ".." {
                            return Err("文件名不能包含路径".into());
                        }
                        let bytes = base64::engine::general_purpose::STANDARD
                            .decode(file["data"].as_str().ok_or("缺少文件内容")?)
                            .map_err(|e| e.to_string())?;
                        let path = folder.join(format!("{i:03}-{name}"));
                        fs::write(&path, bytes).map_err(|e| e.to_string())?;
                        inputs.push(path);
                    }
                    Ok(json!({"ok":true,"inputs":inputs}))
                }
                (Method::Post, "/api/cancel") => {
                    let data = body(&mut request)?;
                    let id = data["id"].as_str().ok_or("缺少任务 ID")?;
                    shared.cancel(Some(id));
                    Ok(json!({"ok":true}))
                }
                (Method::Post, "/api/load") => {
                    let data = body(&mut request)?;
                    let path = data["path"].as_str().ok_or("缺少报告路径")?;
                    Ok(json!({"ok":true,"id":shared.load_report(Path::new(path))?}))
                }
                (Method::Post, "/api/pick") => {
                    let data = body(&mut request)?;
                    #[cfg(windows)]
                    {
                        let kind = data["kind"].as_str().unwrap_or("files");
                        let english = data["language"].as_str() == Some("en");
                        let title = match (kind, english) {
                            ("folder", false) => "选择文献数据目录",
                            ("folder", true) => "Choose bibliographic data folder",
                            ("output", false) => "选择结果保存位置",
                            ("output", true) => "Choose result location",
                            ("report", false) => "打开已有分析结果",
                            ("report", true) => "Open an existing analysis result",
                            (_, false) => "选择文献文件",
                            (_, true) => "Choose bibliographic files",
                        };
                        let paths = match kind {
                            "folder" | "output" | "report" => rfd::FileDialog::new()
                                .set_title(title)
                                .pick_folder()
                                .into_iter()
                                .collect::<Vec<_>>(),
                            _ => rfd::FileDialog::new()
                                .set_title(title)
                                .add_filter(
                                    if english {
                                        "Bibliographic data"
                                    } else {
                                        "文献数据"
                                    },
                                    &["txt", "ris", "csv", "tsv", "bib", "xml"],
                                )
                                .pick_files()
                                .unwrap_or_default(),
                        };
                        Ok(json!({"ok":true,"paths":paths}))
                    }
                    #[cfg(not(windows))]
                    {
                        let _ = data;
                        Err("此系统请用上传或路径导入".into())
                    }
                }
                (Method::Post, "/api/open-output") => {
                    let data = body(&mut request)?;
                    let id = data["id"].as_str().ok_or("缺少任务 ID")?;
                    let jobs = shared.jobs.lock().unwrap();
                    let job = jobs.iter().find(|j| j.id == id).ok_or("报告不存在")?;
                    #[cfg(windows)]
                    std::process::Command::new("explorer.exe")
                        .arg(&job.output)
                        .spawn()
                        .map_err(|e| e.to_string())?;
                    Ok(json!({"ok":true,"output":job.output}))
                }
                _ => Err("API 路径或方法无效".into()),
            }
        })();
        json_response(request, result);
        return;
    }
    if method != Method::Get {
        send(request, 405, "text/plain", b"Method not allowed".to_vec());
        return;
    }
    match path.as_str() {
        "/" => send(
            request,
            200,
            "text/html; charset=utf-8",
            include_str!("../web/index.html")
                .replace("__SESSION_TOKEN__", &shared.token)
                .into_bytes(),
        ),
        "/brand.png" => send(
            request,
            200,
            "image/png",
            include_bytes!("../assets/logo-128.png").to_vec(),
        ),
        "/favicon.ico" => send(
            request,
            200,
            "image/x-icon",
            include_bytes!("../assets/ycscite.ico").to_vec(),
        ),
        "/fonts/noto-sans-sc.woff2" => send(
            request,
            200,
            "font/woff2",
            include_bytes!("../web/fonts/noto-sans-sc.woff2").to_vec(),
        ),
        "/fonts/lora.woff2" => send(
            request,
            200,
            "font/woff2",
            include_bytes!("../web/fonts/lora.woff2").to_vec(),
        ),
        "/app.css" => send(
            request,
            200,
            "text/css; charset=utf-8",
            include_bytes!("../web/app.css").to_vec(),
        ),
        "/app.js" => send(
            request,
            200,
            "text/javascript; charset=utf-8",
            include_bytes!("../web/app.js").to_vec(),
        ),
        _ => {
            let result = (|| -> Result<(Vec<u8>, &str), String> {
                let rest = path.strip_prefix("/report/").ok_or("页面不存在")?;
                let (id, relative) = rest.split_once('/').ok_or("文件路径无效")?;
                let output = {
                    let jobs = shared.jobs.lock().unwrap();
                    jobs.iter()
                        .find(|j| j.id == id)
                        .ok_or("报告不存在")?
                        .output
                        .clone()
                };
                let file = safe_file(&output, relative)?;
                let mime = match file.extension().and_then(|v| v.to_str()).unwrap_or("") {
                    "png" => "image/png",
                    "html" => "text/html; charset=utf-8",
                    "json" => "application/json; charset=utf-8",
                    "svg" => "image/svg+xml",
                    "pdf" => "application/pdf",
                    "docx" => {
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    }
                    "txt" | "ris" | "net" | "gexf" | "sql" => "text/plain; charset=utf-8",
                    "tsv" => "text/tab-separated-values; charset=utf-8",
                    _ => return Err("不支持的报告文件".into()),
                };
                Ok((fs::read(file).map_err(|e| e.to_string())?, mime))
            })();
            match result {
                Ok((bytes, mime)) => send(request, 200, mime, bytes),
                Err(e) => send(request, 404, "text/plain; charset=utf-8", e.into_bytes()),
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn report_paths_cannot_escape() {
        let root = std::env::temp_dir().join(new_id());
        fs::create_dir_all(root.join("data")).unwrap();
        fs::write(root.join("data/a.tsv"), "ok").unwrap();
        assert!(safe_file(&root, "data/a.tsv").is_ok());
        for path in [
            "../a.tsv",
            "data/../../a.tsv",
            "C:/Windows/win.ini",
            "/etc/passwd",
        ] {
            assert!(safe_file(&root, path).is_err());
        }
        fs::remove_file(root.join("data/a.tsv")).unwrap();
        fs::remove_dir(root.join("data")).unwrap();
        fs::remove_dir(root).unwrap();
    }
    #[test]
    fn paths_reject_non_strings() {
        assert!(paths(&json!([4])).is_err());
        assert!(paths(&json!(["中文 路径"])).is_ok());
    }
}
