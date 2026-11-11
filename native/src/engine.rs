use serde_json::{Value, json};
use std::{
    env, fs,
    path::{Path, PathBuf},
    process::Command,
};

#[derive(Clone)]
pub struct Engine {
    pub home: PathBuf,
    pub java: PathBuf,
    pub classpath: PathBuf,
}

impl Engine {
    pub fn find() -> Result<Self, String> {
        let mut roots = Vec::new();
        if let Some(p) = env::var_os("YCSCITE_HOME") {
            roots.push(PathBuf::from(p));
        }
        if let Ok(p) = env::current_exe() {
            if let Some(p) = p.parent() {
                roots.push(p.to_owned());
            }
        }
        let source_root = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .parent()
            .unwrap()
            .to_owned();
        if env::current_exe().is_ok_and(|exe| exe.starts_with(source_root.join("native/target"))) {
            roots.push(source_root);
        }
        for home in roots {
            let jar = home.join("engine/ycscite-engine.jar");
            let classes = home.join("out");
            let classpath = if jar.is_file() {
                jar
            } else if classes.join("ycscite/Cli.class").is_file() {
                classes
            } else {
                continue;
            };
            let mut runtimes = vec![
                home.join("runtime/bin/java.exe"),
                home.join("runtime/bin/java"),
            ];
            if let Some(p) = env::var_os("JAVA_HOME") {
                runtimes.push(PathBuf::from(p).join(if cfg!(windows) {
                    "bin/java.exe"
                } else {
                    "bin/java"
                }));
            }
            // Developer checkout only; portable releases always use their bundled runtime first.
            runtimes.push(PathBuf::from("D:/JDK/jdk-21-temurin/bin/java.exe"));
            runtimes.push(PathBuf::from("java"));
            let java = runtimes
                .into_iter()
                .find(|p| java_major(p).is_some_and(|version| version >= 21))
                .ok_or("需要 Java 21 或更高版本；请使用附带 runtime/ 的完整便携包。")?;
            return Ok(Self {
                home,
                java,
                classpath,
            });
        }
        Err("找不到分析引擎。请使用完整的便携包，或先在项目目录执行 build.cmd。".into())
    }

