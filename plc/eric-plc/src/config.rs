//! `plc.toml` for one scan task. Paths are relative to the file.

use crate::program::{self, Mapping};
use serde::Deserialize;
use std::path::{Path, PathBuf};

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct FileConfig {
    #[serde(default = "default_period")]
    period_ms: u64,
    #[serde(default = "default_fifo")]
    fifo_priority: i32,
    #[serde(default)]
    cpu: Option<usize>,
    #[serde(default = "default_true")]
    mlock: bool,
    #[serde(default = "default_overruns")]
    max_consecutive_overruns: u32,
    #[serde(default = "default_bridge")]
    bridge_timeout_ms: u64,
    shm_path: String,
    program: String,
    entry: String,
    #[serde(default)]
    instance: String,
    #[serde(default)]
    hwmap: String,
    mapping: String,
    status_path: String,
    #[serde(default = "default_label")]
    label: String,
    #[serde(default = "default_true")]
    stop_safe: bool,
    #[serde(default)]
    control_socket: String,
}

fn default_period() -> u64 { 100 }
fn default_fifo() -> i32 { 50 }
fn default_true() -> bool { true }
fn default_overruns() -> u32 { 10 }
fn default_bridge() -> u64 { 100 }
fn default_label() -> String { "container-size, not reference".into() }

#[derive(Debug)]
pub struct Config {
    pub period_ns: u64,
    pub fifo_priority: i32,
    pub cpu: Option<usize>,
    pub cpus: Vec<usize>,
    pub mlock: bool,
    pub max_consecutive_overruns: u32,
    pub bridge_timeout_ns: u64,
    pub shm_path: PathBuf,
    pub program: PathBuf,
    pub entry: String,
    pub instance: String,
    pub hwmap: Option<PathBuf>,
    pub mapping_path: PathBuf,
    pub mapping: Mapping,
    pub status_path: PathBuf,
    pub label: String,
    pub stop_safe: bool,
    pub control_socket: String,
}

impl Config {
    pub fn load(path: &Path) -> Result<Self, String> {
        let text = std::fs::read_to_string(path).map_err(|e| format!("read {}: {e}", path.display()))?;
        let file: FileConfig = toml::from_str(&text).map_err(|e| format!("config {}: {e}", path.display()))?;
        if file.period_ms == 0 || file.period_ms > 60_000 {
            return Err("period_ms must be from 1 to 60000".into());
        }
        if file.bridge_timeout_ms == 0 {
            return Err("bridge_timeout_ms must be at least 1".into());
        }
        let dir = path.parent().unwrap_or_else(|| Path::new("."));
        let mapping_path = resolve(dir, &file.mapping);
        let mapping = program::load_mapping(&mapping_path)?;
        let hwmap = if file.hwmap.is_empty() { None } else { Some(resolve(dir, &file.hwmap)) };
        Ok(Config {
            period_ns: file.period_ms.saturating_mul(1_000_000),
            fifo_priority: file.fifo_priority,
            cpu: file.cpu,
            cpus: file.cpu.map(|cpu| vec![cpu]).unwrap_or_else(crate::resources::assigned_cpus),
            mlock: file.mlock,
            max_consecutive_overruns: file.max_consecutive_overruns,
            bridge_timeout_ns: file.bridge_timeout_ms.saturating_mul(1_000_000),
            shm_path: resolve(dir, &file.shm_path),
            program: if file.program.is_empty() { PathBuf::new() } else { resolve(dir, &file.program) },
            entry: file.entry,
            instance: file.instance,
            hwmap,
            mapping_path,
            mapping,
            status_path: resolve(dir, &file.status_path),
            label: file.label,
            stop_safe: file.stop_safe,
            control_socket: file.control_socket,
        })
    }

    pub fn with_label(mut self, label: Option<String>) -> Self {
        if let Some(label) = label {
            if !label.is_empty() {
                self.label = label;
            }
        }
        self
    }
}

fn resolve(dir: &Path, raw: &str) -> PathBuf {
    let path = PathBuf::from(raw);
    if path.is_absolute() { path } else { dir.join(path) }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    #[test]
    fn omitted_period_and_overruns_use_tolerant_defaults() {
        let dir = std::env::temp_dir().join(format!(
            "eric-plc-config-{}-{}",
            std::process::id(),
            std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()
        ));
        fs::create_dir_all(&dir).unwrap();
        let mapping = dir.join("mapping.json");
        fs::write(
            &mapping,
            r#"{"inputs":[{"address":"%IX0.0","tag":"in","type":"BOOL"}],"outputs":[{"address":"%QW0","tag":"out","type":"INT","safe":"0"}]}"#,
        ).unwrap();
        let cfg = dir.join("plc.toml");
        let mapping_path = mapping.display().to_string().replace('\\', "/");
        fs::write(
            &cfg,
            format!(
                "shm_path = \"image.shm\"\nprogram = \"prog.so\"\nentry = \"P\"\nmapping = \"{mapping_path}\"\nstatus_path = \"status.json\"\n"
            ),
        ).unwrap();
        let loaded = Config::load(&cfg).expect("config");
        assert_eq!(loaded.period_ns, 100 * 1_000_000);
        assert_eq!(loaded.max_consecutive_overruns, 10);
        assert_eq!(loaded.mapping.outputs[0].safe, Some(serde_json::json!("0")));
        let _ = fs::remove_dir_all(&dir);
    }
}
