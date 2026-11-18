//! Scheduling for the scan thread. Failures are recorded and the scan still
//! runs, so a machine that denies real-time priority produces a report
//! instead of refusing to start.
//!
//! Linux uses `SCHED_FIFO`, `mlockall`, and `clock_nanosleep`. Windows uses a
//! high-resolution waitable timer, `timeBeginPeriod`, affinity, and
//! `REALTIME_PRIORITY_CLASS` with a fallback to `HIGH_PRIORITY_CLASS`.

#[cfg(unix)]
use std::io::Error;
#[cfg(unix)]
use std::mem;

#[derive(Clone, Debug, serde::Serialize, serde::Deserialize)]
pub struct RtStatus {
    pub fifo: bool,
    pub fifo_priority: i32,
    pub fifo_error: String,
    pub mlock: bool,
    pub mlock_error: String,
    pub cpu: Option<usize>,
    #[serde(default)]
    pub cpus: Vec<usize>,
    pub cpu_error: String,
}

pub fn apply(fifo_priority: i32, cpus: &[usize], mlock: bool) -> RtStatus {
    let mut status = RtStatus {
        fifo: false,
        fifo_priority: 0,
        fifo_error: String::new(),
        mlock: false,
        mlock_error: String::new(),
        cpu: None,
        cpus: Vec::new(),
        cpu_error: String::new(),
    };
    if !cpus.is_empty() {
        match pin_all(cpus) {
            Ok(()) => {
                status.cpu = cpus.first().copied();
                status.cpus = cpus.to_vec();
            }
            Err(err) => status.cpu_error = err,
        }
    }
    if fifo_priority > 0 {
        match fifo(fifo_priority) {
            Ok(applied) => {
                status.fifo = true;
                status.fifo_priority = applied;
                // Priority 1 is the Windows HIGH_PRIORITY_CLASS fallback. Linux priorities are the SCHED_FIFO value.
                if cfg!(windows) && applied == 1 {
                    status.fifo_error = "REALTIME_PRIORITY_CLASS was denied; the scan thread is HIGH_PRIORITY_CLASS".into();
                }
            }
            Err(err) => status.fifo_error = err,
        }
    }
    if mlock {
        match lock_memory() {
            Ok(()) => status.mlock = true,
            Err(err) => status.mlock_error = err,
        }
    }
    status
}

#[cfg(unix)]
fn pin_all(cpus: &[usize]) -> Result<(), String> {
    unsafe {
        let mut set: libc::cpu_set_t = mem::zeroed();
        for cpu in cpus {
            if *cpu > 4096 {
                return Err(format!("cpu {cpu} is out of range"));
            }
            libc::CPU_SET(*cpu, &mut set);
        }
        let rc = libc::sched_setaffinity(0, mem::size_of::<libc::cpu_set_t>(), &set);
        if rc != 0 {
            return Err(Error::last_os_error().to_string());
        }
    }
    Ok(())
}

#[cfg(unix)]
fn fifo(requested: i32) -> Result<i32, String> {
    unsafe {
        let max = libc::sched_get_priority_max(libc::SCHED_FIFO);
        let min = libc::sched_get_priority_min(libc::SCHED_FIFO);
        if max < 0 || min < 0 {
            return Err(Error::last_os_error().to_string());
        }
        let priority = requested.clamp(min, max);
        let param = libc::sched_param { sched_priority: priority };
        let rc = libc::sched_setscheduler(0, libc::SCHED_FIFO, &param);
        if rc != 0 {
            return Err(Error::last_os_error().to_string());
        }
        Ok(priority)
    }
}

#[cfg(unix)]
fn lock_memory() -> Result<(), String> {
    unsafe {
        let rc = libc::mlockall(libc::MCL_CURRENT | libc::MCL_FUTURE);
        if rc != 0 {
            return Err(Error::last_os_error().to_string());
        }
    }
    let ballast = vec![0u8; 64 * 1024];
    std::hint::black_box(&ballast);
    Ok(())
}