    pub fn command(&self) -> Command {
        let mut cmd = Command::new(&self.java);
        cmd.args([
            "-Dfile.encoding=UTF-8",
            "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8",
            "-Djava.awt.headless=true",
            "-cp",
        ])
        .arg(&self.classpath)
        .arg("ycscite.Cli");
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            cmd.creation_flags(0x08000000);
        }
        cmd
    }

    pub fn workbench(
        &self,
        inputs: Vec<PathBuf>,
        language: Option<&str>,
        config: Option<PathBuf>,
        project: Option<PathBuf>,
        logo: Option<bool>,
    ) -> Result<u32, String> {
        if project.is_some() && (!inputs.is_empty() || config.is_some()) {
            return Err("A saved project cannot be combined with input/config".into());
        }
        if let Some(path) = &config {
            let settings: Value =
                serde_json::from_slice(&fs::read(path).map_err(|e| e.to_string())?)
                    .map_err(|e| e.to_string())?;
            let validated = self.request(
                json!({"id":1,"method":"validate_settings","params":{"settings":settings}}),
            )?;
            if validated["ok"] != true {
                return Err(validated["error"]["message"]
                    .as_str()
                    .unwrap_or("Invalid settings")
                    .into());
            }
        }
        if let Some(path) = &project {
            if !path.is_file() {
                return Err("Project file does not exist".into());
            }
        }
        let mut command = Command::new(&self.java);
        command
            .args([
                "-Dfile.encoding=UTF-8",
                "-Dstdout.encoding=UTF-8",
                "-Dstderr.encoding=UTF-8",
                "-Djava.awt.headless=false",
                "-cp",
            ])
            .arg(&self.classpath)
            .arg("ycscite.Main");
        if let Some(language) = language {
            command.arg("--lang").arg(language);
        }
        if let Some(path) = config {
            command.arg("--config").arg(absolute(&path)?);
        }
        if let Some(path) = project {
            command.arg("--project").arg(absolute(&path)?);
        }
        if let Some(logo) = logo {
            command.arg(if logo { "--logo" } else { "--no-logo" });
        }
        for input in inputs {
            command.arg("--input").arg(absolute(&input)?);
        }
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            command.creation_flags(0x08000000);
        }
        command
            .spawn()
            .map(|child| child.id())
            .map_err(|e| e.to_string())
    }

    pub fn request(&self, request: Value) -> Result<Value, String> {
        self.request_in(request, None)
    }
    pub fn request_in(&self, request: Value, directory: Option<&Path>) -> Result<Value, String> {
        use std::io::Write;
        use std::process::Stdio;
        let mut command = self.command();
        if let Some(directory) = directory {
            command.current_dir(directory);
        }
        let mut child = command
            .arg("headless")
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .spawn()
            .map_err(|e| e.to_string())?;
        {
            let mut stdin = child.stdin.take().ok_or("Missing protocol stdin")?;
            writeln!(stdin, "{request}").map_err(|e| e.to_string())?;
        }
        let output = child.wait_with_output().map_err(|e| e.to_string())?;
        if !output.status.success() {
            return Err(format!("headless engine exited: {}", output.status));
        }
        serde_json::from_slice(&output.stdout)
            .map_err(|e| format!("Invalid headless response: {e}"))
    }
    pub fn capabilities(&self) -> Result<Value, String> {
        let response = self.request(json!({"id":0,"method":"capabilities"}))?;
        if response["ok"] != true {
            return Err(response.to_string());
        }
        Ok(response["result"].clone())
    }

    pub fn doctor(&self) -> Value {
        let check = self.command().arg("version").output();
        let (available, detail) = match check {
            Ok(o) => (
                o.status.success(),
                if o.status.success() {
                    String::from_utf8_lossy(&o.stdout).trim().to_owned()
                } else {
                    String::from_utf8_lossy(&o.stderr).trim().to_owned()
                },
            ),
            Err(e) => (false, e.to_string()),
        };
        json!({"version":env!("CARGO_PKG_VERSION"),"client":"rust","analysis_engine":"java21",
            "engine_available":available,"engine_detail":detail,"home":self.home,"java":self.java,
            "classpath":self.classpath,"bundled_runtime":self.java.starts_with(&self.home),
            "formats":13,"charts":18,"headless":true})
    }
}

pub fn manifest(out: &Path) -> Result<Value, String> {
    let text = fs::read_to_string(out.join("manifest.json"))
        .map_err(|e| format!("报告清单不可读：{e}"))?;
    serde_json::from_str(&text).map_err(|e| e.to_string())
}

pub fn absolute(path: &Path) -> Result<PathBuf, String> {
    if path.is_absolute() {
        Ok(path.to_owned())
    } else {
        Ok(env::current_dir().map_err(|e| e.to_string())?.join(path))
    }
}

fn java_major(path: &Path) -> Option<u32> {
    let mut cmd = Command::new(path);
    cmd.arg("-version");
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x08000000);
    }
    let output = cmd.output().ok()?;
    if !output.status.success() {
        return None;
    }
    let text = String::from_utf8_lossy(&output.stderr);
    let version = text.split('"').nth(1)?;
    let mut parts = version.split('.');
    let major = parts.next()?.parse::<u32>().ok()?;
    if major == 1 {
        parts.next()?.parse().ok()
    } else {
        Some(major)
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn metrics_keep_the_engine_double_precision() {
        for text in [
            "0.9397760200485541",
            "0.642116110460455",
            "0.12954497952296917",
            "0.8441525331131841",
            "0.34126493688490894",
        ] {
            let expected = text.parse::<f64>().unwrap();
            let parsed: serde_json::Value = serde_json::from_str(text).unwrap();
            assert_eq!(
                parsed.as_f64().unwrap().to_bits(),
                expected.to_bits(),
                "{text}"
            );
            let emitted = serde_json::to_string(&parsed).unwrap();
            assert_eq!(
                emitted.parse::<f64>().unwrap().to_bits(),
                expected.to_bits()
            );
        }
    }
}
