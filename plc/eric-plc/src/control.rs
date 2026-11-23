//! Commands from the gateway. The scan thread is the only thread that loads a
//! program or writes outputs. A deploy writes safe outputs before the old
//! library is dropped. A force never replaces a safe or stopped output.

use crate::image::Slot;
use crate::program::Program;
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Condvar, Mutex};
use std::time::Duration;

#[derive(Clone, Debug)]
pub struct DeployJob {
    pub program: PathBuf,
    pub entry: String,
    pub instance: String,
    pub hwmap: Option<PathBuf>,
}

#[derive(Clone, Debug)]
pub struct Force {
    pub name: String,
    pub bits: u64,
    pub output: bool,
}

#[derive(Clone, Debug)]
pub struct Watched {
    pub name: String,
    pub address: String,
    pub bits: u64,
    pub forced: bool,
    pub output: bool,
}

pub(crate) enum Pending {
    Deploy(DeployJob),
    Start,
    Stop,
}

#[derive(Clone, Copy, Default)]
pub struct LiveStats {
    pub period_ns: u64,
    pub jitter_p99_ns: u64,
    pub exec_p99_ns: u64,
    pub overruns: u64,
}

pub struct Controls {
    pending: Mutex<Option<Pending>>,
    cv: Condvar,
    epoch: Mutex<u64>,
    last_error: Mutex<String>,
    forces: Mutex<HashMap<String, Force>>,
    watch: Mutex<Vec<Watched>>,
    live: Mutex<LiveStats>,
    pub paused: std::sync::atomic::AtomicBool,
    pub faulted: std::sync::atomic::AtomicBool,
    pub swapping: std::sync::atomic::AtomicBool,
    latched: std::sync::atomic::AtomicBool,
}

impl Controls {
    pub fn new() -> Self {
        Self {
            pending: Mutex::new(None),
            cv: Condvar::new(),
            epoch: Mutex::new(0),
            last_error: Mutex::new(String::new()),
            forces: Mutex::new(HashMap::new()),
            watch: Mutex::new(Vec::new()),
            live: Mutex::new(LiveStats::default()),
            paused: std::sync::atomic::AtomicBool::new(false),
            faulted: std::sync::atomic::AtomicBool::new(false),
            swapping: std::sync::atomic::AtomicBool::new(false),
            latched: std::sync::atomic::AtomicBool::new(false),
        }
    }

    pub fn paused(&self) -> bool {
        self.paused.load(std::sync::atomic::Ordering::Acquire)
    }

    pub fn faulted(&self) -> bool {
        self.faulted.load(std::sync::atomic::Ordering::Acquire)
    }

    pub fn swapping(&self) -> bool {
        self.swapping.load(std::sync::atomic::Ordering::Acquire)
    }

    pub fn latched(&self) -> bool {
        self.latched.load(std::sync::atomic::Ordering::Acquire)
    }

    /// Watchdog latch. Cleared only by process exit. A deploy does not clear it.
    pub fn latch(&self) {
        self.latched.store(true, std::sync::atomic::Ordering::Release);
    }

    pub fn publish_stats(&self, stats: LiveStats) {
        if let Ok(mut guard) = self.live.lock() {
            *guard = stats;
        }
    }

    pub fn live_stats(&self) -> LiveStats {
        self.live.lock().map(|guard| *guard).unwrap_or_default()
    }

    /// Safe or stopped outputs ignore forces. A running scan uses a force when one is set.
    pub fn output_bits(program_bits: u64, forced: Option<u64>, safe_bits: u64, hold_safe: bool) -> u64 {
        if hold_safe { safe_bits } else { forced.unwrap_or(program_bits) }
    }

    pub fn force_for(&self, name: &str) -> Option<Force> {
        self.forces.lock().ok().and_then(|guard| guard.get(name).cloned())
    }

    pub fn set_force(&self, force: Force) {
        if let Ok(mut guard) = self.forces.lock() {
            guard.insert(force.name.clone(), force);
        }
    }

    pub fn clear_force(&self, name: &str) {
        if let Ok(mut guard) = self.forces.lock() {
            guard.remove(name);
        }
    }

