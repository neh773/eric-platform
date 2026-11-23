//! Load a RuSTy shared library and bind `%I` / `%Q` globals.
//!
//! RuSTy mangles both input and output addresses into `__PI_<n>_<n>`.
//! An input and an output must not use the same address numbers.
//! The program entry is `void Name(Name_type*)`, and `Name_instance` is the
//! global the constructors fill in when the library loads.

use crate::image::{Slot, TYPE_BOOL, TYPE_DINT, TYPE_INT, TYPE_LREAL, TYPE_REAL};
use libloading::Library;
use serde::Deserialize;
use std::collections::HashMap;
use std::ffi::CString;
use std::path::Path;

#[derive(Debug, Clone, Deserialize)]
pub struct Mapping {
    pub inputs: Vec<PointSpec>,
    pub outputs: Vec<PointSpec>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct PointSpec {
    pub address: String,
    pub tag: String,
    #[serde(rename = "type")]
    pub ty: String,
    #[serde(default)]
    pub safe: Option<serde_json::Value>,
}

#[derive(Debug, Deserialize)]
struct HwFile {
    #[serde(rename = "VariableMap")]
    variable_map: Vec<HwEntry>,
}

#[derive(Debug, Deserialize)]
struct HwEntry {
    address: String,
    mangled_name: String,
    access_type: String,
}

pub struct Point {
    pub address: String,
    pub tag: String,
    pub type_code: u8,
    pub width: usize,
    pub safe: u64,
    ptr: *mut u8,
}

pub struct Program {
    _lib: Library,
    _stdlib: Option<Library>,
    entry: unsafe extern "C" fn(*mut u8),
    instance: *mut u8,
    pub inputs: Vec<Point>,
    pub outputs: Vec<Point>,
}

unsafe impl Send for Program {}

impl Program {
    pub fn load(
        so: &Path,
        entry: &str,
        instance: &str,
        mapping: &Mapping,
        hwmap: Option<&Path>,
    ) -> Result<Self, String> {
        let hw = match hwmap {
            Some(path) => Some(load_hw(path)?),
            None => None,
        };
        // The standard library is LGPLv3 and is loaded by name. Keep the copy that
        // sits beside the program mapped with global scope so that name resolves
        // without baking a run path into the shared library.
        let stdlib = preload_stdlib(so)?;
        let lib = open_library(so)?;
        run_constructor_table(&lib);
        let entry_fn = load_fn(&lib, entry)?;
        let instance_ptr = if instance.is_empty() {
            std::ptr::null_mut()
        } else {
            load_data(&lib, instance)?
        };
        let inputs = bind(&lib, &mapping.inputs, &hw)?;
        let outputs = bind(&lib, &mapping.outputs, &hw)?;
        Ok(Program { _lib: lib, _stdlib: stdlib, entry: entry_fn, instance: instance_ptr, inputs, outputs })
    }

    /// A scan with no program. Used to sample the clock without a compiled block.
    pub fn idle() -> Self {
        Self {
            _lib: process_library(),
            _stdlib: None,
            entry: idle_entry,
            instance: std::ptr::null_mut(),
            inputs: Vec::new(),
            outputs: Vec::new(),
        }
    }

    pub fn call(&self) {
        unsafe { (self.entry)(self.instance) }
    }

    pub fn write_input(&self, index: usize, bits: u64) {
        unsafe { write_width(self.inputs[index].ptr, self.inputs[index].width, bits) }
    }

