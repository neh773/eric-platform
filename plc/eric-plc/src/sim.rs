//! Stand-in for the gateway tag bridge. Writes the input image on a period
//! so a timing run does not need the JVM. The real bridge is `PlcBridge`.

use crate::config::Config;
use crate::image::{Image, Slot};
use crate::program::type_code;
use crate::rt;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

pub fn run_sim(config: &Config, duration: Duration, stop: &AtomicBool, expect_motor: bool) -> Result<(), String> {
    let image = Image::open(
        &config.shm_path,
        config.mapping.inputs.len() as u32,
        config.mapping.outputs.len() as u32,
        config.period_ns,
    )?;
    let mut slots = Vec::with_capacity(config.mapping.inputs.len());
    for point in &config.mapping.inputs {
        slots.push(Slot { type_code: type_code(&point.ty)?, quality: 1, value: 0, source_ns: 0 });
    }
    let mut outputs = vec![
        Slot { type_code: 0, quality: 0, value: 0, source_ns: 0 };
        config.mapping.outputs.len()
    ];
    let started = rt::monotonic_ns();
    let mut tick = 0u64;
    let mut saw_motor = false;
    while !stop.load(Ordering::Relaxed) && !crate::PROCESS_STOP.load(Ordering::Relaxed) {
        if rt::monotonic_ns().saturating_sub(started) >= duration.as_nanos() as u64 {
            break;
        }
        let now = rt::realtime_ns() as i64;
        for (index, point) in config.mapping.inputs.iter().enumerate() {
            slots[index].quality = 0;
            slots[index].source_ns = now;
            slots[index].value = simulated(point.address.as_str(), tick);
        }
        image.write_inputs(&slots)?;
        image.set_gateway_heartbeat(now as u64);
        if image.read_outputs(&mut outputs).is_ok() && outputs.first().is_some_and(|slot| slot.value == 1) {
            saw_motor = true;
        }
        tick = tick.wrapping_add(1);
        let next = started.saturating_add(tick.saturating_mul(config.period_ns));
        rt::sleep_until(next, stop);
    }
    if expect_motor && !saw_motor {
        return Err("start request never produced the motor output".into());
    }
    Ok(())
}

fn simulated(address: &str, tick: u64) -> u64 {
    match address {
        "%IX0.0" => 1,
        "%IX0.1" => 0,
        "%IW2" => ((tick * 97) % 32767) as u64,
        _ => 0,
    }
}