    pub fn forces_active(&self) -> bool {
        self.forces.lock().map(|guard| !guard.is_empty()).unwrap_or(false)
    }

    pub fn publish_watch(&self, rows: Vec<Watched>) {
        if let Ok(mut guard) = self.watch.lock() {
            *guard = rows;
        }
    }

    pub fn watch(&self) -> Vec<Watched> {
        self.watch.lock().map(|guard| guard.clone()).unwrap_or_default()
    }

    pub(crate) fn request(&self, item: Pending) -> Result<(), String> {
        {
            let mut guard = self.pending.lock().map_err(|err| err.to_string())?;
            if guard.is_some() {
                return Err("the engine is already handling a command".into());
            }
            *guard = Some(item);
        }
        let start = *self.epoch.lock().map_err(|err| err.to_string())?;
        let deadline = std::time::Instant::now() + Duration::from_secs(2);
        loop {
            let now = *self.epoch.lock().map_err(|err| err.to_string())?;
            if now != start {
                break;
            }
            if std::time::Instant::now() >= deadline {
                if let Ok(mut guard) = self.pending.lock() {
                    *guard = None;
                }
                return Err("the scan did not accept the command".into());
            }
            let guard = self.pending.lock().map_err(|err| err.to_string())?;
            let (_guard, _) = self.cv.wait_timeout(guard, Duration::from_millis(50)).map_err(|err| err.to_string())?;
        }
        let err = self.last_error.lock().map_err(|err| err.to_string())?;
        if err.is_empty() { Ok(()) } else { Err(err.clone()) }
    }

    /// Called only from the scan thread.
    pub(crate) fn take_pending(&self) -> Option<Pending> {
        self.pending.lock().ok().and_then(|mut guard| guard.take())
    }

    pub fn finish(&self, error: Option<String>) {
        if let Ok(mut err) = self.last_error.lock() {
            *err = error.unwrap_or_default();
        }
        if let Ok(mut epoch) = self.epoch.lock() {
            *epoch = epoch.wrapping_add(1);
        }
        self.cv.notify_all();
    }
}

impl Default for Controls {
    fn default() -> Self {
        Self::new()
    }
}

/** The names a force can use for one mapped point: its tag or its address, and its safe value. */
pub struct ForceKey {
    pub tag: String,
    pub address: String,
    pub safe: u64,
}

pub fn force_keys(points: &[crate::program::Point]) -> Vec<ForceKey> {
    points.iter().map(|point| ForceKey { tag: point.tag.clone(), address: point.address.clone(), safe: point.safe }).collect()
}

/// Input forces replace the value read from the tag, every scan, until released. Returns the forced indexes.
pub fn apply_input_forces(slots: &mut [Slot], keys: &[ForceKey], controls: &Controls) -> Vec<usize> {
    let mut forced = Vec::new();
    for (index, key) in keys.iter().enumerate() {
        if let Some(force) = controls.force_for(&key.tag).or_else(|| controls.force_for(&key.address)) {
            if !force.output {
                if let Some(slot) = slots.get_mut(index) {
                    slot.value = force.bits;
                    forced.push(index);
                }
            }
        }
    }
    forced
}

/// Output forces replace what the program wrote, every scan, before the outputs reach the image and the tags.
pub fn apply_output_forces(slots: &mut [Slot], points: &[crate::program::Point], controls: &Controls, hold_safe: bool) {
    apply_output_force_keys(slots, &force_keys(points), controls, hold_safe);
}

pub fn apply_output_force_keys(slots: &mut [Slot], keys: &[ForceKey], controls: &Controls, hold_safe: bool) {
    let forces = controls.forces.lock().ok();
    for (index, slot) in slots.iter_mut().enumerate() {
        let key = match keys.get(index) {
            Some(key) => key,
            None => continue,
        };
        let forced = forces.as_ref().and_then(|map| {
            map.values().find(|force| force.output && (force.name == key.tag || force.name == key.address)).map(|force| force.bits)
        });
        slot.value = Controls::output_bits(slot.value, forced, key.safe, hold_safe);
        if hold_safe {
            slot.quality = 1;
        }
    }
}

