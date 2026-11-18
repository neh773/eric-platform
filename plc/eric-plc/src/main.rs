//! `eric-plc run|sim|stats|report`

use eric_plc::config::Config;
use eric_plc::report::{self, summary};
use eric_plc::scan::{self, RunLimit};
use eric_plc::sim;
use eric_plc::PROCESS_STOP;
use std::env;
use std::process::ExitCode;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

fn main() -> ExitCode {
    match dispatch(env::args().skip(1).collect()) {
        Ok(()) => ExitCode::SUCCESS,
        Err(err) => {
            eprintln!("eric-plc: {err}");
            ExitCode::from(1)
        }
    }
}

fn dispatch(args: Vec<String>) -> Result<(), String> {
    let cmd = args.first().map(String::as_str).unwrap_or("help");
    match cmd {
        "help" | "-h" | "--help" => {
            print_help();
            Ok(())
        }
        "run" => cmd_run(&args[1..]),
        "sim" => cmd_sim(&args[1..]),
        "stats" => cmd_stats(&args[1..]),
        "report" => cmd_report(&args[1..]),
        other => Err(format!("unknown command {other}")),
    }
}

fn cmd_run(args: &[String]) -> Result<(), String> {
    let config_path = flag(args, "--config")?;
    let label = optional(args, "--label");
    let duration = duration_flag(args)?;
    let config = Config::load(std::path::Path::new(&config_path))?.with_label(label);
    install_signals();
    let stop = Arc::new(AtomicBool::new(false));
    let report = scan::run(
        &config,
        &RunLimit { stop, max_scans: None, max_duration: duration },
    )?;
    if report.watchdog {
        eprintln!("eric-plc: watchdog latched the safe state");
    }
    Ok(())
}

fn cmd_sim(args: &[String]) -> Result<(), String> {
    let config_path = flag(args, "--config")?;
    let duration = duration_flag(args)?.unwrap_or(Duration::from_secs(60 * 60));
    let expect_motor = args.iter().any(|arg| arg == "--expect-motor");
    let config = Config::load(std::path::Path::new(&config_path))?;
    install_signals();
    let stop = AtomicBool::new(false);
    sim::run_sim(&config, duration, &stop, expect_motor)
}

fn cmd_stats(args: &[String]) -> Result<(), String> {
    let path = flag(args, "--status")?;
    let status = eric_plc::stats::read_status(std::path::Path::new(&path))?;
    println!("{}", summary(&status));
    println!("label: {}", status.label);
    Ok(())
}

fn cmd_report(args: &[String]) -> Result<(), String> {
    let status = flag(args, "--status")?;
    let out = flag(args, "--out")?;
    report::write_report(std::path::Path::new(&status), std::path::Path::new(&out))
}

fn flag(args: &[String], name: &str) -> Result<String, String> {
    optional(args, name).ok_or_else(|| format!("missing {name}"))
}

fn optional(args: &[String], name: &str) -> Option<String> {
    args.iter().position(|arg| arg == name).and_then(|index| args.get(index + 1)).cloned()
}

fn duration_flag(args: &[String]) -> Result<Option<Duration>, String> {
    let Some(raw) = optional(args, "--duration-ms") else {
        return Ok(None);
    };
    let ms: u64 = raw.parse().map_err(|_| "--duration-ms must be a number".to_string())?;
    if ms == 0 {
        return Ok(None);
    }
    Ok(Some(Duration::from_millis(ms)))
}

fn install_signals() {
    #[cfg(unix)]
    unsafe {
        libc::signal(libc::SIGINT, handle as *const () as libc::sighandler_t);
        libc::signal(libc::SIGTERM, handle as *const () as libc::sighandler_t);
    }
    #[cfg(windows)]
    unsafe {
        eric_plc::win::SetConsoleCtrlHandler(Some(win_handle), 1);
    }
}

#[cfg(unix)]
extern "C" fn handle(_signal: libc::c_int) {
    PROCESS_STOP.store(true, Ordering::Relaxed);
}

#[cfg(windows)]
unsafe extern "system" fn win_handle(_ctrl: u32) -> i32 {
    PROCESS_STOP.store(true, Ordering::Relaxed);
    1
}

fn print_help() {
    eprintln!(
        "\
eric-plc run --config plc.toml [--duration-ms N] [--label TEXT]
eric-plc sim --config plc.toml [--duration-ms N] [--expect-motor]
eric-plc stats --status status.json
eric-plc report --status status.json --out report.md"
    );
}
