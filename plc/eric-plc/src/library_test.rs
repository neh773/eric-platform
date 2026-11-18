//! Host tests for the compiled process library. The numbers come from the blocks.

use libloading::Library;
use std::ffi::CString;
use std::path::PathBuf;
use std::process::Command;
use std::sync::OnceLock;

fn library() -> &'static Library {
    static ONCE: OnceLock<Library> = OnceLock::new();
    ONCE.get_or_init(|| {
        let root = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        let status = Command::new("bash").arg(root.join("scripts/build-library.sh")).status().expect("build");
        assert!(status.success(), "process library failed to compile");
        let so = root.join("build/liberic_library.so");
        unsafe {
            Library::from(libloading::os::unix::Library::open(Some(&so), libc::RTLD_NOW | libc::RTLD_LOCAL).unwrap())
        }
    })
}

fn load<T>(name: &str) -> unsafe extern "C" fn(*mut T) {
    let lib = library();
    let c = CString::new(name).unwrap();
    let sym = unsafe { lib.get::<*mut u8>(c.as_bytes_with_nul()) }.unwrap_or_else(|e| panic!("{name}: {e}"));
    let addr = unsafe { sym.into_raw() }.into_raw() as *mut u8;
    unsafe { std::mem::transmute(addr) }
}

fn fresh<T>(ctor_name: &str) -> T {
    let ctor = load::<T>(ctor_name);
    let mut value = unsafe { std::mem::zeroed::<T>() };
    unsafe { ctor(&mut value) };
    value
}

fn near(actual: f64, expected: f64, tol: f64) {
    assert!((actual - expected).abs() <= tol, "{actual} != {expected}");
}

#[test]
fn process_library() {
    // Loading this shared library in the main test process breaks the PID block tests.
    if std::env::var("ERIC_LIBRARY_CHILD").is_err() {
        let status = Command::new(std::env::current_exe().unwrap())
            .env("ERIC_LIBRARY_CHILD", "1")
            .args(["--exact", "library_test::process_library", "--test-threads=1"])
            .status()
            .unwrap();
        assert!(status.success(), "process library child failed");
        return;
    }
    scale_converts_and_flags_a_raw_outside_the_range();
    alarm_waits_out_the_delay_and_clears_through_the_deadband();
    motor_runs_in_hand_and_latches_a_failed_start();
    valve_reports_fail_to_open_and_fail_to_close();
    ramp_moves_toward_the_target_at_the_given_rate();
    filter_lags_a_step_and_averages_a_constant();
    totalizer_integrates_rate_and_reset_clears_it();
    runtime_counts_starts_and_run_hours();
    hysteresis_holds_between_the_two_levels();
    select_picks_the_higher_or_lower_input();
    split_range_divides_the_stroke_at_the_split();
    time_proportioning_is_on_for_the_requested_fraction();
    lead_lag_matches_the_backward_difference();
    dead_time_delays_a_step_by_the_requested_scans();
    sequencer_advances_on_the_dwell_and_holds();
    alternator_starts_the_lead_pump_and_rotates();
}

#[repr(C)]
struct Scale {
    _v: *mut u8, raw: f64, raw_min: f64, raw_max: f64, eu_min: f64, eu_max: f64, clamp: u8,
    out: f64, bad: u8, _tail: [u8; 640],
}

fn scale_converts_and_flags_a_raw_outside_the_range() {
    let mut b = fresh::<Scale>("ERIC_AI_SCALE__ctor");
    let body = load::<Scale>("ERIC_AI_SCALE");
    b.raw_min = 0.0; b.raw_max = 100.0; b.eu_min = 0.0; b.eu_max = 50.0; b.clamp = 1;
    b.raw = 50.0;
    unsafe { body(&mut b) };
    near(b.out, 25.0, 1e-9);
    assert_eq!(b.bad, 0);
    b.raw = -5.0;
    unsafe { body(&mut b) };
    near(b.out, 0.0, 1e-9);
    assert_eq!(b.bad, 1);
    b.raw_max = 0.0;
    unsafe { body(&mut b) };
    assert_eq!(b.bad, 1);
}

#[repr(C)]
struct Alarm {
    _v: *mut u8, pv: f64, hh: f64, h: f64, l: f64, ll: f64, dead: f64, delay: f64, dt: f64,
    hh_act: u8, h_act: u8, l_act: u8, ll_act: u8, _tail: [u8; 640],
}

