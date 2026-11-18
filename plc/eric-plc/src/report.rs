//! Text report from a status JSON file.

use crate::histogram::HistogramSnap;
use crate::stats::StatusFile;
use std::fmt::Write as _;
use std::path::Path;

pub fn render(status: &StatusFile) -> String {
    let mut out = String::new();
    let _ = writeln!(out, "# PLC scan report");
    let _ = writeln!(out);
    let _ = writeln!(out, "Label: {}", status.label);
    let _ = writeln!(out);
    let _ = writeln!(out, "| | |");
    let _ = writeln!(out, "|---|---|");
    let _ = writeln!(out, "| Host | {} |", status.uname);
    let _ = writeln!(out, "| Period | {} |", format_ns(status.period_ns));
    let _ = writeln!(out, "| Elapsed | {} |", format_ns(status.monotonic_elapsed_ns));
    let _ = writeln!(out, "| Scans | {} |", status.scans);
    let _ = writeln!(out, "| Program calls | {} |", status.program_calls);
    let _ = writeln!(out, "| Overruns | {} |", status.overruns);
    let _ = writeln!(out, "| Missed periods | {} |", status.missed_periods);
    let _ = writeln!(out, "| Max consecutive overruns | {} |", status.max_consecutive_overruns);
    let _ = writeln!(out, "| Watchdog tripped | {} |", status.watchdog_tripped);
    let _ = writeln!(out, "| Bridge-loss scans | {} |", status.bridge_lost_scans);
    let _ = writeln!(out, "| Torn image reads | {} |", status.torn_reads);
    let _ = writeln!(out, "| Scans with a bad or unstamped input | {} |", status.bad_input_scans);
    let _ = writeln!(out, "| SCHED_FIFO | {} |", yes(status.rt.fifo, &status.rt.fifo_error));
    let _ = writeln!(out, "| FIFO priority | {} |", status.rt.fifo_priority);
    let _ = writeln!(out, "| mlockall | {} |", yes(status.rt.mlock, &status.rt.mlock_error));
    let cpu = match status.rt.cpu {
        Some(cpu) => cpu.to_string(),
        None if status.rt.cpu_error.is_empty() => "not requested".into(),
        None => format!("not applied ({})", status.rt.cpu_error),
    };
    let _ = writeln!(out, "| CPU pin | {cpu} |");
    let _ = writeln!(out);
    let _ = writeln!(out, "{}", status.percentile_note);
    let _ = writeln!(out);
    section(&mut out, "Start jitter", &status.jitter_ns);
    section(&mut out, "Program execution", &status.exec_ns);
    section(&mut out, "Input staleness", &status.input_staleness_ns);
    out
}

pub fn write_report(status_path: &Path, out_path: &Path) -> Result<(), String> {
    let status = crate::stats::read_status(status_path)?;
    let text = render(&status);
    if let Some(parent) = out_path.parent() {
        std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    std::fs::write(out_path, text).map_err(|e| format!("write {}: {e}", out_path.display()))?;
    Ok(())
}

fn section(out: &mut String, title: &str, hist: &HistogramSnap) {
    let _ = writeln!(out, "## {title}");
    let _ = writeln!(out);
    let _ = writeln!(out, "| p50 | p99 | max | samples |");
    let _ = writeln!(out, "|---|---|---|---|");
    let _ = writeln!(
        out,
        "| {} | {} | {} | {} |",
        format_ns(hist.p50_ns),
        format_ns(hist.p99_ns),
        format_ns(hist.max_ns),
        hist.count
    );
    let _ = writeln!(out);
    if hist.buckets.is_empty() {
        let _ = writeln!(out, "No samples.");
        let _ = writeln!(out);
        return;
    }
    let peak = hist.buckets.iter().map(|b| b.n).max().unwrap_or(1).max(1);
    for bucket in &hist.buckets {
        let width = (bucket.n * 40 / peak) as usize;
        let bar = "#".repeat(width.max(1));
        let edge = if bucket.le_ns == u64::MAX { "inf".into() } else { format_ns(bucket.le_ns) };
        let _ = writeln!(out, "`{edge:>12}` {bar} {}", bucket.n);
    }
    let _ = writeln!(out);
}

fn yes(flag: bool, err: &str) -> String {
    if flag {
        "applied".into()
    } else if err.is_empty() {
        "not requested".into()
    } else {
        format!("not applied ({err})")
    }
}

pub fn format_ns(ns: u64) -> String {
    if ns == 0 {
        return "0".into();
    }
    if ns < 1_000 {
        return format!("{ns} ns");
    }
    if ns < 1_000_000 {
        return format!("{:.1} us", ns as f64 / 1_000.0);
    }
    if ns < 1_000_000_000 {
        return format!("{:.3} ms", ns as f64 / 1_000_000.0);
    }
    format!("{:.3} s", ns as f64 / 1_000_000_000.0)
}

pub fn summary(status: &StatusFile) -> String {
    format!(
        "scans {}  jitter p50 {} p99 {} max {}  exec p50 {} p99 {} max {}  staleness p50 {} p99 {} max {}  overruns {}  watchdog {}",
        status.scans,
        format_ns(status.jitter_ns.p50_ns),
        format_ns(status.jitter_ns.p99_ns),
        format_ns(status.jitter_ns.max_ns),
        format_ns(status.exec_ns.p50_ns),
        format_ns(status.exec_ns.p99_ns),
        format_ns(status.exec_ns.max_ns),
        format_ns(status.input_staleness_ns.p50_ns),
        format_ns(status.input_staleness_ns.p99_ns),
        format_ns(status.input_staleness_ns.max_ns),
        status.overruns,
        status.watchdog_tripped,
    )
}
