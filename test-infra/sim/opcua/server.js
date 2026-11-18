"use strict";

/**
 * OPC UA test simulator.
 *
 * node-opcua is MIT (https://github.com/node-opcua/node-opcua). This process is
 * a disposable test double: SecurityPolicy None and anonymous identity only,
 * so driver tests can connect without a PKI. Do not copy this profile into a
 * gateway that faces a real network.
 *
 * Nodes (own namespace, string ids):
 *   Sim.Value, Sim.Mode, Sim.Amplitude, Sim.Offset, Sim.PeriodMs,
 *   Sim.EpochMs, Sim.Frozen, Sim.NowMs
 * The same POST /control JSON as the Modbus simulator freezes the clock.
 */

const http = require("http");
const {
  OPCUAServer,
  DataType,
  Variant,
  StatusCodes,
  MessageSecurityMode,
  SecurityPolicy,
} = require("node-opcua");
const { MODES, valueAt } = require("./waveform");

const OPCUA_PORT = Number(process.env.OPCUA_PORT || 4840);
const HTTP_PORT = Number(process.env.HTTP_PORT || 8080);
const MAX_PERIOD_MS = 65535;

const state = {
  mode: "sine",
  amplitude: 100,
  offset: 0,
  periodMs: 10000,
  epochMs: 0,
  frozen: false,
  nowMs: 0,
};

function clock() {
  return state.frozen ? state.nowMs : Date.now();
}

function currentValue() {
  return valueAt(state.mode, clock(), state.epochMs, state.periodMs, state.amplitude, state.offset);
}

function snapshot() {
  const nowMs = clock();
  return {
    mode: state.mode,
    amplitude: state.amplitude,
    offset: state.offset,
    periodMs: state.periodMs,
    epochMs: state.epochMs,
    frozen: state.frozen,
    nowMs,
    value: valueAt(state.mode, nowMs, state.epochMs, state.periodMs, state.amplitude, state.offset),
  };
}

