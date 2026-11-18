//! Shared process image. One writer per region, readers use a seqlock.
//!
//! The gateway is the only writer of the input region and of `gateway_heartbeat`.
//! The scan thread is the only writer of the output region, `plc_heartbeat`,
//! and the state words. A Unix socket would put a syscall on the scan path;
//! this file keeps that path to memory plus `clock_nanosleep`.
//!
//! Layout (little-endian), also implemented by `PlcImage.kt`. Change both together.
//!
//! Header, 128 bytes:
//!   0  u32 magic `ERPC` (bytes 45 52 50 43)
//!   4  u32 version = 1
//!   8  u32 header_bytes = 128
//!  12  u32 slot_bytes = 32
//!  16  u32 input_count
//!  20  u32 output_count
//!  32  u64 input_seq     seqlock, even means stable
//!  40  u64 output_seq
//!  48  u64 gateway_heartbeat_ns   CLOCK_REALTIME
//!  56  u64 plc_heartbeat_ns       CLOCK_REALTIME
//!  64  u32 plc_state     0 stopped, 1 running, 2 safe, 3 bridge hold
//!  68  u32 plc_fault     0 none, 1 watchdog
//!  72  u32 bridge_lost   0 or 1
//!  80  u64 period_ns
//!
//! Then `input_count` slots, then `output_count` slots. Each slot is 32 bytes:
//!   0  u8  type     1 BOOL, 2 INT, 3 DINT, 4 REAL, 5 LREAL
//!   1  u8  quality  0 good, 1 bad
//!   8  u64 value bits
//!  16  i64 source timestamp, CLOCK_REALTIME nanoseconds, 0 if none

use std::fs::OpenOptions;
#[cfg(unix)]
use std::os::unix::io::AsRawFd;
#[cfg(windows)]
use std::os::windows::fs::OpenOptionsExt;
#[cfg(windows)]
use std::os::windows::io::AsRawHandle;
use std::path::Path;
use std::sync::atomic::{AtomicU32, AtomicU64, Ordering};

pub const MAGIC: u32 = 0x4350_5245;
pub const VERSION: u32 = 1;
pub const HEADER_BYTES: usize = 128;
pub const SLOT_BYTES: usize = 32;

pub const OFF_MAGIC: usize = 0;
pub const OFF_VERSION: usize = 4;
pub const OFF_HEADER: usize = 8;
pub const OFF_SLOT: usize = 12;
pub const OFF_INPUTS: usize = 16;
pub const OFF_OUTPUTS: usize = 20;
pub const OFF_INPUT_SEQ: usize = 32;
pub const OFF_OUTPUT_SEQ: usize = 40;
pub const OFF_GW_HB: usize = 48;
pub const OFF_PLC_HB: usize = 56;
pub const OFF_STATE: usize = 64;
pub const OFF_FAULT: usize = 68;
pub const OFF_BRIDGE: usize = 72;
pub const OFF_PERIOD: usize = 80;

pub const SLOT_TYPE: usize = 0;
pub const SLOT_QUALITY: usize = 1;
pub const SLOT_VALUE: usize = 8;
pub const SLOT_SOURCE: usize = 16;

pub const STATE_STOPPED: u32 = 0;
pub const STATE_RUNNING: u32 = 1;
pub const STATE_SAFE: u32 = 2;
pub const STATE_HOLD: u32 = 3;

pub const TYPE_BOOL: u8 = 1;
pub const TYPE_INT: u8 = 2;
pub const TYPE_DINT: u8 = 3;
pub const TYPE_REAL: u8 = 4;
pub const TYPE_LREAL: u8 = 5;

const SPINS: u32 = 10_000;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Slot {
    pub type_code: u8,
    pub quality: u8,
    pub value: u64,
    pub source_ns: i64,
}

pub struct Image {
    fd: std::fs::File,
    base: *mut u8,
    len: usize,
    /// Named mapping handle on Windows. Zero on Linux. Kept open so the name stays alive.
    mapping: isize,
    pub inputs: u32,
    pub outputs: u32,
}

