"use strict";

// Same waveform as test-infra/sim/common/waveform.py and org.oip.testinfra.Waveform.
// phase = floorMod(nowMs - epochMs, periodMs) / periodMs.

const MODES = ["constant", "ramp", "sine", "step"];

function valueAt(mode, nowMs, epochMs, periodMs, amplitude, offset) {
  if (!MODES.includes(mode)) throw new Error(`invalid mode: ${mode}`);
  if (!(periodMs > 0)) throw new Error("periodMs must be > 0");
  const elapsed = BigInt(nowMs) - BigInt(epochMs);
  const period = BigInt(periodMs);
  const mod = ((elapsed % period) + period) % period;
  const phase = Number(mod) / Number(period);
  if (mode === "constant") return offset;
  if (mode === "ramp") return offset + amplitude * phase;
  if (mode === "sine") return offset + amplitude * Math.sin(2 * Math.PI * phase);
  return offset + (phase >= 0.5 ? amplitude : 0);
}

module.exports = { MODES, valueAt };