pub fn reload(job: &DeployJob, mapping: &crate::program::Mapping) -> Result<Program, String> {
    Program::load(&job.program, &job.entry, &job.instance, mapping, job.hwmap.as_deref())
}

pub fn serve(path: &str, controls: std::sync::Arc<Controls>) -> Result<(), String> {
    #[cfg(unix)]
    {
        serve_unix(path, controls)
    }
    #[cfg(windows)]
    {
        serve_pipe(path, controls)
    }
}

#[cfg(unix)]
fn serve_unix(path: &str, controls: std::sync::Arc<Controls>) -> Result<(), String> {
    let _ = std::fs::remove_file(path);
    let listener = std::os::unix::net::UnixListener::bind(path).map_err(|err| err.to_string())?;
    for conn in listener.incoming() {
        let mut conn = match conn {
            Ok(conn) => conn,
            Err(_) => continue,
        };
        let mut reader = std::io::BufReader::new(match conn.try_clone() {
            Ok(cloned) => cloned,
            Err(_) => continue,
        });
        let mut line = String::new();
        if reader.read_line(&mut line).ok().filter(|n| *n > 0).is_none() {
            continue;
        }
        let reply = dispatch(&controls, line.trim());
        let _ = std::io::Write::write_all(&mut conn, reply.as_bytes());
        let _ = std::io::Write::write_all(&mut conn, b"\n");
    }
    Ok(())
}

#[cfg(windows)]
fn serve_pipe(path: &str, controls: std::sync::Arc<Controls>) -> Result<(), String> {
    let name = crate::win::wide(path);
    // Byte mode, one client at a time. Java opens it with RandomAccessFile.
    let pipe = unsafe {
        crate::win::CreateNamedPipeW(name.as_ptr(), 0x3, 0, 1, 65536, 65536, 0, std::ptr::null_mut())
    };
    if pipe == 0 || pipe == -1 {
        return Err(crate::win::last_error(&format!("pipe {path}")));
    }
    loop {
        let connected = unsafe { crate::win::ConnectNamedPipe(pipe, std::ptr::null_mut()) };
        if connected == 0 {
            let err = unsafe { crate::win::GetLastError() };
            if err != 535 {
                unsafe { crate::win::DisconnectNamedPipe(pipe) };
                continue;
            }
        }
        let mut buf = vec![0u8; 65536];
        let mut got = 0u32;
        let ok = unsafe { crate::win::ReadFile(pipe, buf.as_mut_ptr() as *mut _, buf.len() as u32, &mut got, std::ptr::null_mut()) };
        if ok == 0 || got == 0 {
            unsafe { crate::win::DisconnectNamedPipe(pipe) };
            continue;
        }
        let text = String::from_utf8_lossy(&buf[..got as usize]);
        let line = text.lines().next().unwrap_or("");
        let reply = dispatch(&controls, line.trim());
        let bytes = format!("{reply}\n").into_bytes();
        let mut wrote = 0u32;
        unsafe { crate::win::WriteFile(pipe, bytes.as_ptr() as *const _, bytes.len() as u32, &mut wrote, std::ptr::null_mut()) };
        // DisconnectNamedPipe drops bytes the client has not read yet. Wait until the reply is taken.
        unsafe { crate::win::FlushFileBuffers(pipe) };
        unsafe { crate::win::DisconnectNamedPipe(pipe) };
    }
}