function applyPatch(patch) {
  const allowed = new Set(["mode", "amplitude", "offset", "periodMs", "epochMs", "frozen", "nowMs"]);
  for (const key of Object.keys(patch)) {
    if (!allowed.has(key)) throw new Error(`unknown fields: ${key}`);
  }
  if (patch.mode !== undefined) {
    if (!MODES.includes(patch.mode)) throw new Error(`invalid mode: ${patch.mode}`);
    state.mode = patch.mode;
  }
  if (patch.amplitude !== undefined) state.amplitude = Number(patch.amplitude);
  if (patch.offset !== undefined) state.offset = Number(patch.offset);
  if (patch.periodMs !== undefined) {
    const period = patch.periodMs;
    if (!Number.isInteger(period) || period < 1 || period > MAX_PERIOD_MS) {
      throw new Error(`periodMs must be 1..${MAX_PERIOD_MS}`);
    }
    state.periodMs = period;
  }
  if (patch.epochMs !== undefined) state.epochMs = patch.epochMs;
  if (patch.nowMs !== undefined) state.nowMs = patch.nowMs;
  if (patch.frozen !== undefined) {
    if (typeof patch.frozen !== "boolean") throw new Error("frozen must be a boolean");
    if (patch.frozen && !state.frozen && patch.nowMs === undefined) state.nowMs = Date.now();
    state.frozen = patch.frozen;
  }
  return snapshot();
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on("data", (chunk) => {
      size += chunk.length;
      if (size > 65536) {
        reject(new Error("body too large"));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}

function sendJson(res, code, body) {
  const raw = Buffer.from(JSON.stringify(body));
  res.writeHead(code, {
    "Content-Type": "application/json",
    "Content-Length": raw.length,
    Connection: "close",
  });
  res.end(raw);
}

function startHttp() {
  const server = http.createServer(async (req, res) => {
    const path = (req.url || "/").split("?")[0];
    try {
      if (req.method === "GET" && path === "/health") return sendJson(res, 200, { status: "ok" });
      if (req.method === "GET" && path === "/value") return sendJson(res, 200, snapshot());
      if (req.method === "POST" && path === "/control") {
        const raw = await readBody(req);
        const patch = raw ? JSON.parse(raw) : {};
        return sendJson(res, 200, applyPatch(patch));
      }
      return sendJson(res, 404, { error: "not found" });
    } catch (err) {
      return sendJson(res, 400, { error: String(err.message || err) });
    }
  });
  server.listen(HTTP_PORT, "0.0.0.0");
}

function doubleVar(getter, setter) {
  return {
    get: () => new Variant({ dataType: DataType.Double, value: getter() }),
    set: setter
      ? (variant) => {
          setter(variant.value);
          return StatusCodes.Good;
        }
      : undefined,
  };
}

async function main() {
  const server = new OPCUAServer({
    port: OPCUA_PORT,
    resourcePath: "/oip/sim",
    hostname: "localhost",
    alternateHostname: ["localhost", "127.0.0.1", "opcua"],
    allowAnonymous: true,
    securityModes: [MessageSecurityMode.None],
    securityPolicies: [SecurityPolicy.None],
    buildInfo: {
      productName: "ERIC OPC UA Simulator",
      productUri: "urn:oip:test-infra:opcua-sim",
    },
    serverInfo: {
      applicationUri: "urn:oip:test-infra:opcua-sim",
      productUri: "urn:oip:test-infra:opcua-sim",
      applicationName: { text: "ERIC OPC UA Simulator", locale: "en" },
    },
  });

  await server.initialize();
  const addressSpace = server.engine.addressSpace;
  const namespace = addressSpace.getOwnNamespace();
  const device = namespace.addObject({
    organizedBy: addressSpace.rootFolder.objects,
    browseName: "Sim",
  });

  const add = (browseName, nodeId, dataType, value) => {
    namespace.addVariable({
      componentOf: device,
      browseName,
      nodeId,
      dataType,
      minimumSamplingInterval: 100,
      value,
    });
  };

  add("Value", "s=Sim.Value", "Double", doubleVar(currentValue));
  add("Mode", "s=Sim.Mode", "String", {
    get: () => new Variant({ dataType: DataType.String, value: state.mode }),
    set: (variant) => {
      if (!MODES.includes(variant.value)) return StatusCodes.BadOutOfRange;
      state.mode = variant.value;
      return StatusCodes.Good;
    },
  });
  add("Amplitude", "s=Sim.Amplitude", "Double", doubleVar(
    () => state.amplitude,
    (value) => { state.amplitude = Number(value); },
  ));
  add("Offset", "s=Sim.Offset", "Double", doubleVar(
    () => state.offset,
    (value) => { state.offset = Number(value); },
  ));
  add("PeriodMs", "s=Sim.PeriodMs", "Double", {
    get: () => new Variant({ dataType: DataType.Double, value: state.periodMs }),
    set: (variant) => {
      const period = Math.trunc(Number(variant.value));
      if (period < 1 || period > MAX_PERIOD_MS) return StatusCodes.BadOutOfRange;
      state.periodMs = period;
      return StatusCodes.Good;
    },
  });
  add("EpochMs", "s=Sim.EpochMs", "Double", doubleVar(
    () => state.epochMs,
    (value) => { state.epochMs = Math.trunc(Number(value)); },
  ));
  add("Frozen", "s=Sim.Frozen", "Boolean", {
    get: () => new Variant({ dataType: DataType.Boolean, value: state.frozen }),
    set: (variant) => {
      const on = Boolean(variant.value);
      if (on && !state.frozen) state.nowMs = Date.now();
      state.frozen = on;
      return StatusCodes.Good;
    },
  });
  add("NowMs", "s=Sim.NowMs", "Double", doubleVar(
    () => clock(),
    (value) => { state.nowMs = Math.trunc(Number(value)); },
  ));

  await server.start();
  startHttp();
  console.log(`OPC_ENDPOINT=${server.getEndpointUrl()}`);
  console.log(`HTTP_PORT=${HTTP_PORT}`);
  console.log("READY");
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