#[cfg(unix)]
fn record_lock(fd: i32, exclusive: bool) -> Result<(), String> {
    let mut fl: libc::flock = unsafe { std::mem::zeroed() };
    fl.l_type = (if exclusive { libc::F_WRLCK } else { libc::F_UNLCK }) as libc::c_short;
    fl.l_whence = libc::SEEK_SET as libc::c_short;
    let rc = unsafe { libc::syscall(libc::SYS_fcntl, fd, libc::F_SETLKW, &fl as *const libc::flock) };
    if rc < 0 {
        Err(std::io::Error::last_os_error().to_string())
    } else {
        Ok(())
    }
}

fn lock_image(file: &std::fs::File, len: usize, exclusive: bool) -> Result<(), String> {
    #[cfg(unix)]
    {
        let _ = len;
        record_lock(file.as_raw_fd(), exclusive)
    }
    #[cfg(windows)]
    {
        let handle = file.as_raw_handle() as isize;
        let mut overlapped = crate::win::overlapped();
        let flags = if exclusive { 0x2u32 } else { 0 };
        let rc = if exclusive {
            unsafe { crate::win::LockFileEx(handle, flags, 0, len as u32, (len >> 32) as u32, &mut overlapped) }
        } else {
            unsafe { crate::win::UnlockFileEx(handle, 0, len as u32, (len >> 32) as u32, &mut overlapped) }
        };
        if rc == 0 { Err(crate::win::last_error("file lock")) } else { Ok(()) }
    }
}

/// Map the file. On Windows the mapping is also published under `Local\ERIC-PLC-<file>`.
fn map_image(file: &std::fs::File, path: &Path, len: usize) -> Result<(*mut u8, isize), String> {
    #[cfg(unix)]
    {
        let base = unsafe {
            libc::mmap(
                std::ptr::null_mut(),
                len,
                libc::PROT_READ | libc::PROT_WRITE,
                libc::MAP_SHARED,
                file.as_raw_fd(),
                0,
            )
        };
        if base == libc::MAP_FAILED {
            return Err(format!("map {}: {}", path.display(), std::io::Error::last_os_error()));
        }
        Ok((base as *mut u8, 0))
    }
    #[cfg(windows)]
    {
        let handle = file.as_raw_handle() as isize;
        let stem = path.file_name().and_then(|name| name.to_str()).unwrap_or("image");
        let safe: String = stem.chars().map(|ch| if ch.is_ascii_alphanumeric() { ch } else { '-' }).collect();
        let name = crate::win::wide(&format!("Local\\ERIC-PLC-{safe}"));
        let mapping = unsafe { crate::win::CreateFileMappingW(handle, std::ptr::null_mut(), 0x04, 0, 0, name.as_ptr()) };
        if mapping == 0 || mapping == -1 {
            return Err(crate::win::last_error(&format!("map {}", path.display())));
        }
        let view = unsafe { crate::win::MapViewOfFile(mapping, 0x0002 | 0x0004, 0, 0, len) };
        if view.is_null() {
            let err = crate::win::last_error(&format!("view {}", path.display()));
            unsafe { crate::win::CloseHandle(mapping) };
            return Err(err);
        }
        Ok((view as *mut u8, mapping))
    }
}

unsafe impl Send for Image {}
unsafe impl Sync for Image {}

impl Drop for Image {
    fn drop(&mut self) {
        #[cfg(unix)]
        unsafe {
            let _ = self.mapping;
            libc::munmap(self.base as *mut libc::c_void, self.len);
        }
        #[cfg(windows)]
        unsafe {
            let _ = self.len;
            crate::win::UnmapViewOfFile(self.base as *const std::ffi::c_void);
            if self.mapping != 0 {
                crate::win::CloseHandle(self.mapping);
            }
        }
    }
}

