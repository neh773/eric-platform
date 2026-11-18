//! Real-time scan engine for one Structured Text program.
//!
//! The program is a shared library produced by RuSTy, which stays an external
//! tool. This crate does not contain that compiler. Retain memory is not in
//! this preview: a restart clears program state. TODO: retain.

pub mod config;
pub mod control;
pub mod resources;
pub mod histogram;
pub mod image;
pub mod program;
pub mod report;
pub mod rt;
pub mod scan;
pub mod sim;
pub mod stats;
#[cfg(windows)]
pub mod win;

#[cfg(all(test, unix))]
mod engine_test;
#[cfg(all(test, unix))]
mod library_test;
#[cfg(all(test, unix))]
mod pid_test;

use std::sync::atomic::AtomicBool;

/// Set by the CLI signal handler. Tests leave it false.
pub static PROCESS_STOP: AtomicBool = AtomicBool::new(false);