#[cfg(unix)]
pub fn monotonic_ns() -> u64 {
    let mut ts = libc::timespec { tv_sec: 0, tv_nsec: 0 };
    unsafe {
        libc::clock_gettime(libc::CLOCK_MONOTONIC, &mut ts);
    }
    ts.tv_sec as u64 * 1_000_000_000 + ts.tv_nsec as u64
}

#[cfg(unix)]
pub fn realtime_ns() -> u64 {
    let mut ts = libc::timespec { tv_sec: 0, tv_nsec: 0 };
    unsafe {
        libc::clock_gettime(libc::CLOCK_REALTIME, &mut ts);
    }
    ts.tv_sec as u64 * 1_000_000_000 + ts.tv_nsec as u64
}

/// Sleep until an absolute `CLOCK_MONOTONIC` time. A signal retries the wait
/// unless `stop` is already set.
#[cfg(unix)]
pub fn sleep_until(deadline_ns: u64, stop: &std::sync::atomic::AtomicBool) {
    use std::sync::atomic::Ordering;
    let ts = libc::timespec {
        tv_sec: (deadline_ns / 1_000_000_000) as libc::time_t,
        tv_nsec: (deadline_ns % 1_000_000_000) as libc::c_long,
    };
    loop {
        if stop.load(Ordering::Relaxed) || crate::PROCESS_STOP.load(Ordering::Relaxed) {
            return;
        }
        let rc = unsafe {
            libc::clock_nanosleep(libc::CLOCK_MONOTONIC, libc::TIMER_ABSTIME, &ts, std::ptr::null_mut())
        };
        if rc == 0 || rc != libc::EINTR {
            return;
        }
    }
}

#[cfg(unix)]
pub fn uname_line() -> String {
    unsafe {
        let mut buf: libc::utsname = mem::zeroed();
        if libc::uname(&mut buf) != 0 {
            return "unknown".into();
        }
        let sys = cstr(&buf.sysname);
        let rel = cstr(&buf.release);
        let mach = cstr(&buf.machine);
        format!("{sys} {rel} {mach}")
    }
}

#[cfg(unix)]
fn cstr(raw: &[libc::c_char]) -> String {
    let bytes = raw.iter().take_while(|c| **c != 0).map(|c| *c as u8).collect::<Vec<_>>();
    String::from_utf8_lossy(&bytes).into_owned()
}

#[cfg(windows)]
fn pin_all(cpus: &[usize]) -> Result<(), String> {
    if cpus.iter().any(|cpu| *cpu >= 64) {
        return Err("a cpu at or above 64 needs a processor group, which this preview does not set".into());
    }
    let mask = cpus.iter().fold(0usize, |bits, cpu| bits | (1usize << cpu));
    unsafe {
        let process = crate::win::GetCurrentProcess();
        let thread = crate::win::GetCurrentThread();
        if crate::win::SetProcessAffinityMask(process, mask) == 0 {
            return Err(crate::win::last_error("process affinity"));
        }
        if crate::win::SetThreadAffinityMask(thread, mask) == 0 {
            return Err(crate::win::last_error("thread affinity"));
        }
        let ideal = cpus[0] as u32;
        if crate::win::SetThreadIdealProcessor(thread, ideal) == u32::MAX {
            return Err(crate::win::last_error("ideal processor"));
        }
    }
    Ok(())
}

#[cfg(windows)]
fn fifo(requested: i32) -> Result<i32, String> {
    let _ = requested;
    unsafe {
        let process = crate::win::GetCurrentProcess();
        let thread = crate::win::GetCurrentThread();
        // REALTIME_PRIORITY_CLASS, then HIGH_PRIORITY_CLASS if that is denied.
        disable_power_throttling();
        if crate::win::SetPriorityClass(process, 0x00000100) == 0 {
            if crate::win::SetPriorityClass(process, 0x00000080) == 0 {
                return Err(crate::win::last_error("priority class"));
            }
            if crate::win::SetThreadPriority(thread, 15) == 0 {
                return Err(crate::win::last_error("thread priority"));
            }
            return Ok(1);
        }
        if crate::win::SetThreadPriority(thread, 15) == 0 {
            return Err(crate::win::last_error("thread priority"));
        }
        Ok(15)
    }
}