fn alarm_waits_out_the_delay_and_clears_through_the_deadband() {
    let mut b = fresh::<Alarm>("ERIC_ALARM_LIMITS__ctor");
    let body = load::<Alarm>("ERIC_ALARM_LIMITS");
    b.hh = 10.0; b.h = 8.0; b.l = 2.0; b.ll = 1.0; b.dead = 0.5; b.delay = 0.2; b.dt = 0.1; b.pv = 10.0;
    unsafe { body(&mut b) };
    assert_eq!(b.hh_act, 0);
    unsafe { body(&mut b) };
    assert_eq!(b.hh_act, 1);
    b.pv = 9.6;
    unsafe { body(&mut b) };
    assert_eq!(b.hh_act, 1);
    b.pv = 9.4;
    unsafe { body(&mut b) };
    assert_eq!(b.hh_act, 0);
    b.pv = 1.0;
    unsafe { body(&mut b) };
    unsafe { body(&mut b) };
    assert_eq!(b.ll_act, 1);
}

#[repr(C)]
struct Motor {
    _v: *mut u8, hand: u8, auto_: u8, hand_cmd: u8, auto_cmd: u8, permissive: u8, trip: u8, feedback: u8,
    fail_time: f64, dt: f64, reset: u8, run: u8, fail_start: u8, _tail: [u8; 640],
}

fn motor_runs_in_hand_and_latches_a_failed_start() {
    let mut b = fresh::<Motor>("ERIC_MOTOR__ctor");
    let body = load::<Motor>("ERIC_MOTOR");
    b.hand = 1; b.hand_cmd = 1; b.permissive = 1; b.fail_time = 0.3; b.dt = 0.1;
    unsafe { body(&mut b) };
    unsafe { body(&mut b) };
    assert_eq!(b.run, 1);
    unsafe { body(&mut b) };
    assert_eq!(b.fail_start, 1);
    assert_eq!(b.run, 0);
    b.feedback = 1; b.reset = 1;
    unsafe { body(&mut b) };
    assert_eq!(b.fail_start, 0);
}

#[repr(C)]
struct Valve {
    _v: *mut u8, hand: u8, auto_: u8, hand_open: u8, hand_close: u8, auto_open: u8,
    permissive: u8, trip: u8, open_fb: u8, close_fb: u8, fail_time: f64, dt: f64, reset: u8,
    open_out: u8, close_out: u8, fail_open: u8, fail_close: u8, _tail: [u8; 640],
}

fn valve_reports_fail_to_open_and_fail_to_close() {
    let mut b = fresh::<Valve>("ERIC_VALVE__ctor");
    let body = load::<Valve>("ERIC_VALVE");
    b.auto_ = 1; b.auto_open = 1; b.permissive = 1; b.fail_time = 0.2; b.dt = 0.1;
    unsafe { body(&mut b) };
    assert_eq!(b.open_out, 1);
    unsafe { body(&mut b) };
    assert_eq!(b.fail_open, 1);
    assert_eq!(b.open_out, 0);
    b.reset = 1;
    unsafe { body(&mut b) };
    b.reset = 0; b.auto_open = 0; b.close_fb = 0; b.open_fb = 1;
    unsafe { body(&mut b) };
    unsafe { body(&mut b) };
    assert_eq!(b.fail_close, 1);
    assert_eq!(b.close_out, 0);
}

#[repr(C)]
struct Ramp { _v: *mut u8, input: f64, rate: f64, dt: f64, reset: u8, out: f64, _tail: [u8; 64] }

fn ramp_moves_toward_the_target_at_the_given_rate() {
    let mut b = fresh::<Ramp>("ERIC_RAMP__ctor");
    let body = load::<Ramp>("ERIC_RAMP");
    b.rate = 10.0; b.dt = 0.1; b.input = 0.0;
    unsafe { body(&mut b) };
    near(b.out, 0.0, 1e-12);
    b.input = 5.0;
    unsafe { body(&mut b) };
    unsafe { body(&mut b) };
    unsafe { body(&mut b) };
    near(b.out, 3.0, 1e-12);
}

#[repr(C)]
struct Filter { _v: *mut u8, input: f64, t: f64, n: f64, dt: f64, reset: u8, out: f64, avg: f64, _tail: [u8; 640] }

fn filter_lags_a_step_and_averages_a_constant() {
    let mut b = fresh::<Filter>("ERIC_FILTER__ctor");
    let body = load::<Filter>("ERIC_FILTER");
    b.t = 1.0; b.n = 1.0; b.dt = 0.1; b.input = 0.0;
    unsafe { body(&mut b) };
    b.input = 1.0;
    unsafe { body(&mut b) };
    near(b.out, 0.1 / 1.1, 1e-12);
    near(b.avg, b.out, 1e-12);
}

