use crate::config::Config;
use crate::image::{Image, Slot, STATE_HOLD};
use crate::program::type_code;
use crate::scan::{self, RunLimit};
use crate::sim;
use std::path::PathBuf;
use std::process::Command;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, OnceLock};
use std::time::Duration;

fn fake_so() -> PathBuf {
    static ONCE: OnceLock<PathBuf> = OnceLock::new();
    ONCE.get_or_init(|| {
        let dir = std::env::temp_dir().join("eric-plc-fake-so");
        std::fs::create_dir_all(&dir).unwrap();
        let so = dir.join("libfake.so");
        let source = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("testdata/fake_pou.c");
        let status = Command::new("cc")
            .args(["-shared", "-fPIC", "-o"])
            .arg(&so)
            .arg(&source)
            .status()
            .expect("cc");
        assert!(status.success(), "fake program failed to compile");
        so
    })
    .clone()
}

fn write_config(dir: &PathBuf, entry: &str, period_ms: u64, overruns: u32, bridge_ms: u64, stop_safe: bool) -> PathBuf {
    let mapping = dir.join("mapping.json");
    std::fs::write(
        &mapping,
        r#"{
          "inputs": [
            {"address": "%IX0.0", "tag": "plc/start", "type": "BOOL"},
            {"address": "%IX0.1", "tag": "plc/stop", "type": "BOOL"},
            {"address": "%IW2", "tag": "plc/level_raw", "type": "INT"}
          ],
          "outputs": [
            {"address": "%QX1.0", "tag": "plc/motor", "type": "BOOL", "safe": false},
            {"address": "%QW4", "tag": "plc/level_out", "type": "INT", "safe": 0}
          ]
        }"#,
    )
    .unwrap();
    let shm = dir.join("image");
    let status = dir.join("status.json");
    let toml = dir.join("plc.toml");
    std::fs::write(
        &toml,
        format!(
            r#"
period_ms = {period_ms}
fifo_priority = 0
mlock = false
max_consecutive_overruns = {overruns}
bridge_timeout_ms = {bridge_ms}
shm_path = "{shm}"
program = "{program}"
entry = "{entry}"
instance = ""
mapping = "{mapping}"
status_path = "{status}"
label = "test"
stop_safe = {stop_safe}
"#,
            shm = shm.display(),
            program = fake_so().display(),
            mapping = mapping.display(),
            status = status.display(),
        ),
    )
    .unwrap();
    toml
}

