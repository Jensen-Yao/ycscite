use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::{
    fs,
    path::{Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH},
};

/// Application state is independent of the browser origin and desktop port.
#[derive(Clone, Serialize, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub(crate) struct Workspace {
    pub schema_version: u8,
    pub language: String,
    pub settings: Option<Value>,
    pub inputs: Vec<PathBuf>,
    pub output: String,
    pub view: String,
    pub report_id: Option<String>,
    pub download_logo: Option<bool>,
    pub export_format: String,
    pub data_mode: bool,
    pub saved_at_ms: u64,
}
impl Default for Workspace {
    fn default() -> Self {
        Self {
            schema_version: 1,
            language: "zh".into(),
            settings: None,
            inputs: vec![],
            output: String::new(),
            view: "keywords".into(),
            report_id: None,
            download_logo: None,
            export_format: "png".into(),
            data_mode: false,
            saved_at_ms: 0,
        }
    }
}
impl Workspace {
    fn validate(&self, capabilities: &Value) -> Result<(), String> {
        if self.schema_version != 1 {
            return Err("Unsupported workspace schema".into());
        }
        if !["zh", "en"].contains(&self.language.as_str()) {
            return Err("language must be zh or en".into());
        }
        if self.settings.as_ref().is_some_and(|s| !s.is_object()) {
            return Err("settings must be an object".into());
        }
        if self.inputs.len() > 100
            || self
                .inputs
                .iter()
                .any(|p| p.to_string_lossy().len() > 16384)
            || self.output.len() > 16384
        {
            return Err("Workspace input/output paths exceed the supported size".into());
        }
        if !capabilities["charts"]
            .as_array()
            .is_some_and(|charts| charts.iter().any(|c| c["key"].as_str() == Some(&self.view)))
        {
            return Err(format!("Unknown view: {}", self.view));
        }
        if self.report_id.as_ref().is_some_and(|id| id.len() > 128) {
            return Err("Report ID is too long".into());
        }
        if ![
            "png",
            "svg",
            "pdf",
            "docx",
            "sql",
            "pajek",
            "gexf",
            "ris",
            "records-tsv",
        ]
        .contains(&self.export_format.as_str())
        {
            return Err("Unknown export format".into());
        }
        Ok(())
    }
    pub fn patched(&self, patch: &Value, capabilities: &Value) -> Result<Self, String> {
        let patch = patch
            .as_object()
            .ok_or("Workspace update must be an object")?;
        let mut value = serde_json::to_value(self).map_err(|e| e.to_string())?;
        let object = value.as_object_mut().unwrap();
        for (key, entry) in patch {
            if ![
                "language",
                "settings",
                "inputs",
                "output",
                "view",
                "report_id",
                "download_logo",
                "export_format",
                "data_mode",
            ]
            .contains(&key.as_str())
            {
                return Err(format!("Unknown workspace field: {key}"));
            }
            object.insert(key.clone(), entry.clone());
        }
        let mut next: Self =
            serde_json::from_value(value).map_err(|e| format!("Invalid workspace: {e}"))?;
        if patch.contains_key("settings") && !patch.contains_key("language") {
            if let Some(lang) = next.settings.as_ref().and_then(|s| s["language"].as_str()) {
                next.language = lang.into();
            }
        }
        if let Some(settings) = next.settings.as_mut() {
            let map = settings
                .as_object_mut()
                .ok_or("settings must be an object")?;
            map.insert("language".into(), json!(next.language));
        }
        next.validate(capabilities)?;
        Ok(next)
    }
}
pub(crate) fn data_home() -> PathBuf {
    if let Some(path) = std::env::var_os("YCSCITE_DATA_HOME").filter(|s| !s.is_empty()) {
        return path.into();
    }
    if cfg!(windows) {
        if let Some(path) = std::env::var_os("LOCALAPPDATA") {
            return PathBuf::from(path).join("Ycscite");
        }
    } else if cfg!(target_os = "macos") {
        if let Some(path) = std::env::var_os("HOME") {
            return PathBuf::from(path).join("Library/Application Support/Ycscite");
        }
    } else {
        if let Some(path) = std::env::var_os("XDG_DATA_HOME").filter(|s| !s.is_empty()) {
            return PathBuf::from(path).join("Ycscite");
        }
        if let Some(path) = std::env::var_os("HOME") {
            return PathBuf::from(path).join(".local/share/Ycscite");
        }
    }
    std::env::temp_dir().join("Ycscite")
}

