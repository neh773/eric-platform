//! Cyclic scan: sleep until the next absolute deadline, read inputs, run the
//! program, write outputs. A late scan does not try to catch up.
//!
//! Overrun: the scan is still running when the next deadline arrives
//! (`end > deadline + period`). `max_consecutive_overruns` of those in a row
//! latches the safe state. Outputs stay at their configured safe values, the
//! program is not called again, and `plc_fault` is 1 until the process exits.
//!
//! Bridge loss is separate. The program keeps its last inputs and is still
//! called, but the published outputs are the safe values and `plc_state` is
//! bridge hold. A fresh gateway heartbeat resumes normal outputs. Retain is
//! out of scope. TODO: keep selected variables across a restart.

use crate::config::Config;
use crate::control::{self, Controls, Pending, Watched};
use crate::histogram::Histogram;
use crate::image::{Image, STATE_HOLD, STATE_RUNNING, STATE_SAFE, STATE_STOPPED};
use crate::program::{self, Program};
use crate::rt::{self, RtStatus};
use crate::stats::{self, StatusMeta};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

pub struct Stats {
    pub jitter: Histogram,
    pub exec: Histogram,
    pub staleness: Histogram,
    pub scans: AtomicU64,
    pub program_calls: AtomicU64,
    pub overruns: AtomicU64,
    pub missed_periods: AtomicU64,
    pub max_consecutive: AtomicU64,
    pub watchdog: AtomicBool,
    pub bridge_lost_scans: AtomicU64,
    pub torn_reads: AtomicU64,
    pub bad_input_scans: AtomicU64,
}

impl Stats {
    pub fn new() -> Self {
        Self {
            jitter: Histogram::new(),
            exec: Histogram::new(),
            staleness: Histogram::new(),
            scans: AtomicU64::new(0),
            program_calls: AtomicU64::new(0),
            overruns: AtomicU64::new(0),
            missed_periods: AtomicU64::new(0),
            max_consecutive: AtomicU64::new(0),
            watchdog: AtomicBool::new(false),
            bridge_lost_scans: AtomicU64::new(0),
            torn_reads: AtomicU64::new(0),
            bad_input_scans: AtomicU64::new(0),
        }
    }
}

pub struct RunLimit {
    pub stop: Arc<AtomicBool>,
    pub max_scans: Option<u64>,
    pub max_duration: Option<Duration>,
}

pub struct RunReport {
    pub scans: u64,
    pub watchdog: bool,
    pub rt: RtStatus,
}

pub fn run(config: &Config, limit: &RunLimit) -> Result<RunReport, String> {
    let image = Image::open(
        &config.shm_path,
        config.mapping.inputs.len() as u32,
        config.mapping.outputs.len() as u32,
        config.period_ns,
    )?;
    let controls = Arc::new(Controls::new());
    if !config.control_socket.is_empty() {
        let shared = Arc::clone(&controls);
        let path = config.control_socket.clone();
        let _ = std::thread::Builder::new().name("eric-plc-control".into()).spawn(move || {
            if let Err(err) = control::serve(&path, shared) {
                eprintln!("eric-plc control: {err}");
            }
        });
    }
    let program = if config.program.as_os_str().is_empty() {
        Program::idle()
    } else {
        Program::load(
            &config.program,
            &config.entry,
            &config.instance,
            &config.mapping,
            config.hwmap.as_deref(),
        )?
    };
    let stats = Arc::new(Stats::new());
    let started = rt::monotonic_ns();
    let started_wall = rt::realtime_ns();
    if let Some(parent) = config.status_path.parent() {
        std::fs::create_dir_all(parent).map_err(|e| format!("status dir: {e}"))?;
    }
    // The stats thread is created before mlockall. A thread stack allocated
    // after MCL_FUTURE can fail with EAGAIN when the memlock limit is small.
    // FIFO is applied to this thread only, so the stats thread stays normal.
    let rt = Arc::new(std::sync::Mutex::new(rt::RtStatus {
        fifo: false,
        fifo_priority: 0,
        fifo_error: String::new(),
        mlock: false,
        mlock_error: String::new(),
        cpu: None,
        cpus: Vec::new(),
        cpu_error: String::new(),
    }));
    let meta_base = StatusMeta {
        label: config.label.clone(),
        period_ns: config.period_ns,
        uname: rt::uname_line(),
        rt: rt.lock().map(|g| g.clone()).unwrap_or_else(|e| e.into_inner().clone()),
        started_realtime_ns: started_wall,
        started_monotonic_ns: started,
    };
    let status_path = config.status_path.clone();
    let writer_stats = Arc::clone(&stats);
    let writer_stop = Arc::clone(&limit.stop);
    let writer_rt = Arc::clone(&rt);
    let writer_base = meta_base.clone();
    let writer_controls = Arc::clone(&controls);
    let writer = std::thread::Builder::new()
        .name("eric-plc-stats".into())
        .spawn(move || {
            while !writer_stop.load(Ordering::Relaxed) && !crate::PROCESS_STOP.load(Ordering::Relaxed) {
                let mut meta = writer_base.clone();
                if let Ok(current) = writer_rt.lock() {
                    meta.rt = current.clone();
                }
                let _ = stats::write_status(&status_path, &writer_stats, &meta, false);
                // Percentiles are folded off the scan thread so the cycle does not allocate.
                writer_controls.publish_stats(control::LiveStats {
                    period_ns: writer_base.period_ns,
                    jitter_p99_ns: writer_stats.jitter.snapshot().p99_ns,
                    exec_p99_ns: writer_stats.exec.snapshot().p99_ns,
                    overruns: writer_stats.overruns.load(Ordering::Relaxed),
                });
                std::thread::sleep(Duration::from_millis(200));
            }
        })
        .map_err(|e| format!("stats thread: {e}"))?;
    {
        let applied = rt::apply(config.fifo_priority, &config.cpus, config.mlock);
        if let Ok(mut slot) = rt.lock() {
            *slot = applied;
        }
    }

    image.set_state(STATE_RUNNING);
    image.set_plc_heartbeat(rt::realtime_ns());
    let outcome = scan_loop(config, &image, program, &stats, limit, started, &controls);
    limit.stop.store(true, Ordering::Relaxed);
    let _ = writer.join();
    let rt_final = rt.lock().map(|guard| guard.clone()).unwrap_or_else(|err| err.into_inner().clone());
    let mut meta = meta_base;
    meta.rt = rt_final.clone();
    let _ = stats::write_status(&config.status_path, &stats, &meta, true);
    outcome?;
    Ok(RunReport {
        scans: stats.scans.load(Ordering::Relaxed),
        watchdog: stats.watchdog.load(Ordering::Relaxed),
        rt: rt_final,
    })
}