    pub fn read_output(&self, index: usize) -> u64 {
        unsafe { read_width(self.outputs[index].ptr, self.outputs[index].width) }
    }
}

pub fn load_mapping(path: &Path) -> Result<Mapping, String> {
    let text = std::fs::read_to_string(path).map_err(|e| format!("read {}: {e}", path.display()))?;
    let mapping: Mapping = serde_json::from_str(&text).map_err(|e| format!("mapping {}: {e}", path.display()))?;
    if mapping.inputs.is_empty() && mapping.outputs.is_empty() {
        return Err("mapping has no points".into());
    }
    for point in mapping.inputs.iter().chain(mapping.outputs.iter()) {
        type_code(&point.ty)?;
        if !point.address.starts_with('%') {
            return Err(format!("{} is not a % address", point.address));
        }
    }
    Ok(mapping)
}

pub fn type_code(ty: &str) -> Result<u8, String> {
    match ty {
        "BOOL" => Ok(TYPE_BOOL),
        "INT" => Ok(TYPE_INT),
        "DINT" => Ok(TYPE_DINT),
        "REAL" => Ok(TYPE_REAL),
        "LREAL" => Ok(TYPE_LREAL),
        other => Err(format!("unsupported type {other}")),
    }
}

pub fn width_of(type_code: u8) -> usize {
    match type_code {
        TYPE_BOOL => 1,
        TYPE_INT => 2,
        TYPE_DINT | TYPE_REAL => 4,
        TYPE_LREAL => 8,
        _ => 1,
    }
}

pub fn safe_bits(ty: &str, value: Option<&serde_json::Value>) -> Result<u64, String> {
    let code = type_code(ty)?;
    let Some(value) = value else {
        return Ok(0);
    };
    match code {
        TYPE_BOOL => {
            let flag = value.as_bool().ok_or("BOOL safe value must be true or false")?;
            Ok(u64::from(flag))
        }
        TYPE_INT => {
            let n = value.as_i64().ok_or("INT safe value must be an integer")?;
            if !(-32768..=32767).contains(&n) {
                return Err(format!("INT safe value {n} is out of range"));
            }
            Ok((n as u16) as u64)
        }
        TYPE_DINT => {
            let n = value.as_i64().ok_or("DINT safe value must be an integer")?;
            if !(-2147483648..=2147483647).contains(&n) {
                return Err(format!("DINT safe value {n} is out of range"));
            }
            Ok((n as u32) as u64)
        }
        TYPE_REAL => {
            let n = value.as_f64().ok_or("REAL safe value must be a number")?;
            Ok(u64::from((n as f32).to_bits()))
        }
        TYPE_LREAL => {
            let n = value.as_f64().ok_or("LREAL safe value must be a number")?;
            Ok(n.to_bits())
        }
        _ => Ok(0),
    }
}

pub fn empty_slots(points: &[Point], quality: u8) -> Vec<Slot> {
    points
        .iter()
        .map(|point| Slot { type_code: point.type_code, quality, value: 0, source_ns: 0 })
        .collect()
}

pub fn safe_slots(points: &[Point]) -> Vec<Slot> {
    points
        .iter()
        .map(|point| Slot { type_code: point.type_code, quality: 1, value: point.safe, source_ns: 0 })
        .collect()
}

fn bind(lib: &Library, specs: &[PointSpec], hw: &Option<HashMap<String, (String, usize)>>) -> Result<Vec<Point>, String> {
    let mut points = Vec::with_capacity(specs.len());
    for spec in specs {
        let code = type_code(&spec.ty)?;
        let (symbol, width) = if let Some(map) = hw {
            map.get(&spec.address)
                .cloned()
                .ok_or_else(|| format!("hwmap has no {}", spec.address))?
        } else {
            (mangle(&spec.address)?, width_of(code))
        };
        if width != width_of(code) {
            return Err(format!("{} is {} bytes in the map and {} in the type", spec.address, width, width_of(code)));
        }
        points.push(Point {
            address: spec.address.clone(),
            tag: spec.tag.clone(),
            type_code: code,
            width,
            safe: safe_bits(&spec.ty, spec.safe.as_ref())?,
            ptr: load_data(lib, &symbol)?,
        });
    }
    Ok(points)
}

fn load_hw(path: &Path) -> Result<HashMap<String, (String, usize)>, String> {
    let text = std::fs::read_to_string(path).map_err(|e| format!("read {}: {e}", path.display()))?;
    let file: HwFile = serde_json::from_str(&text).map_err(|e| format!("hwmap {}: {e}", path.display()))?;
    let mut map = HashMap::new();
    for entry in file.variable_map {
        let width = match entry.access_type.as_str() {
            "Bit" | "Byte" => 1,
            "Word" => 2,
            "DWord" => 4,
            "LWord" => 8,
            other => return Err(format!("hwmap access {other} is not supported")),
        };
        map.insert(entry.address, (entry.mangled_name, width));
    }
    Ok(map)
}

pub fn mangle(address: &str) -> Result<String, String> {
    let rest = address.trim().trim_start_matches('%');
    let bytes = rest.as_bytes();
    if bytes.len() < 2 || !matches!(bytes[0], b'I' | b'Q' | b'M' | b'G') {
        return Err(format!("cannot mangle {address}"));
    }
    if !matches!(bytes[1], b'X' | b'B' | b'W' | b'D' | b'L') {
        return Err(format!("cannot mangle {address}"));
    }
    let numbers = &rest[2..];
    if numbers.is_empty() || numbers.split('.').any(|part| part.is_empty() || !part.bytes().all(|b| b.is_ascii_digit())) {
        return Err(format!("cannot mangle {address}"));
    }
    Ok(format!("__PI_{}", numbers.replace('.', "_")))
}

unsafe extern "C" fn idle_entry(_instance: *mut u8) {}

fn process_library() -> Library {
    #[cfg(unix)]
    {
        Library::from(libloading::os::unix::Library::this())
    }
    #[cfg(windows)]
    {
        Library::from(libloading::os::windows::Library::this().unwrap_or_else(|_| {
            unsafe { libloading::os::windows::Library::new("kernel32.dll") }.expect("kernel32")
        }))
    }
}

fn open_library(so: &Path) -> Result<Library, String> {
    #[cfg(unix)]
    {
        let unix = unsafe { libloading::os::unix::Library::open(Some(so), libc::RTLD_NOW | libc::RTLD_LOCAL) }
            .map_err(|e| format!("dlopen {}: {e}", so.display()))?;
        Ok(Library::from(unix))
    }
    #[cfg(windows)]
    {
        let win = unsafe { libloading::os::windows::Library::new(so) }
            .map_err(|e| format!("LoadLibrary {}: {e}", so.display()))?;
        Ok(Library::from(win))
    }
}

fn preload_stdlib(program: &Path) -> Result<Option<Library>, String> {
    let Some(dir) = program.parent() else { return Ok(None) };
    #[cfg(unix)]
    let sibling = dir.join("libiec61131std.so");
    #[cfg(windows)]
    let sibling = dir.join("iec61131std.dll");
    if !sibling.exists() {
        return Ok(None);
    }
    open_global(&sibling)
}

fn open_global(sibling: &Path) -> Result<Option<Library>, String> {
    #[cfg(unix)]
    {
        let unix = unsafe { libloading::os::unix::Library::open(Some(sibling), libc::RTLD_NOW | libc::RTLD_GLOBAL) }
            .map_err(|e| format!("dlopen {}: {e}", sibling.display()))?;
        Ok(Some(Library::from(unix)))
    }
    #[cfg(windows)]
    {
        let win = unsafe { libloading::os::windows::Library::new(sibling) }
            .map_err(|e| format!("LoadLibrary {}: {e}", sibling.display()))?;
        Ok(Some(Library::from(win)))
    }
}

/// ERIC's Windows link step builds the program DLL with no C runtime entry point, so the loader does not
/// run LLVM's constructor table (`.CRT$XCU`). The link step brackets that table with two exported zero
/// slots; call every non-null entry between them, in order. Libraries without the markers are untouched.
fn run_constructor_table(lib: &Library) {
    let (Ok(begin), Ok(end)) = (load_data(lib, "__eric_ctors_begin"), load_data(lib, "__eric_ctors_end")) else {
        return;
    };
    unsafe { call_table(begin as *const usize, end as *const usize) }
}

unsafe fn call_table(begin: *const usize, end: *const usize) {
    let mut slot = begin;
    while slot < end {
        let fun = slot.read();
        if fun != 0 {
            let ctor: unsafe extern "C" fn() = std::mem::transmute(fun);
            ctor();
        }
        slot = slot.add(1);
    }
}

fn load_fn(lib: &Library, name: &str) -> Result<unsafe extern "C" fn(*mut u8), String> {
    let addr = load_data(lib, name)?;
    Ok(unsafe { std::mem::transmute(addr) })
}

fn load_data(lib: &Library, name: &str) -> Result<*mut u8, String> {
    let c = CString::new(name).map_err(|e| e.to_string())?;
    // `get` only accepts a pointer-sized type. The pointer it stores is the symbol address.
    let sym = unsafe { lib.get::<*mut u8>(c.as_bytes_with_nul()) }.map_err(|e| format!("symbol {name}: {e}"))?;
    #[cfg(unix)]
    {
        Ok(unsafe { sym.into_raw() }.into_raw() as *mut u8)
    }
    #[cfg(windows)]
    {
        let raw = unsafe { sym.into_raw() }.into_raw();
        let addr = raw.map(|fun| fun as usize).unwrap_or(0);
        if addr == 0 {
            return Err(format!("symbol {name} is null"));
        }
        Ok(addr as *mut u8)
    }
}

unsafe fn write_width(ptr: *mut u8, width: usize, bits: u64) {
    match width {
        1 => ptr.write(if bits == 0 { 0 } else { 1 }),
        2 => (ptr as *mut u16).write_unaligned(bits as u16),
        4 => (ptr as *mut u32).write_unaligned(bits as u32),
        8 => (ptr as *mut u64).write_unaligned(bits),
        _ => {}
    }
}

unsafe fn read_width(ptr: *mut u8, width: usize) -> u64 {
    match width {
        1 => u64::from(ptr.read()),
        2 => u64::from((ptr as *const u16).read_unaligned()),
        4 => u64::from((ptr as *const u32).read_unaligned()),
        _ => (ptr as *const u64).read_unaligned(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mangles_like_rusty() {
        assert_eq!(mangle("%IX0.0").unwrap(), "__PI_0_0");
        assert_eq!(mangle("%IX0.1").unwrap(), "__PI_0_1");
        assert_eq!(mangle("%IW2").unwrap(), "__PI_2");
        assert_eq!(mangle("%QX1.0").unwrap(), "__PI_1_0");
        assert_eq!(mangle("%QW4").unwrap(), "__PI_4");
    }

    static CALLS: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
    unsafe extern "C" fn bump() {
        CALLS.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
    }

    #[test]
    fn constructor_table_calls_each_entry_and_skips_empty_slots() {
        let table: [usize; 5] = [0, bump as usize, 0, bump as usize, 0];
        let range = table.as_ptr_range();
        unsafe { call_table(range.start, range.end) };
        assert_eq!(CALLS.load(std::sync::atomic::Ordering::SeqCst), 2);
    }
}
