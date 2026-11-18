//! Host tests for the compiled ERIC_PID block. The numbers are the block's,
//! not a second implementation.

use libloading::Library;
use std::collections::VecDeque;
use std::ffi::CString;
use std::mem::{offset_of, size_of};
use std::path::PathBuf;
use std::process::Command;
use std::sync::OnceLock;

#[repr(C)]
struct EricPid {
    _vtable: *mut u8,
    sp: f64,
    pv: f64,
    manual: u8,
    _pad_manual: [u8; 7],
    man_out: f64,
    kp: f64,
    ti: f64,
    td: f64,
    n: f64,
    out_min: f64,
    out_max: f64,
    direct: u8,
    _pad_direct: [u8; 7],
    dt: f64,
    reset: u8,
    _pad_reset: [u8; 7],
    out: f64,
    error: f64,
    limited_high: u8,
    limited_low: u8,
    _pad_lim: [u8; 6],
    integral: f64,
    d_filt: f64,
    pv_prev: f64,
    out_prev: f64,
    kp_prev: f64,
    ti_prev: f64,
    td_prev: f64,
    n_prev: f64,
    direct_prev: u8,
    manual_prev: u8,
    started: u8,
    _pad_flags: [u8; 5],
    sign: f64,
    p_term: f64,
    d_term: f64,
    raw_d: f64,
    tf: f64,
    alpha: f64,
    unlimited: f64,
    hold: u8,
    drive_high: u8,
    drive_low: u8,
    _pad_tail: [u8; 5],
}

struct Block {
    _stdlib: Library,
    _lib: Library,
    body: unsafe extern "C" fn(*mut EricPid),
    fb: EricPid,
}

fn pid_library() -> PathBuf {
    static ONCE: OnceLock<PathBuf> = OnceLock::new();
    ONCE.get_or_init(|| {
        let root = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        let script = root.join("scripts/build-pid.sh");
        let status = Command::new("bash").arg(&script).status().expect("build-pid.sh");
        assert!(status.success(), "ERIC_PID failed to compile");
        root.join("build/liberic_pid.so")
    })
    .clone()
}

fn load_fn(lib: &Library, name: &str) -> unsafe extern "C" fn(*mut EricPid) {
    let c = CString::new(name).unwrap();
    let sym = unsafe { lib.get::<*mut u8>(c.as_bytes_with_nul()) }.unwrap_or_else(|e| panic!("{name}: {e}"));
    let addr = unsafe { sym.into_raw() }.into_raw() as *mut u8;
    unsafe { std::mem::transmute(addr) }
}

fn open_block() -> Block {
    let so = pid_library();
    let std = so.parent().unwrap().join("libiec61131std.so");
    let stdlib = unsafe {
        Library::from(libloading::os::unix::Library::open(Some(&std), libc::RTLD_NOW | libc::RTLD_GLOBAL).unwrap())
    };
    let lib = unsafe {
        Library::from(libloading::os::unix::Library::open(Some(&so), libc::RTLD_NOW | libc::RTLD_LOCAL).unwrap())
    };
    let ctor = load_fn(&lib, "ERIC_PID__ctor");
    let body = load_fn(&lib, "ERIC_PID");
    let mut fb = unsafe { std::mem::zeroed::<EricPid>() };
    unsafe { ctor(&mut fb) };
    fb.kp = 1.0;
    fb.ti = 1.0;
    fb.td = 0.0;
    fb.n = 0.0;
    fb.out_min = -1.0e6;
    fb.out_max = 1.0e6;
    fb.direct = 1;
    fb.dt = 0.1;
    Block { _stdlib: stdlib, _lib: lib, body, fb }
}

impl Block {
    fn scan(&mut self) {
        unsafe { (self.body)(&mut self.fb) };
    }
}

fn near(actual: f64, expected: f64, tol: f64) {
    assert!((actual - expected).abs() <= tol, "{actual} != {expected} (tol {tol})");
}

#[test]
fn layout_matches_the_generated_c_struct() {
    assert_eq!(size_of::<EricPid>(), 272);
    assert_eq!(offset_of!(EricPid, sp), 8);
    assert_eq!(offset_of!(EricPid, pv), 16);
    assert_eq!(offset_of!(EricPid, manual), 24);
    assert_eq!(offset_of!(EricPid, man_out), 32);
    assert_eq!(offset_of!(EricPid, direct), 88);
    assert_eq!(offset_of!(EricPid, dt), 96);
    assert_eq!(offset_of!(EricPid, reset), 104);
    assert_eq!(offset_of!(EricPid, out), 112);
    assert_eq!(offset_of!(EricPid, error), 120);
    assert_eq!(offset_of!(EricPid, limited_high), 128);
    assert_eq!(offset_of!(EricPid, limited_low), 129);
    assert_eq!(offset_of!(EricPid, integral), 136);
}