impl Image {
    pub fn open(path: &Path, inputs: u32, outputs: u32, period_ns: u64) -> Result<Self, String> {
        if inputs > 4096 || outputs > 4096 {
            return Err("image supports at most 4096 inputs and 4096 outputs".into());
        }
        let len = HEADER_BYTES + (inputs as usize + outputs as usize) * SLOT_BYTES;
        let mut options = OpenOptions::new();
        options.read(true).write(true).create(true);
        // The gateway maps the same file. It has to be allowed to open it while this process holds it.
        #[cfg(windows)]
        options.share_mode(0x1 | 0x2 | 0x4);
        let file = options.open(path).map_err(|e| format!("open {}: {e}", path.display()))?;
        lock_image(&file, len, true).map_err(|e| format!("lock {}: {e}", path.display()))?;
        let meta = file.metadata().map_err(|e| e.to_string())?;
        if meta.len() < len as u64 {
            file.set_len(len as u64).map_err(|e| format!("size {}: {e}", path.display()))?;
        }
        let mapped = match map_image(&file, path, len) {
            Ok(mapped) => mapped,
            Err(err) => {
                let _ = lock_image(&file, len, false);
                return Err(err);
            }
        };
        let image = Image { fd: file, base: mapped.0, len, mapping: mapped.1, inputs, outputs };
        let ready = image.init_header(period_ns)?;
        let _ = lock_image(&image.fd, len, false);
        if !ready {
            return Err(format!("image {} has a different layout", path.display()));
        }
        Ok(image)
    }

    fn init_header(&self, period_ns: u64) -> Result<bool, String> {
        let magic = self.load_u32(OFF_MAGIC);
        if magic == 0 {
            self.store_u32(OFF_VERSION, VERSION);
            self.store_u32(OFF_HEADER, HEADER_BYTES as u32);
            self.store_u32(OFF_SLOT, SLOT_BYTES as u32);
            self.store_u32(OFF_INPUTS, self.inputs);
            self.store_u32(OFF_OUTPUTS, self.outputs);
            self.store_u64(OFF_PERIOD, period_ns);
            self.store_u64(OFF_INPUT_SEQ, 0);
            self.store_u64(OFF_OUTPUT_SEQ, 0);
            for index in 0..self.inputs {
                self.poke_quality(self.input_offset(index), 1);
            }
            for index in 0..self.outputs {
                self.poke_quality(self.output_offset(index), 1);
            }
            self.atomic_u32(OFF_MAGIC).store(MAGIC, Ordering::Release);
            return Ok(true);
        }
        if magic != MAGIC {
            return Ok(false);
        }
        let ok = self.load_u32(OFF_VERSION) == VERSION
            && self.load_u32(OFF_HEADER) == HEADER_BYTES as u32
            && self.load_u32(OFF_SLOT) == SLOT_BYTES as u32
            && self.load_u32(OFF_INPUTS) == self.inputs
            && self.load_u32(OFF_OUTPUTS) == self.outputs;
        Ok(ok)
    }

    pub fn write_inputs(&self, slots: &[Slot]) -> Result<(), String> {
        self.write_slots(OFF_INPUT_SEQ, true, slots)
    }

    pub fn read_inputs(&self, out: &mut [Slot]) -> Result<(), String> {
        self.read_slots(OFF_INPUT_SEQ, true, out)
    }

    pub fn write_outputs(&self, slots: &[Slot]) -> Result<(), String> {
        self.write_slots(OFF_OUTPUT_SEQ, false, slots)
    }

    pub fn read_outputs(&self, out: &mut [Slot]) -> Result<(), String> {
        self.read_slots(OFF_OUTPUT_SEQ, false, out)
    }

    pub fn set_gateway_heartbeat(&self, ns: u64) {
        self.atomic_u64(OFF_GW_HB).store(ns, Ordering::Release);
    }

    pub fn gateway_heartbeat(&self) -> u64 {
        self.atomic_u64(OFF_GW_HB).load(Ordering::Acquire)
    }

    pub fn set_plc_heartbeat(&self, ns: u64) {
        self.atomic_u64(OFF_PLC_HB).store(ns, Ordering::Release);
    }