fn dispatch(controls: &Controls, line: &str) -> String {
    let value: serde_json::Value = match serde_json::from_str(line) {
        Ok(value) => value,
        Err(err) => return format!("{{\"ok\":false,\"error\":{}}}", serde_json::to_string(&err.to_string()).unwrap_or_else(|_| "\"bad json\"".into())),
    };
    let op = value.get("op").and_then(|item| item.as_str()).unwrap_or("");
    let result = match op {
        "status" => Ok(status_body(controls)),
        "watch" => Ok(watch_body(controls)),
        "start" => controls.request(Pending::Start).map(|_| status_body(controls)),
        "stop" => controls.request(Pending::Stop).map(|_| status_body(controls)),
        "deploy" => {
            let program = value.get("program").and_then(|item| item.as_str()).unwrap_or("").to_string();
            if program.is_empty() {
                Err("program path is required".into())
            } else {
                controls.request(Pending::Deploy(DeployJob {
                    program: PathBuf::from(program),
                    entry: value.get("entry").and_then(|item| item.as_str()).unwrap_or("").to_string(),
                    instance: value.get("instance").and_then(|item| item.as_str()).unwrap_or("").to_string(),
                    hwmap: value.get("hwmap").and_then(|item| item.as_str()).filter(|text| !text.is_empty()).map(PathBuf::from),
                })).map(|_| status_body(controls))
            }
        }
        "force" => {
            let name = value.get("name").and_then(|item| item.as_str()).unwrap_or("").to_string();
            if name.is_empty() {
                Err("name is required".into())
            } else {
                controls.set_force(Force {
                    name,
                    bits: json_bits(value.get("value")),
                    output: value.get("output").and_then(|item| item.as_bool()).unwrap_or(true),
                });
                Ok(status_body(controls))
            }
        }
        "unforce" => {
            let name = value.get("name").and_then(|item| item.as_str()).unwrap_or("");
            controls.clear_force(name);
            Ok(status_body(controls))
        }
        _ => Err(format!("unknown command {op}")),
    };
    match result {
        Ok(body) => format!("{{\"ok\":true,{body}}}"),
        Err(err) => format!("{{\"ok\":false,\"error\":{}}}", serde_json::to_string(&err).unwrap_or_else(|_| "\"failed\"".into())),
    }
}

fn engine_state(controls: &Controls) -> &'static str {
    if controls.faulted() || controls.latched() {
        "faulted"
    } else if controls.swapping() {
        "swapping"
    } else if controls.paused() {
        "stopped"
    } else {
        "running"
    }
}

fn status_body(controls: &Controls) -> String {
    let state = engine_state(controls);
    let stats = controls.live_stats();
    let period_ms = stats.period_ns / 1_000_000;
    // A stored force is not "active" while outputs are held at the safe value.
    let forces = state == "running" && controls.forces_active();
    format!(
        "\"state\":\"{state}\",\"forcesActive\":{forces},\"periodMs\":{period_ms},\"jitterP99Ns\":{},\"execP99Ns\":{},\"overruns\":{}",
        stats.jitter_p99_ns, stats.exec_p99_ns, stats.overruns
    )
}

fn watch_body(controls: &Controls) -> String {
    let rows = controls.watch();
    let mut items = Vec::new();
    for row in rows {
        items.push(format!(
            "{{\"name\":{},\"address\":{},\"bits\":{},\"forced\":{},\"output\":{}}}",
            serde_json::to_string(&row.name).unwrap_or_else(|_| "\"\"".into()),
            serde_json::to_string(&row.address).unwrap_or_else(|_| "\"\"".into()),
            row.bits,
            row.forced,
            row.output,
        ));
    }
    let state = engine_state(controls);
    let forces = state == "running" && controls.forces_active();
    format!("\"state\":\"{state}\",\"forcesActive\":{forces},\"variables\":[{}]", items.join(","))
}

fn json_bits(value: Option<&serde_json::Value>) -> u64 {
    match value {
        Some(serde_json::Value::Bool(flag)) => u64::from(*flag),
        Some(serde_json::Value::Number(number)) => number.as_u64().or_else(|| number.as_i64().map(|n| n as u64)).unwrap_or(0),
        _ => 0,
    }
}

