//! Status JSON written beside the scan, and again when the scan stops.

use crate::histogram::HistogramSnap;
use crate::rt::RtStatus;
use crate::scan::Stats;
use serde::{Deserialize, Serialize};
use std::path::Path;
use std::sync::atomic::Ordering;

#[derive(Clone)]
pub struct StatusMeta {
    pub label: String,
    pub period_ns: u64,
    pub uname: String,
    pub rt: RtStatus,
    pub started_realtime_ns: u64,
    pub started_monotonic_ns: u64,
}

#[derive(Clone, Serialize, Deserialize)]
pub struct StatusFile {
    pub label: String,
    pub uname: String,
    pub period_ns: u64,
    pub scans: u64,
    pub program_calls: u64,
    pub overruns: u64,
    pub missed_periods: u64,
    pub max_consecutive_overruns: u64,
    pub watchdog_tripped: bool,
    pub bridge_lost_scans: u64,
    pub torn_reads: u64,
    pub bad_input_scans: u64,
    pub monotonic_elapsed_ns: u64,
    pub started_realtime_ns: u64,
    pub finished: bool,
    pub rt: RtStatus,
    pub jitter_ns: HistogramSnap,
    pub exec_ns: HistogramSnap,
    pub input_staleness_ns: HistogramSnap,
    pub percentile_note: String,
}

const NOTE: &str = "p50 and p99 are the upper edge of the histogram bucket that covers that rank. max is the exact sample. Buckets are 100 ns through 100 us, then 1 us through 2 ms.";

pub fn write_status(path: &Path, stats: &Stats, meta: &StatusMeta, finished: bool) -> Result<(), String> {
    let body = StatusFile {
        label: meta.label.clone(),
        uname: meta.uname.clone(),
        period_ns: meta.period_ns,
        scans: stats.scans.load(Ordering::Relaxed),
        program_calls: stats.program_calls.load(Ordering::Relaxed),
        overruns: stats.overruns.load(Ordering::Relaxed),
        missed_periods: stats.missed_periods.load(Ordering::Relaxed),
        max_consecutive_overruns: stats.max_consecutive.load(Ordering::Relaxed),
        watchdog_tripped: stats.watchdog.load(Ordering::Relaxed),
        bridge_lost_scans: stats.bridge_lost_scans.load(Ordering::Relaxed),
        torn_reads: stats.torn_reads.load(Ordering::Relaxed),
        bad_input_scans: stats.bad_input_scans.load(Ordering::Relaxed),
        monotonic_elapsed_ns: crate::rt::monotonic_ns().saturating_sub(meta.started_monotonic_ns),
        started_realtime_ns: meta.started_realtime_ns,
        finished,
        rt: meta.rt.clone(),
        jitter_ns: stats.jitter.snapshot(),
        exec_ns: stats.exec.snapshot(),
        input_staleness_ns: stats.staleness.snapshot(),
        percentile_note: NOTE.to_string(),
    };
    let text = serde_json::to_string_pretty(&body).map_err(|e| e.to_string())?;
    let tmp = path.with_extension("json.tmp");
    std::fs::write(&tmp, &text).map_err(|e| format!("write {}: {e}", tmp.display()))?;
    std::fs::rename(&tmp, path).map_err(|e| format!("rename {}: {e}", path.display()))?;
    Ok(())
}

pub fn read_status(path: &Path) -> Result<StatusFile, String> {
    let text = std::fs::read_to_string(path).map_err(|e| format!("read {}: {e}", path.display()))?;
    serde_json::from_str(&text).map_err(|e| format!("status {}: {e}", path.display()))
}
