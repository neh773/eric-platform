"""Gateway targets. the reference platform is configured only through the documented 8.3 HTTP API.

Device and OPC connection resources:
POST /data/api/v1/resources/com.reference.opcua/device
POST /data/api/v1/resources/reference/opc-connection
header X-Reference-API-Token.
On 8.3.10 the body is a JSON array and the response carries changes[].newSignature.

Tag JSON import: POST /data/api/v1/tags/import (8.3.2+).
8.3.10 requires query type=json and collisionPolicy=Overwrite, and an
application/octet-stream body. collisionPolicy names are the import table
(Overwrite, not the scripting letter "o"). A non-zero failureCount is an error.

8.3.10 has no REST tag-read route. Reads use the reference platform's OPC UA server.

Drift and heap: GET /data/api/v1/systemPerformance/driftGauge and currentGauges.

Trial time left: GET /data/api/v1/trial (License Status, Trial Information).
The harness never POSTs that route. a manual license reset stays manual (ADR 0010).

Modbus item paths use the public designators, including the unit id prefix
(`[device]3.HR1024`).

The harness never reads or writes the gateway's files.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from time import monotonic as _monotonic

from bench.formula import (
    GROUP_ID,
    REFERENCE_DEVICE,
    REFERENCE_OPC_SERVER,
    OPC_CONNECTION,
    TAG_KIND,
    TAG_MODULE,
    BenchConfig,
    address,
    tag_name,
)
from bench.opcua import (
    AttributeValue,
    OpcUaError,
    OpcUaReader,
    format_node_id,
    reference_node_id,
)


class TargetError(Exception):
    pass


@dataclass
class HostSample:
    cpu_percent_of_quota: float | None = None
    rss_bytes: int | None = None
    memory_percent: float | None = None
    heap_bytes: int | None = None
    heap_max_bytes: int | None = None
    drift_gauge_ms: float | None = None
    sim_cpu_percent: float | None = None
    # Percent, already scaled. Our gateway publishes a ratio on clockDrift;
    # the harness multiplies that ratio by 100 before storing it here.
    clock_drift_percent: float | None = None


@dataclass
class TagReading:
    index: int
    value: int | None
    good: bool
    quality: str
    gateway_timestamp_ms: int | None = None


class HttpTransport:
    """HTTP client that puts the API token in a header and never echoes it."""

    def __init__(self, base_url: str, token: str | None = None, timeout: float = 60) -> None:
        self.base = base_url.rstrip("/")
        self.token = token or ""
        self.timeout = timeout
        # Our gateway: session cookie and CSRF token from POST /api/v1/auth/login.
        self.cookie: str | None = None
        self.csrf: str | None = None
        self.secrets: list[str] = []
        self.last_headers = None

    def request(
        self,
        method: str,
        path: str,
        body: object | None = None,
        query: dict | None = None,
        content_type: str = "application/json",
    ) -> tuple[int, object]:
        url = self.base + path
        if query:
            url += ("&" if "?" in url else "?") + urllib.parse.urlencode(query)
        data = None if body is None else json.dumps(body).encode()
        headers = {"accept": "application/json"}
        if data is not None:
            headers["content-type"] = content_type
        if self.token:
            headers["X-Reference-API-Token"] = self.token
        if self.cookie:
            headers["cookie"] = self.cookie
            if self.csrf and method.upper() not in ("GET", "HEAD"):
                headers["X-CSRF-Token"] = self.csrf
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                raw = resp.read()
                status = resp.status
                self.last_headers = getattr(resp, "headers", None)
        except urllib.error.HTTPError as exc:
            raw = exc.read()
            status = exc.code
            self.last_headers = getattr(exc, "headers", None)
        if not raw:
            return status, {}
        try:
            return status, json.loads(raw.decode())
        except json.JSONDecodeError:
            text = self._redact(raw.decode(errors="replace"))
            return status, {"raw": text[:500]}

    def _redact(self, text: str) -> str:
        if self.token and self.token in text:
            text = text.replace(self.token, "[redacted]")
        for secret in self.secrets:
            if secret and secret in text:
                text = text.replace(secret, "[redacted]")
        return text


def modbus_device_body(name: str, host: str, port: int, timeout_ms: int = 2000, concurrent: int = 2) -> dict:
    """Modbus TCP device resource. Field groups follow the public device page and system.config.create."""
    return {
        "name": name,
        "enabled": True,
        "description": "oip bench modbus",
        "config": {
            "profile": {"type": "ModbusTcp"},
            "settings": {
                "connectivity": {
                    "hostname": host,
                    "port": port,
                    "localAddress": "",
                    "communicationTimeout": timeout_ms,
                },
                "requestOptimization": {
                    "maxHoldingRegistersPerRequest": 125,
                    "maxHoldingRegistersPerWriteRequest": 123,
                    "maxInputRegistersPerRequest": 125,
                    "maxCoilsPerRequest": 2000,
                    "maxDiscreteInputsPerRequest": 2000,
                    "concurrentRequests": concurrent,
                    "spanGaps": True,
                },
                "writeRequests": {
                    "writeMultipleRegistersRequestAllowed": True,
                    "forceMultipleRegisterWritesEnabled": False,
                    "writeMultipleCoilsRequestAllowed": True,
                },
                "readRequests": {
                    "readMultipleRegistersRequestAllowed": True,
                    "readMultipleCoilsAllowed": True,
                    "readMultipleDiscreteInputsAllowed": True,
                },
                "advanced": {
                    "reconnectAfterConsecutiveTimeouts": True,
                    "reverseWordOrder": False,
                    "zeroBasedAddressing": False,
                    "maxRetryCount": 1,
                },
                "stringHandling": {
                    "reverseByteOrder": False,
                    "rightJustifyStrings": False,
                    "readRawStrings": False,
                },
            },
        },
    }


def opc_connection_body(name: str, endpoint: str) -> dict:
    return {
        "name": name,
        "enabled": True,
        "description": "oip bench opc ua",
        "config": {
            "endpointUrl": endpoint,
            "securityPolicy": "None",
            "securityMode": "None",
            "authentication": {"type": "Anonymous"},
        },
    }


def reference_tag(index: int, cfg: BenchConfig, protocol: str, device: str = REFERENCE_DEVICE) -> dict:
    located = address(index, cfg)
    if protocol == "modbus":
        item = f"[{device}]{located.reference_item}"
        server = REFERENCE_OPC_SERVER
    elif protocol == "opcua":
        item = located.opc_item
        server = OPC_CONNECTION
    else:
        raise TargetError("sparkplug tags are not imported; the simulator publishes them for a subscriber")
    return {
        "name": tag_name(index),
        "tagType": "AtomicTag",
        "valueSource": "opc",
        "dataType": "Int2",
        "opcItemPath": item,
        "opcServer": server,
        "sampleMode": "TagGroup",
        "tagGroup": "Default",
        "deadbandMode": "Off",
    }


def reference_import_document(indexes: list[int], cfg: BenchConfig, protocol: str) -> dict:
    return {"tags": [reference_tag(i, cfg, protocol) for i in indexes]}


def oip_modbus_device(name: str, host: str, port: int, period_ms: int) -> dict:
    """Resource body matching ModbusDeviceConfig field names. The running gateway
    rejects kind Device until a device service registers that schema."""
    return {
        "apiVersion": "oip/v1",
        "kind": "Device",
        "metadata": {
            "name": name,
            "module": "org.oip.driver-modbus",
            "labels": {"bench": "load"},
            "version": 0,
        },
        "spec": {
            "name": name,
            "host": host,
            "port": port,
            "mode": "TCP",
            "unitId": 1,
            "zeroBasedAddressing": False,
            "concurrentRequests": 2,
            "defaultScanPeriodMs": period_ms,
            "maxHoldingRegistersPerRead": 125,
            "spanGaps": True,
        },
    }


def oip_opc_connection(name: str, endpoint: str) -> dict:
    return {
        "apiVersion": "oip/v1",
        "kind": "Connection",
        "metadata": {
            "name": name,
            "module": "org.oip.driver-opcua-client",
            "labels": {"bench": "load"},
            "version": 0,
        },
        "spec": {
            "name": name,
            "endpointUrl": endpoint,
            "securityPolicy": "NONE",
            "securityMode": "NONE",
            "trustAllServerCertificates": False,
        },
    }


def oip_tag(index: int, cfg: BenchConfig, protocol: str, server: str, period_ms: int) -> dict:
    located = address(index, cfg)
    if protocol == "modbus":
        # Unit id in the item path selects the register window on a shared TCP connection.
        # Our driver parses the same designators as the public addressing page.
        item = located.reference_item
    elif protocol == "opcua":
        item = located.opc_item
    else:
        raise TargetError("sparkplug is published by the simulator; this gateway has no Sparkplug tag source in this harness")
    return {
        "apiVersion": "oip/v1",
        "kind": TAG_KIND,
        "metadata": {
            "name": tag_name(index),
            "module": TAG_MODULE,
            "labels": {"bench": "load"},
            "version": 0,
        },
        "spec": {
            "provider": "default",
            "path": f"bench/{tag_name(index)}",
            "type": "opc",
            "dataType": "Int32",
            "opcServer": server,
            "opcItemPath": item,
            "scanPeriodMillis": period_ms,
        },
    }


HISTORY_MODULE = "historian"
HISTORY_KIND = "HistorySettings"
HISTORY_STATS_PATH = "/api/v1/diagnostics/history/providers"


def oip_history_settings(index: int, history: dict) -> dict:
    """One historian/HistorySettings resource per bench tag (ADR 0010 PRF-006: local history on).

    Defaults are the schema's: onChange, deadband off, storage provider ``local``
    (the embedded historian). At 100% changing every tick that is one row per tag per tick.
    """
    spec = {
        "tagPath": f"[default]bench/{tag_name(index)}",
        "sampleMode": str(history.get("sampleMode") or "onChange"),
        "storageProvider": str(history.get("storageProvider") or "local"),
    }
    if history.get("sampleRateMillis") is not None:
        spec["sampleRateMillis"] = int(history["sampleRateMillis"])
    return {
        "apiVersion": "oip/v1",
        "kind": HISTORY_KIND,
        "metadata": {
            "name": f"bench-{tag_name(index)}",
            "module": HISTORY_MODULE,
            "labels": {"bench": "load"},
            "version": 0,
        },
        "spec": spec,
    }


def history_window(stats: list[tuple[float, dict]]) -> dict | None:
    """Rows the collector wrote between the first and last sample of a step window."""
    rows = [(t, s) for t, s in stats if isinstance(s, dict) and isinstance(s.get("written"), int)]
    if not rows:
        return None
    t0, first = rows[0]
    t1, last = rows[-1]
    seconds = t1 - t0
    written = last["written"] - first["written"]
    dropped_first = first.get("dropped") if isinstance(first.get("dropped"), int) else 0
    dropped_last = last.get("dropped") if isinstance(last.get("dropped"), int) else 0
    pending = [s.get("pending") for _, s in rows if isinstance(s.get("pending"), int)]
    failures = [s.get("consecutiveFlushFailures") for _, s in rows if isinstance(s.get("consecutiveFlushFailures"), int)]
    return {
        "samples": len(rows),
        "windowSeconds": round(seconds, 3),
        "rowsWritten": written,
        "rowsPerSec": (written / seconds) if seconds > 0 else None,
        "writtenTotal": last["written"],
        "dropped": dropped_last - dropped_first,
        "droppedTotal": dropped_last,
        "pendingMax": max(pending) if pending else None,
        "pendingLast": pending[-1] if pending else None,
        "maxConsecutiveFlushFailures": max(failures) if failures else None,
        "lastFlushError": last.get("lastFlushError"),
    }


def oip_server_for(index: int, cfg: BenchConfig, protocol: str, port_count: int) -> str:
    if protocol == "opcua":
        return "bench-opc"
    unit = address(index, cfg).unit_id
    slot = (unit - 1) % max(1, port_count)
    return f"bench-mb-{slot}"


def as_int(value) -> int | None:
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, float) and value.is_integer():
        return int(value)
    if isinstance(value, str):
        try:
            return int(value, 10)
        except ValueError:
            return None
    return None


def is_good(quality) -> bool:
    if isinstance(quality, str):
        return quality.lower().startswith("good")
    if isinstance(quality, dict):
        name = str(quality.get("name") or quality.get("quality") or "")
        if name:
            return name.lower().startswith("good")
        code = quality.get("code")
        if isinstance(code, int):
            return code == 0 or code == 192 or (code & 0xC0000000) == 0
        return False
    if isinstance(quality, int):
        return quality == 0 or quality == 192 or (quality & 0xC0000000) == 0
    return False


def parse_readings(payload, indexes: list[int]) -> list[TagReading]:
    rows = payload
    if isinstance(payload, dict):
        for key in ("values", "results", "items"):
            if isinstance(payload.get(key), list):
                rows = payload[key]
                break
        else:
            rows = []
    if not isinstance(rows, list):
        rows = []
    out = []
    for index, row in zip(indexes, rows):
        if not isinstance(row, dict):
            out.append(TagReading(index, None, False, "unparsed"))
            continue
        value = row.get("value", row.get("raw"))
        if isinstance(value, dict) and "value" in value:
            quality = value.get("quality")
            timestamp = value.get("timestamp")
            value = value.get("value")
        else:
            quality = row.get("quality")
            timestamp = row.get("timestamp")
        good = is_good(quality) if quality is not None else False
        out.append(TagReading(index, as_int(value), good, _quality_name(quality), _timestamp_ms(timestamp)))
    while len(out) < len(indexes):
        out.append(TagReading(indexes[len(out)], None, False, "missing"))
    return out


def _quality_name(quality) -> str:
    if quality is None:
        return "missing"
    if isinstance(quality, str):
        return quality
    if isinstance(quality, dict):
        return str(quality.get("name") or quality.get("code") or "unknown")
    return str(quality)


def _timestamp_ms(value) -> int | None:
    if isinstance(value, (int, float)):
        return int(value if value > 10_000_000_000 else value * 1000)
    if isinstance(value, str):
        from datetime import datetime
        text = value.replace("Z", "+00:00")
        try:
            return int(datetime.fromisoformat(text).timestamp() * 1000)
        except ValueError:
            return None
    return None


def metric_number(payload) -> float | None:
    if isinstance(payload, (int, float)) and not isinstance(payload, bool):
        return float(payload)
    if not isinstance(payload, dict):
        return None
    for key in ("value", "gauge", "heapUsedBytes"):
        if key in payload:
            inner = payload[key]
            if isinstance(inner, (int, float)) and not isinstance(inner, bool):
                return float(inner)
            if isinstance(inner, dict) and "value" in inner and isinstance(inner["value"], (int, float)):
                return float(inner["value"])
    return None


@dataclass
class referenceTarget:
    transport: HttpTransport
    sim_host: str
    modbus_port: int
    opcua_port: int
    provider: str = "default"
    batch: int = 2000
    device_name: str = REFERENCE_DEVICE
    configured_through: int = 0
    protocol: str = "modbus"
    signature: str | None = None
    opc_signature: str | None = None
    notes: list[str] = field(default_factory=list)
    docker: object | None = None
    opcua_read_endpoint: str = "opc.tcp://127.0.0.1:62541"
    delete_tags: bool = False
    reader: object | None = None
    trial_licensed: bool = False

    def setup(self, cfg: BenchConfig) -> None:
        self._cfg = cfg

    def apply_step(self, points: int, period_ms: int, protocol: str, cfg: BenchConfig) -> None:
        self.protocol = protocol
        self._cfg = cfg
        if protocol == "sparkplug":
            self.notes.append(
                f"Sparkplug group {GROUP_ID} edge {formula_edge()} is publishing from the simulator. "
                "This harness does not create an MQTT engine connection; that module is optional on a trial gateway. "
                "Gateway values/s for this phase stay empty and the phase is not a failure."
            )
            return
        if protocol == "modbus":
            self._ensure_modbus()
        else:
            self._ensure_opc()
        if points < self.configured_through:
            self.configured_through = 0
        indexes = list(range(self.configured_through, points))
        for start in range(0, len(indexes), self.batch):
            chunk = indexes[start : start + self.batch]
            body = reference_import_document(chunk, cfg, protocol)
            # 8.3.10: the body is the export document as octet-stream. type and collisionPolicy are words.
            status, payload = self.transport.request(
                "POST",
                "/data/api/v1/tags/import",
                body,
                {"provider": self.provider, "type": "json", "collisionPolicy": "Overwrite"},
                content_type="application/octet-stream",
            )
            if isinstance(payload, dict) and payload.get("failureCount"):
                raise TargetError(f"tag import reported failures: {_short(payload)}")
            if status >= 400:
                raise TargetError(f"tag import failed ({status}): {_short(payload)}")
        self.configured_through = points

    def _ensure_modbus(self) -> None:
        if self.signature:
            return
        body = modbus_device_body(self.device_name, self.sim_host, self.modbus_port)
        self.signature = self._create_resource(
            "com.reference.opcua",
            "device",
            self.device_name,
            body,
            "Modbus device create failed through the documented device resource API. "
            "One device is used per host IP, with the unit id in the item path.",
        )

    def _ensure_opc(self) -> None:
        if self.opc_signature:
            return
        endpoint = f"opc.tcp://{self.sim_host}:{self.opcua_port}/oip/bench"
        body = opc_connection_body(OPC_CONNECTION, endpoint)
        self.opc_signature = self._create_resource(
            "reference",
            "opc-connection",
            OPC_CONNECTION,
            body,
            "OPC UA connection create failed through /data/api/v1/resources/reference/opc-connection. "
            "GET .../resources/type/reference/opc-connection shows the schema this gateway build expects.",
        )

    def _create_resource(self, module: str, kind: str, name: str, body: dict, what: str) -> str | None:
        # 8.3.10 resource POST takes a JSON array and answers {"success", "changes": [{"newSignature"}]}.
        collection = f"/data/api/v1/resources/{module}/{kind}"
        status, payload = self.transport.request("POST", collection, [body])
        if status == 409 or _resource_failed(payload):
            found, current = self.transport.request("GET", f"/data/api/v1/resources/find/{module}/{kind}/{name}")
            signature = _new_signature(current) if found < 400 else None
            if signature:
                self.transport.request("DELETE", f"{collection}/{name}/{signature}")
                status, payload = self.transport.request("POST", collection, [body])
        if status >= 400 or _resource_failed(payload):
            raise TargetError(f"{what} ({status}): {_short(payload)}")
        return _new_signature(payload)

    def read_values(self, indexes: list[int], now_ms: int) -> list[TagReading]:
        del now_ms
        if self.protocol == "sparkplug":
            return [TagReading(i, None, False, "not-subscribed") for i in indexes]
        # 8.3.10 has no REST tag read. Read ns=2;s=[provider]name on the reference platform's OPC UA server.
        node_ids = [reference_node_id(self.provider, tag_name(index)) for index in indexes]
        return self._read_opcua(node_ids, indexes, "the reference platform OPC UA server")

    def trial_seconds_left(self) -> float | None:
        """GET /data/api/v1/trial. Never POST. a manual license reset is manual.

        A licensed gateway leaves the profile cap alone. ``trial_licensed`` records that case
        so the caller does not ask for ``--trial-seconds-left``.
        """
        self.trial_licensed = False
        try:
            status, payload = self.transport.request("GET", "/data/api/v1/trial")
        except OSError:
            return None
        if status >= 400 or not isinstance(payload, dict):
            return None
        mode = payload.get("licenseMode")
        if isinstance(mode, str) and mode.lower() not in ("", "trial"):
            self.trial_licensed = True
            return None
        return parse_trial_seconds_left(payload)

    def sample_host(self) -> HostSample:
        sample = HostSample()
        if self.docker is not None:
            sample = self.docker() or sample
        # 8.3.10: driftGauge is {"clockDrift"} and currentGauges is {"heapMemory","maxMemory"}.
        status, payload = self.transport.request("GET", "/data/api/v1/systemPerformance/driftGauge")
        if status < 400 and isinstance(payload, dict) and payload.get("clockDrift") is not None:
            try:
                sample.drift_gauge_ms = float(payload["clockDrift"])
            except (TypeError, ValueError):
                pass
        heap_status, heap = self.transport.request("GET", "/data/api/v1/systemPerformance/currentGauges")
        if heap_status < 400 and isinstance(heap, dict):
            used = _gauge_int(heap.get("heapMemory"))
            if used is not None:
                sample.heap_bytes = used
            maximum = _gauge_int(heap.get("maxMemory"))
            if maximum is not None:
                sample.heap_max_bytes = maximum
        return sample

    def cleanup(self) -> None:
        self._close_reader()
        if self.signature:
            self._delete_resource("com.reference.opcua", "device", self.device_name, self.signature)
            self.signature = None
        if self.opc_signature:
            self._delete_resource("reference", "opc-connection", OPC_CONNECTION, self.opc_signature)
            self.opc_signature = None
        self.notes.append(REFERENCE_OPCUA_NOTE)
        if self.delete_tags:
            self._recreate_provider()
            return
        self.notes.append(
            "Device and OPC connection resources created by this run are deleted through the resource API. "
            "8.3.10 has no tag-delete route. Imported tags stay in the provider. "
            "Pass --delete-tags to delete and recreate that provider, which removes every tag in it, "
            "or remove the bench tags (names pNNNNNNN) from the designer before the next trial."
        )

    def _delete_resource(self, module: str, kind: str, name: str, signature: str | None) -> None:
        found, current = self.transport.request("GET", f"/data/api/v1/resources/find/{module}/{kind}/{name}")
        fresh = _new_signature(current) if found < 400 else None
        signature = fresh or signature
        if signature:
            self.transport.request("DELETE", f"/data/api/v1/resources/{module}/{kind}/{name}/{signature}")

    def _recreate_provider(self) -> None:
        """Opt-in. 8.3.10 cannot delete one tag. Deleting the provider drops every tag in it."""
        if self.provider.lower() == "system":
            raise TargetError("refusing to delete the system tag provider")
        module, kind = "reference", "tag-provider"
        found, current = self.transport.request("GET", f"/data/api/v1/resources/find/{module}/{kind}/{self.provider}")
        if found >= 400 or not isinstance(current, dict):
            raise TargetError(
                f"tag provider {self.provider} was not readable ({found}): {_short(current)}. "
                "It was not deleted."
            )
        signature = _new_signature(current)
        if not signature:
            raise TargetError(f"tag provider {self.provider} has no signature to delete")
        saved = {key: value for key, value in current.items() if key != "signature"}
        status, payload = self.transport.request(
            "DELETE", f"/data/api/v1/resources/{module}/{kind}/{self.provider}/{signature}",
        )
        if status >= 400:
            raise TargetError(f"tag provider delete failed ({status}): {_short(payload)}")
        status, payload = self.transport.request("POST", f"/data/api/v1/resources/{module}/{kind}", [saved])
        if status >= 400 or _resource_failed(payload):
            raise TargetError(
                f"tag provider {self.provider} was deleted and recreating it failed ({status}): {_short(payload)}. "
                "Create the provider again in the gateway before the next run."
            )
        self.notes.append(
            f"Deleted and recreated tag provider {self.provider}. "
            "8.3.10 has no tag-delete route, so this removes every tag in that provider, not only bench tags."
        )

    def _read_opcua(self, node_ids: list[str], indexes: list[int], where: str) -> list[TagReading]:
        try:
            values = self._session().read(node_ids)
        except OpcUaError as exc:
            raise TargetError(f"tag read through {where} failed: {exc}") from exc
        return readings_from_attributes(indexes, values)

    def _session(self):
        if self.reader is None:
            self.reader = OpcUaReader(self.opcua_read_endpoint)
        return self.reader

    def _close_reader(self) -> None:
        reader = self.reader
        self.reader = None
        close = getattr(reader, "close", None)
        if close is None:
            return
        try:
            close()
        except Exception:
            return


@dataclass
class OipTarget:
    transport: HttpTransport
    sim_host: str
    modbus_ports: list[int]
    opcua_port: int
    require_devices: bool = True
    batch_note: str = ""
    configured_through: int = 0
    protocol: str = "modbus"
    created: list = field(default_factory=list)
    notes: list[str] = field(default_factory=list)
    docker: object | None = None
    read_via_opcua: bool = False
    opcua_read_endpoint: str | None = None
    opcua_node_template: str = "ns=2;s=Tags/{provider}/{path}"
    reader: object | None = None
    username: str | None = None
    password: str | None = None
    tag_batch: int = 2000
    # (host, port) pairs when the simulator fleet spans more than one machine.
    # None keeps every device on sim_host, which is the single-host path.
    modbus_endpoints: list | None = None
    # historian/HistorySettings spec defaults for every bench tag; None leaves history off.
    history: dict | None = None
    history_stats: list = field(default_factory=list)
    clock: object | None = None

    def setup(self, cfg: BenchConfig) -> None:
        self._cfg = cfg
        self._login()

    def _login(self) -> None:
        """POST /api/v1/auth/login. Keep the oip_session cookie and the CSRF token."""
        if not self.username or self.password is None:
            return
        secrets = getattr(self.transport, "secrets", None)
        if isinstance(secrets, list) and self.password not in secrets:
            secrets.append(self.password)
        self.transport.cookie = None
        self.transport.csrf = None
        status, payload = self.transport.request(
            "POST", "/api/v1/auth/login", {"username": self.username, "password": self.password},
        )
        if status >= 400 or not isinstance(payload, dict):
            raise TargetError(
                f"login to our gateway failed ({status}); check --oip-user and --oip-password-file"
            )
        headers = getattr(self.transport, "last_headers", None)
        cookie = _session_cookie(headers)
        if cookie is None:
            raise TargetError("login answered without an oip_session cookie")
        self.transport.cookie = cookie
        csrf = payload.get("csrfToken")
        self.transport.csrf = None if csrf is None else str(csrf)

    def _detail(self, payload) -> str:
        return _short(payload, getattr(self.transport, "secrets", None))

    def _request(self, method: str, path: str, body=None, query=None):
        status, payload = self.transport.request(method, path, body, query)
        if status == 401 and self.username:
            self._login()
            status, payload = self.transport.request(method, path, body, query)
        return status, payload

    def apply_step(self, points: int, period_ms: int, protocol: str, cfg: BenchConfig) -> None:
        self.protocol = protocol
        self._cfg = cfg
        if protocol == "sparkplug":
            self.notes.append("Sparkplug is measured at the simulator. This gateway image has no Sparkplug client in the harness.")
            return
        self._ensure_devices(period_ms, protocol, cfg)
        if points < self.configured_through:
            self.configured_through = 0
        batch: list[dict] = []
        start = self.configured_through
        size = max(1, int(self.tag_batch))

        def flush() -> None:
            if not batch:
                return
            self._post_tag_batch(batch)
            batch.clear()

        for index in range(start, points):
            server = oip_server_for(index, cfg, protocol, len(self._modbus_endpoints()))
            batch.append(oip_tag(index, cfg, protocol, server, period_ms))
            if len(batch) >= size:
                flush()
        flush()
        if self.history is not None:
            for index in range(start, points):
                batch.append(oip_history_settings(index, self.history))
                if len(batch) >= size:
                    flush()
            flush()
        self.history_stats = []
        if points > start:
            # Tags stay in the provider. A fresh scale container has an empty /data;
            # a rerun against a live gateway is an upsert (see _post_tag_batch).
            self.created.append(("tags", start, points))
        self.configured_through = points

    def _post_tag_batch(self, items: list[dict]) -> None:
        body = {"scope": "gateway", "items": items}
        status, payload = self._request("POST", "/api/v1/config/batch", body)
        if status == 409:
            # putAll rejects the whole batch when any item's version is stale.
            # A rerun sends version 0 against resources stored at version 1+.
            # Reload those versions and submit the same batch as an update.
            refreshed = self._with_stored_versions(items)
            status, payload = self._request(
                "POST", "/api/v1/config/batch", {"scope": "gateway", "items": refreshed},
            )
        if status >= 400:
            raise TargetError(
                f"tag batch of {len(items)} was rejected ({status}): {self._detail(payload)}. "
                "Tags are config resources created through POST /api/v1/config/batch."
            )

    def _with_stored_versions(self, items: list[dict]) -> list[dict]:
        kinds = []
        for item in items:
            meta = item.get("metadata") or {}
            key = (str(meta.get("module") or TAG_MODULE), str(item.get("kind") or TAG_KIND))
            if key not in kinds:
                kinds.append(key)
        versions: dict[str, int] = {}
        for module, kind in kinds:
            status, payload = self._request("GET", f"/api/v1/config/{module}/gateway/{kind}")
            if status >= 400 or not isinstance(payload, dict):
                raise TargetError(
                    f"tag batch conflicted and the current versions could not be read ({status}): {self._detail(payload)}"
                )
            versions.update(stored_versions(payload))
        refreshed = []
        for item in items:
            copy = {
                "apiVersion": item.get("apiVersion"),
                "kind": item.get("kind"),
                "metadata": dict(item.get("metadata") or {}),
                "spec": dict(item.get("spec") or {}),
            }
            name = str(copy["metadata"].get("name") or "")
            if name in versions:
                copy["metadata"]["version"] = versions[name]
            refreshed.append(copy)
        return refreshed

    def _modbus_endpoints(self) -> list[tuple[str, int]]:
        if self.modbus_endpoints:
            return [(str(host), int(port)) for host, port in self.modbus_endpoints]
        return [(self.sim_host, int(port)) for port in self.modbus_ports]

    def _ensure_devices(self, period_ms: int, protocol: str, cfg: BenchConfig) -> None:
        if protocol == "opcua":
            body = oip_opc_connection("bench-opc", f"opc.tcp://{self.sim_host}:{self.opcua_port}/oip/bench")
            self._put_device("/api/v1/config/org.oip.driver-opcua-client/gateway/Connection/bench-opc", body)
            return
        for slot, (host, port) in enumerate(self._modbus_endpoints()):
            name = f"bench-mb-{slot}"
            body = oip_modbus_device(name, host, port, period_ms)
            self._put_device(f"/api/v1/config/org.oip.driver-modbus/gateway/Device/{name}", body)

    def _put_device(self, path: str, body: dict) -> None:
        if path in self.created:
            return
        status, payload = self._request("PUT", path, body)
        if status == 409:
            status, payload = self._put_device_version(path, body)
        if status >= 400:
            message = (
                f"device resource {path} was rejected ({status}): {self._detail(payload)}. "
                "Modbus Device and OPC UA Connection resources are created through the config API."
            )
            if self.require_devices:
                raise TargetError(message)
            self.notes.append(message)
            return
        self.created.append(path)

    def _put_device_version(self, path: str, body: dict) -> tuple[int, object]:
        """A rerun finds the device already stored. PUT with its current version."""
        found, current = self._request("GET", path)
        version = None
        if found < 400 and isinstance(current, dict):
            version = (current.get("metadata") or {}).get("version") if isinstance(current.get("metadata"), dict) else None
        if not isinstance(version, int):
            return found if found >= 400 else 409, current
        updated = {
            "apiVersion": body.get("apiVersion"),
            "kind": body.get("kind"),
            "metadata": dict(body.get("metadata") or {}),
            "spec": dict(body.get("spec") or {}),
        }
        updated["metadata"]["version"] = version
        return self._request("PUT", path, updated)

    def read_values(self, indexes: list[int], now_ms: int) -> list[TagReading]:
        del now_ms
        if self.protocol == "sparkplug":
            return [TagReading(i, None, False, "not-subscribed") for i in indexes]
        if self.read_via_opcua:
            return self._read_opcua(indexes)
        paths = [f"[default]bench/{tag_name(i)}" for i in indexes]
        status, payload = self._request("POST", "/api/v1/tags/read", {"paths": paths})
        if status >= 400:
            raise TargetError(
                f"tag read failed ({status}): {self._detail(payload)}. "
                "POST /api/v1/tags/read returns value, quality, and timestamp in request order. "
                "Pass --read-via-opcua to read the same tags through the gateway OPC UA server."
            )
        return parse_readings(payload, indexes)

    def _read_opcua(self, indexes: list[int]) -> list[TagReading]:
        if not self.opcua_read_endpoint and self.reader is None:
            raise TargetError(
                "OPC UA reads are enabled, but no endpoint is set. "
                "Pass --opcua-read-endpoint or set oip.opcuaReadEndpoint. "
                "The gateway OPC UA server is M2.4 and is not assumed to be running."
            )
        node_ids = []
        for index in indexes:
            name = tag_name(index)
            try:
                node_ids.append(format_node_id(
                    self.opcua_node_template,
                    provider="default",
                    name=name,
                    path=f"bench/{name}",
                    index=index,
                ))
            except OpcUaError as exc:
                raise TargetError(str(exc)) from exc
        try:
            values = self._session().read(node_ids)
        except OpcUaError as exc:
            raise TargetError(f"tag read through the gateway OPC UA server failed: {exc}") from exc
        return readings_from_attributes(indexes, values)

    def _session(self):
        if self.reader is None:
            self.reader = OpcUaReader(
                self.opcua_read_endpoint or "",
                username=self.username,
                password=self.password,
            )
        return self.reader

    def sample_host(self) -> HostSample:
        sample = HostSample()
        if self.docker is not None:
            sample = self.docker() or sample
        status, payload = self._request("GET", "/api/v1/diagnostics/memory")
        if status < 400 and isinstance(payload, dict):
            heap = payload.get("heap") or {}
            if isinstance(heap, dict) and isinstance(heap.get("usedBytes"), int):
                sample.heap_bytes = heap["usedBytes"]
            if isinstance(heap, dict) and isinstance(heap.get("maxBytes"), int):
                sample.heap_max_bytes = heap["maxBytes"]
        perf_status, perf = self._request("GET", "/api/v1/diagnostics/performance")
        if perf_status < 400 and isinstance(perf, dict):
            if sample.heap_bytes is None and isinstance(perf.get("heapUsedBytes"), int):
                sample.heap_bytes = perf["heapUsedBytes"]
            # clockDrift is (actualWake - expectedWake) / 1 s. Positive means late.
            # The cutoff is +/-10% of that ratio, so the stored value is the percent.
            drift = perf.get("clockDrift")
            if isinstance(drift, dict):
                # The gateway answers {intervalMs, drift, lateMillis, samples}; drift is the ratio.
                drift = drift.get("drift")
            if isinstance(drift, (int, float)) and not isinstance(drift, bool):
                sample.clock_drift_percent = float(drift) * 100.0
        if self.history is not None:
            hist_status, hist = self._request("GET", HISTORY_STATS_PATH)
            collector = hist.get("collector") if hist_status < 400 and isinstance(hist, dict) else None
            if isinstance(collector, dict):
                now = self.clock.monotonic() if self.clock is not None else _monotonic()
                self.history_stats.append((now, collector))
        return sample

    def step_metrics(self) -> dict:
        """Extra per-step figures for the ramp record: local history rows written in the window."""
        if self.history is None:
            return {}
        return {"history": history_window(self.history_stats)}

    def cleanup(self) -> None:
        reader = self.reader
        self.reader = None
        close = getattr(reader, "close", None)
        if close is not None:
            try:
                close()
            except Exception:
                pass
        for entry in reversed(self.created):
            if isinstance(entry, tuple):
                continue
            self._request("DELETE", entry)


REFERENCE_OPCUA_NOTE = (
    "Tag reads use the reference platform's OPC UA server because 8.3.10 has no REST tag-read route. "
    "On Connections > OPC UA > Server Settings, for this run: Bind Addresses includes 0.0.0.0 "
    "(the default localhost is not reachable from the harness), Security Policies includes None, "
    "Anonymous Access Allowed is true, and Expose Tag Providers is true. Publish bind port 62541 "
    "and point reference.opcuaReadEndpoint at it. Node ids are ns=2;s=[provider]name. "
    "Anonymous access and SecurityPolicy None are not the product defaults. Restore the previous "
    "server settings after the run."
)


def parse_trial_seconds_left(payload) -> float | None:
    """Remaining trial seconds from GET /data/api/v1/trial, or None when the gateway is not in trial.

    A licensed gateway must not be capped by a zero countdown. An expired trial is zero seconds,
    so the ramp refuses to start a step. This function does not reset the trial.
    """
    if not isinstance(payload, dict):
        return None
    mode = payload.get("licenseMode")
    if isinstance(mode, str) and mode.lower() not in ("", "trial"):
        return None
    if payload.get("expired") is True:
        return 0.0
    left = payload.get("trialSecondsLeft")
    if isinstance(left, bool) or not isinstance(left, (int, float)):
        return None
    return max(0.0, float(left))


def readings_from_attributes(indexes: list[int], values: list[AttributeValue]) -> list[TagReading]:
    out = []
    for index, value in zip(indexes, values):
        out.append(TagReading(index, as_int(value.value), value.good, value.status, value.timestamp_ms))
    while len(out) < len(indexes):
        out.append(TagReading(indexes[len(out)], None, False, "missing"))
    return out


def _resource_failed(payload) -> bool:
    return isinstance(payload, dict) and payload.get("success") is False


def _new_signature(payload) -> str | None:
    if isinstance(payload, dict):
        signature = payload.get("signature")
        if signature:
            return str(signature)
        for change in payload.get("changes") or []:
            if isinstance(change, dict) and change.get("newSignature"):
                return str(change["newSignature"])
    return None


def _gauge_int(value) -> int | None:
    """currentGauges sends byte counts as JSON numbers, sometimes in exponent form."""
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, (int, float)):
        return int(value)
    if isinstance(value, str):
        try:
            return int(float(value))
        except ValueError:
            return None
    return None


def _session_cookie(headers) -> str | None:
    if headers is None:
        return None
    getter = getattr(headers, "get_all", None)
    values = getter("Set-Cookie") if getter is not None else None
    if not values:
        single = getattr(headers, "get", None)
        raw = single("Set-Cookie") if single is not None else None
        values = [] if raw is None else [raw]
    for value in values:
        first = str(value).split(";", 1)[0].strip()
        if first.startswith("oip_session="):
            return first
    return None


def stored_versions(payload: dict) -> dict[str, int]:
    """Name to stored metadata.version from GET /api/v1/config/tags/gateway/Tag."""
    rows = payload.get("items")
    if not isinstance(rows, list):
        return {}
    versions = {}
    for row in rows:
        if not isinstance(row, dict):
            continue
        metadata = row.get("metadata")
        if not isinstance(metadata, dict):
            continue
        name = metadata.get("name")
        version = metadata.get("version")
        if isinstance(name, str) and isinstance(version, int) and not isinstance(version, bool):
            versions[name] = version
        elif isinstance(name, str) and isinstance(version, float) and version.is_integer():
            versions[name] = int(version)
    return versions


def formula_edge() -> str:
    from bench.formula import EDGE_NODE
    return EDGE_NODE


def _short(payload, secrets: list | None = None) -> str:
    text = json.dumps(payload) if not isinstance(payload, str) else payload
    for secret in secrets or []:
        if secret:
            text = text.replace(str(secret), "[redacted]")
    return text[:400]