    pub fn plc_heartbeat(&self) -> u64 {
        self.atomic_u64(OFF_PLC_HB).load(Ordering::Acquire)
    }

    pub fn set_state(&self, state: u32) {
        self.atomic_u32(OFF_STATE).store(state, Ordering::Release);
    }

    pub fn state(&self) -> u32 {
        self.atomic_u32(OFF_STATE).load(Ordering::Acquire)
    }

    pub fn set_fault(&self, fault: u32) {
        self.atomic_u32(OFF_FAULT).store(fault, Ordering::Release);
    }

    pub fn fault(&self) -> u32 {
        self.atomic_u32(OFF_FAULT).load(Ordering::Acquire)
    }

    pub fn set_bridge_lost(&self, lost: bool) {
        self.atomic_u32(OFF_BRIDGE).store(u32::from(lost), Ordering::Release);
    }

    pub fn bridge_lost(&self) -> bool {
        self.atomic_u32(OFF_BRIDGE).load(Ordering::Acquire) != 0
    }

    /// Test aid: leave the input seqlock odd so a reader must give up.
    pub fn leave_input_seq_odd(&self) {
        let seq = self.atomic_u64(OFF_INPUT_SEQ);
        if seq.load(Ordering::Relaxed) % 2 == 0 {
            seq.fetch_add(1, Ordering::Release);
        }
    }

    fn write_slots(&self, seq_off: usize, inputs: bool, slots: &[Slot]) -> Result<(), String> {
        let expect = if inputs { self.inputs } else { self.outputs };
        if slots.len() != expect as usize {
            return Err(format!("slot count {} != {expect}", slots.len()));
        }
        let seq = self.atomic_u64(seq_off);
        let seen = seq.fetch_add(1, Ordering::Release);
        if seen % 2 == 1 {
            return Err("seqlock writer overlapped".into());
        }
        std::sync::atomic::fence(Ordering::Release);
        for (index, slot) in slots.iter().enumerate() {
            let off = if inputs { self.input_offset(index as u32) } else { self.output_offset(index as u32) };
            self.store_slot(off, slot);
        }
        std::sync::atomic::fence(Ordering::Release);
        seq.fetch_add(1, Ordering::Release);
        Ok(())
    }

    fn read_slots(&self, seq_off: usize, inputs: bool, out: &mut [Slot]) -> Result<(), String> {
        let expect = if inputs { self.inputs } else { self.outputs };
        if out.len() != expect as usize {
            return Err(format!("slot count {} != {expect}", out.len()));
        }
        let seq = self.atomic_u64(seq_off);
        for _ in 0..SPINS {
            let s1 = seq.load(Ordering::Acquire);
            if s1 % 2 == 1 {
                std::hint::spin_loop();
                continue;
            }
            std::sync::atomic::fence(Ordering::Acquire);
            for (index, slot) in out.iter_mut().enumerate() {
                let off = if inputs { self.input_offset(index as u32) } else { self.output_offset(index as u32) };
                *slot = self.load_slot(off);
            }
            std::sync::atomic::fence(Ordering::Acquire);
            let s2 = seq.load(Ordering::Acquire);
            if s1 == s2 {
                return Ok(());
            }
        }
        Err("image torn".into())
    }

    fn store_slot(&self, off: usize, slot: &Slot) {
        let head = u32::from(slot.type_code) | (u32::from(slot.quality) << 8);
        self.atomic_u32(off).store(head, Ordering::Relaxed);
        self.atomic_u64(off + SLOT_VALUE).store(slot.value, Ordering::Relaxed);
        self.atomic_u64(off + SLOT_SOURCE).store(slot.source_ns as u64, Ordering::Relaxed);
    }

    fn load_slot(&self, off: usize) -> Slot {
        let head = self.atomic_u32(off).load(Ordering::Relaxed);
        Slot {
            type_code: (head & 0xff) as u8,
            quality: ((head >> 8) & 0xff) as u8,
            value: self.atomic_u64(off + SLOT_VALUE).load(Ordering::Relaxed),
            source_ns: self.atomic_u64(off + SLOT_SOURCE).load(Ordering::Relaxed) as i64,
        }
    }