#[repr(C)]
struct Total { _v: *mut u8, rate: f64, dt: f64, reset: u8, total: f64, _tail: [u8; 32] }

fn totalizer_integrates_rate_and_reset_clears_it() {
    let mut b = fresh::<Total>("ERIC_TOTALIZER__ctor");
    let body = load::<Total>("ERIC_TOTALIZER");
    b.rate = 2.0; b.dt = 0.5;
    unsafe { body(&mut b) };
    unsafe { body(&mut b) };
    near(b.total, 2.0, 1e-12);
    b.reset = 1;
    unsafe { body(&mut b) };
    near(b.total, 0.0, 1e-12);
}

#[repr(C)]
struct Runtime { _v: *mut u8, run: u8, dt: f64, reset: u8, hours: f64, starts: f64, _tail: [u8; 32] }

fn runtime_counts_starts_and_run_hours() {
    let mut b = fresh::<Runtime>("ERIC_RUNTIME__ctor");
    let body = load::<Runtime>("ERIC_RUNTIME");
    b.dt = 3600.0; b.run = 1;
    unsafe { body(&mut b) };
    unsafe { body(&mut b) };
    near(b.hours, 2.0, 1e-12);
    near(b.starts, 1.0, 1e-12);
    b.run = 0;
    unsafe { body(&mut b) };
    b.run = 1;
    unsafe { body(&mut b) };
    near(b.starts, 2.0, 1e-12);
}

#[repr(C)]
struct Hyst { _v: *mut u8, input: f64, on: f64, off: f64, out: u8, _tail: [u8; 32] }

fn hysteresis_holds_between_the_two_levels() {
    let mut b = fresh::<Hyst>("ERIC_HYSTERESIS__ctor");
    let body = load::<Hyst>("ERIC_HYSTERESIS");
    b.on = 10.0; b.off = 8.0; b.input = 9.0;
    unsafe { body(&mut b) };
    assert_eq!(b.out, 0);
    b.input = 10.0;
    unsafe { body(&mut b) };
    assert_eq!(b.out, 1);
    b.input = 9.0;
    unsafe { body(&mut b) };
    assert_eq!(b.out, 1);
    b.input = 8.0;
    unsafe { body(&mut b) };
    assert_eq!(b.out, 0);
}

#[repr(C)]
struct Select { _v: *mut u8, a: f64, b: f64, high: u8, out: f64, use_b: u8, _tail: [u8; 32] }

fn select_picks_the_higher_or_lower_input() {
    let mut b = fresh::<Select>("ERIC_SELECT__ctor");
    let body = load::<Select>("ERIC_SELECT");
    b.a = 1.0; b.b = 3.0; b.high = 1;
    unsafe { body(&mut b) };
    near(b.out, 3.0, 1e-12);
    assert_eq!(b.use_b, 1);
    b.high = 0;
    unsafe { body(&mut b) };
    near(b.out, 1.0, 1e-12);
}

#[repr(C)]
struct Split { _v: *mut u8, input: f64, split: f64, out_a: f64, out_b: f64, _tail: [u8; 64] }

fn split_range_divides_the_stroke_at_the_split() {
    let mut b = fresh::<Split>("ERIC_SPLIT_RANGE__ctor");
    let body = load::<Split>("ERIC_SPLIT_RANGE");
    b.split = 50.0; b.input = 25.0;
    unsafe { body(&mut b) };
    near(b.out_a, 50.0, 1e-12);
    near(b.out_b, 0.0, 1e-12);
    b.input = 75.0;
    unsafe { body(&mut b) };
    near(b.out_a, 100.0, 1e-12);
    near(b.out_b, 50.0, 1e-12);
}

#[repr(C)]
struct Tpo { _v: *mut u8, input: f64, cycle: f64, dt: f64, out: u8, _tail: [u8; 64] }

fn time_proportioning_is_on_for_the_requested_fraction() {
    let mut b = fresh::<Tpo>("ERIC_TPO__ctor");
    let body = load::<Tpo>("ERIC_TPO");
    b.input = 50.0; b.cycle = 1.0; b.dt = 0.25;
    let mut ons = 0;
    for _ in 0..4 {
        unsafe { body(&mut b) };
        ons += u32::from(b.out);
    }
    assert_eq!(ons, 2);
    b.input = 0.0;
    unsafe { body(&mut b) };
    assert_eq!(b.out, 0);
    b.input = 100.0;
    unsafe { body(&mut b) };
    assert_eq!(b.out, 1);
}