fn scan_loop(
    config: &Config,
    image: &Image,
    mut program: Program,
    stats: &Stats,
    limit: &RunLimit,
    started: u64,
    controls: &Controls,
) -> Result<(), String> {
    let period = config.period_ns;
    let mut deadline = {
        let now = rt::monotonic_ns();
        (now / period + 1) * period
    };
    let mut inputs = program::empty_slots(&program.inputs, 1);
    let mut fresh = inputs.clone();
    let mut outputs = program::empty_slots(&program.outputs, 1);
    let mut safe = program::safe_slots(&program.outputs);
    let mut consecutive: u32 = 0;
    let mut safe_latched = false;

    loop {
        if stop_now(limit) {
            break;
        }
        if let Some(max) = limit.max_scans {
            if stats.scans.load(Ordering::Relaxed) >= max {
                break;
            }
        }
        if let Some(budget) = limit.max_duration {
            if rt::monotonic_ns().saturating_sub(started) >= budget.as_nanos() as u64 {
                break;
            }
        }
        if let Some(pending) = controls.take_pending() {
            match pending {
                Pending::Deploy(job) => {
                    controls.swapping.store(true, Ordering::Release);
                    let _ = image.write_outputs(&safe);
                    image.set_state(STATE_HOLD);
                    match control::reload(&job, &config.mapping) {
                        Ok(next) => {
                            program = next;
                            inputs = program::empty_slots(&program.inputs, 1);
                            fresh = inputs.clone();
                            outputs = program::empty_slots(&program.outputs, 1);
                            safe = program::safe_slots(&program.outputs);
                            if !safe_latched {
                                controls.paused.store(false, Ordering::Release);
                            }
                            controls.faulted.store(false, Ordering::Release);
                            controls.finish(None);
                        }
                        Err(err) => {
                            controls.faulted.store(true, Ordering::Release);
                            controls.finish(Some(err));
                        }
                    }
                    controls.swapping.store(false, Ordering::Release);
                }
                Pending::Start => {
                    if !safe_latched && !controls.faulted() {
                        controls.paused.store(false, Ordering::Release);
                    }
                    controls.finish(None);
                }
                Pending::Stop => {
                    controls.paused.store(true, Ordering::Release);
                    let _ = image.write_outputs(&safe);
                    image.set_state(STATE_STOPPED);
                    controls.finish(None);
                }
            }
        }

        rt::sleep_until(deadline, &limit.stop);
        if stop_now(limit) {
            break;
        }
        let wake = rt::monotonic_ns();
        let jitter = wake.saturating_sub(deadline);
        stats.jitter.record(jitter);

        let lost = bridge_lost(image, started, config.bridge_timeout_ns);
        image.set_bridge_lost(lost);
        if lost {
            stats.bridge_lost_scans.fetch_add(1, Ordering::Relaxed);
        }

        if !safe_latched && !lost {
            match image.read_inputs(&mut fresh) {
                Ok(()) => {
                    std::mem::swap(&mut inputs, &mut fresh);
                    let mut worst = 0u64;
                    let mut any_stamp = false;
                    let mut bad = false;
                    let now = rt::realtime_ns() as i64;
                    for (index, slot) in inputs.iter().enumerate() {
                        program.write_input(index, slot.value);
                        if slot.quality != 0 {
                            bad = true;
                        }
                        if slot.source_ns > 0 {
                            any_stamp = true;
                            let age = now.saturating_sub(slot.source_ns).max(0) as u64;
                            worst = worst.max(age);
                        }
                    }
                    if bad || !any_stamp {
                        stats.bad_input_scans.fetch_add(1, Ordering::Relaxed);
                    }
                    if any_stamp {
                        stats.staleness.record(worst);
                    }
                    if !controls.paused() && !controls.faulted() && !controls.swapping() {
                        for (index, point) in program.inputs.iter().enumerate() {
                            if let Some(force) = controls.force_for(&point.tag).or_else(|| controls.force_for(&point.address)) {
                                if !force.output {
                                    program.write_input(index, force.bits);
                                    if let Some(slot) = inputs.get_mut(index) {
                                        slot.value = force.bits;
                                    }
                                }
                            }
                        }
                    }
                }
                Err(_) => {
                    stats.torn_reads.fetch_add(1, Ordering::Relaxed);
                }
            }
        }

        let skip_program = safe_latched || controls.paused() || controls.faulted() || controls.swapping();
        if !skip_program {
            let t0 = rt::monotonic_ns();
            program.call();
            let exec = rt::monotonic_ns().saturating_sub(t0);
            stats.exec.record(exec);
            stats.program_calls.fetch_add(1, Ordering::Relaxed);
        }

        let hold_outputs = skip_program || lost;
        if hold_outputs {
            image.write_outputs(&safe)?;
            outputs = safe.clone();
            image.set_state(if safe_latched || controls.faulted() { STATE_SAFE } else if controls.paused() { STATE_STOPPED } else { STATE_HOLD });
        } else {
            for (index, slot) in outputs.iter_mut().enumerate() {
                slot.value = program.read_output(index);
                slot.quality = 0;
                slot.type_code = program.outputs[index].type_code;
                slot.source_ns = rt::realtime_ns() as i64;
            }
            control::apply_output_forces(&mut outputs, &program.outputs, controls, false);
            image.write_outputs(&outputs)?;
            image.set_state(STATE_RUNNING);
        }
        image.set_plc_heartbeat(rt::realtime_ns());
        let mut watched = Vec::new();
        for (index, point) in program.inputs.iter().enumerate() {
            let forced = !hold_outputs && controls.force_for(&point.tag).or_else(|| controls.force_for(&point.address)).is_some_and(|force| !force.output);
            watched.push(Watched {
                name: point.tag.clone(),
                address: point.address.clone(),
                bits: inputs.get(index).map(|slot| slot.value).unwrap_or(0),
                forced,
                output: false,
            });
        }
        for (index, point) in program.outputs.iter().enumerate() {
            let forced = !hold_outputs && controls.force_for(&point.tag).or_else(|| controls.force_for(&point.address)).is_some_and(|force| force.output);
            watched.push(Watched {
                name: point.tag.clone(),
                address: point.address.clone(),
                bits: outputs.get(index).map(|slot| slot.value).unwrap_or(point.safe),
                forced,
                output: true,
            });
        }
        controls.publish_watch(watched);

        let end = rt::monotonic_ns();
        let next = deadline.saturating_add(period);
        if end > next {
            let missed = end.saturating_sub(deadline) / period;
            stats.overruns.fetch_add(1, Ordering::Relaxed);
            stats.missed_periods.fetch_add(missed, Ordering::Relaxed);
            consecutive = consecutive.saturating_add(1);
            let prev = stats.max_consecutive.load(Ordering::Relaxed);
            if (consecutive as u64) > prev {
                stats.max_consecutive.store(consecutive as u64, Ordering::Relaxed);
            }
            if !safe_latched && config.max_consecutive_overruns > 0 && consecutive >= config.max_consecutive_overruns {
                safe_latched = true;
                controls.latch();
                stats.watchdog.store(true, Ordering::Relaxed);
                image.set_fault(1);
                image.set_state(STATE_SAFE);
                image.write_outputs(&safe)?;
            }
            deadline = (end / period + 1) * period;
        } else if !safe_latched {
            consecutive = 0;
            deadline = next;
        } else {
            deadline = next;
        }
        stats.scans.fetch_add(1, Ordering::Relaxed);
    }

    if config.stop_safe {
        image.write_outputs(&safe)?;
    }
    image.set_state(STATE_STOPPED);
    image.set_plc_heartbeat(rt::realtime_ns());
    Ok(())
}

fn stop_now(limit: &RunLimit) -> bool {
    limit.stop.load(Ordering::Relaxed) || crate::PROCESS_STOP.load(Ordering::Relaxed)
}

fn bridge_lost(image: &Image, started: u64, timeout_ns: u64) -> bool {
    let hb = image.gateway_heartbeat();
    if hb == 0 {
        return rt::monotonic_ns().saturating_sub(started) > timeout_ns;
    }
    let now = rt::realtime_ns();
    now.saturating_sub(hb) > timeout_ns
}