    fn poke_quality(&self, off: usize, quality: u8) {
        self.atomic_u32(off).store(u32::from(quality) << 8, Ordering::Relaxed);
    }

    fn input_offset(&self, index: u32) -> usize {
        HEADER_BYTES + index as usize * SLOT_BYTES
    }

    fn output_offset(&self, index: u32) -> usize {
        HEADER_BYTES + (self.inputs as usize + index as usize) * SLOT_BYTES
    }

    fn atomic_u64(&self, off: usize) -> &AtomicU64 {
        debug_assert!(off % 8 == 0);
        unsafe { &*(self.base.add(off) as *const AtomicU64) }
    }

    fn atomic_u32(&self, off: usize) -> &AtomicU32 {
        debug_assert!(off % 4 == 0);
        unsafe { &*(self.base.add(off) as *const AtomicU32) }
    }

    fn load_u32(&self, off: usize) -> u32 {
        self.atomic_u32(off).load(Ordering::Acquire)
    }

    fn store_u32(&self, off: usize, value: u32) {
        self.atomic_u32(off).store(value, Ordering::Relaxed);
    }

    fn store_u64(&self, off: usize, value: u64) {
        self.atomic_u64(off).store(value, Ordering::Relaxed);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn temp_path(name: &str) -> std::path::PathBuf {
        let nanos = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
        std::env::temp_dir().join(format!("eric-plc-{name}-{nanos}-{}", std::process::id()))
    }

    #[test]
    fn header_prefix_matches_the_gateway() {
        let path = temp_path("hdr");
        let image = Image::open(&path, 1, 1, 10_000_000).unwrap();
        let bytes = unsafe { std::slice::from_raw_parts(image.base, 16) };
        assert_eq!(&bytes[..16], &[0x45, 0x52, 0x50, 0x43, 1, 0, 0, 0, 128, 0, 0, 0, 32, 0, 0, 0]);
        assert_eq!(image.load_u32(OFF_INPUTS), 1);
        assert_eq!(image.load_u32(OFF_OUTPUTS), 1);
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn seqlock_reader_sees_consistent_pairs() {
        let path = temp_path("seq");
        let image = Arc::new(Image::open(&path, 1, 0, 10_000_000).unwrap());
        let writer = Arc::clone(&image);
        let stop = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let stop2 = Arc::clone(&stop);
        let thread = std::thread::spawn(move || {
            let mut n = 0u64;
            while !stop2.load(Ordering::Relaxed) {
                writer
                    .write_inputs(&[Slot { type_code: TYPE_INT, quality: 0, value: n, source_ns: n as i64 }])
                    .unwrap();
                n = n.wrapping_add(1);
            }
        });
        let mut torn = 0u64;
        let mut seen = 0u64;
        let mut slot = [Slot { type_code: 0, quality: 0, value: 0, source_ns: 0 }];
        let start = std::time::Instant::now();
        while start.elapsed() < std::time::Duration::from_millis(50) {
            match image.read_inputs(&mut slot) {
                Ok(()) => {
                    assert_eq!(slot[0].value, slot[0].source_ns as u64);
                    seen += 1;
                }
                Err(_) => torn += 1,
            }
        }
        stop.store(true, Ordering::Relaxed);
        thread.join().unwrap();
        assert!(seen > 100, "saw {seen} consistent reads, torn {torn}");
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn odd_seqlock_returns_instead_of_spinning() {
        let path = temp_path("odd");
        let image = Image::open(&path, 1, 0, 1).unwrap();
        image.leave_input_seq_odd();
        let mut slot = [Slot { type_code: 0, quality: 0, value: 0, source_ns: 0 }];
        let err = image.read_inputs(&mut slot).unwrap_err();
        assert_eq!(err, "image torn");
        let _ = std::fs::remove_file(&path);
    }
}
