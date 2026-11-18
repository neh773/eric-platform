"""Sparkplug B edge-node simulator.

Publishes to the companion Mosquitto broker as group OIP, edge node SimEdge,
device SimDevice. NBIRTH/DBIRTH are retained; DDATA carries the scripted
Value every 200 ms. The MQTT Will is NDEATH with the same bdSeq the next
birth uses, per Sparkplug 3.0:
https://sparkplug.eclipse.org/specification/version/3.0.0/

Data messages use MQTT QoS 0. The will uses QoS 1 so the broker keeps the
death certificate for the session (Eclipse Tahu reference-client behavior).
POST /control on this process uses the same waveform patch as the Modbus
simulator and forces a rebirth so subscribers observe the new mode.
An NCMD with metric "Node Control/Rebirth" = true also rebirths.
"""

from __future__ import annotations

import argparse
import os
import threading
import time

import paho.mqtt.client as mqtt

from common.signal import SignalState, serve_http
from sparkplug import TYPE_BOOLEAN, TYPE_DOUBLE, TYPE_STRING, TYPE_UINT64, decode_payload, encode_payload


class EdgeSession:
    def __init__(self, state: SignalState, host: str, port: int, group: str, edge: str, device: str) -> None:
        self.state = state
        self.host = host
        self.port = port
        self.group = group
        self.edge = edge
        self.device = device
        self.bd_seq = 0
        self.seq = 0
        self._lock = threading.Lock()

    def topic(self, message_type: str, device: str | None = None) -> str:
        base = f"spBv1.0/{self.group}/{message_type}/{self.edge}"
        return base if device is None else f"{base}/{device}"

    def _next_seq(self) -> int:
        with self._lock:
            current = self.seq & 0xFF
            self.seq = (self.seq + 1) & 0xFF
            return current

    def _reset_seq(self) -> None:
        with self._lock:
            self.seq = 0

    def _payload(self, metrics: list[dict], seq: int | None = None) -> bytes:
        snap = self.state.snapshot()
        return encode_payload(snap["nowMs"] if snap["nowMs"] >= 0 else 0, self._next_seq() if seq is None else seq, metrics)

    def birth_metrics_node(self) -> list[dict]:
        return [
            {"name": "bdSeq", "datatype": TYPE_UINT64, "long_value": self.bd_seq},
            {"name": "Node Control/Rebirth", "datatype": TYPE_BOOLEAN, "bool_value": False},
        ]

    def birth_metrics_device(self) -> list[dict]:
        snap = self.state.snapshot()
        return [
            {"name": "Value", "datatype": TYPE_DOUBLE, "double_value": snap["value"]},
            {"name": "Mode", "datatype": TYPE_STRING, "string_value": snap["mode"]},
        ]

    def publish_birth(self, client: mqtt.Client) -> None:
        self._reset_seq()
        client.publish(self.topic("NBIRTH"), self._payload(self.birth_metrics_node()), qos=0, retain=True)
        client.publish(self.topic("DBIRTH", self.device), self._payload(self.birth_metrics_device()), qos=0, retain=True)

    def publish_ddata(self, client: mqtt.Client) -> None:
        snap = self.state.snapshot()
        metrics = [
            {"name": "Value", "datatype": TYPE_DOUBLE, "double_value": snap["value"]},
            {"name": "Mode", "datatype": TYPE_STRING, "string_value": snap["mode"]},
        ]
        client.publish(self.topic("DDATA", self.device), self._payload(metrics), qos=0, retain=False)

    def publish_ndata(self, client: mqtt.Client) -> None:
        snap = self.state.snapshot()
        metrics = [{"name": "Mode", "datatype": TYPE_STRING, "string_value": snap["mode"]}]
        client.publish(self.topic("NDATA"), self._payload(metrics), qos=0, retain=False)

    def run(self) -> None:
        while True:
            client = mqtt.Client(
                mqtt.CallbackAPIVersion.VERSION2,
                client_id=f"oip-{self.edge}",
                protocol=mqtt.MQTTv311,
            )
            session = self

            def on_message(_client, _userdata, message) -> None:  # noqa: ANN001
                if not message.topic.endswith(f"/NCMD/{session.edge}"):
                    return
                try:
                    payload = decode_payload(message.payload)
                except ValueError:
                    return
                for metric in payload["metrics"]:
                    if metric.get("name") == "Node Control/Rebirth" and metric.get("bool_value"):
                        session.state.update({})

            client.on_message = on_message
            death = encode_payload(0, 0, [{"name": "bdSeq", "datatype": TYPE_UINT64, "long_value": self.bd_seq}])
            client.will_set(self.topic("NDEATH"), payload=death, qos=1, retain=False)
            try:
                client.connect(self.host, self.port, keepalive=30)
                client.subscribe(self.topic("NCMD"), qos=0)
                client.loop_start()
                self.state.set_ready(True)
                self.publish_birth(client)
                seen = self.state.generation
                last_node = time.monotonic()
                while True:
                    generation = self.state.generation
                    if generation != seen:
                        self.publish_birth(client)
                        seen = generation
                    self.publish_ddata(client)
                    now = time.monotonic()
                    if now - last_node >= 1.0:
                        self.publish_ndata(client)
                        last_node = now
                    time.sleep(0.2)
            except Exception as exc:  # noqa: BLE001 - reconnect on any broker failure
                print(f"mqtt reconnect after {exc}", flush=True)
                self.state.set_ready(False)
                time.sleep(0.5)
            finally:
                try:
                    client.loop_stop()
                    client.disconnect()
                except Exception:  # noqa: BLE001
                    pass


def main() -> None:
    parser = argparse.ArgumentParser(description="OIP Sparkplug B edge-node simulator")
    parser.add_argument("--bind", default="0.0.0.0")
    parser.add_argument("--http-port", type=int, default=int(os.environ.get("HTTP_PORT", "8080")))
    parser.add_argument("--mqtt-host", default=os.environ.get("MQTT_HOST", "mosquitto"))
    parser.add_argument("--mqtt-port", type=int, default=int(os.environ.get("MQTT_PORT", "1883")))
    parser.add_argument("--group", default=os.environ.get("GROUP_ID", "OIP"))
    parser.add_argument("--edge", default=os.environ.get("EDGE_NODE", "SimEdge"))
    parser.add_argument("--device", default=os.environ.get("DEVICE_ID", "SimDevice"))
    args = parser.parse_args()
    state = SignalState()
    state.set_ready(False)
    http_port = serve_http(state, args.bind, args.http_port)
    print(f"HTTP_PORT={http_port}", flush=True)
    print("READY", flush=True)
    EdgeSession(state, args.mqtt_host, args.mqtt_port, args.group, args.edge, args.device).run()


if __name__ == "__main__":
    main()