#[test]
fn proportional_and_integral_follow_the_discrete_law() {
    let mut pid = open_block();
    pid.fb.sp = 1.0;
    pid.fb.pv = 0.0;
    pid.fb.kp = 2.0;
    pid.fb.ti = 1.0;
    pid.fb.dt = 0.1;
    pid.scan();
    near(pid.fb.error, 1.0, 1e-12);
    near(pid.fb.out, 2.0, 1e-9);
    pid.scan();
    near(pid.fb.out, 2.2, 1e-9);
    pid.scan();
    near(pid.fb.out, 2.4, 1e-9);
    assert_eq!(pid.fb.limited_high, 0);
    assert_eq!(pid.fb.limited_low, 0);
}

#[test]
fn setpoint_step_does_not_kick_the_derivative() {
    let mut pid = open_block();
    pid.fb.kp = 1.0;
    pid.fb.ti = 0.0;
    pid.fb.td = 4.0;
    pid.fb.n = 8.0;
    pid.fb.pv = 0.0;
    pid.fb.sp = 0.0;
    pid.scan();
    near(pid.fb.out, 0.0, 1e-12);
    pid.fb.sp = 10.0;
    pid.scan();
    near(pid.fb.out, 10.0, 1e-6);
    near(pid.fb.d_term, 0.0, 1e-9);
}

#[test]
fn saturation_releases_on_the_next_reversed_error() {
    let mut pid = open_block();
    pid.fb.kp = 4.0;
    pid.fb.ti = 0.2;
    pid.fb.dt = 0.1;
    pid.fb.sp = 10.0;
    pid.fb.pv = 0.0;
    pid.fb.out_min = -1.0;
    pid.fb.out_max = 1.0;
    for _ in 0..30 {
        pid.scan();
    }
    assert_eq!(pid.fb.limited_high, 1);
    near(pid.fb.out, 1.0, 1e-9);
    pid.fb.sp = -10.0;
    pid.scan();
    assert!(pid.fb.out < 0.5, "output stayed saturated at {}", pid.fb.out);
    assert_eq!(pid.fb.limited_high, 0);
}

#[test]
fn manual_transfer_does_not_jump() {
    let mut pid = open_block();
    pid.fb.sp = 1.0;
    pid.fb.pv = 0.2;
    pid.fb.kp = 1.5;
    pid.fb.ti = 3.0;
    pid.fb.td = 0.4;
    pid.fb.n = 8.0;
    for _ in 0..25 {
        pid.fb.pv += 0.01;
        pid.scan();
    }
    let held = pid.fb.out;
    pid.fb.man_out = held;
    pid.fb.manual = 1;
    pid.scan();
    near(pid.fb.out, held, 1e-9);
    pid.fb.pv += 0.2;
    pid.scan();
    near(pid.fb.out, held, 1e-9);
    pid.fb.manual = 0;
    pid.scan();
    near(pid.fb.out, held, 1e-6);
}

#[test]
fn gain_change_does_not_step_the_output() {
    let mut pid = open_block();
    pid.fb.sp = 2.0;
    pid.fb.pv = 1.0;
    pid.fb.kp = 1.2;
    pid.fb.ti = 4.0;
    pid.fb.td = 0.3;
    pid.fb.n = 10.0;
    for _ in 0..15 {
        pid.scan();
    }
    let held = pid.fb.out;
    pid.fb.kp = 6.0;
    pid.fb.ti = 1.0;
    pid.scan();
    near(pid.fb.out, held, 1e-6);
}

#[test]
fn reverse_action_opposes_direct_action() {
    let mut direct = open_block();
    direct.fb.direct = 1;
    direct.fb.kp = 2.0;
    direct.fb.ti = 0.0;
    direct.fb.sp = 4.0;
    direct.fb.pv = 1.0;
    direct.scan();

    let mut reverse = open_block();
    reverse.fb.direct = 0;
    reverse.fb.kp = 2.0;
    reverse.fb.ti = 0.0;
    reverse.fb.sp = 4.0;
    reverse.fb.pv = 1.0;
    reverse.scan();

    near(direct.fb.out, 6.0, 1e-9);
    near(reverse.fb.out, -6.0, 1e-9);
    near(direct.fb.error, 3.0, 1e-12);
    near(reverse.fb.error, -3.0, 1e-12);
}