fn temp_dir(name: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!("eric-plc-{name}-{}-{}", std::process::id(), name.len()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

#[test]
fn seal_in_and_scale_follow_the_image() {
    let dir = temp_dir("seal");
    let toml = write_config(&dir, "SealIn", 5, 50, 500, false);
    let config = Config::load(&toml).unwrap();
    let stop = Arc::new(AtomicBool::new(false));
    let sim_stop = Arc::clone(&stop);
    let sim_cfg = Config::load(&toml).unwrap();
    let sim_thread = std::thread::spawn(move || {
        sim::run_sim(&sim_cfg, Duration::from_millis(400), &sim_stop, false).unwrap();
    });
    std::thread::sleep(Duration::from_millis(20));
    let report = scan::run(
        &config,
        &RunLimit {
            stop: Arc::clone(&stop),
            max_scans: Some(30),
            max_duration: Some(Duration::from_millis(500)),
        },
    )
    .unwrap();
    stop.store(true, Ordering::Relaxed);
    sim_thread.join().unwrap();
    assert!(report.scans >= 30, "scans {}", report.scans);
    assert!(!report.watchdog);
    let image = Image::open(&config.shm_path, 3, 2, config.period_ns).unwrap();
    let mut outputs = [Slot { type_code: 0, quality: 0, value: 0, source_ns: 0 }; 2];
    image.read_outputs(&mut outputs).unwrap();
    assert_eq!(outputs[0].value, 1, "motor should seal in");
    assert!(outputs[1].value <= 1000, "scaled level {}", outputs[1].value);
    let status = crate::stats::read_status(&config.status_path).unwrap();
    assert!(status.finished);
    assert!(status.jitter_ns.count >= 30);
    assert_eq!(status.label, "test");
}

#[test]
fn watchdog_latches_safe_outputs() {
    let dir = temp_dir("watch");
    let toml = write_config(&dir, "SealIn_spin", 2, 2, 5_000, true);
    let config = Config::load(&toml).unwrap();
    let image = Image::open(&config.shm_path, 3, 2, config.period_ns).unwrap();
    let mut inputs = Vec::new();
    for point in &config.mapping.inputs {
        inputs.push(Slot {
            type_code: type_code(&point.ty).unwrap(),
            quality: 0,
            value: 1,
            source_ns: crate::rt::realtime_ns() as i64,
        });
    }
    image.write_inputs(&inputs).unwrap();
    image.set_gateway_heartbeat(crate::rt::realtime_ns());
    drop(image);
    let stop = Arc::new(AtomicBool::new(false));
    let report = scan::run(
        &config,
        &RunLimit { stop, max_scans: Some(8), max_duration: Some(Duration::from_secs(3)) },
    )
    .unwrap();
    assert!(report.watchdog, "watchdog should latch");
    let image = Image::open(&config.shm_path, 3, 2, config.period_ns).unwrap();
    assert_eq!(image.fault(), 1);
    assert_eq!(image.state(), crate::image::STATE_STOPPED);
    let mut outputs = [Slot { type_code: 0, quality: 0, value: 9, source_ns: 0 }; 2];
    image.read_outputs(&mut outputs).unwrap();
    assert_eq!(outputs[0].value, 0);
    assert_eq!(outputs[0].quality, 1);
}

#[test]
fn bridge_loss_publishes_safe_outputs() {
    let dir = temp_dir("bridge");
    let toml = write_config(&dir, "SealIn", 5, 50, 40, false);
    let config = Config::load(&toml).unwrap();
    let image = Image::open(&config.shm_path, 3, 2, config.period_ns).unwrap();
    let mut inputs = Vec::new();
    for (index, point) in config.mapping.inputs.iter().enumerate() {
        inputs.push(Slot {
            type_code: type_code(&point.ty).unwrap(),
            quality: 0,
            value: if index == 0 { 1 } else { 0 },
            source_ns: crate::rt::realtime_ns() as i64,
        });
    }
    image.write_inputs(&inputs).unwrap();
    image.set_gateway_heartbeat(crate::rt::realtime_ns());
    drop(image);
    let stop = Arc::new(AtomicBool::new(false));
    let stop2 = Arc::clone(&stop);
    let cfg = Config::load(&toml).unwrap();
    let thread = std::thread::spawn(move || {
        scan::run(
            &cfg,
            &RunLimit { stop: stop2, max_scans: None, max_duration: Some(Duration::from_millis(180)) },
        )
        .unwrap();
    });
    std::thread::sleep(Duration::from_millis(30));
    let image = Image::open(&config.shm_path, 3, 2, config.period_ns).unwrap();
    let mut outputs = [Slot { type_code: 0, quality: 0, value: 0, source_ns: 0 }; 2];
    image.read_outputs(&mut outputs).unwrap();
    assert_eq!(outputs[0].value, 1, "motor follows start while the bridge is fresh");
    let stale = crate::rt::realtime_ns().saturating_sub(1_000_000_000);
    image.set_gateway_heartbeat(stale);
    std::thread::sleep(Duration::from_millis(80));
    let mut held = [Slot { type_code: 0, quality: 0, value: 0, source_ns: 0 }; 2];
    image.read_outputs(&mut held).unwrap();
    assert_eq!(image.state(), STATE_HOLD);
    assert_eq!(held[0].value, 0, "bridge loss forces the safe motor value");
    assert_eq!(held[0].quality, 1);
    stop.store(true, Ordering::Relaxed);
    thread.join().unwrap();
}