pub(crate) struct WorkspaceStore {
    pub current: Workspace,
    pub warning: Option<String>,
    pub initialized: bool,
    pub initial_report_id: Option<String>,
}
fn timestamp() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
        .min(u64::MAX as u128) as u64
}
impl WorkspaceStore {
    pub fn read(folder: &Path, capabilities: &Value) -> Self {
        let path = folder.join("workspace.json");
        let mut result = Self {
            current: Workspace::default(),
            warning: None,
            initialized: false,
            initial_report_id: None,
        };
        if !path.exists() {
            return result;
        }
        let read = (|| -> Result<Workspace, String> {
            if fs::metadata(&path).map_err(|e| e.to_string())?.len() > 1024 * 1024 {
                return Err("Workspace file is too large".into());
            }
            let saved: Workspace =
                serde_json::from_slice(&fs::read(&path).map_err(|e| e.to_string())?)
                    .map_err(|e| e.to_string())?;
            saved.validate(capabilities)?;
            Ok(saved)
        })();
        match read {
            Ok(saved) => {
                result.current = saved;
                result.initialized = true;
            }
            Err(error) => {
                let backup = folder.join(format!(
                    "workspace-recovery-{}-{:08x}.json",
                    timestamp(),
                    rand::random::<u32>()
                ));
                let recovery = if path.is_file() && fs::copy(&path, &backup).is_ok() {
                    format!("Recovery copy: {}", backup.display())
                } else {
                    "The original workspace file was left unchanged".into()
                };
                result.warning = Some(format!(
                    "Saved workspace could not be read: {error}. {recovery}"
                ));
            }
        }
        result
    }
    /// Replace on disk before exposing the new state to callers. A failed save keeps the old state.
    pub fn write(&mut self, folder: &Path, mut next: Workspace) -> Result<(), String> {
        next.saved_at_ms = timestamp();
        let data = serde_json::to_vec_pretty(&next).map_err(|e| e.to_string())?;
        if data.len() > 1024 * 1024 {
            return Err("Workspace file is too large".into());
        }
        let staged = folder.join(format!("workspace-{:08x}.new", rand::random::<u32>()));
        fs::write(&staged, data).map_err(|e| format!("Cannot save workspace: {e}"))?;
        if let Err(error) = fs::rename(&staged, folder.join("workspace.json")) {
            let _ = fs::remove_file(&staged);
            return Err(format!("Cannot save workspace: {error}"));
        }
        self.current = next;
        self.warning = None;
        self.initialized = true;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn capabilities() -> Value {
        json!({"charts":[{"key":"keywords"},{"key":"timeline"}]})
    }
    #[test]
    fn patches_preserve_other_fields_and_reject_invalid_types() {
        let initial = Workspace::default();
        let changed = initial
            .patched(
                &json!({"language":"en","inputs":["中文 路径.ris"],"view":"timeline"}),
                &capabilities(),
            )
            .unwrap();
        let again = changed
            .patched(&json!({"download_logo":false}), &capabilities())
            .unwrap();
        assert_eq!(again.language, "en");
        assert_eq!(again.view, "timeline");
        assert_eq!(again.inputs.len(), 1);
        for invalid in [
            json!({"view":"../escape"}),
            json!({"language":"fr"}),
            json!({"inputs":[1]}),
            json!({"unknown":1}),
            json!({"data_mode":"true"}),
        ] {
            assert!(again.patched(&invalid, &capabilities()).is_err());
        }
    }
    #[test]
    fn saved_settings_and_interface_language_stay_consistent() {
        let initial = Workspace::default();
        let changed = initial
            .patched(
                &json!({"settings":{"language":"en","g_index_k":19}}),
                &capabilities(),
            )
            .unwrap();
        assert_eq!(changed.language, "en");
        let again = changed
            .patched(&json!({"language":"zh"}), &capabilities())
            .unwrap();
        assert_eq!(again.settings.unwrap()["language"], "zh");
    }
    #[test]
    fn a_failed_replace_keeps_the_previous_workspace() {
        let folder = std::env::temp_dir().join(format!(
            "ycscite-workspace-failure-{:016x}",
            rand::random::<u64>()
        ));
        fs::create_dir(&folder).unwrap();
        let mut store = WorkspaceStore::read(&folder, &capabilities());
        let first = store
            .current
            .patched(&json!({"language":"en"}), &capabilities())
            .unwrap();
        store.write(&folder, first).unwrap();
        fs::remove_file(folder.join("workspace.json")).unwrap();
        fs::create_dir(folder.join("workspace.json")).unwrap();
        let next = store
            .current
            .patched(&json!({"language":"zh"}), &capabilities())
            .unwrap();
        assert!(store.write(&folder, next).is_err());
        assert_eq!(store.current.language, "en");
        assert!(!fs::read_dir(&folder).unwrap().any(|entry| {
            entry
                .unwrap()
                .path()
                .extension()
                .is_some_and(|extension| extension == "new")
        }));
        fs::remove_dir(folder.join("workspace.json")).unwrap();
        fs::remove_dir(folder).unwrap();
    }
    #[test]
    fn disk_roundtrip_and_corruption_recovery() {
        let folder = std::env::temp_dir().join(format!(
            "ycscite-workspace-test-{:016x}",
            rand::random::<u64>()
        ));
        fs::create_dir(&folder).unwrap();
        let mut store = WorkspaceStore::read(&folder, &capabilities());
        let changed = store
            .current
            .patched(&json!({"language":"en","view":"timeline"}), &capabilities())
            .unwrap();
        store.write(&folder, changed).unwrap();
        let replacement = store
            .current
            .patched(&json!({"download_logo":false}), &capabilities())
            .unwrap();
        store.write(&folder, replacement).unwrap();
        let loaded = WorkspaceStore::read(&folder, &capabilities());
        assert!(loaded.initialized);
        assert_eq!(loaded.current.view, "timeline");
        fs::write(folder.join("workspace.json"), b"{broken").unwrap();
        let recovered = WorkspaceStore::read(&folder, &capabilities());
        assert!(!recovered.initialized);
        assert!(recovered.warning.is_some());
        let files: Vec<_> = fs::read_dir(&folder)
            .unwrap()
            .map(|e| e.unwrap().path())
            .collect();
        assert!(files.iter().any(|p| {
            p.file_name()
                .unwrap()
                .to_string_lossy()
                .starts_with("workspace-recovery-")
        }));
        for path in files {
            fs::remove_file(path).unwrap();
        }
        fs::remove_dir(folder).unwrap();
    }
}