#[repr(C)]
struct Lead { _v: *mut u8, input: f64, t_lead: f64, t_lag: f64, dt: f64, reset: u8, out: f64, _tail: [u8; 64] }

fn lead_lag_matches_the_backward_difference() {
    let mut b = fresh::<Lead>("ERIC_LEADLAG__ctor");
    let body = load::<Lead>("ERIC_LEADLAG");
    b.t_lead = 0.0; b.t_lag = 1.0; b.dt = 0.1; b.input = 0.0;
    unsafe { body(&mut b) };
    b.input = 1.0;
    unsafe { body(&mut b) };
    near(b.out, 0.1 / 1.1, 1e-12);
    b.reset = 1;
    unsafe { body(&mut b) };
    b.reset = 0; b.t_lead = 1.0; b.t_lag = 1.0; b.input = 0.0;
    unsafe { body(&mut b) };
    b.input = 1.0;
    unsafe { body(&mut b) };
    near(b.out, 1.0, 1e-12);
}

#[repr(C)]
struct Dead { _v: *mut u8, input: f64, time: f64, dt: f64, reset: u8, out: f64, ready: u8, _tail: [u8; 640] }

fn dead_time_delays_a_step_by_the_requested_scans() {
    let mut b = fresh::<Dead>("ERIC_DEADTIME__ctor");
    let body = load::<Dead>("ERIC_DEADTIME");
    b.time = 0.3; b.dt = 0.1; b.input = 1.0;
    unsafe { body(&mut b) };
    unsafe { body(&mut b) };
    unsafe { body(&mut b) };
    near(b.out, 0.0, 1e-12);
    assert_eq!(b.ready, 0);
    unsafe { body(&mut b) };
    near(b.out, 1.0, 1e-12);
    assert_eq!(b.ready, 1);
}

#[repr(C)]
struct Seq {
    _v: *mut u8, start: u8, hold: u8, reset: u8, trans: u8,
    t1: f64, t2: f64, t3: f64, t4: f64, dt: f64, step: f64, elapsed: f64, done: u8, _tail: [u8; 64],
}

fn sequencer_advances_on_the_dwell_and_holds() {
    let mut b = fresh::<Seq>("ERIC_SEQUENCER__ctor");
    let body = load::<Seq>("ERIC_SEQUENCER");
    b.t1 = 0.2; b.t2 = 0.2; b.t3 = 0.2; b.t4 = 0.2; b.dt = 0.1; b.trans = 1; b.start = 1;
    unsafe { body(&mut b) };
    near(b.step, 1.0, 1e-12);
    unsafe { body(&mut b) };
    near(b.step, 1.0, 1e-12);
    unsafe { body(&mut b) };
    near(b.step, 2.0, 1e-12);
    b.hold = 1;
    let elapsed = b.elapsed;
    unsafe { body(&mut b) };
    near(b.elapsed, elapsed, 1e-12);
    near(b.step, 2.0, 1e-12);
    b.hold = 0; b.reset = 1;
    unsafe { body(&mut b) };
    near(b.step, 0.0, 1e-12);
}

#[repr(C)]
struct Alt {
    _v: *mut u8, demand: f64, avail_1: u8, avail_2: u8, rotate: u8, rotate_after: f64, dt: f64, reset: u8,
    run_1: u8, run_2: u8, lead: f64, _tail: [u8; 64],
}

fn alternator_starts_the_lead_pump_and_rotates() {
    let mut b = fresh::<Alt>("ERIC_ALTERNATOR__ctor");
    let body = load::<Alt>("ERIC_ALTERNATOR");
    b.demand = 1.0; b.avail_1 = 1; b.avail_2 = 1; b.dt = 1.0;
    unsafe { body(&mut b) };
    assert_eq!(b.run_1, 1);
    assert_eq!(b.run_2, 0);
    near(b.lead, 1.0, 1e-12);
    b.rotate = 1;
    unsafe { body(&mut b) };
    assert_eq!(b.run_2, 1);
    assert_eq!(b.run_1, 0);
    b.rotate = 0; b.demand = 2.0;
    unsafe { body(&mut b) };
    assert_eq!(b.run_1, 1);
    assert_eq!(b.run_2, 1);
}
