//! Win32 calls used by the Windows scan clock, the file mapping, and the pipe.
//! This module is compiled only on Windows. The on-disk image layout stays the
//! one `image.rs` shares with the gateway.

#![allow(non_snake_case)]

use std::ffi::c_void;

pub type HANDLE = isize;

#[repr(C)]
pub struct Overlapped {
    pub internal: usize,
    pub internal_high: usize,
    pub offset: u32,
    pub offset_high: u32,
    pub event: HANDLE,
}

#[repr(C)]
pub struct FileTime {
    pub low: u32,
    pub high: u32,
}

#[repr(C)]
pub struct PowerThrottle {
    pub version: u32,
    pub control: u32,
    pub state: u32,
}

#[link(name = "kernel32")]
extern "system" {
    pub fn GetLastError() -> u32;
    pub fn CreateFileMappingW(file: HANDLE, sec: *mut c_void, protect: u32, max_high: u32, max_low: u32, name: *const u16) -> HANDLE;
    pub fn MapViewOfFile(mapping: HANDLE, access: u32, off_high: u32, off_low: u32, bytes: usize) -> *mut c_void;
    pub fn UnmapViewOfFile(base: *const c_void) -> i32;
    pub fn CloseHandle(handle: HANDLE) -> i32;
    pub fn LockFileEx(file: HANDLE, flags: u32, reserved: u32, low: u32, high: u32, overlapped: *mut Overlapped) -> i32;
    pub fn UnlockFileEx(file: HANDLE, reserved: u32, low: u32, high: u32, overlapped: *mut Overlapped) -> i32;
    pub fn QueryPerformanceCounter(out: *mut i64) -> i32;
    pub fn QueryPerformanceFrequency(out: *mut i64) -> i32;
    pub fn GetSystemTimePreciseAsFileTime(out: *mut FileTime);
    pub fn CreateWaitableTimerExW(sec: *mut c_void, name: *const u16, flags: u32, access: u32) -> HANDLE;
    pub fn SetWaitableTimer(timer: HANDLE, due: *const i64, period: i32, routine: *mut c_void, arg: *mut c_void, resume: i32) -> i32;
    pub fn WaitForSingleObject(handle: HANDLE, ms: u32) -> u32;
    pub fn GetCurrentProcess() -> HANDLE;
    pub fn GetCurrentThread() -> HANDLE;
    pub fn SetPriorityClass(process: HANDLE, class: u32) -> i32;
    pub fn SetThreadPriority(thread: HANDLE, priority: i32) -> i32;
    pub fn SetProcessAffinityMask(process: HANDLE, mask: usize) -> i32;
    pub fn SetThreadAffinityMask(thread: HANDLE, mask: usize) -> usize;
    pub fn SetThreadIdealProcessor(thread: HANDLE, cpu: u32) -> u32;
    pub fn VirtualLock(addr: *const c_void, size: usize) -> i32;
    pub fn SetProcessWorkingSetSizeEx(process: HANDLE, min: usize, max: usize, flags: u32) -> i32;
    pub fn SetProcessInformation(process: HANDLE, class: i32, info: *mut c_void, size: u32) -> i32;
    pub fn CreateNamedPipeW(name: *const u16, access: u32, mode: u32, instances: u32, out_buf: u32, in_buf: u32, timeout: u32, sec: *mut c_void) -> HANDLE;
    pub fn ConnectNamedPipe(pipe: HANDLE, overlapped: *mut c_void) -> i32;
    pub fn DisconnectNamedPipe(pipe: HANDLE) -> i32;
    pub fn ReadFile(file: HANDLE, buf: *mut c_void, bytes: u32, read: *mut u32, overlapped: *mut c_void) -> i32;
    pub fn WriteFile(file: HANDLE, buf: *const c_void, bytes: u32, written: *mut u32, overlapped: *mut c_void) -> i32;
    pub fn FlushFileBuffers(file: HANDLE) -> i32;
    pub fn SetConsoleCtrlHandler(handler: Option<unsafe extern "system" fn(u32) -> i32>, add: i32) -> i32;
}

#[link(name = "winmm")]
extern "system" {
    pub fn timeBeginPeriod(period: u32) -> u32;
}

pub fn wide(text: &str) -> Vec<u16> {
    text.encode_utf16().chain(std::iter::once(0)).collect()
}

pub fn last_error(what: &str) -> String {
    let code = unsafe { GetLastError() };
    format!("{what} (win32 {code})")
}

#[allow(dead_code)]
fn _keep_libc() -> libc::c_int {
    0
}

pub fn overlapped() -> Overlapped {
    Overlapped { internal: 0, internal_high: 0, offset: 0, offset_high: 0, event: 0 }
}