#[test]
fn step_on_a_slow_plant_settles_without_a_large_overshoot() {
    let dt = 0.1;
    let mut pid = open_block();
    pid.fb.dt = dt;
    pid.fb.kp = 2.5;
    pid.fb.ti = 12.0;
    pid.fb.td = 0.4;
    pid.fb.n = 8.0;
    pid.fb.sp = 1.0;
    pid.fb.out_min = 0.0;
    pid.fb.out_max = 2.0;
    let mut plant = FirstOrder::new(dt, 1.0, 8.0, 1.0);
    let mut peak = 0.0_f64;
    for _ in 0..900 {
        pid.fb.pv = plant.y;
        pid.scan();
        plant.step(pid.fb.out);
        peak = peak.max(plant.y);
    }
    assert!(peak < 1.15, "overshoot peaked at {peak}");
    near(plant.y, 1.0, 0.03);
    assert!(pid.fb.out > 0.5 && pid.fb.out < 1.5, "settled output {}", pid.fb.out);
}

#[test]
fn reverse_plant_settles_when_action_is_reversed() {
    let dt = 0.1;
    let mut pid = open_block();
    pid.fb.direct = 0;
    pid.fb.dt = dt;
    pid.fb.kp = 2.0;
    pid.fb.ti = 6.0;
    pid.fb.td = 0.0;
    pid.fb.sp = 1.0;
    pid.fb.out_min = -2.0;
    pid.fb.out_max = 2.0;
    let mut y = 0.0;
    let tau = 4.0;
    for _ in 0..700 {
        pid.fb.pv = y;
        pid.scan();
        y += dt / tau * (-pid.fb.out - y);
    }
    near(y, 1.0, 0.05);
}

struct FirstOrder {
    y: f64,
    delay: VecDeque<f64>,
    hold: usize,
    k: f64,
    tau: f64,
    dt: f64,
}

impl FirstOrder {
    fn new(dt: f64, k: f64, tau: f64, dead: f64) -> Self {
        Self {
            y: 0.0,
            delay: VecDeque::new(),
            hold: (dead / dt).round() as usize,
            k,
            tau,
            dt,
        }
    }

    fn step(&mut self, input: f64) {
        self.delay.push_back(input);
        let delayed = if self.delay.len() > self.hold {
            self.delay.pop_front().unwrap_or(0.0)
        } else {
            0.0
        };
        self.y += self.dt / self.tau * (self.k * delayed - self.y);
    }
}

#[test]
fn level_program_links_and_scans() {
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    let _ = pid_library();
    let so = root.join("build/liblevel.so");
    let std = root.join("build/libiec61131std.so");
    let _stdlib = unsafe {
        Library::from(libloading::os::unix::Library::open(Some(&std), libc::RTLD_NOW | libc::RTLD_GLOBAL).unwrap())
    };
    let lib = unsafe {
        Library::from(libloading::os::unix::Library::open(Some(&so), libc::RTLD_NOW | libc::RTLD_LOCAL).unwrap())
    };
    let body: unsafe extern "C" fn(*mut u8) = {
        let c = CString::new("LevelLoop").unwrap();
        let sym = unsafe { lib.get::<*mut u8>(c.as_bytes_with_nul()) }.unwrap();
        let addr = unsafe { sym.into_raw() }.into_raw() as *mut u8;
        unsafe { std::mem::transmute(addr) }
    };
    let instance = {
        let c = CString::new("LevelLoop_instance").unwrap();
        let sym = unsafe { lib.get::<*mut u8>(c.as_bytes_with_nul()) }.unwrap();
        unsafe { sym.into_raw() }.into_raw() as *mut u8
    };
    let set = |name: &str, value: f64| {
        let c = CString::new(name).unwrap();
        let sym = unsafe { lib.get::<*mut u8>(c.as_bytes_with_nul()) }.unwrap();
        let ptr = unsafe { sym.into_raw() }.into_raw() as *mut f64;
        unsafe { ptr.write(value) };
    };
    set("__PI_0", 1.0);
    set("__PI_1", 0.0);
    set("__PI_3", 1.0);
    set("__PI_4", 8.0);
    set("__PI_7", 0.0);
    set("__PI_8", 100.0);
    let direct = {
        let c = CString::new("__PI_0_1").unwrap();
        let sym = unsafe { lib.get::<*mut u8>(c.as_bytes_with_nul()) }.unwrap();
        unsafe { sym.into_raw() }.into_raw() as *mut u8
    };
    unsafe { direct.write(1) };
    unsafe { body(instance) };
    let out = {
        let c = CString::new("__PI_20").unwrap();
        let sym = unsafe { lib.get::<*mut u8>(c.as_bytes_with_nul()) }.unwrap();
        let ptr = unsafe { sym.into_raw() }.into_raw() as *const f64;
        unsafe { ptr.read() }
    };
    assert!(out > 0.5, "level output {out}");
}