#[cfg(windows)]
fn disable_power_throttling() {
    unsafe {
        let mut state = crate::win::PowerThrottle { version: 1, control: 0x1, state: 0 };
        crate::win::SetProcessInformation(
            crate::win::GetCurrentProcess(),
            4,
            &mut state as *mut crate::win::PowerThrottle as *mut std::ffi::c_void,
            std::mem::size_of::<crate::win::PowerThrottle>() as u32,
        );
    }
}

#[cfg(windows)]
fn lock_memory() -> Result<(), String> {
    let requested = crate::resources::assigned_memory_bytes();
    let bytes = if requested == 0 { 64 * 1024 * 1024 } else { requested.min(256 * 1024 * 1024) as usize };
    let mut ballast = vec![0u8; bytes];
    let locked = unsafe { crate::win::VirtualLock(ballast.as_mut_ptr() as *const std::ffi::c_void, ballast.len()) };
    std::hint::black_box(&ballast);
    unsafe {
        // QUOTA_LIMITS_HARDWS_MIN_ENABLE. The minimum is the locked size.
        crate::win::SetProcessWorkingSetSizeEx(
            crate::win::GetCurrentProcess(),
            ballast.len(),
            ballast.len().saturating_mul(2),
            0x1,
        );
    }
    if locked == 0 {
        return Err(crate::win::last_error("VirtualLock"));
    }
    // Keep the ballast mapped for the life of the process.
    std::mem::forget(ballast);
    Ok(())
}

#[cfg(windows)]
pub fn monotonic_ns() -> u64 {
    use std::sync::OnceLock;
    static FREQ: OnceLock<u64> = OnceLock::new();
    let freq = *FREQ.get_or_init(|| {
        let mut hz = 0i64;
        unsafe { crate::win::QueryPerformanceFrequency(&mut hz) };
        hz.max(1) as u64
    });
    let mut counter = 0i64;
    unsafe { crate::win::QueryPerformanceCounter(&mut counter) };
    ((counter.max(0) as u128) * 1_000_000_000 / freq as u128) as u64
}

#[cfg(windows)]
pub fn realtime_ns() -> u64 {
    let mut file = crate::win::FileTime { low: 0, high: 0 };
    unsafe { crate::win::GetSystemTimePreciseAsFileTime(&mut file) };
    let ticks = (file.high as u64) << 32 | file.low as u64;
    ticks.saturating_mul(100).saturating_sub(11_644_473_600_000_000_000)
}

#[cfg(windows)]
pub fn sleep_until(deadline_ns: u64, stop: &std::sync::atomic::AtomicBool) {
    use std::sync::atomic::Ordering;
    use std::sync::OnceLock;
    static TIMER: OnceLock<isize> = OnceLock::new();
    unsafe { crate::win::timeBeginPeriod(1) };
    let timer = *TIMER.get_or_init(|| unsafe {
        crate::win::CreateWaitableTimerExW(std::ptr::null_mut(), std::ptr::null(), 0x2, 0x1F0003)
    });
    if stop.load(Ordering::Relaxed) || crate::PROCESS_STOP.load(Ordering::Relaxed) {
        return;
    }
    let now = monotonic_ns();
    if now >= deadline_ns {
        return;
    }
    if timer == 0 || timer == -1 {
        std::thread::sleep(std::time::Duration::from_nanos(deadline_ns - now));
        return;
    }
    let due = -(((deadline_ns - now) / 100).max(1) as i64);
    unsafe {
        if crate::win::SetWaitableTimer(timer, &due, 0, std::ptr::null_mut(), std::ptr::null_mut(), 0) == 0 {
            std::thread::sleep(std::time::Duration::from_nanos(deadline_ns - now));
            return;
        }
        crate::win::WaitForSingleObject(timer, 0xFFFF_FFFF);
    }
}

#[cfg(windows)]
pub fn uname_line() -> String {
    format!("Windows {} CI-capable, not a real-time kernel", std::env::consts::ARCH)
}