#[cfg(unix)]
use std::io::BufRead;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_force_does_not_override_the_safe_output() {
        assert_eq!(Controls::output_bits(7, Some(3), 0, true), 0);
        assert_eq!(Controls::output_bits(7, Some(3), 0, false), 3);
        assert_eq!(Controls::output_bits(7, None, 0, false), 7);
    }

    fn slot(value: u64) -> Slot {
        Slot { type_code: 1, quality: 0, value, source_ns: 1 }
    }

    fn key(tag: &str, address: &str) -> ForceKey {
        ForceKey { tag: tag.into(), address: address.into(), safe: 0 }
    }

    /// One scan the way scan.rs runs it: read the input tags, apply input forces, run the logic, apply output
    /// forces, and return what goes to the output tags. Logic here: Out := In; Speed := Level * 2.
    fn scan(controls: &Controls, tags: &[u64; 2], held_out: &mut [u64; 2]) -> [u64; 2] {
        let inputs_keys = [key("In", "%IX0.0"), key("Level", "%ID1")];
        let output_keys = [key("Out", "%QX0.0"), key("Speed", "%QD1")];
        let mut inputs = [slot(tags[0]), slot(tags[1])];
        apply_input_forces(&mut inputs, &inputs_keys, controls);
        held_out[0] = inputs[0].value;
        held_out[1] = inputs[1].value * 2;
        let mut outputs = [slot(held_out[0]), slot(held_out[1])];
        apply_output_force_keys(&mut outputs, &output_keys, controls, false);
        [outputs[0].value, outputs[1].value]
    }

    #[test]
    fn a_forced_input_overrides_the_tag_every_scan_until_released() {
        let controls = Controls::new();
        let mut program_out = [0, 0];
        assert_eq!(scan(&controls, &[1, 10], &mut program_out), [1, 20]);
        controls.set_force(Force { name: "In".into(), bits: 0, output: false });
        controls.set_force(Force { name: "%ID1".into(), bits: 7, output: false });
        for _ in 0..3 {
            assert_eq!(scan(&controls, &[1, 10], &mut program_out), [0, 14], "the logic sees the forced inputs");
        }
        controls.clear_force("In");
        controls.clear_force("%ID1");
        assert_eq!(scan(&controls, &[1, 10], &mut program_out), [1, 20], "release brings the real values back");
    }

    #[test]
    fn a_forced_output_reaches_the_tag_despite_the_logic() {
        let controls = Controls::new();
        let mut program_out = [0, 0];
        controls.set_force(Force { name: "Out".into(), bits: 1, output: true });
        for _ in 0..3 {
            assert_eq!(scan(&controls, &[0, 10], &mut program_out)[0], 1, "held every scan");
            assert_eq!(program_out[0], 0, "the program itself still computes Out := In");
        }
        controls.clear_force("Out");
        assert_eq!(scan(&controls, &[0, 10], &mut program_out)[0], 0, "release restores the program's value");
    }

    #[test]
    fn a_write_is_overwritten_by_the_logic_but_a_force_is_not() {
        let controls = Controls::new();
        let mut program_out = [0, 0];
        // Write once to the output: the next scan's logic replaces it.
        let mut tags_out = scan(&controls, &[0, 10], &mut program_out);
        tags_out[0] = 1;
        assert_eq!(tags_out[0], 1);
        assert_eq!(scan(&controls, &[0, 10], &mut program_out)[0], 0, "a one-shot write lasts until the next scan");
        // Force: kept.
        controls.set_force(Force { name: "Out".into(), bits: 1, output: true });
        assert_eq!(scan(&controls, &[0, 10], &mut program_out)[0], 1);
        assert_eq!(scan(&controls, &[0, 10], &mut program_out)[0], 1);
    }

    #[test]
    fn a_udt_member_point_is_forced_by_its_tag() {
        let controls = Controls::new();
        let keys = [key("[default]MiamiDade/OS-1A/InhibitCmd", "%QX5.0")];
        let mut outputs = [slot(0)];
        controls.set_force(Force { name: "[default]MiamiDade/OS-1A/InhibitCmd".into(), bits: 1, output: true });
        apply_output_force_keys(&mut outputs, &keys, &controls, false);
        assert_eq!(outputs[0].value, 1);
    }

    #[test]
    fn a_latched_engine_hides_forces() {
        let controls = Controls::new();
        controls.set_force(Force { name: "motor".into(), bits: 1, output: true });
        controls.latch();
        let body = status_body(&controls);
        assert!(body.contains("\"state\":\"faulted\""), "{body}");
        assert!(body.contains("\"forcesActive\":false"), "{body}");
    }
}
